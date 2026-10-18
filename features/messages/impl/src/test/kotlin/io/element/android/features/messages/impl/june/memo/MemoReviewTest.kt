/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱17: the AI review asked for from a card (A), the selection (B) and the open note (C); the server answers are fakes.
 * Robolectric: org.json of the plain unit tests is a stub.
 */
class MemoReviewTest : RobolectricTest() {
    /** A fake memo bot room: answers `review` and `review_status` with [answer]; [offline] throws like a lost network. */
    private class Room(var answer: (MemoOp) -> MemoResponse = { op -> ok(op, JSONObject()) }) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()
        var offline = false

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            if (offline) throw MemoSendException("off")
            return answer(op)
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    private fun ids(op: MemoOp): List<String> = memoArgStrings(op.args["ids"])

    private fun states(vararg rows: Triple<String, String, String>) =
        JSONObject().put("op", MEMO_OP_REVIEW_STATUS).put(
            "상태",
            JSONArray(rows.map { (id, state, result) -> JSONObject().put("id", id).put("상태", state).put("결과", result).put("시각", "2026-10-17T10:00:00+09:00") }),
        )

    // ---------------------------------------------------------------- A: the swipe

    @Test
    fun `a card moves only to the left, at most its width, and asks for the review past 30 percent`() {
        val w = 1000f
        assertEquals(-120f, memoReviewSwipeOffset(0f, -120f, w))
        // No move to the right of its place.
        assertEquals(0f, memoReviewSwipeOffset(0f, 80f, w))
        assertEquals(-50f, memoReviewSwipeOffset(-150f, 100f, w))
        assertEquals(-1000f, memoReviewSwipeOffset(-900f, -400f, w))
        assertFalse(memoReviewSwipeReached(-299f, w))
        assertTrue(memoReviewSwipeReached(-300f, w))
        assertTrue(memoReviewSwipeReached(-700f, w))
        // Not measured yet: never.
        assertFalse(memoReviewSwipeReached(-300f, 0f))
        assertEquals(0.3f, MEMO_REVIEW_SWIPE_FRACTION)
        assertEquals("✨ 검토", memoReviewSwipeHint(reached = false))
        assertEquals("✨ 놓으면 검토", memoReviewSwipeHint(reached = true))
    }

    @Test
    fun `a card is pushed only on a list - not in the selection mode, over a photo, a note, the tag manager or the trash`() {
        assertTrue(memoReviewSwipeAllowed(MemoLocation(), selecting = false, viewer = false))
        assertTrue(memoReviewSwipeAllowed(MemoLocation(tab = MemoTab.Folders, folder = "여행"), selecting = false, viewer = false, folder = "여행"))
        assertTrue(memoReviewSwipeAllowed(MemoLocation(tab = MemoTab.Search, searchResults = true), selecting = false, viewer = false))
        assertFalse(memoReviewSwipeAllowed(MemoLocation(), selecting = true, viewer = false))
        assertFalse(memoReviewSwipeAllowed(MemoLocation(), selecting = false, viewer = true))
        assertFalse(memoReviewSwipeAllowed(MemoLocation(note = "n1"), selecting = false, viewer = false))
        assertFalse(memoReviewSwipeAllowed(MemoLocation(note = "n1", editing = true), selecting = false, viewer = false))
        assertFalse(memoReviewSwipeAllowed(MemoLocation(tagManager = true), selecting = false, viewer = false))
        assertFalse(memoReviewSwipeAllowed(MemoLocation(tab = MemoTab.Folders, folder = MEMO_TRASH), selecting = false, viewer = false, folder = MEMO_TRASH))
    }

    // ---------------------------------------------------------------- C: the button of the open note

    @Test
    fun `the open note shows the review button except while editing or in the trash, dimmed in the selection mode`() {
        assertEquals(MemoReviewButton(enabled = true), memoReviewButton(trashed = false, editing = false, selecting = false))
        assertEquals(MemoReviewButton(enabled = false), memoReviewButton(trashed = false, editing = false, selecting = true))
        assertNull(memoReviewButton(trashed = false, editing = true, selecting = false))
        assertNull(memoReviewButton(trashed = true, editing = false, selecting = false))
    }

    // ---------------------------------------------------------------- the protocol

