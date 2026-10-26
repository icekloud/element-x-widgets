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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
 * 앱30 (관리자 2026-10-26: 수락 카드가 "처리 중 · 메모봇에 전하는 중"에서 오래 멈춤): the server had done the accept and
 * answered, but the phone never read that answer (an encrypted room only hands answers to the open screen), so the
 * card stayed locked. An answer that stays unconfirmed is settled against the server's proposal list instead of
 * waiting for an answer that cannot arrive. All data is made up.
 */
class MemoAnswerConfirmTest : RobolectricTest() {
    private val t0 = 1_761_440_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    /**
     * The memo bot room. [proposals] is the `제안[]` of the index state (null: it cannot be read); [answerPush] false
     * makes `sync_push` end without the answer the phone waits for — the real case, where the answer event of the
     * encrypted room never reaches the waiting call. [onFetch] runs while the proposal list is being read.
     */
    private class Room(var proposals: List<JSONObject>? = emptyList()) : MemoSyncTransport {
        var answerPush = true
        var pushHangs = false
        var onFetch: (suspend () -> Unit)? = null
        val ops = mutableListOf<String>()
        var fetches = 0
        var pushGate: CompletableDeferred<Unit>? = null

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op.op
            return when (op.op) {
                "sync_pull" -> MemoResponse.Sync("r", JSONObject().put("노트", JSONArray()).put("기준", 0))
                "sync_push" -> {
                    pushGate?.await()
                    if (pushHangs) awaitCancellation()
                    if (!answerPush) withTimeout(1) { awaitCancellation() }
                    val answers = JSONArray()
                    (op.args["제안응답"] as? JSONArray)?.let { a ->
                        for (i in 0 until a.length()) {
                            val o = a.getJSONObject(i)
                            answers.put(JSONObject().put("id", o.getString("id")).put("상태", o.getString("응답")))
                        }
                    }
                    MemoResponse.SyncAck("r", JSONObject().put("결과", JSONArray()).put("제안결과", answers).put("서버순번", 0))
                }
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? {
            fetches++
            onFetch?.invoke()
            return proposals
        }

        override suspend fun upload(bytes: ByteArray): String = throw MemoSendException("no uploads here")
        override suspend fun maxUploadSize(): Long? = null

        fun pushes() = ops.count { it == "sync_push" }
        fun pulls() = ops.count { it == "sync_pull" }
    }

    private fun runner(db: MemoDb, room: Room) = MemoSyncRunner(db, room, clock = { now }, readFile = { null })

    private fun card(id: String, note: String) = JSONObject()
        .put("id", id)
        .put("종류", "제목")
        .put("요약", "가짜 제안 $id")
        .put("상태", "대기")
        .put("노트", note)
        .put("제안내용", JSONObject().put("제목", "가짜 제목"))

    private fun answerRows(db: MemoDb) = db.outbox().filter { it.kind == MEMO_OUTBOX_PROPOSAL }.map { it.target }

    private suspend fun answer(local: MemoLocalBackend, id: String) {
        local.call(MemoOp("accept", mapOf("id" to id)), "q")
    }

    // ---------------------------------------------------------------- ① 확인 받기 한 번

    @Test
    fun `app30 (가) the answer went up but no result came - one confirm pull closes the card`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("aa01.ttl", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        // The press: the answer is written and goes up at once, but its answer event is never read (time out).
        room.answerPush = false
        answer(local, "aa01.ttl")
        assertFalse(r.pushAnswers())
        // The answer waits and the card stays locked: nothing says the server did it.
        assertEquals(listOf("aa01.ttl"), answerRows(db))
        assertEquals(MEMO_PROPOSAL_SENDING, local.fetchIndex().proposals.single().status)
        // 앱30 ②: one confirm pull is asked for (not a timer: it is null while everything goes well).
        val confirm = r.answerConfirm.value
        assertNotNull(confirm)
        assertEquals(MEMO_ANSWER_CONFIRM_MS, confirm!!.waitMs)
        // The server did the accept meanwhile: its proposal list no longer has the card.
        room.proposals = emptyList()
        val pushesBefore = room.pushes()
        now += MEMO_ANSWER_CONFIRM_MS
        assertTrue(r.confirmAnswers(confirm.seq))
        // The card is closed, the answer is not sent again (D6: no new duplicate push).
        assertTrue(answerRows(db).isEmpty())
        assertTrue(local.fetchIndex().proposals.isEmpty())
        assertEquals(pushesBefore, room.pushes())
        assertNull(r.answerConfirm.value)
    }

