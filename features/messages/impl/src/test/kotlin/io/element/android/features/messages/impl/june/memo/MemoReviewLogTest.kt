/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱32 (관리자: "검토요청 햇는데 검토 내용이 없는건지 아니면 반영이 안된건지 대기중엔 알수가 없음"): what the phone remembers
 * about the reviews it asked for, and the words the card mark and the 검토 현황 list are drawn from.
 */
class MemoReviewLogTest : RobolectricTest() {
    private val t0 = 1_761_500_000_000L

    private fun entry(
        id: String,
        batch: String = "b-1",
        askedMs: Long = t0,
        state: String = "",
        result: String = "",
        directiveResult: String = "",
        queuePos: Int? = null,
        lastActivity: String = "",
        checkedMs: Long = 0L,
        boot: String = "a1",
        stopped: Boolean = false,
        seen: Boolean = false,
        skipped: String = "",
        withDirective: Boolean = false,
    ) = MemoReviewEntry(
        id = id,
        batch = batch,
        askedMs = askedMs,
        withDirective = withDirective,
        state = state,
        result = result,
        directiveResult = directiveResult,
        queuePos = queuePos,
        lastActivity = lastActivity,
        checkedMs = checkedMs,
        boot = boot,
        stopped = stopped,
        seen = seen,
        skipped = skipped,
    )

    private fun row(
        id: String,
        state: String,
        result: String = "",
        progress: Boolean = true,
        directiveResult: String = "태그 반영",
        queuePos: Int? = null,
        last: String = "2026-10-27T02:00:00+09:00",
    ) = JSONObject().put("id", id).put("상태", state).put("결과", result).put("시각", last).put("사유", "").apply {
        if (progress) {
            put("지시결과", directiveResult)
            queuePos?.let { put("대기순번", it) }
            put("마지막활동", last)
        }
    }

    private fun answer(vararg rows: JSONObject, boot: String = "a1") =
        JSONObject().put("op", MEMO_OP_REVIEW_STATUS).put("상태", JSONArray(rows.toList())).put("서버기동", boot)

    // ---------------------------------------------------------------- the server fields (요구 ④, 서버26)

    @Test
    fun `review_status rows carry the new progress fields, and an old server gives none of them`() {
        val states = memoReviewStates(answer(row("n1", "대기", queuePos = 2), row("n2", "완료", "제안 2건")))
        assertEquals(2, states.size)
        assertEquals("태그 반영", states[0].directiveResult)
        assertEquals(2, states[0].queuePos)
        assertEquals("2026-10-27T02:00:00+09:00", states[0].lastActivity)
        // 서버25 도장: the boot id of the answer (the phone tells a restart by it).
        assertEquals("a1", memoReviewServerBoot(answer(row("n1", "대기"))))
        // 옛 서버(서버 25차 이하): no 지시결과, no 대기순번, no 마지막활동 — and nothing else changes.
        val old = memoReviewStates(answer(row("n1", "완료", "제안 없음", progress = false)))
        assertEquals("", old[0].directiveResult)
        assertNull(old[0].queuePos)
        assertEquals("", old[0].lastActivity)
        assertEquals("완료", old[0].state)
        assertEquals("제안 없음", old[0].result)
        assertEquals("", memoReviewServerBoot(JSONObject()))
    }

    @Test
    fun `a note being reviewed has queue place 0 and a waiting one keeps its number`() {
        val states = memoReviewStates(answer(row("n1", "검토중", queuePos = 0), row("n2", "대기", queuePos = 3)))
        assertEquals(0, states[0].queuePos)
        assertEquals(3, states[1].queuePos)
    }

    // ---------------------------------------------------------------- the phases (요구 ②·⑤)

    @Test
    fun `the phase of an entry follows the server state`() {
        assertEquals(MemoReviewPhase.Asked, memoReviewPhase(entry("n1"), t0 + 1_000, "a1"))
        assertEquals(MemoReviewPhase.Queued, memoReviewPhase(entry("n1", state = "대기", lastActivity = ""), t0 + 1_000, "a1"))
        assertEquals(MemoReviewPhase.Working, memoReviewPhase(entry("n1", state = "검토중"), t0 + 1_000, "a1"))
        assertEquals(MemoReviewPhase.Done, memoReviewPhase(entry("n1", state = "완료", result = "제안 2건"), t0 + 1_000, "a1"))
    }