    @Test
    fun `review and review_status carry v 1, the request id and the ids, nothing else`() {
        val o = memoReviewOp(listOf("n1", "n2")).toJson("r1")
        assertEquals("review", o.getString("op"))
        assertEquals(1, o.getInt("v"))
        assertEquals("r1", o.getString("요청id"))
        assertEquals(listOf("n1", "n2"), (0 until o.getJSONArray("ids").length()).map { o.getJSONArray("ids").getString(it) })
        assertEquals(setOf("op", "v", "요청id", "ids"), o.keys().asSequence().toSet())
        assertTrue(memoReviewOp(listOf("n1")).isChange)
        val s = memoReviewStatusOp(listOf("n1")).toJson("r2")
        assertEquals("review_status", s.getString("op"))
        assertEquals(setOf("op", "v", "요청id", "ids"), s.keys().asSequence().toSet())
    }

    @Test
    fun `more than 30 notes go in turns of 30, in order, each once`() {
        val many = (1..65).map { "n$it" }
        val b = memoReviewBatches(many + listOf("n3", ""))
        assertEquals(listOf(30, 30, 5), b.map { it.size })
        assertEquals(many, b.flatten())
        assertTrue(memoReviewBatches(emptyList()).isEmpty())
    }

    @Test
    fun `a second press for the same note within 3 s sends nothing, another note goes, after 3 s it goes again`() {
        var now = 1_000_000L
        val r = MemoReviewRepeat { now }
        assertEquals(listOf("n1"), r.admit(listOf("n1")))
        now += 2_999
        assertEquals(emptyList<String>(), r.admit(listOf("n1")))
        assertEquals(listOf("n2"), r.admit(listOf("n1", "n2")))
        now += 1
        assertEquals(listOf("n1"), r.admit(listOf("n1", "n2")))
        now += 3_000
        assertEquals(listOf("n1", "n2"), r.admit(listOf("n1", "n2", "n1")))
    }

    @Test
    fun `the answer says how many were taken and why the others were skipped`() {
        val content = JSONObject().put("op", "review").put("접수", 1).put(
            "건너뜀",
            JSONArray()
                .put(JSONObject().put("id", "n2").put("사유", "휴지통"))
                .put(JSONObject().put("id", "n3").put("사유", "이미 대기 중"))
                .put(JSONObject().put("id", "n4").put("사유", "이미 대기 중")),
        )
        val a = memoReviewAnswer(listOf("n1", "n2", "n3", "n4"), content)
        assertEquals(listOf("n1"), a.accepted)
        assertEquals("휴지통 1건, 이미 대기 중 2건", memoReviewSkipText(a.skipped))
        assertEquals("검토를 요청했습니다 (1건) · 건너뜀: 휴지통 1건, 이미 대기 중 2건", memoReviewSentText(a))
        // 접수 0 is still an ok answer: the reasons are told.
        val none = memoReviewAnswer(listOf("x"), JSONObject().put("접수", 0).put("건너뜀", JSONArray().put(JSONObject().put("id", "x").put("사유", "없음"))))
        assertEquals("검토를 요청하지 않았습니다 · 건너뜀: 없음 1건", memoReviewSentText(none))
        assertEquals("검토를 요청했습니다 (2건)", memoReviewSentText(MemoReviewSent(listOf("a", "b"))))
        assertEquals("검토 요청 실패: 보내지 못했습니다", memoReviewSentText(MemoReviewSent(emptyList(), failure = "보내지 못했습니다")))
    }

    @Test
    fun `the end notice counts the proposals, says none, or says the review failed`() {
        val one = memoReviewStates(states(Triple("n1", "완료", "제안 2건")))
        assertTrue(memoReviewFinished(listOf("n1"), one))
        assertEquals("검토 완료: 제안 2건", memoReviewDoneText(listOf("n1"), one))
        val none = memoReviewStates(states(Triple("n1", "완료", "제안 없음")))
        assertEquals("검토 완료: 제안 없음", memoReviewDoneText(listOf("n1"), none))
        val failed = memoReviewStates(states(Triple("n1", "완료", "실패")))
        assertEquals("검토 실패", memoReviewDoneText(listOf("n1"), failed))
        val mixed = memoReviewStates(states(Triple("n1", "완료", "제안 1건"), Triple("n2", "완료", "제안 3건"), Triple("n3", "완료", "실패")))
        assertEquals("검토 완료: 제안 4건 (실패 1건)", memoReviewDoneText(listOf("n1", "n2", "n3"), mixed))
        // Still waiting or working: not finished; a note the server does not know (없음) is not waited for.
        assertFalse(memoReviewFinished(listOf("n1", "n2"), memoReviewStates(states(Triple("n1", "완료", "제안 1건"), Triple("n2", "검토중", "")))))
        assertFalse(memoReviewFinished(listOf("n1"), memoReviewStates(states(Triple("n1", "대기", "")))))
        assertFalse(memoReviewFinished(listOf("n1", "n2"), one))
        assertTrue(memoReviewFinished(listOf("n1", "n2"), memoReviewStates(states(Triple("n1", "완료", "제안 1건"), Triple("n2", "없음", "")))))
        assertEquals(2, memoReviewProposals("제안 2건"))
        assertEquals(0, memoReviewProposals("제안 없음"))
        assertNull(memoReviewProposals(""))
    }