    @Test
    fun `app30 (나) the server still lists the card - the answer is kept and nothing is asked again`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("bb01.ttl", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        room.answerPush = false
        answer(local, "bb01.ttl")
        assertFalse(r.pushAnswers())
        val confirm = r.answerConfirm.value!!
        // The server has not applied it (it still waits): nothing is dropped.
        assertTrue(r.confirmAnswers(confirm.seq))
        assertEquals(listOf("bb01.ttl"), answerRows(db))
        assertEquals(MEMO_PROPOSAL_SENDING, local.fetchIndex().proposals.single().status)
        // One confirm per push round, never a chain of them: the answer now waits for the usual outbox retry.
        assertNull(r.answerConfirm.value)
    }

    @Test
    fun `app30 (다) the index could not be read - nothing is dropped`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("cc01.ttl", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        room.answerPush = false
        answer(local, "cc01.ttl")
        assertFalse(r.pushAnswers())
        val confirm = r.answerConfirm.value!!
        room.proposals = null
        r.confirmAnswers(confirm.seq)
        assertEquals(listOf("cc01.ttl"), answerRows(db))
    }

    @Test
    fun `app30 (라) a stale confirm number does nothing once the answer got through`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("dd01.ttl", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        room.answerPush = false
        answer(local, "dd01.ttl")
        assertFalse(r.pushAnswers())
        val first = r.answerConfirm.value!!
        // The usual retry got through meanwhile: nothing waits any more.
        room.answerPush = true
        now += MemoSync.BACKOFF_MS[0] + 1
        assertTrue(r.push(force = true))
        assertTrue(answerRows(db).isEmpty())
        assertNull(r.answerConfirm.value)
        val pulls = room.pulls()
        // The screen was already waiting for the old confirm: it pulls nothing.
        assertTrue(r.confirmAnswers(first.seq))
        assertEquals(pulls, room.pulls())
    }

    // ---------------------------------------------------------------- ② 모든 받기에서 정리

    @Test
    fun `app30 (마) the next screen open pulls and settles an answer the server no longer lists`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("ee01.ttl", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        room.answerPush = false
        answer(local, "ee01.ttl")
        assertFalse(r.pushAnswers())
        assertEquals(listOf("ee01.ttl"), answerRows(db))
        // The screen is opened again later; the server applied the accept and took the card off its list.
        room.proposals = emptyList()
        room.answerPush = true
        now += 600_000
        assertTrue(r.syncNow(push = false))
        assertTrue(answerRows(db).isEmpty())
        assertTrue(local.fetchIndex().proposals.isEmpty())
        // 앱9 C: it does not come back as a card when an older index lists it again.
        assertTrue("ee01.ttl" in memoAnsweredProposals(db))
    }

    @Test
    fun `app30 (바) an answer written while the list was being read is not settled`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("ff01.ttl", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        // The list read of the next pull no longer has the card, but the press happens while that read is on its way:
        // this answer may well be about a card the server still has (D8).
        room.proposals = emptyList()
        room.onFetch = {
            now += 1
            answer(local, "ff01.ttl")
        }
        assertTrue(r.syncNow(push = false))
        assertEquals(listOf("ff01.ttl"), answerRows(db))
        assertFalse("ff01.ttl" in memoAnsweredProposals(db))
    }

    // ---------------------------------------------------------------- ③ 취소

    @Test
    fun `app30 (사) the push is cancelled with the screen - the answer stays, the diagnosis says 취소`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("gg01.ttl", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        // The first sync sent the new note: only what the press does is counted from here.
        room.ops.clear()
        room.pushHangs = true
        answer(local, "gg01.ttl")
        val press = launch { r.pushAnswers() }
        runCurrent()
        assertEquals(1, room.pushes())
        // The screen goes away: the scope of the press is cancelled while the answer is on its way.
        press.cancel()
        advanceUntilIdle()
        // The answer is kept (the card stays locked) and nothing claims the server was not reached.
        assertEquals(listOf("gg01.ttl"), answerRows(db))
        assertFalse("gg01.ttl" in MemoAnswerFailures.ids.value)
        assertEquals("처리 중 · 메모봇에 전하는 중", memoProposalLockedText(failed = false))
        // 앱30 ③: the diagnosis tells a cancel from a send failure, and a confirm is asked for.
        val trace = memoAnswerTraceOf(db)
        assertNotNull(trace)
        assertEquals(MEMO_ANSWER_END_CANCELLED, trace!!.result)
        assertNotNull(r.answerConfirm.value)
    }

