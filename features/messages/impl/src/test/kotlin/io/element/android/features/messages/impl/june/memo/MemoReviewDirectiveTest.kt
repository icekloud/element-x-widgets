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

/** 앱21 ①: the directive of a review from the open note (서버 15차 규약 §1). Made up notes and words. */
class MemoReviewDirectiveTest : RobolectricTest() {
    @Test
    fun `without a directive the review op is exactly the one of before`() {
        val before = memoReviewOp(listOf("n1")).toJson("r1").toString()
        assertEquals(before, memoReviewOp(listOf("n1"), null).toJson("r1").toString())
        assertEquals(before, memoReviewOp(listOf("n1"), "").toJson("r1").toString())
        assertEquals(before, memoReviewOp(listOf("n1"), "  \n\t ").toJson("r1").toString())
        assertFalse(memoReviewOp(listOf("n1"), " ").args.containsKey(MEMO_ARG_DIRECTIVE))
    }

    @Test
    fun `a directive goes as the 지시 argument, trimmed, its inner lines kept`() {
        val op = memoReviewOp(listOf("n1", "n2"), "  음식점 이름을 찾아줘\n메뉴도  \n")
        assertEquals(MEMO_OP_REVIEW, op.op)
        assertEquals("음식점 이름을 찾아줘\n메뉴도", op.args[MEMO_ARG_DIRECTIVE])
        assertEquals(listOf("n1", "n2"), memoArgStrings(op.args["ids"]))
        assertEquals("지시", MEMO_ARG_DIRECTIVE)
    }

    @Test
    fun `the directive is at most 500 characters, the field never holds more`() {
        assertEquals(500, MEMO_REVIEW_DIRECTIVE_MAX)
        val long = "가".repeat(600)
        assertEquals(500, memoReviewDirective(long)!!.length)
        assertEquals(500, memoReviewDirectiveInput(long).length)
        assertEquals("abc", memoReviewDirectiveInput("abc"))
        assertEquals("0/500", memoReviewDirectiveCount(""))
        assertEquals("3/500", memoReviewDirectiveCount("abc"))
        assertNull(memoReviewDirective(null))
    }

    @Test
    fun `the answer tells whether the server took the directive and cut it`() {
        val ok = JSONObject().put("접수", 1).put("건너뜀", JSONArray()).put("지시", JSONObject().put("글자수", 17).put("잘림", false).put("반영", 1))
        assertEquals(MemoDirectiveAnswer(taken = true, cut = false, applied = 1), memoReviewAnswer(listOf("n1"), ok, directiveSent = true).directive)
        // Not sent: nothing to tell, even if the answer had the object.
        assertNull(memoReviewAnswer(listOf("n1"), ok).directive)
        // A server of before (14차) answers without it: not taken.
        val old = JSONObject().put("접수", 1).put("건너뜀", JSONArray())
        assertEquals(MemoDirectiveAnswer(taken = false), memoReviewAnswer(listOf("n1"), old, directiveSent = true).directive)
        val cut = JSONObject().put("접수", 1).put("지시", JSONObject().put("잘림", true).put("반영", 1))
        assertTrue(memoReviewAnswer(listOf("n1"), cut, directiveSent = true).directive!!.cut)
    }

    @Test
    fun `the notice after the press says what became of the directive, and nothing more without one`() {
        assertEquals("검토를 요청했습니다 (1건)", memoReviewSentText(MemoReviewSent(listOf("n1"))))
        assertEquals("검토를 요청했습니다 (1건) · 지시 포함", memoReviewSentText(MemoReviewSent(listOf("n1"), directive = MemoDirectiveAnswer(true))))
        assertEquals(
            "검토를 요청했습니다 (1건) · 지시문이 500자로 잘렸습니다",
            memoReviewSentText(MemoReviewSent(listOf("n1"), directive = MemoDirectiveAnswer(true, cut = true))),
        )
        assertTrue(memoReviewSentText(MemoReviewSent(listOf("n1"), directive = MemoDirectiveAnswer(false))).contains("지시는 반영되지 않았습니다"))
    }

    /** The memo bot room as a fake: records the ops, answers like 서버 15차 (with `지시` only when one came). */
    private class Room(private val knowsDirective: Boolean = true) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            val ids = memoArgStrings(op.args["ids"])
            val content = JSONObject().put("op", op.op).put("접수", ids.size).put("건너뜀", JSONArray())
            val d = op.args[MEMO_ARG_DIRECTIVE] as? String
            if (knowsDirective && d != null) content.put("지시", JSONObject().put("글자수", d.length).put("잘림", false).put("반영", ids.size))
            return MemoResponse.Ok("r", op.op, null, null, content)
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    @Test
    fun `the reviewer sends the directive with the ids, and the same request without it as before`() = runBlocking {
        val room = Room()
        val reviewer = MemoReviewer(room, dispatcher = Dispatchers.Unconfined)
        val plain = reviewer.request(listOf("n1"))
        val with = reviewer.request(listOf("n2"), "사진 속 간판을 읽어줘")
        assertEquals(listOf("n1"), plain.accepted)
        assertNull(plain.directive)
        assertFalse(room.ops[0].args.containsKey(MEMO_ARG_DIRECTIVE))
        assertEquals("사진 속 간판을 읽어줘", room.ops[1].args[MEMO_ARG_DIRECTIVE])
        assertEquals(MemoDirectiveAnswer(true, false, 1), with.directive)
        // Blank: the review of before.
        reviewer.request(listOf("n3"), "   ")
        assertFalse(room.ops[2].args.containsKey(MEMO_ARG_DIRECTIVE))
    }

    @Test
    fun `an old server that ignores the directive still reviews, the notice says so`() = runBlocking {
        val room = Room(knowsDirective = false)
        val sent = MemoReviewer(room, dispatcher = Dispatchers.Unconfined).request(listOf("n1"), "메뉴를 찾아줘")
        assertEquals(listOf("n1"), sent.accepted)
        assertEquals(MemoDirectiveAnswer(false), sent.directive)
    }

    @Test
    fun `a note asked for a moment ago counts as recent for 3 s, then not`() {
        var now = 1_000L
        val repeat = MemoReviewRepeat { now }
        assertFalse(repeat.recent("n1"))
        repeat.admit(listOf("n1"))
        assertTrue(repeat.recent("n1"))
        now += MEMO_REVIEW_REPEAT_MS
        assertFalse(repeat.recent("n1"))
    }
}
