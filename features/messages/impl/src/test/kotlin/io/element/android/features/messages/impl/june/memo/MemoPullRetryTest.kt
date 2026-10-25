/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱29 (관리자 2026-10-26: 서버에서 바뀐 것이 폰에 늦게 뜸): a pull at every review end, the two pull retries after a failed
 * pull (15 s, 60 s, only while the screen is shown, never on a timer when pulls go well), "받기 필요" after both, and the
 * readable failure line of the diagnosis. All data is made up.
 */
class MemoPullRetryTest : RobolectricTest() {
    private val t0 = 1_761_359_065_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    /** How the next `sync_pull` ends. */
    private enum class Pull { Ok, SendFails, Http502, TimesOut, Refused, WrongShape }

    /** The memo bot room: `sync_pull` ends as [next] says (then [pull]); every call is recorded. */
    private class Room(var next: ArrayDeque<Pull> = ArrayDeque()) : MemoSyncTransport {
        val ops = mutableListOf<String>()
        var pull: JSONObject = JSONObject().put("노트", JSONArray()).put("기준", 0)
        var gate: CompletableDeferred<Unit>? = null
        var fetches = 0

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op.op
            if (op.op != "sync_pull") return MemoResponse.SyncAck("r", JSONObject().put("결과", JSONArray()).put("서버순번", 0))
            gate?.await()
            return when (next.removeFirstOrNull() ?: Pull.Ok) {
                Pull.Ok -> MemoResponse.Sync("r", pull)
                Pull.SendFails -> throw MemoSendException("off")
                Pull.Http502 -> throw MemoSendException("보내지 못했습니다 (502)", httpCode = 502)
                Pull.TimesOut -> withTimeout(1) { awaitCancellation() }
                Pull.Refused -> MemoResponse.Error("r", op.op, "서버가 바쁩니다")
                Pull.WrongShape -> MemoResponse.Ok("r", op.op, null, null)
            }
        }

        override suspend fun fetchProposals(): List<JSONObject> = emptyList<JSONObject>().also { fetches++ }
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null