    // ---------------------------------------------------------------- ④ 진단 줄

    @Test
    fun `app30 (아) the diagnosis line of one accept - pressed, sent, read, and the last four of the id`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("20261026-001359-ilps.ttl", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        answer(local, "20261026-001359-ilps.ttl")
        val pressed = memoAnswerTraceOf(db)
        assertNotNull(pressed)
        assertEquals(now, pressed!!.pressedMs)
        assertNull(pressed.sentMs)
        now += 2_000
        assertTrue(r.pushAnswers())
        val done = memoAnswerTraceOf(db)!!
        assertEquals(t0, done.pressedMs)
        assertEquals(t0 + 2_000, done.sentMs)
        assertEquals(t0 + 2_000, done.doneMs)
        assertEquals(MEMO_ANSWER_END_ANSWERED, done.result)
        // The line: times and the last four characters of the id only, never the note or the title.
        val line = memoAnswerTraceLine(done, t0 + 2_000)
        assertTrue(line.startsWith("마지막 수락: 눌림 "))
        assertTrue(line.contains(" · 올림 "))
        assertTrue(line.contains(" · $MEMO_ANSWER_END_ANSWERED "))
        assertTrue(line.endsWith("(…ilps)"))
        assertFalse(line.contains("가짜 메모"))
        assertEquals("마지막 수락: 없음", memoAnswerTraceLine(null, now))
        // The line is the last of the proposal lines, so the lines before it keep their place.
        val lines = memoProposalDiagLines(memoProposalDiagOf(db), null, now)
        assertEquals(line, lines.last())
        assertTrue(lines[0].startsWith("제안 목록(방 상태):"))
    }

    @Test
    fun `app30 (자) a settle without an answer event is told from one with it`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("가짜 메모")
        val room = Room(proposals = listOf(card("hh01.ttl", note)))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        room.answerPush = false
        answer(local, "hh01.ttl")
        assertFalse(r.pushAnswers())
        assertEquals(MEMO_ANSWER_END_UNREAD, memoAnswerTraceOf(db)!!.result)
        room.proposals = emptyList()
        now += MEMO_ANSWER_CONFIRM_MS
        assertTrue(r.confirmAnswers(r.answerConfirm.value!!.seq))
        val t = memoAnswerTraceOf(db)!!
        assertEquals(MEMO_ANSWER_END_LISTED, t.result)
        assertEquals(now, t.doneMs)
        assertTrue(memoAnswerTraceLine(t, now).contains(" · $MEMO_ANSWER_END_LISTED "))
    }

    // ---------------------------------------------------------------- ⑤ 화면 쪽(보일 때만, 한 번만)

    @Test
    fun `app30 (차) the confirm waits only while the screen is shown and runs once`() = runTest {
        var slept = 0L
        val ran = mutableListOf<Long>()
        val sleep: suspend (Long) -> Unit = { slept += it }
        val check: suspend (Long) -> Unit = { ran += it }
        // Hidden: nothing waits, nothing runs.
        assertFalse(memoConfirmAnswersWhenDue(false, MemoAnswerConfirm(1, MEMO_ANSWER_CONFIRM_MS), sleep, check))
        assertEquals(0L, slept)
        assertTrue(ran.isEmpty())
        // Nothing asked for: nothing waits (no timer while the answers go through).
        assertFalse(memoConfirmAnswersWhenDue(true, null, sleep, check))
        assertEquals(0L, slept)
        // Asked for and shown: it waits once, then confirms that number once.
        assertTrue(memoConfirmAnswersWhenDue(true, MemoAnswerConfirm(7, MEMO_ANSWER_CONFIRM_MS), sleep, check))
        assertEquals(MEMO_ANSWER_CONFIRM_MS, slept)
        assertEquals(listOf(7L), ran)
    }
}