    private fun row(id: String, state: String, result: String, reason: String? = null) =
        JSONObject().put("id", id).put("상태", state).put("결과", result).put("시각", "2026-10-18T02:00:00+09:00").also { o ->
            reason?.let { o.put("사유", it) }
        }

    private fun statesOf(vararg rows: JSONObject) = JSONObject().put("op", MEMO_OP_REVIEW_STATUS).put("상태", JSONArray(rows.toList()))

    @Test
    fun `app18 - one note without a proposal tells the server's reason, more notes only count them`() {
        val one = memoReviewStates(statesOf(row("n1", "완료", "제안 없음", "이미 제목·태그·폴더가 알맞아요")))
        assertEquals("이미 제목·태그·폴더가 알맞아요", one.single().reason)
        assertEquals("검토 완료: 제안 없음 — 이미 제목·태그·폴더가 알맞아요", memoReviewDoneText(listOf("n1"), one))
        // 서버 13차 (no 사유) and an empty 사유: as before.
        val old = memoReviewStates(statesOf(row("n1", "완료", "제안 없음")))
        assertEquals("", old.single().reason)
        assertEquals("검토 완료: 제안 없음", memoReviewDoneText(listOf("n1"), old))
        assertEquals("검토 완료: 제안 없음", memoReviewDoneText(listOf("n1"), memoReviewStates(statesOf(row("n1", "완료", "제안 없음", "  ")))))
        // Two notes without a proposal: the count, no reason.
        val two = memoReviewStates(statesOf(row("n1", "완료", "제안 없음", "가"), row("n2", "완료", "제안 없음", "나")))
        assertEquals("검토 완료: 제안 없음 2건", memoReviewDoneText(listOf("n1", "n2"), two))
        // A proposal somewhere: the proposals, no reason.
        val mixed = memoReviewStates(statesOf(row("n1", "완료", "제안 1건"), row("n2", "완료", "제안 없음", "나")))
        assertEquals("검토 완료: 제안 1건", memoReviewDoneText(listOf("n1", "n2"), mixed))
        // One ok without a proposal and one failed: the reason of the ok one and the failed count.
        val withFail = memoReviewStates(statesOf(row("n1", "완료", "제안 없음", "짧은 메모예요"), row("n2", "완료", "실패")))
        assertEquals("검토 완료: 제안 없음 — 짧은 메모예요 (실패 1건)", memoReviewDoneText(listOf("n1", "n2"), withFail))
        // A long reason with line breaks: one line, cut with "…".
        val long = "줄바꿈이\n있는   사유 " + "가".repeat(100)
        val cut = memoReviewReasonText(long)
        assertEquals(MEMO_REVIEW_REASON_CHARS, cut.length)
        assertTrue(cut.startsWith("줄바꿈이 있는 사유 가"))
        assertTrue(cut.endsWith("…"))
        assertEquals("짧다", memoReviewReasonText(" 짧다 "))
    }

    @Test
    fun `app18 - a result notice stays 8 s, the notice of a taken request 4 s`() {
        assertEquals(4_000L, MEMO_NOTICE_SHOW_MS)
        assertEquals(8_000L, MEMO_NOTICE_RESULT_SHOW_MS)
        assertEquals(MEMO_NOTICE_SHOW_MS, memoReviewSentShowMs(MemoReviewSent(listOf("a"))))
        assertEquals(MEMO_NOTICE_RESULT_SHOW_MS, memoReviewSentShowMs(MemoReviewSent(emptyList(), failure = "보내지 못했습니다")))
    }

    @Test
    fun `the look-ups run every 3 s for at most 2 minutes`() {
        val t0 = 1_000_000L
        assertEquals(3_000L, memoReviewPollDelayMs(t0, t0))
        assertEquals(3_000L, memoReviewPollDelayMs(t0, t0 + 117_000))
        assertNull(memoReviewPollDelayMs(t0, t0 + 117_001))
        assertNull(memoReviewPollDelayMs(t0, t0 + 200_000))
    }

