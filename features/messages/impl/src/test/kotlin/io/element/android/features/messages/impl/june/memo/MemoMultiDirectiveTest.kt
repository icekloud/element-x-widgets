/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱31 (관리자: "노트 여러개 선택 후 검토 시에도 지시 입력 가능하게끔"): the directive sheet of the selection bar and what
 * one directive does over several `review` turns. Made up note ids and words only.
 */
class MemoMultiDirectiveTest : RobolectricTest() {
    /** A fake memo bot room; [knowsDirective] answers the `지시` object only for the first [knowsDirective] calls. */
    private class Room(private val knowsDirective: Int = Int.MAX_VALUE, private val cutAt: Int = -1) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()
        private var reviews = 0

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            val ids = memoArgStrings(op.args["ids"])
            val content = JSONObject().put("op", op.op).put("접수", ids.size).put("건너뜀", JSONArray())
            val d = op.args[MEMO_ARG_DIRECTIVE] as? String
            if (op.op == MEMO_OP_REVIEW) {
                val n = reviews++
                if (d != null && n < knowsDirective) {
                    content.put(MEMO_ARG_DIRECTIVE, JSONObject().put("글자수", d.length).put("잘림", n == cutAt).put("반영", ids.size))
                }
            }
            return MemoResponse.Ok("r", op.op, null, null, content)
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null