    @Test
    fun `a review the server does not know is a thing to look at, not a quiet nothing`() {
        val e = entry("n1", state = "없음", checkedMs = t0 + 2_000)
        assertEquals(MemoReviewPhase.Attention, memoReviewPhase(e, t0 + 3_000, "a1"))
        assertTrue(memoReviewStateText(e, t0 + 3_000, "a1").startsWith("서버가 모름"))
        assertTrue(memoReviewStateText(e, t0 + 3_000, "a1").contains("다시 요청"))
    }

    @Test
    fun `a note waiting longer than three minutes says how long and where in the queue it is`() {
        val short = entry("n1", state = "대기", queuePos = 1, askedMs = t0, checkedMs = t0 + 60_000)
        assertEquals(MemoReviewPhase.Queued, memoReviewPhase(short, t0 + 60_000, "a1"))
        assertEquals("서버 대기 · 대기 1번째", memoReviewStateText(short, t0 + 60_000, "a1"))
        val long = entry("n1", state = "대기", queuePos = 4, askedMs = t0, checkedMs = t0 + 300_000)
        assertEquals(MemoReviewPhase.Attention, memoReviewPhase(long, t0 + 300_000, "a1"))
        assertEquals("서버 대기 5분째 · 대기 4번째", memoReviewStateText(long, t0 + 300_000, "a1"))
        assertEquals(180_000L, MEMO_REVIEW_WAIT_LONG_MS)
    }

    @Test
    fun `the last activity of the server is what the waiting time counts from`() {
        // The request is old but the server touched it a moment ago: not a stall.
        val e = entry("n1", state = "대기", askedMs = t0, lastActivity = memoIso(t0 + 540_000), checkedMs = t0 + 600_000)
        assertEquals(MemoReviewPhase.Queued, memoReviewPhase(e, t0 + 600_000, "a1"))
    }

    @Test
    fun `a server that restarted after the request asks to be looked at again`() {
        val e = entry("n1", state = "대기", boot = "a1", checkedMs = t0 + 1_000)
        assertEquals(MemoReviewPhase.Attention, memoReviewPhase(e, t0 + 2_000, "b2"))
        assertEquals("서버가 다시 시작됐습니다. 다시 확인해 주세요", memoReviewStateText(e, t0 + 2_000, "b2"))
        // A note the server does not know is the more urgent fact (결정기록 D8).
        val gone = entry("n1", state = "없음", boot = "a1", checkedMs = t0 + 1_000)
        assertTrue(memoReviewStateText(gone, t0 + 2_000, "b2").startsWith("서버가 모름"))
        // The boot id of a finished review does not matter any more.
        val done = entry("n1", state = "완료", result = "제안 1건", boot = "a1")
        assertEquals(MemoReviewPhase.Done, memoReviewPhase(done, t0 + 2_000, "b2"))
    }

    @Test
    fun `a follow-up that gave up says so and points at the refresh`() {
        val e = entry("n1", state = "검토중", stopped = true, checkedMs = t0 + 1_000)
        assertEquals(MemoReviewPhase.Attention, memoReviewPhase(e, t0 + 2_000, "a1"))
        // 앱33 추가요청 1 ④: the same words as the instructions use, with when the last look-up that worked was.
        assertEquals("$MEMO_CHECK_STOPPED_TEXT(마지막 확인 방금)", memoReviewStateText(e, t0 + 2_000, "a1"))
        // A look-up after that clears it.
        val again = memoReviewLogLooked(listOf(e), memoReviewStates(answer(row("n1", "완료", "제안 1건"))), "a1", t0 + 3_000)
        assertFalse(again.first().stopped)
        assertEquals(MemoReviewPhase.Done, memoReviewPhase(again.first(), t0 + 3_000, "a1"))
    }

