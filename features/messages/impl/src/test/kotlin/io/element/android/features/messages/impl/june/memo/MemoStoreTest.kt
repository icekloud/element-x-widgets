/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoStoreTest : RobolectricTest() {
    private var rid = 0
    private fun store(backend: FakeMemoBackend) = MemoStore(backend, newRequestId = { "r${++rid}" })

    @Test
    fun `pages of 30, next page near the end, restored without asking again`() = runTest {
        val backend = FakeMemoBackend()
        val store = store(backend)
        store.refreshIndex()
        val key = MemoListKey.of("링크보관", emptyList())
        store.loadFirst(key)
        var s = store.list(key)
        assertEquals(30, s.items.size)
        assertEquals(1, s.pages)
        assertFalse(s.endReached)
        // Not near the end yet.
        assertFalse(memoShouldLoadMore(s, lastVisibleIndex = 20))
        assertTrue(memoShouldLoadMore(s, lastVisibleIndex = 25))
        store.loadMore(key)
        store.loadMore(key)
        s = store.list(key)
        assertEquals(65, s.items.size)
        assertEquals(3, s.pages)
        assertTrue(s.endReached)
        assertFalse(memoShouldLoadMore(s, lastVisibleIndex = 64))
        // The cursor of the second request is the one of the first answer.
        assertEquals("30", backend.received[1].getString("cursor"))
        assertEquals("r1", backend.received[0].getString("요청id"))
        // Going back to the folder: same items, scroll kept, no new request.
        store.saveScroll(key, 41, 12)
        val before = backend.received.size
        store.loadFirst(key)
        assertEquals(before, backend.received.size)
        assertEquals(41, store.list(key).scrollIndex)
        assertEquals(12, store.list(key).scrollOffset)
        assertEquals(65, store.list(key).items.size)
    }

    @Test
    fun `tags of a list are AND and keyed in any order`() = runTest {
        val backend = FakeMemoBackend()
        val store = store(backend)
        val key = MemoListKey.of("여행", listOf("교토", "이동"))
        assertEquals(key, MemoListKey.of("여행", listOf("이동", "교토")))
        store.loadFirst(key)
        assertEquals(listOf("가와라마치 역 출구"), store.list(key).items.map { it.title })
        assertEquals(2, backend.received.single().getJSONArray("tags").length())
        // Without tags the request has no tags field at all.
        store.loadFirst(MemoListKey.of("여행", emptyList()))
        assertFalse(backend.received.last().has("tags"))
    }

    @Test
    fun `no answer in 15 seconds gives an error, retry works`() = runTest {
        val backend = FakeMemoBackend().apply { silent = true }
        val store = store(backend)
        val key = MemoListKey.of("여행", emptyList())
        store.loadFirst(key)
        val s = store.list(key)
        assertTrue(s.error!!.contains("15초"))
        assertFalse(s.loading)
        assertFalse(memoShouldLoadMore(s, 0))
        backend.silent = false
        store.retry(key)
        assertNull(store.list(key).error)
        assertEquals(5, store.list(key).items.size)
    }

    @Test
    fun `server error reason is shown as is`() = runTest {
        val backend = FakeMemoBackend().apply { failing += "pin" }
        val r = store(backend).request(MemoOp.pin(MemoTarget(noteId = "x")))
        assertEquals(MemoResult.Failed("가짜 오류: pin"), r)
    }

    @Test
    fun `offline index keeps the last good one and flags the error`() = runTest {
        val backend = FakeMemoBackend()
        val store = store(backend)
        assertTrue(store.refreshIndex())
        val good = store.index.value
        backend.offline = true
        assertFalse(store.refreshIndex())
        assertTrue(store.indexError.value)
        assertEquals(good, store.index.value)
    }

    @Test
    fun `new notes badge from the index count`() = runTest {
        val backend = FakeMemoBackend()
        val store = store(backend)
        store.refreshIndex()
        val key = MemoListKey.of("여행", emptyList())
        store.loadFirst(key)
        assertEquals(0, store.newCount(key))
        val n = backend.notes.first()
        backend.notes += n.copy(item = n.item.copy(id = "new1", date = "2026-10-12T00:00:00+09:00"))
        backend.notes += n.copy(item = n.item.copy(id = "new2", date = "2026-10-12T00:00:01+09:00"))
        store.refreshIndex()
        assertEquals(2, store.newCount(key))
        store.reloadTop(key)
        assertEquals(0, store.newCount(key))
        assertEquals("new2", store.list(key).items.first().id)
    }

    @Test
    fun `accepted changes update the cached lists`() = runTest {
        val backend = FakeMemoBackend()
        val store = store(backend)
        store.refreshIndex()
        val key = MemoListKey.of("여행", emptyList())
        store.loadFirst(key)
        val id = "20261011-101500-c3d4"
        assertTrue(store.request(MemoOp.move(MemoTarget(noteId = id), "아이디어")) is MemoResult.Ok)
        assertFalse(store.list(key).items.any { it.id == id })
        val curry = "20261002-120000-i9j0"
        store.request(MemoOp.pin(MemoTarget(noteId = curry)))
        val item = store.list(key).items.first { it.id == curry }
        assertTrue(item.pinned)
        assertFalse(item.pinSuggested)
        store.request(MemoOp.editTags(MemoTarget(noteId = curry), listOf("카레")))
        assertEquals(listOf("카레"), store.list(key).items.first { it.id == curry }.tags)
        // A move to a missing folder is refused by the server and changes nothing.
        val r = store.request(MemoOp.move(MemoTarget(noteId = curry), "없는폴더"))
        assertTrue(r is MemoResult.Failed)
        assertTrue(store.list(key).items.any { it.id == curry })
    }

    @Test
    fun `proposal accept, folder creation and tags`() = runTest {
        val backend = FakeMemoBackend()
        val store = store(backend)
        store.refreshIndex()
        assertEquals(2, store.index.value.pendingProposals)
        store.request(MemoOp.accept("p-folder-1", folder = "영상 나중에"))
        store.request(MemoOp.mkdir("반려동물/병원", null))
        store.refreshIndex()
        assertEquals(1, store.index.value.pendingProposals)
        assertTrue(store.index.value.folder("영상 나중에") != null)
        assertEquals(listOf("반려동물/병원"), memoSubfolders(store.index.value, "반려동물").map { it.path })
        store.request(MemoOp.defer("20261011-143012-a1b2", "2026-10-06"))
        store.refreshIndex()
        assertEquals(0, store.index.value.pendingProposals)
        assertEquals("재검토", store.index.value.proposals.single().status)
        val tags = store.loadTags()
        assertEquals("교토", tags.first().name)
        store.request(MemoOp.tagMerge(listOf("빵집", "카레"), "빵집"))
        assertTrue(store.tags.value.isEmpty())
        assertFalse(store.loadTags().any { it.name == "카레" })
    }

    @Test
    fun `note is fetched once and search sends tags as filters`() = runTest {
        val backend = FakeMemoBackend()
        val store = store(backend)
        val r = store.loadNote("20261011-143012-a1b2")
        assertTrue(r is MemoResult.Ok)
        assertEquals("여행", store.cachedNote("20261011-143012-a1b2")!!.folder)
        val result = store.search(MemoSearchQuery(text = "#교토 가와라마치")) as MemoResult.Ok
        val sent = backend.received.last()
        assertEquals("가와라마치", sent.getString("q"))
        assertEquals("교토", sent.getJSONObject("filters").getJSONArray("tags").getString(0))
        val items = (result.response as MemoResponse.Search).page.items
        // 환전 mentions 가와라마치 but has no #교토: the tag filter is AND.
        assertEquals(listOf("○○베이커리", "가와라마치 역 출구", "숙소 체크인 15:00"), items.map { it.title })
        assertTrue(items.all { "교토" in it.tags })
        assertTrue(items.first().snippet!!.contains("<mark>가와라마치</mark>"))
        // Inbox is excluded unless asked.
        val hidden = store.search(MemoSearchQuery(text = "영상")) as MemoResult.Ok
        assertTrue((hidden.response as MemoResponse.Search).page.items.isEmpty())
        val wide = store.search(MemoSearchQuery(text = "영상", includeHidden = true)) as MemoResult.Ok
        assertEquals(1, (wide.response as MemoResponse.Search).page.items.size)
    }
}
