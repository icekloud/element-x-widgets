/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** [MemoStore] on the local database (설계 A2): the same store API the screens use, answered without any server. */
class MemoLocalBackendTest : RobolectricTest() {
    private var now = 1_791_772_260_000L // 2026-10-12T11:31:00+09:00

    private fun setup(): Triple<MemoDb, MemoLocalBackend, MemoStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        val db = MemoDb(driver)
        val backend = MemoLocalBackend({ db }, clock = { now }, dispatcher = EmptyCoroutineContext)
        return Triple(db, backend, MemoStore(backend))
    }

    private suspend fun MemoLocalBackend.add(text: String, folder: String = MEMO_INBOX, tags: List<String> = emptyList()): String {
        now += 1_000
        val id = create(text, folder = folder)
        if (tags.isNotEmpty()) call(MemoOp.editTags(MemoTarget(id), tags), "t")
        return id
    }

    @Test
    fun `a typed note is saved at once, dirty, with one waiting upload, and shows in the inbox`() = runTest {
        val (db, backend, store) = setup()
        val id = backend.create("교토 빵집 메모\n가와라마치 7시")
        val n = db.note(id)!!
        // 앱7 항목 1: typed text goes to the body only, the title stays empty.
        assertEquals("", n.title)
        assertEquals("텍스트", n.kind)
        assertEquals(MEMO_INBOX, n.folder)
        assertTrue(n.dirty)
        assertTrue(Regex("^20261012-113100-[a-z0-9]{4}$").matches(id))
        val outbox = db.outbox().single()
        assertEquals(id, outbox.target)
        val payload = JSONObject(outbox.payload)
        assertEquals("교토 빵집 메모\n가와라마치 7시", payload.getString("본문"))
        assertEquals("2026-10-12T11:31:00+09:00", payload.getJSONObject("rev").getJSONArray("본문").getString(0))
        assertEquals(0, payload.getInt("삭제"))

        store.refreshIndex()
        assertEquals(1, store.index.value.folder(MEMO_INBOX)?.count)
        val key = MemoListKey.of(MEMO_INBOX, emptyList())
        store.loadFirst(key)
        assertEquals(listOf(id), store.list(key).items.map { it.id })
        assertEquals("링크", backend.run { db.note(create("https://tabelog.com/kyoto/"))!!.kind })
    }

    @Test
    fun `pages of 30 come from the database, the store keeps them for back navigation`() = runTest {
        val (_, backend, store) = setup()
        repeat(65) { backend.add("링크 모음 $it", folder = "링크보관") }
        val key = MemoListKey.of("링크보관", emptyList())
        store.loadFirst(key)
        assertEquals(30, store.list(key).items.size)
        assertEquals("링크 모음 64", store.list(key).items.first().title)
        store.loadMore(key)
        store.loadMore(key)
        assertEquals(65, store.list(key).items.size)
        assertTrue(store.list(key).endReached)
        assertEquals(65, store.list(key).items.map { it.id }.toSet().size)
    }

    @Test
    fun `move, tags, pin, trash and restore are local changes with a new stamp of their bundle`() = runTest {
        val (db, backend, store) = setup()
        val id = backend.add("환전 10만엔")
        val before = db.note(id)!!.revs
        val target = MemoTarget(id)
        assertTrue(store.request(MemoOp.move(target, "여행")) is MemoResult.Ok)
        assertTrue(store.request(MemoOp.editTags(target, listOf("환전", "할일"))) is MemoResult.Ok)
        assertTrue(store.request(MemoOp.pin(target)) is MemoResult.Ok)
        var n = db.note(id)!!
        assertEquals("여행", n.folder)
        assertEquals(listOf("환전", "할일"), n.tags)
        assertTrue(n.pinned)
        assertEquals(before.body, n.revs.body)
        assertTrue(n.revs.classes > before.classes)
        assertTrue(n.revs.mark > before.mark)
        // still one upload for the note, with the newest content
        assertEquals(1, db.outbox().count { it.target == id })
        assertEquals("여행", JSONObject(db.outbox().first { it.target == id }.payload).getString("폴더"))

        assertTrue(store.request(MemoOp.trash(target)) is MemoResult.Ok)
        n = db.note(id)!!
        assertEquals(MEMO_DELETED_TRASH, n.deleted)
        assertEquals("여행", n.folder)
        store.refreshIndex()
        assertEquals(1, store.index.value.folder(MEMO_TRASH)?.count)
        assertEquals(0, store.index.value.folder("여행")?.count)
        val trash = MemoListKey.of(MEMO_TRASH, emptyList())
        store.loadFirst(trash)
        assertEquals(listOf(id), store.list(trash).items.map { it.id })
        assertTrue(store.request(MemoOp.restore(target)) is MemoResult.Ok)
        assertEquals(MEMO_DELETED_NONE, db.note(id)!!.deleted)
        assertEquals("여행", db.note(id)!!.folder)
    }

    @Test
    fun `edit body and title, then get answers the note from the database`() = runTest {
        val (_, backend, store) = setup()
        val id = backend.add("처음 본문", tags = listOf("교토"))
        assertTrue(store.request(MemoOp.edit(MemoTarget(id), "# 고친 본문\n내용", "고친 제목", listOf("교토", "빵집"))) is MemoResult.Ok)
        val r = store.loadNote(id) as MemoResult.Ok
        val note = (r.response as MemoResponse.Note).note
        assertEquals("# 고친 본문\n내용", note.body)
        assertEquals("고친 제목", note.header.getString("제목"))
        assertEquals(listOf("교토", "빵집"), note.tags)
        assertEquals(MEMO_INBOX, note.folder)
        assertNotNull(store.cachedNote(id))
        assertTrue(store.loadNote("없는-노트") is MemoResult.Failed)
    }

    @Test
    fun `search runs on the local database with the store query`() = runTest {
        val (_, backend, store) = setup()
        val a = backend.add("교토 빵집 가와라마치", folder = "여행", tags = listOf("빵집"))
        backend.add("오사카 카레", folder = "여행", tags = listOf("카레"))
        backend.add("교토 영상 나중에") // inbox: hidden unless asked
        val r = store.search(MemoSearchQuery(text = "교토 #빵집")) as MemoResult.Ok
        val items = (r.response as MemoResponse.Search).page.items
        assertEquals(listOf(a), items.map { it.id })
        // 앱7 항목 1: no title was written, so the word is found in the body.
        assertEquals("본문", items.single().matchField)
        val all = store.search(MemoSearchQuery(text = "교토", includeHidden = true)) as MemoResult.Ok
        assertEquals(2, (all.response as MemoResponse.Search).page.items.size)
        val period = store.search(MemoSearchQuery(text = "교토", period = MemoPeriod.Today, includeHidden = true)) as MemoResult.Ok
        assertEquals(2, (period.response as MemoResponse.Search).page.items.size)
    }

    @Test
    fun `folders - mkdir, rename with subfolders and notes, rmdir sends the notes to the trash`() = runTest {
        val (db, backend, store) = setup()
        assertTrue(store.request(MemoOp.mkdir("여행/교토", "⛩")) is MemoResult.Ok)
        assertTrue(store.request(MemoOp.mkdir("여행/교토", null)) is MemoResult.Failed)
        val id = backend.add("후시미 이나리", folder = "여행/교토")
        assertTrue(store.request(MemoOp.renameDir("여행", "여행2026")) is MemoResult.Ok)
        assertEquals("여행2026/교토", db.note(id)!!.folder)
        store.refreshIndex()
        val paths = store.index.value.folders.map { it.path }
        assertTrue("여행2026" in paths && "여행2026/교토" in paths && "여행" !in paths)
        assertEquals(1, store.index.value.folder("여행2026")?.total)
        assertEquals("⛩", store.index.value.folder("여행2026/교토")?.icon)
        assertTrue(store.request(MemoOp.rmdir("여행2026")) is MemoResult.Ok)
        assertEquals(MEMO_DELETED_TRASH, db.note(id)!!.deleted)
        store.refreshIndex()
        assertNull(store.index.value.folder("여행2026"))
        assertTrue(store.request(MemoOp.rmdir(MEMO_INBOX)) is MemoResult.Failed)
    }

    @Test
    fun `tags - counts, rename, merge and delete change the notes`() = runTest {
        val (db, backend, store) = setup()
        val a = backend.add("하나", tags = listOf("교토", "빵집"))
        val b = backend.add("둘", tags = listOf("Kyoto"))
        assertEquals(listOf("Kyoto" to 1, "교토" to 1, "빵집" to 1).toSet(), store.loadTags().map { it.name to it.count }.toSet())
        assertTrue(store.request(MemoOp.tagMerge(listOf("Kyoto", "교토"), "교토")) is MemoResult.Ok)
        assertEquals(listOf("교토"), db.note(b)!!.tags)
        assertTrue(store.request(MemoOp.tagRename("빵집", "베이커리")) is MemoResult.Ok)
        assertEquals(listOf("교토", "베이커리"), db.note(a)!!.tags)
        assertTrue(store.request(MemoOp.tagDelete("교토")) is MemoResult.Ok)
        assertEquals(listOf("베이커리"), db.note(a)!!.tags)
        assertEquals(emptyList<String>(), db.note(b)!!.tags)
        assertTrue(store.request(MemoOp.tagCreate("할일", "행동")) is MemoResult.Ok)
        assertEquals(listOf("베이커리", "할일").toSet(), store.loadTags().map { it.name }.toSet())
    }

    @Test
    fun `no request needs the network and nothing waits - proposals are answered as not yet available`() = runTest {
        val (_, backend, store) = setup()
        val before = MemoLocalChanges.version.value
        backend.add("메모테스트")
        assertTrue(MemoLocalChanges.version.value > before)
        assertTrue(store.refreshIndex())
        assertFalse(store.indexError.value)
        assertEquals(0, store.index.value.pendingProposals)
        val r = store.request(MemoOp.accept("p1"))
        assertTrue(r is MemoResult.Failed && !r.timedOut)
    }

    @Test
    fun `the search op goes back to the same query`() {
        val q = MemoSearchQuery(
            text = "교토",
            tags = listOf("빵집"),
            kinds = setOf("사진"),
            folder = "여행",
            period = MemoPeriod.Range("2026-10-01", "2026-10-12"),
            schedule = true,
            pinned = true,
            includeHidden = true,
        )
        assertEquals(q, searchQueryOf(MemoOp.search(q, null)))
        assertEquals(MemoPeriod.Days7, searchQueryOf(MemoOp.search(MemoSearchQuery(period = MemoPeriod.Days7), null)).period)
    }
}