    @Test
    fun `a note the server skipped keeps its reason`() {
        val e = entry("n1", skipped = "휴지통")
        assertEquals(MemoReviewPhase.Attention, memoReviewPhase(e, t0 + 1_000, "a1"))
        assertEquals("보내지 못함 — 휴지통", memoReviewStateText(e, t0 + 1_000, "a1"))
    }

    // ---------------------------------------------------------------- 검토 결과 vs 지시 결과 (요구 ④)

    @Test
    fun `the review result and the directive result are two separate lines`() {
        val e = entry("n1", state = "완료", result = "제안 2건", directiveResult = "제목·태그 반영")
        assertEquals("검토 결과: 제안 2건", memoReviewResultText(e))
        assertEquals("지시 결과: 제목·태그 반영", memoReviewDirectiveResultText(e))
        val none = entry("n2", state = "완료", result = "제안 없음", directiveResult = "지시 없음")
        assertEquals("검토 결과: 제안 없음", memoReviewResultText(none))
        assertEquals("지시 결과: 지시 없음", memoReviewDirectiveResultText(none))
    }

    @Test
    fun `an old server that tells no directive result says it is unknown, never 제안 없음`() {
        val e = entry("n1", state = "완료", result = "제안 없음", directiveResult = "")
        assertEquals("검토 결과: 제안 없음", memoReviewResultText(e))
        assertEquals("지시 결과 모름(서버가 알려 주지 않음)", memoReviewDirectiveResultText(e))
        // Nothing of the review result leaks into the directive line.
        assertFalse(memoReviewDirectiveResultText(e).contains("제안"))
        // Still waiting: no result line at all.
        assertEquals("", memoReviewResultText(entry("n3", state = "대기")))
    }

    // ---------------------------------------------------------------- the log itself (요구 ①)

    @Test
    fun `asking for reviews writes one line per note of the press, newest first`() {
        val log = memoReviewLogAsked(emptyList(), listOf("n1", "n2"), "b-1", t0, withDirective = true)
        assertEquals(listOf("n1", "n2"), log.map { it.id })
        assertTrue(log.all { it.batch == "b-1" && it.askedMs == t0 && it.withDirective })
        assertTrue(log.all { it.state.isEmpty() && !it.seen })
        val later = memoReviewLogAsked(log, listOf("n3"), "b-2", t0 + 10_000, withDirective = false)
        assertEquals(listOf("n3", "n1", "n2"), later.map { it.id })
        assertFalse(later.first().withDirective)
    }

    @Test
    fun `asking for the same note again rewrites its line instead of adding a second one`() {
        val first = memoReviewLogAsked(emptyList(), listOf("n1"), "b-1", t0, withDirective = false)
        val done = memoReviewLogLooked(first, memoReviewStates(answer(row("n1", "완료", "제안 1건"))), "a1", t0 + 1_000)
        val again = memoReviewLogAsked(done, listOf("n1"), "b-2", t0 + 60_000, withDirective = true)
        assertEquals(1, again.size)
        assertEquals("b-2", again.first().batch)
        assertEquals(t0 + 60_000, again.first().askedMs)
        assertEquals("", again.first().state)
        assertEquals("", again.first().result)
        assertTrue(again.first().withDirective)
    }

    @Test
    fun `the log keeps the newest fifty lines and never the directive text`() {
        assertEquals(50, MEMO_REVIEW_LOG_MAX)
        var log = emptyList<MemoReviewEntry>()
        (1..60).forEach { i -> log = memoReviewLogAsked(log, listOf("n$i"), "b-$i", t0 + i * 1_000L, withDirective = true) }
        assertEquals(50, log.size)
        assertEquals("n60", log.first().id)
        assertEquals("n11", log.last().id)
        // 요구 ①: the words of a directive are never written down, only that there was one.
        val json = memoReviewLogJson(log)
        assertFalse(json.contains("지시문"))
        assertFalse(json.contains("음식점"))
        val one = memoReviewLogAsked(emptyList(), listOf("n1"), "b-1", t0, withDirective = true)
        assertTrue(memoReviewLogJson(one).contains("\"d\":true"))
    }

