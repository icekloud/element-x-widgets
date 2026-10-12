/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoCardsTest : RobolectricTest() {
    private fun mine(key: String, kind: MemoEntryKind, t: Long, text: String = key) =
        MemoEntry(key, "\$$key", isMine = true, timeMillis = t, kind = kind, text = text)

    private fun bot(key: String, t: Long, text: String, replyTo: String? = null, noteId: String? = null) =
        MemoEntry(key, "\$$key", isMine = false, timeMillis = t, kind = MemoEntryKind.Text, text = text, replyToEventId = replyTo, noteId = noteId)

    @Test
    fun `bot line with folder, tags, review and pin`() {
        val info = parseMemoReply("📁 여행 · #빵집 #교토 #10월5일 #방문후보 · 📅 플랜 검토 요청 · 📌?")!!
        assertEquals("여행", info.folder)
        assertEquals(listOf("빵집", "교토", "10월5일", "방문후보"), info.tags)
        assertEquals(MemoReviewState.Reviewing, info.review)
        assertTrue(info.pinSuggested)
        assertFalse(info.inbox)
        assertEquals(MemoReviewState.Proposed, parseMemoReply("📁 여행 · #a · 📅 플랜봇 제안 도착")!!.review)
        assertEquals(MemoReviewState.NotNeeded, parseMemoReply("📁 쇼핑·구매 · #화장수 · 검토 불필요")!!.review)
    }

    @Test
    fun `folder names with a middle dot are split on the separator only`() {
        // "쇼핑·구매" contains "·": the folder is the first segment, the rest is read as its own chips.
        val info = parseMemoReply("📁 쇼핑 · #화장수")!!
        assertEquals("쇼핑", info.folder)
        assertEquals(listOf("화장수"), info.tags)
    }

    @Test
    fun `inbox line with confidence and two candidates`() {
        val info = parseMemoReply("📥 받은편지함(확신 0.4) · 후보: 아이디어 / 링크보관 / 기타")!!
        assertTrue(info.inbox)
        assertEquals(MEMO_INBOX, info.folder)
        assertEquals(0.4, info.confidence!!, 0.0001)
        assertEquals(listOf("아이디어", "링크보관"), info.candidates)
    }

    @Test
    fun `not a memo line`() {
        assertNull(parseMemoReply("안녕하세요"))
        val tags = parseMemoReply("📁 a · #1 #2 #3 #4 #5 #6 #7")!!.tags
        assertEquals(MEMO_MAX_CARD_TAGS, tags.size)
    }

    @Test
    fun `cards group photos, follow replies and keep bot only notices`() {
        val cards = buildMemoCards(
            listOf(
                mine("link", MemoEntryKind.Link, 0, "https://tabelog.com/kyoto"),
                mine("p1", MemoEntryKind.Photo, 10_000),
                mine("p2", MemoEntryKind.Photo, 20_000),
                // The answer to the link arrives after the photos, as a reply.
                bot("r1", 30_000, "📁 여행 · #빵집", replyTo = "\$link", noteId = "n1"),
                // No m.relates_to: goes to the oldest card without an answer (the photos).
                bot("r2", 40_000, "📁 쇼핑·구매 · #화장수"),
                bot("notice", 50_000, "📅 일정 제안이 도착했습니다"),
                mine("t", MemoEntryKind.Text, 200_000, "환전 10만엔 더"),
            ),
        )
        assertEquals(listOf("link", "p1", "notice", "t"), cards.map { it.key })
        assertEquals(listOf("r1"), cards[0].replies.map { it.key })
        assertEquals(2, cards[1].photoCount)
        assertEquals("사진 2장", cards[1].headline())
        assertEquals(listOf("r2"), cards[1].replies.map { it.key })
        assertTrue(cards[2].isBotOnly)
        assertNull(cards[2].info)
        assertEquals("\"환전 10만엔 더\"", cards[3].headline())
        assertEquals("https://tabelog.com/kyoto", cards[0].headline())
    }

    @Test
    fun `photo after an answered photo or after a minute opens a new card`() {
        val cards = buildMemoCards(
            listOf(
                mine("p1", MemoEntryKind.Photo, 0),
                bot("r1", 5_000, "📁 a"),
                mine("p2", MemoEntryKind.Photo, 10_000),
                mine("p3", MemoEntryKind.Photo, 100_000),
            ),
        )
        assertEquals(listOf("p1", "p2", "p3"), cards.map { it.key })
    }

    @Test
    fun `chip actions map to memo ops with the note id or the input event`() {
        val withId = buildMemoCards(listOf(mine("a", MemoEntryKind.Text, 0), bot("r", 1, "📁 x", noteId = "n1"))).single()
        val move = memoCardOp(withId, MemoCardAction.MoveTo("아이디어"))!!.toJson("q")
        assertEquals("move", move.getString("op"))
        assertEquals("n1", move.getString("id"))
        assertEquals("\$a", move.getString("이벤트"))
        assertEquals("아이디어", move.getString("folder"))
        assertEquals("q", move.getString("요청id"))
        val noId = buildMemoCards(listOf(mine("b", MemoEntryKind.Text, 0), bot("r", 1, "📁 x"))).single()
        val pin = memoCardOp(noId, MemoCardAction.Pin)!!.toJson("q")
        assertEquals("pin", pin.getString("op"))
        assertFalse(pin.has("id"))
        assertEquals("\$b", pin.getString("이벤트"))
        assertEquals("trash", memoCardOp(noId, MemoCardAction.Trash)!!.op)
        assertEquals("unpin", memoCardOp(noId, MemoCardAction.Unpin)!!.op)
        assertNull(memoCardOp(noId, MemoCardAction.OpenFolderSheet))
        assertNull(memoCardOp(noId, MemoCardAction.OpenTagSheet))
        // Tag sheet result.
        val tags = MemoOp.editTags(noId.target, listOf("a", "b")).toJson("q")
        assertEquals("edit", tags.getString("op"))
        assertEquals(2, tags.getJSONArray("태그").length())
        assertFalse(tags.has("본문"))
        // A bot only card has nothing to point at.
        val notice = buildMemoCards(listOf(bot("n", 0, "📁 x"))).single()
        assertNull(memoCardOp(notice, MemoCardAction.Pin))
    }
}