        fun directives(): List<String?> = ops.filter { it.op == MEMO_OP_REVIEW }.map { it.args[MEMO_ARG_DIRECTIVE] as? String }
    }

    // ---------------------------------------------------------------- the sheet of the selection bar

    @Test
    fun `the selection bar opens the directive sheet only with selected notes and a server`() {
        assertEquals(listOf("n1", "n2"), memoBulkReviewIds(listOf("n1", "n2"), hasReviewer = true))
        // No server (the fake data): the button is not even drawn; nothing opens.
        assertNull(memoBulkReviewIds(listOf("n1"), hasReviewer = false))
        // Nothing selected: nothing opens (the button is dimmed).
        assertNull(memoBulkReviewIds(emptyList(), hasReviewer = true))
        assertNull(memoBulkReviewIds(listOf("", "  "), hasReviewer = true))
        // Empty ids are dropped, the order and the doubles of the selection are kept as they are sent.
        assertEquals(listOf("n1", "n2"), memoBulkReviewIds(listOf("n1", "", "n2"), hasReviewer = true))
    }

    @Test
    fun `the sheet tells how many notes the directive goes to, and says nothing for a single note`() {
        assertEquals("선택한 3개 노트에 함께 적용됩니다", memoBulkReviewTargetText(3))
        assertEquals("선택한 2개 노트에 함께 적용됩니다", memoBulkReviewTargetText(2))
        assertEquals("", memoBulkReviewTargetText(1))
        assertEquals("", memoBulkReviewTargetText(0))
    }

    // ---------------------------------------------------------------- the same directive in every turn

    @Test
    fun `31 selected notes go as two turns and both carry the very same directive`() = runBlocking {
        val room = Room()
        val sent = MemoReviewer(room, dispatcher = Dispatchers.Unconfined).request((1..31).map { "n$it" }, "  사진 속 간판을 읽어줘 ")
        assertEquals(listOf(30, 1), room.ops.filter { it.op == MEMO_OP_REVIEW }.map { memoArgStrings(it.args["ids"]).size })
        assertEquals(listOf("사진 속 간판을 읽어줘", "사진 속 간판을 읽어줘"), room.directives())
        assertEquals(31, sent.accepted.size)
        assertEquals(MemoDirectiveAnswer(taken = true, cut = false, applied = 31), sent.directive)
        // One line for the whole press, not one per turn.
        assertEquals("검토를 요청했습니다 (31건) · 지시 포함", memoReviewSentText(sent))
    }

    @Test
    fun `an empty directive on many notes sends the review of before - no 지시 key in any turn`() = runBlocking {
        val room = Room()
        val r = MemoReviewer(room, dispatcher = Dispatchers.Unconfined)
        val sent = r.request((1..31).map { "n$it" }, "")
        assertEquals(listOf(null, null), room.directives())
        assertTrue(room.ops.none { it.args.containsKey(MEMO_ARG_DIRECTIVE) })
        assertNull(sent.directive)
        assertEquals("검토를 요청했습니다 (31건)", memoReviewSentText(sent))
        room.ops.clear()
        r.request(listOf("m1", "m2"), "  \n\t ")
        assertTrue(room.ops.none { it.args.containsKey(MEMO_ARG_DIRECTIVE) })
    }

    // ---------------------------------------------------------------- one notice for every turn together

    @Test
    fun `when only some turns took the directive the notice says which notes it missed`() = runBlocking {
        // The first turn is answered by a server that knows 지시, the second by one that does not (서버 14차).
        val room = Room(knowsDirective = 1)
        val sent = MemoReviewer(room, dispatcher = Dispatchers.Unconfined).request((1..31).map { "n$it" }, "메뉴를 찾아줘")
        assertEquals(31, sent.accepted.size)
        val text = memoReviewSentText(sent)
        assertEquals("검토를 요청했습니다 (31건) · 지시 포함(30건) · 1건에는 지시가 닿지 않았습니다", text)
        assertFalse(text, text.contains("서버가 지시를 모릅니다"))
        // Said once, not once per turn.
        assertEquals(1, Regex("지시 포함").findAll(text).count())
    }

    @Test
    fun `no turn took the directive - the old wording, once`() = runBlocking {
        val room = Room(knowsDirective = 0)
        val sent = MemoReviewer(room, dispatcher = Dispatchers.Unconfined).request((1..31).map { "n$it" }, "메뉴를 찾아줘")
        assertEquals(MemoDirectiveAnswer(taken = false), sent.directive)
        val text = memoReviewSentText(sent)
        assertEquals("검토를 요청했습니다 (31건) · 지시는 반영되지 않았습니다(서버가 지시를 모릅니다)", text)
        assertEquals(1, Regex("지시는 반영되지 않았습니다").findAll(text).count())
    }

    @Test
    fun `a cut in any turn is told once for the whole press`() = runBlocking {
        val room = Room(cutAt = 1)
        val sent = MemoReviewer(room, dispatcher = Dispatchers.Unconfined).request((1..31).map { "n$it" }, "메뉴를 찾아줘")
        assertTrue(sent.directive!!.cut)
        val text = memoReviewSentText(sent)
        assertEquals("검토를 요청했습니다 (31건) · 쌓인 지시가 2000자를 넘어 이번 지시가 잘렸습니다", text)
        assertEquals(1, Regex("잘렸습니다").findAll(text).count())
    }

    @Test
    fun `the notice of one note is exactly the one of before`() {
        assertEquals("검토를 요청했습니다 (1건) · 지시 포함", memoReviewSentText(MemoReviewSent(listOf("n1"), directive = MemoDirectiveAnswer(true))))
        assertEquals(
            "검토를 요청했습니다 (1건) · 지시 포함",
            memoReviewSentText(MemoReviewSent(listOf("n1"), directive = MemoDirectiveAnswer(true, false, 1))),
        )
        assertEquals("검토를 요청했습니다 (2건) · 지시 포함", memoReviewSentText(MemoReviewSent(listOf("n1", "n2"), directive = MemoDirectiveAnswer(true, false, 2))))
        // The skip reasons still come after the directive.
        val skipped = MemoReviewSent(listOf("n1"), listOf(MemoReviewSkip("n2", "휴지통")), directive = MemoDirectiveAnswer(true, false, 1))
        assertEquals("검토를 요청했습니다 (1건) · 지시 포함 · 건너뜀: 휴지통 1건", memoReviewSentText(skipped))
    }

    // ---------------------------------------------------------------- 연타로 걸러진 노트

    @Test
    fun `notes asked for a moment ago are held back and many notes say so, one note stays silent`() {
        assertEquals(" · 방금 보낸 1건은 빼고 보냈습니다", memoReviewHeldBackText(asked = 3, fresh = 2, withDirective = false))
        assertEquals(" · 방금 보낸 2건은 빼고 보냈습니다(지시도 가지 않습니다)", memoReviewHeldBackText(asked = 3, fresh = 1, withDirective = true))
        // Nothing held back: nothing said.
        assertEquals("", memoReviewHeldBackText(asked = 3, fresh = 3, withDirective = true))
        // One note (the button of the open note, a card pushed to the left): as before, no word.
        assertEquals("", memoReviewHeldBackText(asked = 1, fresh = 0, withDirective = true))
        assertEquals("", memoReviewHeldBackText(asked = 1, fresh = 0, withDirective = false))
        // What the controller counts: each id once, empty ones dropped.
        assertEquals(2, memoReviewAskedCount(listOf("n1", "n2", "n1", "")))
        assertEquals(0, memoReviewAskedCount(listOf("", "  ")))
    }

    @Test
    fun `the repeat guard lets the other notes of the selection through`() {
        var now = 1_000_000L
        val repeat = MemoReviewRepeat { now }
        assertEquals(listOf("n1"), repeat.admit(listOf("n1")))
        val fresh = repeat.admit(listOf("n1", "n2", "n3"))
        assertEquals(listOf("n2", "n3"), fresh)
        assertEquals(" · 방금 보낸 1건은 빼고 보냈습니다(지시도 가지 않습니다)", memoReviewHeldBackText(3, fresh.size, withDirective = true))
    }

    // ---------------------------------------------------------------- 추가요청 1: 카드 스와이프도 시트를 먼저 연다

    @Test
    fun `app31 extra1 - the words behind a pushed card say the sheet opens, not that it is sent`() {
        assertEquals("✨ 검토", memoReviewSwipeHint(reached = false))
        assertEquals("✨ 놓으면 지시 입력", memoReviewSwipeHint(reached = true))
        // What a card pushed for a note asked for a moment ago says instead of opening the sheet.
        assertEquals("방금 검토를 요청한 노트입니다", MEMO_REVIEW_RECENT_NOTICE)
    }

    @Test
    fun `app31 extra1 - a card can still be pushed only on a list, the conditions are untouched`() {
        assertTrue(memoReviewSwipeAllowed(MemoLocation(), selecting = false, viewer = false))
        assertFalse(memoReviewSwipeAllowed(MemoLocation(), selecting = true, viewer = false))
        assertFalse(memoReviewSwipeAllowed(MemoLocation(note = "n1"), selecting = false, viewer = false))
        assertFalse(memoReviewSwipeAllowed(MemoLocation(tab = MemoTab.Folders, folder = MEMO_TRASH), selecting = false, viewer = false, folder = MEMO_TRASH))
        // The 연타 guard the sheet asks before it opens.
        var now = 1_000L
        val repeat = MemoReviewRepeat { now }
        assertFalse(repeat.recent("n1"))
        repeat.admit(listOf("n1"))
        assertTrue(repeat.recent("n1"))
        now += MEMO_REVIEW_REPEAT_MS
        assertFalse(repeat.recent("n1"))
    }

    // ---------------------------------------------------------------- the directive is kept nowhere

    @Test
    fun `the log of a many note review tells only the length of the directive, never its words`() = runBlocking {
        val lines = mutableListOf<String>()
        val room = Room()
        MemoReviewer(room, dispatcher = Dispatchers.Unconfined, log = { lines += it }).request((1..31).map { "n$it" }, "메뉴를 찾아줘")
        assertTrue(lines.toString(), lines.none { it.contains("메뉴") })
        assertTrue(lines.toString(), lines.none { it.contains("n1") })
        assertEquals(listOf("검토 요청 30건 · 지시 있음(7자)", "검토 요청 1건 · 지시 있음(7자)", "검토 접수 31건 · 건너뜀 0건"), lines)
    }
}