    @Test
    fun `the log goes to text and back exactly, and broken text reads as an empty log`() {
        val log = memoReviewLogLooked(
            memoReviewLogAsked(emptyList(), listOf("n1", "n2"), "b-1", t0, withDirective = true),
            memoReviewStates(answer(row("n1", "대기", queuePos = 2), row("n2", "완료", "제안 1건", directiveResult = "본문 반영"))),
            "a1",
            t0 + 5_000,
        )
        val back = memoReviewLogParse(memoReviewLogJson(log))
        assertEquals(log, back)
        assertEquals(2, back.first().queuePos)
        assertEquals("본문 반영", back[1].directiveResult)
        assertTrue(memoReviewLogParse(null).isEmpty())
        assertTrue(memoReviewLogParse("").isEmpty())
        assertTrue(memoReviewLogParse("{").isEmpty())
        assertTrue(memoReviewLogParse("[{\"x\":1}]").isEmpty())
    }

    @Test
    fun `the phone database keeps the log without a new table`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        val db = MemoDb(driver)
        val store = MemoDbReviewLog(db)
        assertTrue(store.read().isEmpty())
        val log = memoReviewLogAsked(emptyList(), listOf("n1"), "b-1", t0, withDirective = false)
        store.write(log)
        assertEquals(log, store.read())
        // It lives in the sync_state key-value table the diagnosis also uses (no schema change).
        assertNotNull(db.state(MEMO_REVIEW_LOG_KEY))
        assertEquals("review_log", MEMO_REVIEW_LOG_KEY)
    }

    // ---------------------------------------------------------------- what a look-up changes

    @Test
    fun `a look-up writes the state, the results and the queue place of each note`() {
        val log = memoReviewLogAsked(emptyList(), listOf("n1", "n2"), "b-1", t0, withDirective = false)
        val after = memoReviewLogLooked(
            log,
            memoReviewStates(answer(row("n1", "검토중", queuePos = 0), row("n2", "완료", "제안 없음", directiveResult = "변경 없음"), boot = "b2")),
            "b2",
            t0 + 7_000,
        )
        val n1 = after.first { it.id == "n1" }
        val n2 = after.first { it.id == "n2" }
        assertEquals("검토중", n1.state)
        assertEquals(0, n1.queuePos)
        assertEquals(t0 + 7_000, n1.checkedMs)
        assertEquals("제안 없음", n2.result)
        assertEquals("변경 없음", n2.directiveResult)
        // The boot id of the request stays: it is what a restart is told from (결정기록 D8 c).
        assertEquals("a1", memoReviewLogSent(log, "b-1", listOf("n1", "n2"), emptyList(), null, "a1").first().boot)
        // A row about a note the log does not know changes nothing.
        assertEquals(after, memoReviewLogLooked(after, memoReviewStates(answer(row("zz", "완료", "제안 1건"))), "b2", t0 + 8_000))
    }

    @Test
    fun `the answer of the press marks the notes the server skipped and the ones that never went out`() {
        val log = memoReviewLogAsked(emptyList(), listOf("n1", "n2", "n3"), "b-1", t0, withDirective = false)
        val after = memoReviewLogSent(
            log,
            "b-1",
            accepted = listOf("n1"),
            skipped = listOf(MemoReviewSkip("n2", "휴지통")),
            failure = "보내지 못했습니다",
            boot = "a1",
        )
        assertEquals("", after.first { it.id == "n1" }.skipped)
        assertEquals("휴지통", after.first { it.id == "n2" }.skipped)
        assertEquals("보내지 못함", after.first { it.id == "n3" }.skipped)
        assertTrue(after.all { it.boot == "a1" })
        // Without a failure the notes not answered for stay as they are (a later look-up tells).
        val ok = memoReviewLogSent(log, "b-1", accepted = listOf("n1", "n2", "n3"), skipped = emptyList(), failure = null, boot = "a1")
        assertTrue(ok.all { it.skipped.isEmpty() })
    }

    @Test
    fun `giving up on a follow-up is remembered for the notes of that follow-up only`() {
        val log = memoReviewLogAsked(emptyList(), listOf("n1", "n2"), "b-1", t0, withDirective = false)
        val after = memoReviewLogGaveUp(log, listOf("n1"))
        assertTrue(after.first { it.id == "n1" }.stopped)
        assertFalse(after.first { it.id == "n2" }.stopped)
    }

    // ---------------------------------------------------------------- the card mark (요구 ②)

    @Test
    fun `a note never asked for has no second line, and a finished one loses it when it was seen`() {
        val log = memoReviewLogAsked(emptyList(), listOf("n1"), "b-1", t0, withDirective = false)
        assertNull(memoReviewMarkOf(log, "other", t0 + 1_000, "a1"))
        assertEquals(MemoReviewPhase.Asked, memoReviewMarkOf(log, "n1", t0 + 1_000, "a1"))
        val done = memoReviewLogLooked(log, memoReviewStates(answer(row("n1", "완료", "제안 1건"))), "a1", t0 + 2_000)
        assertEquals(MemoReviewPhase.Done, memoReviewMarkOf(done, "n1", t0 + 3_000, "a1"))
        val seen = memoReviewLogSeen(done, "n1", t0 + 4_000)
        assertNull(memoReviewMarkOf(seen, "n1", t0 + 5_000, "a1"))
    }

    @Test
    fun `seeing a note that is still being reviewed keeps its mark`() {
        val log = memoReviewLogLooked(
            memoReviewLogAsked(emptyList(), listOf("n1"), "b-1", t0, withDirective = false),
            memoReviewStates(answer(row("n1", "검토중"))),
            "a1",
            t0 + 1_000,
        )
        val seen = memoReviewLogSeen(log, "n1", t0 + 2_000)
        assertFalse(seen.first().seen)
        assertEquals(MemoReviewPhase.Working, memoReviewMarkOf(seen, "n1", t0 + 3_000, "a1"))
    }

    // ---------------------------------------------------------------- the 검토 현황 list (요구 ③·⑦)

    @Test
    fun `the head line counts what is running and what is done but not seen`() {
        val log = memoReviewLogLooked(
            memoReviewLogAsked(emptyList(), listOf("n1", "n2", "n3"), "b-1", t0, withDirective = false),
            memoReviewStates(answer(row("n1", "대기", queuePos = 1), row("n2", "완료", "제안 1건"), row("n3", "완료", "제안 없음"))),
            "a1",
            t0 + 1_000,
        )
        assertEquals("검토 현황 · 진행 1 · 완료 2", memoReviewHeadText(log, t0 + 2_000, "a1"))
        assertEquals(1, memoReviewRunningCount(log, t0 + 2_000, "a1"))
        assertTrue(memoReviewHeadText(emptyList(), t0, "a1").isEmpty())
    }

    @Test
    fun `one press of many notes is one row that tells how many of them are done`() {
        val many = memoReviewLogAsked(emptyList(), listOf("n1", "n2", "n3"), "b-1", t0, withDirective = false)
        val one = memoReviewLogAsked(many, listOf("n9"), "b-2", t0 + 1_000, withDirective = false)
        val log = memoReviewLogLooked(one, memoReviewStates(answer(row("n1", "완료", "제안 1건"), row("n2", "대기", queuePos = 1))), "a1", t0 + 2_000)
        val rows = memoReviewRows(log, t0 + 3_000, "a1")
        // Newest press first: the single note, then the group of three.
        assertEquals(2, rows.size)
        val single = rows[0] as MemoReviewRow.Note
        assertEquals("n9", single.entry.id)
        val group = rows[1] as MemoReviewRow.Group
        assertEquals("b-1", group.batch)
        assertEquals(listOf("n1", "n2", "n3"), group.entries.map { it.id })
        assertEquals("3개 중 1개 완료", memoReviewGroupText(group))
    }

    @Test
    fun `the time since the press is said in words`() {
        assertEquals("방금", memoReviewAgoText(0))
        assertEquals("방금", memoReviewAgoText(59_000))
        assertEquals("1분 전", memoReviewAgoText(60_000))
        assertEquals("59분 전", memoReviewAgoText(59 * 60_000L))
        assertEquals("1시간 전", memoReviewAgoText(60 * 60_000L))
        assertEquals("2일 전", memoReviewAgoText(48 * 3_600_000L))
    }

    // ---------------------------------------------------------------- the manual refresh (요구 ⑥, 원칙)

    @Test
    fun `the refresh asks about the notes still running, at most thirty, newest press first`() {
        assertEquals(30, MEMO_REVIEW_REFRESH_MAX)
        var log = emptyList<MemoReviewEntry>()
        (1..40).forEach { i -> log = memoReviewLogAsked(log, listOf("n$i"), "b-$i", t0 + i * 1_000L, withDirective = false) }
        val done = memoReviewLogLooked(log, memoReviewStates(answer(row("n40", "완료", "제안 1건"))), "a1", t0 + 50_000)
        val ids = memoReviewLogRunningIds(done, t0 + 60_000)
        assertEquals(30, ids.size)
        assertFalse("n40" in ids)
        assertEquals("n39", ids.first())
        // A note the server skipped is not asked about again (it never went on the queue).
        val skipped = memoReviewLogSent(memoReviewLogAsked(emptyList(), listOf("n1"), "b-1", t0, false), "b-1", emptyList(), listOf(MemoReviewSkip("n1", "휴지통")), null, "a1")
        assertTrue(memoReviewLogRunningIds(skipped, t0 + 1_000).isEmpty())
    }

    @Test
    fun `the keeper writes to the phone database and never talks to the server by itself`() = runBlocking {
        val store = FakeReviewLogStore()
        val keeper = MemoReviewLogKeeper(store) { t0 }
        keeper.load()
        keeper.asked(listOf("n1", "n2"), "b-1", withDirective = false)
        assertEquals(listOf("n1", "n2"), keeper.entries.value.map { it.id })
        assertEquals(keeper.entries.value, store.saved)
        keeper.sent("b-1", listOf("n1"), listOf(MemoReviewSkip("n2", "휴지통")), null, "a1")
        keeper.looked(memoReviewStates(answer(row("n1", "완료", "제안 2건"))), "a1")
        assertEquals("완료", keeper.entries.value.first { it.id == "n1" }.state)
        // A second keeper over the same store reads back what was written (the screen was closed and opened).
        val again = MemoReviewLogKeeper(store) { t0 }
        again.load()
        assertEquals(keeper.entries.value, again.entries.value)
        // 원칙: nothing here starts a timer or a coroutine of its own.
        assertEquals(0, store.writes - 3)
    }

    @Test
    fun `one refresh asks the server exactly once and never again by itself`() = runBlocking {
        val room = CountingRoom()
        val reviewer = MemoReviewer(room, clock = { t0 })
        val keeper = MemoReviewLogKeeper(FakeReviewLogStore()) { t0 }
        keeper.asked(listOf("n1", "n2"), "b-1", withDirective = false)
        val asked = memoReviewRefresh(keeper, reviewer)
        assertEquals(2, asked)
        assertEquals(1, room.ops.size)
        assertEquals(MEMO_OP_REVIEW_STATUS, room.ops.first().op)
        assertEquals("완료", keeper.entries.value.first { it.id == "n1" }.state)
        // Time passing on its own asks nothing more (no timer anywhere in the log side).
        kotlinx.coroutines.delay(50)
        assertEquals(1, room.ops.size)
        // Nothing running: nothing is asked.
        assertEquals(0, memoReviewRefresh(keeper, reviewer))
        assertEquals(1, room.ops.size)
    }

    /** A memo bot room that counts the ops it gets and answers every `review_status` with 완료. */
    private class CountingRoom : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            val ids = memoArgStrings(op.args["ids"])
            val rows = JSONArray(
                ids.map { JSONObject().put("id", it).put("상태", "완료").put("결과", "제안 1건").put("지시결과", "지시 없음").put("마지막활동", "2026-10-27T02:00:00+09:00") }
            )
            return MemoResponse.Ok("r", op.op, null, null, JSONObject().put("op", op.op).put("상태", rows).put("서버기동", "a1"))
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    private class FakeReviewLogStore : MemoReviewLogStore {
        var saved: List<MemoReviewEntry> = emptyList()
        var writes = 0

        override fun read(): List<MemoReviewEntry> = saved

        override fun write(entries: List<MemoReviewEntry>) {
            saved = entries
            writes++
        }
    }
}