    // ---------------------------------------------------------------- the sends (fake server)

    @Test
    fun `31 selected notes go as two review requests one after the other, the waiting changes go up first`() = runBlocking {
        val order = mutableListOf<String>()
        val room = Room { op ->
            order += op.op
            ok(op, JSONObject().put("접수", ids(op).size).put("건너뜀", JSONArray()))
        }
        val r = MemoReviewer(room, prepare = { order += "push" }, dispatcher = Dispatchers.Unconfined)
        val sent = r.request((1..31).map { "n$it" })
        assertEquals(listOf("push", "review", "review"), order)
        assertEquals(listOf(30, 1), room.ops.map { ids(it).size })
        assertEquals(31, sent.accepted.size)
        assertNull(sent.failure)
    }

    @Test
    fun `offline the request fails at once and says so - nothing is queued`() = runBlocking {
        val room = Room().apply { offline = true }
        val r = MemoReviewer(room, dispatcher = Dispatchers.Unconfined)
        val sent = r.request(listOf("n1"))
        assertTrue(sent.accepted.isEmpty())
        assertEquals("검토 요청 실패: 보내지 못했습니다. 연결을 확인해 주세요", memoReviewSentText(sent))
        assertEquals(1, room.ops.size)
        // A refused request (bad form) tells the server's reason.
        room.offline = false
        room.answer = { op -> MemoResponse.Error("r", op.op, "ids 형식 오류") }
        assertEquals("검토 요청 실패: ids 형식 오류", memoReviewSentText(r.request(listOf("n1"))))
    }

    @Test
    fun `after the request the status is asked every 3 s until the review is done`() = runBlocking {
        var now = 0L
        var asked = 0
        val room = Room { op ->
            asked++
            val state = if (asked < 3) Triple("n1", "검토중", "") else Triple("n1", "완료", "제안 2건")
            ok(op, states(state))
        }
        val waits = mutableListOf<Long>()
        val sleep: suspend (Long) -> Unit = { ms ->
            waits += ms
            now += ms
        }
        val r = MemoReviewer(room, clock = { now }, dispatcher = Dispatchers.Unconfined, sleep = sleep)
        assertEquals("검토 완료: 제안 2건", r.follow(listOf("n1")))
        assertEquals(listOf(3_000L, 3_000L, 3_000L), waits)
        assertTrue(room.ops.all { it.op == MEMO_OP_REVIEW_STATUS && ids(it) == listOf("n1") })
    }

    @Test
    fun `a failed look-up or two minutes without an end stop quietly`() = runBlocking {
        var now = 0L
        val room = Room().apply { offline = true }
        val r = MemoReviewer(room, clock = { now }, dispatcher = Dispatchers.Unconfined, sleep = { now += it })
        assertNull(r.follow(listOf("n1")))
        assertEquals(1, room.ops.size)
        room.offline = false
        room.ops.clear()
        room.answer = { op -> ok(op, states(Triple("n1", "대기", ""))) }
        now = 0L
        assertNull(r.follow(listOf("n1")))
        // 3, 6, … 120 s: 40 look-ups in the 2 minutes.
        assertEquals(40, room.ops.size)
    }

    @Test
    fun `the review of an edited note waits for the save the editor started when it left`() = runBlocking {
        val id = "review-save-1"
        MemoEditHub.drop(id)
        val entry = MemoEditDraft("제목", "", "처음", MemoPhotoEdit(emptyList()))
        MemoEditHub.open(id, entry)
        MemoEditHub.change(id) { it.copy(body = "고친 본문") }
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        MemoEditHub.saveLater(id) { current ->
            gate.await()
            order += "saved ${current.draft.body}"
            MemoSaveResult(current.draft)
        }
        val room = Room { op ->
            order += op.op
            ok(op, JSONObject().put("접수", 1).put("건너뜀", JSONArray()))
        }
        val r = MemoReviewer(room, dispatcher = Dispatchers.Unconfined)
        val review = async(Dispatchers.Default) {
            MemoEditHub.settled(id)
            r.request(listOf(id))
        }
        kotlinx.coroutines.delay(100)
        assertTrue(room.ops.isEmpty())
        gate.complete(Unit)
        withTimeout(5_000) { review.await() }
        assertEquals(listOf("saved 고친 본문", "review"), order)
        MemoEditHub.drop(id)
    }

