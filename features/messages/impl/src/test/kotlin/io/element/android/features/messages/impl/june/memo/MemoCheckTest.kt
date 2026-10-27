/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱33 (관리자: "새로고침 전까지는 확인 불가능한 현상 수정"), 추가요청 1:
 *  ② an answer that came back after its call gave up is taken all the same (지시 · 대화),
 *  ④ the line under an instruction never says a state the phone has not checked,
 *  ⑤ one line per look-up in the diagnosis file (no sentence, no note, no room).
 * All data is made up.
 */
class MemoCheckTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    /** 앱33 ⑤: the lines the look-ups wrote while a test ran. */
    private val lines = mutableListOf<String>()

    @After
    fun tearDown() {
        MemoCheckLog.uninstall()
    }

    private fun collectLines() {
        lines.clear()
        MemoCheckLog.install { lines += it }
    }

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    /** A memo bot room that answers 지시 and 지시조회, and can let a look-up time out like the real 30 s does. */
    private class Room(
        var pollRows: JSONArray = JSONArray(),
        var pollTimesOut: Boolean = false,
        var requestId: String = "ab12cd34",
    ) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()

        /** The 30 s of a call running out, the way the room makes it (its constructor is not ours to call). */
        private suspend fun giveUp(): Nothing {
            withTimeout(1) { delay(1_000) }
            error("이 줄에 닿지 않는다")
        }

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            return when {
                op.op == "지시" -> MemoResponse.Ok(requestId, "지시", op.args["id"] as String, null, JSONObject().put("상태", "접수"))
                op.op == "지시조회" && pollTimesOut -> giveUp()
                op.op == "지시조회" -> MemoResponse.Ok(requestId, "지시조회", null, null, JSONObject().put("결과", pollRows))
                op.op == MEMO_OP_CHAT_POLL && pollTimesOut -> giveUp()
                op.op == MEMO_OP_CHAT_POLL -> MemoResponse.Ok(requestId, MEMO_OP_CHAT_POLL, null, null, JSONObject().put("말풍선", JSONArray()))
                op.op == MEMO_OP_CHAT -> MemoResponse.Ok(requestId, MEMO_OP_CHAT, null, null, JSONObject().put("순번", 1).put("상태", "접수"))
                else -> MemoResponse.Error(requestId, op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = emptyList()
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    private fun directives(db: MemoDb, room: Room) =
        MemoDirectives(db, room, clock = { now }, dispatcher = Dispatchers.Unconfined, newId = { _, _ -> "dev-1" })

    private fun chat(db: MemoDb, room: Room) =
        MemoChat(db, room, clock = { now }, dispatcher = Dispatchers.Unconfined, newId = { _, _ -> "dev-c1" })

    /** The answer of the server for one instruction, as it comes in `결과[]`. */
    private fun row(id: String, state: String, message: String = "") =
        JSONObject().put("id", id).put("상태", state).put("메시지", message)

    private fun pollAnswer(vararg rows: JSONObject, requestId: String = "ab12cd34"): MemoResponse.Ok =
        MemoResponse.Ok(requestId, "지시조회", null, null, JSONObject().put("결과", JSONArray().apply { rows.forEach { put(it) } }))

    // ---------------------------------------------------------------- ② a late answer is still taken

    @Test
    fun `an answer that came after the look-up gave up is taken, and the same one twice changes nothing`() = runTest {
        collectLines()
        val db = db()
        val room = Room()
        val d = directives(db, room)
        val sent = d.send("태그 책상 정리해줘")!!
        assertEquals(MemoDirectiveStatus.RECEIVED, sent.status)

        // The reproduction of 앱33 ① (가): the server answered, but its answer reached the phone after the call had
        // given up (30 s). Before the fix nothing took it, and the instruction stayed open with no refresh in sight.
        room.pollTimesOut = true
        now += 31_000
        assertEquals(0, d.poll())
        assertEquals(MemoDirectiveStatus.RECEIVED, d.items.value.single().status)
        assertTrue(d.hasOpen())
        assertTrue(d.check.value.failed)

        // 앱33 ②: the same answer, handed over late, is written down with the very same rules.
        now += 1_000
        val late = pollAnswer(row(sent.id, "완료", "태그 책상을 지웠어요 (노트 3건)"))
        assertEquals(1, d.applyLate(late))
        assertEquals(MemoDirectiveStatus.DONE, d.items.value.single().status)
        assertEquals("태그 책상을 지웠어요 (노트 3건)", d.items.value.single().message)
        assertFalse(d.hasOpen())
        assertFalse(d.check.value.failed)
        assertEquals(now, d.check.value.lastOkMs)

        // Twice is safe: it is no longer open, so nothing is counted as finished again.
        assertEquals(0, d.applyLate(late))
        assertEquals(MemoDirectiveStatus.DONE, d.items.value.single().status)
        // Nothing more was sent to the server for any of this.
        assertEquals(1, room.ops.count { it.op == "지시조회" })
    }

    @Test
    fun `a late answer is only taken for a look-up, and never for a cleared instruction`() = runTest {
        collectLines()
        val db = db()
        val room = Room()
        val d = directives(db, room)
        val sent = d.send("노트 제목 정리")!!
        // An answer of another kind (the answer to the send itself) is not a look-up: it changes nothing.
        assertEquals(0, d.applyLate(MemoResponse.Ok("zz", "지시", sent.id, null, JSONObject().put("상태", "완료"))))
        assertEquals(MemoDirectiveStatus.RECEIVED, d.items.value.single().status)
        // Neither is a sync answer or an error.
        assertEquals(0, d.applyLate(MemoResponse.SyncAck("zz", JSONObject())))
        assertEquals(0, d.applyLate(MemoResponse.Error("zz", "지시조회", "모름")))
        assertEquals(MemoDirectiveStatus.RECEIVED, d.items.value.single().status)

        // 앱18 추가요청 1: an instruction taken off the screen does not come back with a late answer.
        assertEquals(1, d.clear())
        assertEquals(0, d.applyLate(pollAnswer(row(sent.id, "완료", "끝"))))
        assertTrue(d.items.value.isEmpty())
    }

    @Test
    fun `a late answer of the chat is taken once, by its number`() = runTest {
        collectLines()
        val db = db()
        val room = Room()
        val c = chat(db, room)
        c.send("오늘 메모 정리해줘")
        val bubble = JSONObject()
            .put("순번", 2)
            .put("쪽", "메모봇")
            .put("내용", "정리했어요")
        val late = MemoResponse.Ok(
            "cd34ef",
            MEMO_OP_CHAT_POLL,
            null,
            null,
            JSONObject().put("말풍선", JSONArray().put(bubble)),
        )
        assertEquals(1, c.applyLate(late))
        assertEquals(1, c.items.value.count { !it.isUser })
        // The same answer again is one row all the same (the numbers decide).
        assertEquals(1, c.applyLate(late))
        assertEquals(1, c.items.value.count { !it.isUser })
        // Not a 대화조회 answer: nothing happens.
        assertEquals(0, c.applyLate(pollAnswer(row("x", "완료"))))
    }

    @Test
    fun `the router hands an answer nobody waits for to the late reader`() = runTest {
        val router = MemoAnswerRouter()
        val got = mutableListOf<MemoResponse>()
        val remove = router.onLate { got += it }

        // While a call waits, its own answer ends that call and no reader sees it.
        val waiting = router.wait("r1")
        assertTrue(router.deliver(MemoResponse.Ok("r1", "지시조회", null, null, JSONObject())))
        assertTrue(waiting.isCompleted)
        assertTrue(got.isEmpty())
        router.drop("r1")

        // The call gave up (앱33 ① 후보 가): the answer still arrives, and now it is handed over.
        assertFalse(router.deliver(MemoResponse.Ok("r2", "지시조회", null, null, JSONObject())))
        assertEquals(listOf("r2"), got.map { it.requestId })

        // Once the screen is gone the reader is off again: nothing is kept running in the background.
        remove()
        assertFalse(router.deliver(MemoResponse.Ok("r3", "지시조회", null, null, JSONObject())))
        assertEquals(1, got.size)
    }

    // ---------------------------------------------------------------- ④ the words under an instruction

    @Test
    fun `the line says when the last look-up was, that one failed, or that the automatic ones are over`() {
        val open = MemoDirective("i1", "태그 정리", MemoDirectiveStatus.RECEIVED, sentMs = t0)
        // (1) nothing looked up yet: as before, only how long it has been.
        assertEquals("메모봇이 받았어요 · 처리 중… (12초)", memoDirectiveAnswerText(open, t0 + 12_000))
        // (2) the look-ups are working: when the last one that worked was.
        assertEquals(
            "메모봇이 받았어요 · 처리 중… (20초) · 마지막 확인 12초 전",
            memoDirectiveAnswerText(open, t0 + 20_000, MemoCheckState(lastOkMs = t0 + 8_000)),
        )
        // Just now is said in words, not in seconds.
        assertEquals(
            "메모봇이 받았어요 · 처리 중… (20초) · 마지막 확인 방금",
            memoDirectiveAnswerText(open, t0 + 20_000, MemoCheckState(lastOkMs = t0 + 15_000)),
        )
        // (3) the look-up itself did not get through: never a word about where the work stands.
        assertEquals(
            "$MEMO_CHECK_FAILED_TEXT(마지막 확인 20초 전)",
            memoDirectiveAnswerText(open, t0 + 40_000, MemoCheckState(lastOkMs = t0 + 20_000, failed = true)),
        )
        // (4) after three minutes the automatic look-ups are over: the line asks for a refresh.
        assertEquals(
            "$MEMO_CHECK_STOPPED_TEXT(마지막 확인 3분 전)",
            memoDirectiveAnswerText(open, t0 + 190_000, MemoCheckState(lastOkMs = t0 + 10_000)),
        )
        // The old line is gone for good: it said the bot was slow when the phone had stopped asking.
        assertFalse(memoDirectiveAnswerText(open, t0 + 615_000).contains("오래 걸려요"))
        assertTrue(memoDirectiveAnswerText(open, t0 + 615_000).contains("새로고침"))
        // A send on its way counts its own seconds, whatever the look-ups do.
        val sending = MemoDirective("i2", "x", MemoDirectiveStatus.SENDING, sentMs = t0)
        assertEquals("보내는 중… (2초)", memoDirectiveAnswerText(sending, t0 + 2_000, MemoCheckState(failed = true)))
        // A finished instruction says what the bot said, with no look-up words at all.
        val done = MemoDirective("i3", "x", MemoDirectiveStatus.DONE, message = "끝났어요", sentMs = t0)
        assertEquals("끝났어요", memoDirectiveAnswerText(done, t0 + 615_000, MemoCheckState(failed = true)))
    }

    @Test
    fun `a bubble of the chat says the same four things`() {
        val open = MemoBubble(1, MemoChatSide.USER, "메모 정리해줘", MemoDirectiveStatus.WORKING, sentMs = t0)
        assertEquals("메모봇이 생각하는 중… (30초)", memoBubbleStatusText(open, t0 + 30_000))
        assertEquals(
            "메모봇이 생각하는 중… (30초) · 마지막 확인 방금",
            memoBubbleStatusText(open, t0 + 30_000, MemoCheckState(lastOkMs = t0 + 28_000)),
        )
        assertEquals(
            "$MEMO_CHECK_FAILED_TEXT(마지막 확인 30초 전)",
            memoBubbleStatusText(open, t0 + 60_000, MemoCheckState(lastOkMs = t0 + 30_000, failed = true)),
        )
        val late = memoBubbleStatusText(open, t0 + 700_000, MemoCheckState(lastOkMs = t0 + 100_000))
        assertEquals("$MEMO_CHECK_STOPPED_TEXT(마지막 확인 10분 전)", late)
        assertFalse(late.contains("오래 걸려요"))
    }

    @Test
    fun `how long ago the last look-up was, in words`() {
        assertNull(memoLastCheckText(0L, t0))
        assertEquals("방금", memoLastCheckText(t0, t0))
        assertEquals("방금", memoLastCheckText(t0, t0 + 9_000))
        assertEquals("12초 전", memoLastCheckText(t0, t0 + 12_000))
        assertEquals("3분 전", memoLastCheckText(t0, t0 + 190_000))
        assertEquals("2시간 전", memoLastCheckText(t0, t0 + 7_300_000))
        // A clock that went backwards is not a negative age.
        assertEquals("방금", memoLastCheckText(t0, t0 - 5_000))
    }

    // ---------------------------------------------------------------- ③ no new timer, one look-up per happening

    @Test
    fun `the automatic look-ups are untouched - three minutes and no more`() {
        // 추가요청 1 ③: no 30 s extension was added; after three minutes nothing is asked on its own any more.
        assertEquals(3_000L, memoDirectivePollDelayMs(t0, t0))
        assertEquals(5_000L, memoDirectivePollDelayMs(t0, t0 + 30_000))
        assertEquals(15_000L, memoDirectivePollDelayMs(t0, t0 + 90_000))
        assertNull(memoDirectivePollDelayMs(t0, t0 + 181_000))
        assertNull(memoDirectivePollDelayMs(t0, t0 + 3_600_000))
        // The chat keeps its own times too (ten minutes, 앱22 결정기록 3).
        assertEquals(20_000L, memoChatPollDelayMs(t0, t0 + 200_000))
        assertNull(memoChatPollDelayMs(t0, t0 + 601_000))
    }

    @Test
    fun `one look-up per happening asks the server once, and nothing when nothing is open`() = runTest {
        collectLines()
        val db = db()
        val room = Room()
        val d = directives(db, room)
        val sent = d.send("태그 정리")!!
        // Coming back to the screen (앱33 ③): one look-up, with its own word in the diagnosis line.
        room.pollRows = JSONArray().put(row(sent.id, "처리중"))
        assertEquals(0, d.poll(MemoCheckCause.BACK))
        assertEquals(1, room.ops.count { it.op == "지시조회" })
        assertTrue(lines.last(), lines.last().contains("지시조회 복귀 답 받음"))

        // It is done: a later happening asks nothing at all (hasOpen is false, and poll itself sends nothing).
        room.pollRows = JSONArray().put(row(sent.id, "완료", "끝"))
        assertEquals(1, d.poll(MemoCheckCause.SYNC))
        assertFalse(d.hasOpen())
        val asked = room.ops.count { it.op == "지시조회" }
        assertEquals(0, d.poll(MemoCheckCause.BACK))
        assertEquals(asked, room.ops.count { it.op == "지시조회" })
    }

    // ---------------------------------------------------------------- ⑤ the diagnosis file

    @Test
    fun `a look-up line has the time, what it was, how long it took and the first characters of the id`() {
        assertEquals(
            "10-28 00:31:02 지시조회 자동 답 받음 3.2초 ab12cd",
            memoCheckLogLine(isoMs("2026-10-28T00:31:02"), MemoCheckKind.DIRECTIVE, MemoCheckCause.AUTO, MemoCheckResult.OK, 3_200, "ab12cd34-ef"),
        )
        // An answer that came in on its own has no time of its own, and a failed look-up has no id.
        assertEquals(
            "10-28 00:31:02 지시조회 늦은답 늦은 답 반영 - ab12cd",
            memoCheckLogLine(isoMs("2026-10-28T00:31:02"), MemoCheckKind.DIRECTIVE, MemoCheckCause.LATE, MemoCheckResult.LATE_APPLIED, -1, "ab12cd34"),
        )
        assertEquals(
            "10-28 00:31:02 검토조회 복귀 시간 초과 30.0초 -",
            memoCheckLogLine(isoMs("2026-10-28T00:31:02"), MemoCheckKind.REVIEW, MemoCheckCause.BACK, MemoCheckResult.TIMEOUT, 30_000, ""),
        )
    }

    @Test
    fun `the file keeps the last 200 lines and never a sentence of an instruction`() = runTest {
        collectLines()
        val db = db()
        val room = Room()
        val d = directives(db, room)
        val secret = "비밀 문장: 책상 노트를 지워줘"
        val sent = d.send(secret)!!
        room.pollRows = JSONArray().put(row(sent.id, "처리중", "처리하는 중…"))
        d.poll()
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertFalse(line, line.contains("비밀"))
            assertFalse(line, line.contains("책상"))
            assertFalse(line, line.contains(sent.id))
        }

        // 200 lines at most, the oldest go first.
        var kept = (1..199).map { "줄 $it" }
        kept = memoCheckLogTrim(kept, "줄 200")
        assertEquals(200, kept.size)
        kept = memoCheckLogTrim(kept, "줄 201")
        assertEquals(MEMO_CHECK_LOG_MAX, kept.size)
        assertEquals("줄 2", kept.first())
        assertEquals("줄 201", kept.last())
        // Empty lines of a half-written file are dropped, not kept as rows.
        assertEquals(listOf("줄 1", "줄 2"), memoCheckLogTrim(listOf("줄 1", "", "  "), "줄 2"))
    }

    @Test
    fun `with nowhere to write the lines nothing breaks`() {
        MemoCheckLog.uninstall()
        MemoCheckLog.add(MemoCheckKind.DIRECTIVE, MemoCheckCause.AUTO, MemoCheckResult.OK, 1_000, "abcdef", t0)
    }

    /** 2026-10-28 00:31:02 of the phone's own time zone (the file writes the time the phone shows). */
    private fun isoMs(@Suppress("UNUSED_PARAMETER") iso: String): Long =
        java.util.GregorianCalendar(2026, java.util.Calendar.OCTOBER, 28, 0, 31, 2).timeInMillis
}
