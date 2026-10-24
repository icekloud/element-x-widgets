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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱25 (관리자: "수락,거부 등 작업내용 승인 시는 동기화 타이밍과 관계없이 즉시 반영해야함 … 두개 이상의 제안내용을 거의 동시에
 * 클릭"): the answer of a card goes up right after the press, in one `sync_push` with what else waits, one push at a
 * time. All data is made up.
 */
class MemoAnswerPushTest : RobolectricTest() {
    private var now = 1_760_000_000_000L

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    /**
     * The memo bot room. Every `sync_push` is recorded (also a failed one), may wait for [gate], and is answered for
     * each note (`수락`) and each answer (its own value). [inFlight] counts the requests on their way at the same time.
     */
    private class Room(var proposals: List<JSONObject> = emptyList()) : MemoSyncTransport {
        var online = true
        var gate: CompletableDeferred<Unit>? = null
        var pullGate: CompletableDeferred<Unit>? = null
        val pushes = mutableListOf<Map<String, Any?>>()
        val pulls = mutableListOf<Map<String, Any?>>()
        private var inFlight = 0
        var maxInFlight = 0

        override suspend fun call(op: MemoOp): MemoResponse {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                return when (op.op) {
                    "sync_pull" -> {
                        pulls += op.args
                        pullGate?.await()
                        if (!online) throw MemoSendException("off")
                        MemoResponse.Sync("r", JSONObject().put("노트", JSONArray()).put("기준", 0))
                    }
                    "sync_push" -> {
                        pushes += op.args
                        gate?.await()
                        if (!online) throw MemoSendException("off")
                        val results = JSONArray()
                        val changes = op.args["변경"] as JSONArray
                        for (i in 0 until changes.length()) results.put(JSONObject().put("id", changes.getJSONObject(i).getString("id")).put("상태", "수락"))
                        val answers = JSONArray()
                        (op.args["제안응답"] as? JSONArray)?.let { a ->
                            for (i in 0 until a.length()) {
                                val o = a.getJSONObject(i)
                                answers.put(JSONObject().put("id", o.getString("id")).put("상태", o.getString("응답")))
                            }
                        }
                        MemoResponse.SyncAck("r", JSONObject().put("결과", results).put("제안결과", answers).put("서버순번", 0))
                    }
                    else -> MemoResponse.Error("r", op.op, "모름")
                }
            } finally {
                inFlight--
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = if (online) proposals else null
        override suspend fun upload(bytes: ByteArray): String = throw MemoSendException("no uploads here")
        override suspend fun maxUploadSize(): Long? = null

        fun answersOf(push: Map<String, Any?>): List<String> {
            val a = push["제안응답"] as? JSONArray ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it).getString("id") }
        }

        fun notesOf(push: Map<String, Any?>): List<JSONObject> {
            val a = push["변경"] as JSONArray
            return (0 until a.length()).map { a.getJSONObject(it) }
        }
    }

    private fun runner(db: MemoDb, room: Room) = MemoSyncRunner(db, room, clock = { now }, readFile = { null })

    private fun card(id: String, note: String, kind: String = "분류", content: JSONObject = JSONObject().put("태그", JSONArray(listOf("여행")))) =
        JSONObject().put("id", id).put("종류", kind).put("요약", "가짜 제안 $id").put("상태", "대기").put("노트", note).put("제안내용", content)

    private suspend fun group(local: MemoLocalBackend, id: String) = memoPanelGroups(local.fetchIndex()).single { id in it.ids }

    private fun answerRows(db: MemoDb) = db.outbox().filter { it.kind == MEMO_OUTBOX_PROPOSAL }.map { it.target }

    @Test
    fun `(가) an accepted card goes up at once - no input pause, no back-off, while a draft waits too`() = runTest {
        val db = db()
        val local = backend(db)
        val store = MemoStore(local)
        val note = local.create("가짜 교토 메모")
        // A kind the phone does not apply itself (the server carries it out): the press writes only the answer.
        val room = Room(proposals = listOf(card("ga1", note, kind = "가짜종류")))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        room.pushes.clear()
        // The editor is open: its auto save wrote a draft, which the automatic push holds while editing, and an earlier
        // try of it failed (its back-off is not over).
        assertTrue(store.request(MemoOp.edit(MemoTarget(note), body = "가짜 초안", title = null, tags = null)) is MemoResult.Ok)
        MemoSync.failUploads(db, db.outbox().filter { it.kind == MEMO_OUTBOX_NOTE }, now, "서버에 닿지 못함")
        assertTrue(r.nextRetryInMs()!! > 0)
        val start = testScheduler.currentTime
        memoSendAnswers(store, memoGroupOps(group(local, "ga1"), MemoAnswer.Accept), push = { r.pushAnswers() }) {}
        // Up right after the press: no 2.5 s wait (no virtual time went by), no back-off, whatever the editor does.
        assertEquals(start, testScheduler.currentTime)
        assertEquals(1, room.pushes.size)
        assertEquals(listOf("ga1"), room.answersOf(room.pushes.single()))
        // What else waited went in the same request (요구 ②), the draft too.
        assertEquals(listOf(note), room.notesOf(room.pushes.single()).map { it.getString("id") })
        assertTrue(answerRows(db).isEmpty())
        assertFalse(r.hasPendingUploads())
        assertTrue(local.fetchIndex().proposals.isEmpty())
    }

    @Test
    fun `(나) three cards pressed one after the other - one push at a time, the rest goes in the next one, each once`() = runTest {
        val db = db()
        val local = backend(db)
        val store = MemoStore(local)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("na1", note), card("na2", note, kind = "기타"), card("na3", note, kind = "또다른")))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        room.pushes.clear()
        room.maxInFlight = 0
        val gate = CompletableDeferred<Unit>()
        room.gate = gate
        val press = { id: String -> launch { memoSendAnswers(store, memoGroupOps(group(local, id), MemoAnswer.Accept), push = { r.pushAnswers() }) {} } }
        val first = press("na1")
        runCurrent()
        // The first answer is on its way (the server has not answered yet).
        assertEquals(1, room.pushes.size)
        val second = press("na2")
        val third = press("na3")
        runCurrent()
        // The two others are written and wait: no second request while one runs.
        assertEquals(listOf("na1", "na2", "na3"), answerRows(db))
        assertEquals(1, room.pushes.size)
        room.gate = null
        gate.complete(Unit)
        advanceUntilIdle()
        joinAll(first, second, third)
        assertEquals(1, room.maxInFlight)
        assertEquals(2, room.pushes.size)
        assertEquals(listOf("na1"), room.answersOf(room.pushes[0]))
        assertEquals(listOf("na2", "na3"), room.answersOf(room.pushes[1]))
        assertTrue(answerRows(db).isEmpty())
        assertTrue(local.fetchIndex().proposals.isEmpty())
    }

    @Test
    fun `(나) a press while a pull holds the lock waits for it and never runs beside it`() = runTest {
        val db = db()
        val local = backend(db)
        val store = MemoStore(local)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("nb1", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        room.pushes.clear()
        room.maxInFlight = 0
        val pullGate = CompletableDeferred<Unit>()
        room.pullGate = pullGate
        val screen = launch { r.syncNow(push = false) }
        runCurrent()
        val press = launch { memoSendAnswers(store, memoGroupOps(group(local, "nb1"), MemoAnswer.Dismiss), push = { r.pushAnswers() }) {} }
        runCurrent()
        // The pull of the screen is on its way: the answer waits for the same lock (no push beside the pull).
        assertEquals(listOf("nb1"), answerRows(db))
        assertTrue(room.pushes.isEmpty())
        room.pullGate = null
        pullGate.complete(Unit)
        advanceUntilIdle()
        joinAll(screen, press)
        assertEquals(1, room.maxInFlight)
        assertEquals(listOf("nb1"), room.pushes.flatMap { room.answersOf(it) })
        assertTrue(answerRows(db).isEmpty())
    }

    @Test
    fun `(다) a title card goes up with its note change and a waiting folder icon in the same request`() = runTest {
        val db = db()
        val local = backend(db)
        val store = MemoStore(local)
        val note = local.create("제목 없는 가짜 메모")
        val title = card("da1", note, kind = "제목", content = JSONObject().put("제목", "가짜 교토 여행"))
        val room = Room(proposals = listOf(title))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        assertTrue(store.request(MemoOp.mkdir("가짜운동", null)) is MemoResult.Ok)
        assertTrue(store.request(memoFolderIconOp("가짜운동", "🏃")) is MemoResult.Ok)
        room.pushes.clear()
        memoSendAnswers(store, memoGroupOps(group(local, "da1"), MemoAnswer.Accept), push = { r.pushAnswers() }) {}
        // One request: the note the card changed, the answer and the icon (the server takes the notes first).
        assertEquals(1, room.pushes.size)
        val push = room.pushes.single()
        assertEquals(listOf(note), room.notesOf(push).map { it.getString("id") })
        assertEquals("가짜 교토 여행", room.notesOf(push).single().getString("제목"))
        assertEquals(listOf("da1"), room.answersOf(push))
        assertEquals("가짜운동", (push["폴더아이콘"] as JSONArray).getJSONObject(0).getString("경로"))
        assertFalse(r.hasPendingUploads())
    }

    @Test
    fun `(라) a failed push keeps the answer, the card says the server was not reached, a later push clears it`() = runTest {
        val db = db()
        val local = backend(db)
        val store = MemoStore(local)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("ra1", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        room.pushes.clear()
        room.online = false
        memoSendAnswers(store, memoGroupOps(group(local, "ra1"), MemoAnswer.Accept), push = { r.pushAnswers() }) {}
        assertEquals(1, room.pushes.size)
        // Still in the outbox (with its back-off), the card stays locked and says so.
        assertEquals(listOf("ra1"), answerRows(db))
        assertTrue(r.nextRetryInMs()!! > 0)
        assertEquals(MEMO_PROPOSAL_SENDING, local.fetchIndex().proposals.single().status)
        assertTrue("ra1" in MemoAnswerFailures.ids.value)
        assertEquals("처리 중 · 서버에 닿지 못함 · 다시 시도해요", memoProposalLockedText(failed = true))
        assertEquals("처리 중 · 메모봇에 전하는 중", memoProposalLockedText(failed = false))
        // The server is back: the usual retry after the back-off sends it once, the line goes with the card.
        room.online = true
        now += MemoSync.BACKOFF_MS[0] + 1
        assertTrue(r.push())
        assertEquals(listOf("ra1", "ra1"), room.pushes.flatMap { room.answersOf(it) })
        assertTrue(answerRows(db).isEmpty())
        assertFalse("ra1" in MemoAnswerFailures.ids.value)
        assertTrue(local.fetchIndex().proposals.isEmpty())
    }

    @Test
    fun `nothing waiting - a press sends no empty request`() = runTest {
        val db = db()
        val room = Room()
        val r = runner(db, room)
        assertTrue(r.pushAnswers())
        assertTrue(room.pushes.isEmpty())
    }
}