    // ---------------------------------------------------------------- 앱19 ①: the proposals come right after the notice

    @Test
    fun `app19 - only a notice with one proposal or more wants the proposals synced`() {
        assertTrue(memoReviewSyncWanted("검토 완료: 제안 2건"))
        assertTrue(memoReviewSyncWanted("검토 완료: 제안 1건 (실패 1건)"))
        assertFalse(memoReviewSyncWanted("검토 완료: 제안 없음"))
        assertFalse(memoReviewSyncWanted("검토 완료: 제안 없음 — 이미 제목·태그·폴더가 알맞아요"))
        assertFalse(memoReviewSyncWanted("검토 완료: 제안 없음 3건"))
        assertFalse(memoReviewSyncWanted("검토 완료: 제안 0건"))
        assertFalse(memoReviewSyncWanted("검토 실패"))
        assertFalse(memoReviewSyncWanted("검토 요청 실패: 보내지 못했습니다. 연결을 확인해 주세요"))
        assertFalse(memoReviewSyncWanted(null))
        // The texts of memoReviewDoneText itself.
        val two = memoReviewStates(states(Triple("n1", "완료", "제안 2건")))
        assertTrue(memoReviewSyncWanted(memoReviewDoneText(listOf("n1"), two)))
        val none = memoReviewStates(states(Triple("n1", "완료", "제안 없음")))
        assertFalse(memoReviewSyncWanted(memoReviewDoneText(listOf("n1"), none)))
    }

    @Test
    fun `app19 - a review end with proposals syncs once, none or a failure syncs nothing`() = runBlocking {
        val shown = mutableListOf<String>()
        val synced = mutableListOf<List<String>>()
        val sync: suspend (List<String>) -> Unit = { synced += it }
        memoReviewFinish("검토 완료: 제안 2건", listOf("n1", "n2"), show = { shown += it }, sync = sync)
        assertEquals(listOf("검토 완료: 제안 2건"), shown)
        assertEquals(listOf(listOf("n1", "n2")), synced)
        memoReviewFinish("검토 완료: 제안 없음", listOf("n3"), show = { shown += it }, sync = sync)
        memoReviewFinish("검토 실패", listOf("n4"), show = { shown += it }, sync = sync)
        // No notice (a failed look-up, two minutes without an end): nothing shown, nothing synced.
        memoReviewFinish(null, listOf("n5"), show = { shown += it }, sync = sync)
        assertEquals(listOf("검토 완료: 제안 2건", "검토 완료: 제안 없음", "검토 실패"), shown)
        assertEquals(1, synced.size)
        // The fake data (no sync): the notice still shows.
        memoReviewFinish("검토 완료: 제안 1건", listOf("n6"), show = { shown += it }, sync = null)
        assertEquals("검토 완료: 제안 1건", shown.last())
        assertEquals(1, synced.size)
    }

    @Test
    fun `app19 - a sync that fails leaves the notice as it is and throws nothing`() = runBlocking {
        val shown = mutableListOf<String>()
        var calls = 0
        memoReviewFinish("검토 완료: 제안 3건", listOf("n1"), show = { shown += it }) {
            calls++
            throw MemoSendException("off")
        }
        assertEquals(1, calls)
        assertEquals(listOf("검토 완료: 제안 3건"), shown)
    }

    @Test
    fun `app19 - a new or changed card of a reviewed note counts, an old card or another note does not`() {
        val before = listOf("n1.cls" to "{a}", "x9.cls" to "{b}")
        assertFalse(memoReviewCardsArrived(listOf("n1"), before, before))
        assertTrue(memoReviewCardsArrived(listOf("n1"), before, before + ("n1.ttl" to "{c}")))
        assertTrue(memoReviewCardsArrived(listOf("n1"), before, listOf("n1.cls" to "{a2}")))
        assertTrue(memoReviewCardsArrived(listOf("n2", "n1"), emptyList(), listOf("n1" to "{}")))
        assertFalse(memoReviewCardsArrived(listOf("n1"), before, before + ("n10.cls" to "{d}")))
        assertFalse(memoReviewCardsArrived(listOf("n1"), before, before + ("x9.ttl" to "{e}")))
        assertFalse(memoReviewCardsArrived(listOf(""), emptyList(), listOf(".cls" to "{}")))
    }

    private companion object {
        fun ok(op: MemoOp, content: JSONObject) = MemoResponse.Ok("r", op.op, null, null, content)
    }
}