        fun pulls() = ops.count { it == "sync_pull" }
    }

    private fun failing(vararg p: Pull) = ArrayDeque(p.toList())

    private fun runner(db: MemoDb, room: Room) = MemoSyncRunner(db, room, clock = { now })

    // ---------------------------------------------------------------- ① a pull at every review end

    @Test
    fun `app29 - a review end without proposals pulls once and reads the index once`() = runTest {
        val db = db()
        val id = backend(db).create("교토 메모")
        val room = Room()
        assertTrue(runner(db, room).syncAfterReview(listOf(id), withCards = false))
        assertEquals(1, room.pulls())
        // No card is waited for: no second read of the index after the pause.
        assertEquals(1, room.fetches)
        assertFalse(room.ops.contains("sync_push"))
    }

    @Test
    fun `app29 - the review end pull is not skipped by the 10 s rule of the button`() = runTest {
        val db = db()
        val id = backend(db).create("메모")
        val room = Room()
        val r = runner(db, room)
        assertTrue(r.syncNow(push = false))
        now += 1_000
        assertTrue(r.syncAfterReview(listOf(id), withCards = false))
        assertEquals(2, room.pulls())
    }

    @Test
    fun `app29 - two review ends waiting behind a pull share the next pull`() = runTest {
        val db = db()
        val id = backend(db).create("메모")
        val room = Room()
        val r = runner(db, room)
        val gate = CompletableDeferred<Unit>()
        room.gate = gate
        // The screen's pull started before the reviews ended: it cannot answer them.
        val screen = launch { r.syncNow(push = false) }
        runCurrent()
        val a = launch { r.syncAfterReview(listOf(id), withCards = false) }
        val b = launch { r.syncAfterReview(listOf(id), withCards = false) }
        runCurrent()
        room.gate = null
        gate.complete(Unit)
        joinAll(screen, a, b)
        // The screen's pull, then one for both review ends (the second asked before it started).
        assertEquals(2, room.pulls())
    }

    // ---------------------------------------------------------------- ② the two retries and "받기 필요"

    @Test
    fun `app29 - a failed pull is retried after 15 s then 60 s, then 받기 필요 and nothing more`() = runTest {
        val db = db()
        val room = Room(failing(Pull.SendFails, Pull.SendFails, Pull.SendFails))
        val r = runner(db, room)
        // Pulls going well: no retry, nothing waits on a timer.
        assertNull(r.pullRetry.value)
        assertFalse(r.syncNow(push = false))
        val first = r.pullRetry.value
        assertNotNull(first)
        assertEquals(15_000L, first!!.waitMs)
        assertFalse(r.pullNeeded.value)

        assertFalse(r.retryPull(first.seq))
        val second = r.pullRetry.value!!
        assertEquals(60_000L, second.waitMs)
        assertFalse(r.pullNeeded.value)

        assertFalse(r.retryPull(second.seq))
        assertNull(r.pullRetry.value)
        assertTrue(r.pullNeeded.value)
        assertEquals(3, room.pulls())
        assertEquals(MEMO_PULL_RETRY_MS, listOf(15_000L, 60_000L))
    }

    @Test
    fun `app29 - no retry runs while the screen is hidden or when nothing failed`() = runTest {
        val retry = MemoPullRetry(seq = 1, waitMs = 15_000)
        val slept = mutableListOf<Long>()
        val pulled = mutableListOf<Long>()
        assertFalse(memoRetryPullWhenDue(visible = false, retry = retry, sleep = { slept += it }) { pulled += it })
        assertFalse(memoRetryPullWhenDue(visible = true, retry = null, sleep = { slept += it }) { pulled += it })
        assertTrue(slept.isEmpty() && pulled.isEmpty())
        assertTrue(memoRetryPullWhenDue(visible = true, retry = retry, sleep = { slept += it }) { pulled += it })
        assertEquals(listOf(15_000L), slept)
        assertEquals(listOf(1L), pulled)
    }

    @Test
    fun `app29 - a retry whose turn was taken by another pull sends nothing`() = runTest {
        val db = db()
        val room = Room(failing(Pull.SendFails))
        val r = runner(db, room)
        r.syncNow(push = false)
        val stale = r.pullRetry.value!!.seq
        // The screen came back and pulled before the retry was due: that pull answers it.
        assertTrue(r.syncNow(push = false))
        assertTrue(r.retryPull(stale))
        assertEquals(2, room.pulls())
    }

    @Test
    fun `app29 - a good pull ends the retries and takes 받기 필요 away`() = runTest {
        val db = db()
        val room = Room(failing(Pull.SendFails, Pull.SendFails))
        val r = runner(db, room)
        r.syncNow(push = false)
        // The first retry goes well: no second one.
        r.retryPull(r.pullRetry.value!!.seq)
        assertEquals(60_000L, r.pullRetry.value!!.waitMs)
        assertTrue(r.retryPull(r.pullRetry.value!!.seq))
        assertNull(r.pullRetry.value)
        assertFalse(r.pullNeeded.value)

        // After 받기 필요, the next open (or the button) pulls; a good one takes the mark away.
        room.next = failing(Pull.SendFails, Pull.SendFails, Pull.SendFails)
        r.syncNow(push = false)
        r.retryPull(r.pullRetry.value!!.seq)
        r.retryPull(r.pullRetry.value!!.seq)
        assertTrue(r.pullNeeded.value)
        now += 60_000
        assertEquals(MemoButtonSync.Done, r.syncByButton())
        assertFalse(r.pullNeeded.value)
        assertNull(r.pullRetry.value)
    }

    @Test
    fun `app29 - a failed open after 받기 필요 starts its own two retries and keeps the mark`() = runTest {
        val db = db()
        val room = Room(failing(Pull.SendFails, Pull.SendFails, Pull.SendFails, Pull.SendFails))
        val r = runner(db, room)
        r.syncNow(push = false)
        r.retryPull(r.pullRetry.value!!.seq)
        r.retryPull(r.pullRetry.value!!.seq)
        assertTrue(r.pullNeeded.value)
        assertFalse(r.syncNow(push = false))
        assertEquals(15_000L, r.pullRetry.value!!.waitMs)
        assertTrue(r.pullNeeded.value)
    }

    // ---------------------------------------------------------------- ③ the failure line of the diagnosis

    @Test
    fun `app29 - the failure text tells the kind and the HTTP code when there is one`() {
        assertEquals("보내기 실패(HTTP 502)", memoCallFailureText(MemoCallFailure(MemoCallFailKind.Send, httpCode = 502)))
        assertEquals("보내기 실패(망 오류 UnknownHostException)", memoCallFailureText(MemoCallFailure(MemoCallFailKind.Send, detail = "망 오류 UnknownHostException")))
        assertEquals("보내기 실패", memoCallFailureText(MemoCallFailure(MemoCallFailKind.Send)))
        assertEquals("응답 시간 초과", memoCallFailureText(MemoCallFailure(MemoCallFailKind.Timeout)))
        assertEquals("복호화·읽기 실패(답 모양 다름)", memoCallFailureText(MemoCallFailure(MemoCallFailKind.Read, detail = "답 모양 다름")))
        assertEquals("기타(IllegalStateException)", memoCallFailureText(MemoCallFailure(MemoCallFailKind.Other, detail = "IllegalStateException")))
        // An exception text with an address or a path never reaches the line.
        val dirty = memoCallFailureText(MemoCallFailure(MemoCallFailKind.Other, detail = "https://example.org/x /data/user/0/a !room:example.org"))
        listOf("https://", "/data", "!room", "example.org").forEach { assertFalse("$it in $dirty", dirty.contains(it)) }
    }

    @Test
    fun `app29 - each kind of failed pull is written as a readable line with the next retry`() = runTest {
        val cases = listOf(
            Pull.Http502 to "실패: 보내기 실패(HTTP 502) · 15초 뒤 다시",
            Pull.SendFails to "실패: 보내기 실패 · 15초 뒤 다시",
            Pull.TimesOut to "실패: 응답 시간 초과 · 15초 뒤 다시",
            Pull.Refused to "실패: 기타(서버가 바쁩니다) · 15초 뒤 다시",
            Pull.WrongShape to "실패: 복호화·읽기 실패(답 모양 다름) · 15초 뒤 다시",
        )
        for ((kind, line) in cases) {
            val db = db()
            val r = runner(db, Room(failing(kind)))
            assertFalse(r.syncNow(push = false))
            assertEquals(kind.name, line, memoProposalDiagOf(db).pull?.result)
        }
        val db = db()
        val r = runner(db, Room(failing(Pull.Http502, Pull.TimesOut, Pull.SendFails)))
        r.syncNow(push = false)
        r.retryPull(r.pullRetry.value!!.seq)
        assertEquals("실패: 응답 시간 초과 · 60초 뒤 다시", memoProposalDiagOf(db).pull?.result)
        r.retryPull(r.pullRetry.value!!.seq)
        assertEquals("실패: 보내기 실패 · 받기 필요", memoProposalDiagOf(db).pull?.result)
        val text = memoProposalDiagLines(memoProposalDiagOf(db), null, now)[1]
        assertEquals("받기(sync_pull): 11:24:25 실패: 보내기 실패 · 받기 필요", text)
        assertFalse(text.contains("MemoSendException"))
    }

    @Test
    fun `app29 - the push line uses the same words`() = runTest {
        val db = db()
        val room = object : MemoSyncTransport {
            override suspend fun call(op: MemoOp): MemoResponse = throw MemoSendException("보내지 못했습니다 (503)", httpCode = 503)
            override suspend fun fetchProposals(): List<JSONObject>? = null
            override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
            override suspend fun maxUploadSize(): Long? = null
        }
        backend(db).create("올릴 메모")
        assertFalse(MemoSyncRunner(db, room, clock = { now }).push(force = true))
        assertEquals("실패: 보내기 실패(HTTP 503)", memoProposalDiagOf(db).push?.result)
    }

    // ---------------------------------------------------------------- (바) the editor is never overwritten

    @Test
    fun `app29 - a retried pull while editing sends nothing and leaves what is typed`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("처음 글")
        MemoEditHub.drop(id)
        val entry = memoEditDraftOf((local.call(MemoOp.get(id), "g") as MemoResponse.Note).note)
        MemoEditHub.open(id, entry)
        MemoEditHub.typed(id, entry.copy(body = "폰에서 치는 중"))
        try {
            val room = Room(failing(Pull.SendFails))
            val r = runner(db, room)
            assertFalse(r.syncNow(push = false))
            val fromServer = db.note(id)!!.toChangeJson()
                .put("본문", "서버가 고친 글")
                .put("수정", memoIso(now + 60_000))
                .put("기기", MEMO_DEVICE_SERVER)
                .put("순번", 99)
            room.pull = JSONObject().put("노트", JSONArray().put(fromServer)).put("기준", 5)
            now += 15_000
            assertTrue(r.retryPull(r.pullRetry.value!!.seq))
            assertEquals("폰에서 치는 중", MemoEditHub.state(id)!!.draft.body)
            assertFalse(room.ops.contains("sync_push"))
        } finally {
            MemoEditHub.drop(id)
        }
    }
}
