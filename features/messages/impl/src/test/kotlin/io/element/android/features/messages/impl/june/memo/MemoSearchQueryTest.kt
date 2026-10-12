/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoSearchQueryTest : RobolectricTest() {
    @Test
    fun `the tag being typed is found at the end only`() {
        assertEquals("이", memoTagPrefix("가와라마치 #이"))
        assertEquals("", memoTagPrefix("가와라마치 #"))
        assertNull(memoTagPrefix("가와라마치"))
        assertNull(memoTagPrefix("#교토 가와라마치"))
    }

    @Test
    fun `choosing a suggestion turns the typed word into a chip`() {
        val q = MemoSearchQuery(text = "가와라마치 #이", tags = listOf("교토")).applyTagSuggestion("이동")
        assertEquals("가와라마치", q.text)
        assertEquals(listOf("교토", "이동"), q.tags)
        // Same tag twice is ignored.
        assertEquals(listOf("교토", "이동"), q.withTag("#이동").tags)
    }

    @Test
    fun `typed complete hash words become tags, the last unfinished one stays`() {
        val q = MemoSearchQuery(text = "#교토 가와라마치 #이").normalized(keepTyping = true)
        assertEquals(listOf("교토"), q.tags)
        assertEquals("가와라마치 #이", q.text)
        val sent = MemoSearchQuery(text = "#교토 가와라마치 #이동").normalized()
        assertEquals(listOf("교토", "이동"), sent.tags)
        assertEquals("가와라마치", sent.serverText())
        assertEquals("#교토 AND #이동 AND \"가와라마치\"", sent.describe())
    }

    @Test
    fun `filters hold only what is set, tags are AND`() {
        val q = MemoSearchQuery(tags = listOf("교토", "이동"), kinds = setOf("음성", "사진"), period = MemoPeriod.Days7, pinned = true)
        val json = MemoOp.search(q, null).toJson("r1")
        val filters = json.getJSONObject("filters")
        assertEquals("교토", filters.getJSONArray("tags").getString(0))
        assertEquals("이동", filters.getJSONArray("tags").getString(1))
        // Kinds keep the chip order.
        assertEquals("사진", filters.getJSONArray("종류").getString(0))
        assertEquals("7일", filters.getString("기간"))
        assertTrue(filters.getBoolean("고정"))
        assertFalse(filters.has("일정연결"))
        assertFalse(filters.has("포함"))
        assertFalse(json.has("cursor"))
        val wide = MemoOp.search(MemoSearchQuery(text = "x", includeHidden = true, period = MemoPeriod.Range("2026-10-01", "2026-10-05")), "c").toJson("r2")
        assertEquals(2, wide.getJSONObject("filters").getJSONArray("포함").length())
        assertEquals("2026-10-01", (wide.getJSONObject("filters").get("기간") as JSONObject).getString("시작"))
        assertEquals("c", wide.getString("cursor"))
    }

    @Test
    fun `empty query shows the empty state`() {
        assertTrue(MemoSearchQuery().isEmpty)
        assertFalse(MemoSearchQuery(tags = listOf("a")).isEmpty)
        assertFalse(MemoSearchQuery().toggleKind("사진").isEmpty)
        assertTrue(MemoSearchQuery().toggleKind("사진").toggleKind("사진").isEmpty)
    }

    @Test
    fun `suggestions put prefix matches first, then by count, without selected ones`() {
        val tags = FakeMemoBackend.sampleTags()
        val s = suggestMemoTags(tags, "이", selected = listOf("이번주"))
        // 빵집 matches through its synonym 베이커리, after the names starting with the prefix.
        assertEquals(listOf("이동", "이자카야", "빵집"), s.map { it.name })
        // Synonyms match too.
        assertEquals("빵집", suggestMemoTags(tags, "베이커리", emptyList()).single().name)
        val axes = memoTagsByAxis(tags)
        assertEquals(listOf("주제", "장소", "시간", "행동"), axes.map { it.first })
        assertEquals("교토", axes[1].second.first().name)
    }

    @Test
    fun `snippet marks become highlighted parts`() {
        val parts = parseMemoSnippet("… <mark>가와라마치</mark> 역 &amp; 출구 <mark>교토")
        assertEquals(
            listOf(
                MemoSnippetPart("… ", false),
                MemoSnippetPart("가와라마치", true),
                MemoSnippetPart(" 역 & 출구 ", false),
                MemoSnippetPart("교토", true),
            ),
            parts,
        )
        assertEquals("음성: ", memoMatchPrefix("음성"))
        assertEquals("", memoMatchPrefix(null))
    }
}
