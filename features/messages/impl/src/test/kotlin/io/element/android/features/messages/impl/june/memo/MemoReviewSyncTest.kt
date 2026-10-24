/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.compose.runtime.mutableStateOf
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱19 ① (관리자: "제안 생겼다는 알림오는 경우 바로 제안탭 새로고침"): the review of the controller, over a fake memo bot
 * room. A notice with proposals brings them at once (one sync); none, a failure or no end syncs nothing.
 */
class MemoReviewSyncTest : RobolectricTest() {
    /** Takes every review; `review_status` says [result] for every note. */
    private class Room(var result: String, var lookUpFails: Boolean = false) : MemoSyncTransport {
        override suspend fun call(op: MemoOp): MemoResponse {
            val ids = memoArgStrings(op.args["ids"])
            if (op.op == MEMO_OP_REVIEW_STATUS && lookUpFails) throw MemoSendException("off")
            val content = when (op.op) {
                MEMO_OP_REVIEW -> JSONObject().put("op", op.op).put("접수", ids.size).put("건너뜀", JSONArray())
                else -> JSONObject().put("op", op.op).put(
                    "상태",
                    JSONArray(ids.map { JSONObject().put("id", it).put("상태", "완료").put("결과", result).put("시각", "2026-10-18T10:00:00+09:00") }),
                )
            }
            return MemoResponse.Ok("r", op.op, null, null, content)
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    /** Runs one review of [ids] to its end and returns the ids each sync was asked for, and the last notice. */
    private fun reviewOnce(room: Room, ids: List<String>, sync: (suspend (List<String>) -> Unit)? = null): Pair<List<List<String>>, String?> = runBlocking {
        val synced = mutableListOf<List<String>>()
        val c = MemoController(MemoStore(FakeMemoBackend()), this, mutableStateOf(MemoLocation()))
        c.reviewer = MemoReviewer(room, dispatcher = Dispatchers.Unconfined, sleep = {})
        c.reviewSync = sync ?: { synced += it }
        c.review(ids)
        withTimeout(5_000) {
            while (c.notice?.text?.startsWith("검토를 요청했습니다") != false) yield()
        }
        // Let the end of the follow-up (and a sync) run.
        repeat(20) { yield() }
        c.stopFollowing()
        synced to c.notice?.text
    }

    @Test
    fun `a review ending with proposals syncs once for the reviewed notes`() {
        val (synced, notice) = reviewOnce(Room("제안 2건"), listOf("n1", "n2"))
        assertEquals("검토 완료: 제안 4건", notice)
        assertEquals(listOf(listOf("n1", "n2")), synced)
    }

    @Test
    fun `no proposal or a failed review syncs nothing`() {
        val (none, noneText) = reviewOnce(Room("제안 없음"), listOf("n1"))
        assertEquals("검토 완료: 제안 없음", noneText)
        assertTrue(none.isEmpty())
        val (failed, failedText) = reviewOnce(Room("실패"), listOf("n1"))
        assertEquals("검토 실패", failedText)
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `a failed look-up shows no end and syncs nothing`() = runBlocking {
        val synced = mutableListOf<List<String>>()
        val c = MemoController(MemoStore(FakeMemoBackend()), this, mutableStateOf(MemoLocation()))
        c.reviewer = MemoReviewer(Room("제안 2건", lookUpFails = true), dispatcher = Dispatchers.Unconfined, sleep = {})
        c.reviewSync = { synced += it }
        c.review(listOf("n1"))
        repeat(50) { yield() }
        c.stopFollowing()
        assertEquals("검토를 요청했습니다 (1건)", c.notice?.text)
        assertTrue(synced.isEmpty())
    }

    @Test
    fun `a sync that fails keeps the notice and the controller goes on`() {
        var calls = 0
        val (_, notice) = reviewOnce(Room("제안 1건"), listOf("n1")) {
            calls++
            throw MemoSendException("off")
        }
        assertEquals(1, calls)
        assertEquals("검토 완료: 제안 1건", notice)
    }
}
