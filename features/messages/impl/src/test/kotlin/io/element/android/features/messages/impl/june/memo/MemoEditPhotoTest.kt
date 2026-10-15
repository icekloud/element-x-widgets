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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 앱13 D (관리자 2026-10-15: "사진 첨부 후 나갔다가 다시 편집 들어왔다 나가면 사진이 여러 개 추가된 것처럼 보임"): one
 * picture picked once is one photo, however often the editor is opened and left again. All data is made up.
 */
class MemoEditPhotoTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0
    private var copies = 0
    private val dir: File = File(System.getProperty("java.io.tmpdir"), "memo-edit-photo-${System.nanoTime()}").apply { mkdirs() }

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    private suspend fun open(local: MemoLocalBackend, id: String) = (local.call(MemoOp.get(id), "g") as MemoResponse.Note).note

    /** A made up picture file next to the database, the way memoCopyPictures makes one per pick. */
    private fun photoFile(content: ByteArray = byteArrayOf(1, 2, 3, 4, 5)): MemoLocalAttachment {
        copies++
        val f = File(dir, "photo-$copies.jpg").apply { writeBytes(content) }
        return MemoLocalAttachment(id = f.name, localPath = f.absolutePath, mime = "image/jpeg", bytes = f.length())
    }

    /** The picks of the editor, copied like memoCopyPictures: every pick of the same picture gives the same bytes. */
    private fun copyPicks(uris: List<String>): List<MemoLocalAttachment> = uris.map { photoFile(it.toByteArray()) }

    /** The editor opens on the note as the screen reads it (MemoNoteEditor: `MemoEditHub.open(note.id, …)`). */
    private suspend fun enter(local: MemoLocalBackend, id: String) = MemoEditHub.open(id, memoEditDraftOf(open(local, id)))

    private fun pick(id: String, vararg uris: String) {
        val s = MemoEditHub.state(id)!!
        MemoEditHub.typed(id, s.draft.copy(photos = s.draft.photos.pick(uris.toList())))
    }

    /** The save of the editor leaving the screen (onDispose), the way MemoNoteEditor does it. */
    private suspend fun leave(local: MemoLocalBackend, id: String) {
        now += 1_000
        MemoEditHub.save(id, dropWhenDone = true) { current ->
            val added = copyPicks(current.draft.photos.picked).map(::memoPhotoArg)
            val op = memoEditOp(id, current.saved, current.draft, added, includePhotos = true)
            val saved = memoEditSaved(current.saved, current.draft, includePhotos = true)
            when (val r = op?.let { local.call(it, "e") }) {
                is MemoResponse.Error -> MemoSaveResult(null, r.reason)
                else -> MemoSaveResult(saved, photosWritten = true)
            }
        }
    }

    @Test
    fun `a photo added, then the editor opened and left twice more, is still one photo`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("가슴 운동 세트별 중량")
        MemoEditHub.drop(id)
        enter(local, id)
        pick(id, "content://fake/1")
        leave(local, id)
        // 앱12: the session kept the pick after it was written, so it was never let go and every later leave added it again.
        assertNull(MemoEditHub.state(id))
        assertEquals(1, db.note(id)!!.attachments.size)
        repeat(2) {
            enter(local, id)
            assertEquals(1, MemoEditHub.state(id)!!.draft.photos.count)
            assertFalse(MemoEditHub.state(id)!!.photosDirty)
            leave(local, id)
        }
        assertEquals(1, db.note(id)!!.attachments.size)
        assertEquals(1, open(local, id).attachments.size)
        assertEquals(1, copies)
    }

    @Test
    fun `a save that wrote the picks leaves only what was picked while it ran`() {
        val existing = MemoAttachment("mxc://test/1")
        val entry = MemoEditDraft(body = "글", photos = MemoPhotoEdit(listOf(existing)))
        val sent = entry.copy(photos = entry.photos.pick(listOf("content://fake/1")))
        val before = MemoEditState("n", entry, entry, sent)
        // While the copy ran the user picked one more picture.
        val after = before.typed(sent.copy(photos = sent.photos.pick(listOf("content://fake/2"))))
        val written = MemoAttachment(MEMO_LOCAL_FILE_PREFIX + "/p/new.jpg", localPath = "/p/new.jpg")
        val saved = sent.copy(photos = MemoPhotoEdit(listOf(existing, written)))
        val next = memoEditAfterSave(before, after, MemoSaveResult(saved, photosWritten = true))
        assertEquals(listOf("content://fake/2"), next.draft.photos.picked)
        assertEquals(listOf(existing, written), next.draft.photos.existing)
        assertTrue(next.photosDirty)
        // A text save (the photos wait) leaves the picks alone, a failed one changes nothing but the reason.
        assertEquals(after.draft, memoEditAfterSave(before, after, MemoSaveResult(sent)).draft)
        assertEquals("없음", memoEditAfterSave(before, after, MemoSaveResult(null, "없음")).failure)
        assertEquals(after.saved, memoEditAfterSave(before, after, MemoSaveResult(null, "없음")).saved)
    }

    @Test
    fun `the same picture picked twice in one edit is one photo`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("두 번 고른 사진")
        MemoEditHub.drop(id)
        enter(local, id)
        // The gallery gives two different uris for the same picture (two picks): two copies, one picture.
        pick(id, "content://fake/1")
        val again = photoFile("content://fake/1".toByteArray())
        val first = photoFile("content://fake/1".toByteArray())
        val op = MemoOp.edit(MemoTarget(noteId = id), null, null, null, photosAdded = listOf(memoPhotoArg(first), memoPhotoArg(again)))
        local.call(op, "e")
        assertEquals(1, db.note(id)!!.attachments.size)
        // A copy of a picture the note already has is not added either.
        val again = listOf(memoPhotoArg(photoFile("content://fake/1".toByteArray())))
        local.call(MemoOp.edit(MemoTarget(noteId = id), null, null, null, photosAdded = again), "e2")
        assertEquals(1, db.note(id)!!.attachments.size)
        // Another picture is another photo.
        local.call(MemoOp.edit(MemoTarget(noteId = id), null, null, null, photosAdded = listOf(memoPhotoArg(photoFile(byteArrayOf(9, 9))))), "e3")
        assertEquals(2, db.note(id)!!.attachments.size)
        MemoEditHub.drop(id)
    }

    @Test
    fun `copies stored by the 앱12 editor are drawn once, and taking that one out takes them all`() = runTest {
        val db = db()
        val local = backend(db)
        val content = byteArrayOf(7, 7, 7)
        val stored = listOf(photoFile(content), photoFile(content), photoFile(content))
        val id = local.create("이미 쌓인 사본", stored, kind = MEMO_KIND_TEXT)
        // The rows stay as they are (nothing is deleted on reading); the open note and the card draw one.
        assertEquals(3, db.note(id)!!.attachments.size)
        assertEquals(1, open(local, id).attachments.size)
        val card = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.single { it.id == id }
        assertEquals(1, (card.photos ?: card.preview as MemoPreview.Photo).count)
        stored.forEach { assertTrue(File(it.localPath!!).isFile) }
        // Opening and leaving the editor without touching the photos writes nothing.
        MemoEditHub.drop(id)
        enter(local, id)
        val rev = db.note(id)!!.revs.attach
        leave(local, id)
        assertEquals(rev, db.note(id)!!.revs.attach)
        assertEquals(3, db.note(id)!!.attachments.size)
        // ✕ on the one photo shown takes out its hidden copies too, or the photo would come back.
        enter(local, id)
        val shown = MemoEditHub.state(id)!!.draft.photos.existing.single()
        val s = MemoEditHub.state(id)!!
        MemoEditHub.typed(id, s.draft.copy(photos = s.draft.photos.toggle(shown)))
        leave(local, id)
        assertTrue(db.note(id)!!.attachments.isEmpty())
        assertTrue(open(local, id).attachments.isEmpty())
        // The phone files are left alone (no file is deleted by the editor).
        stored.forEach { assertTrue(File(it.localPath!!).isFile) }
    }

    @Test
    fun `two different pictures of the same size stay two, and a photo without a phone file is never merged`() {
        val a = photoFile(byteArrayOf(1, 2, 3))
        val b = photoFile(byteArrayOf(3, 2, 1))
        assertEquals(listOf(a, b), memoDistinctPhotos(listOf(a, b)))
        // Uploaded, the phone original cleaned: nothing to compare, both stay.
        val up1 = MemoLocalAttachment(id = "u1", mxc = "mxc://test/1", bytes = 3)
        val up2 = MemoLocalAttachment(id = "u2", mxc = "mxc://test/2", bytes = 3)
        assertEquals(listOf(up1, up2), memoDistinctPhotos(listOf(up1, up2)))
        // The same key twice (one row read twice) is drawn once.
        assertEquals(listOf(up1), memoDistinctPhotos(listOf(up1, up1)))
        // Removing one of two different pictures removes only that one.
        assertEquals(setOf(a.key), memoRemovedWithCopies(listOf(a, b), setOf(a.key)))
        assertEquals(memoPhotoFingerprint(a.localPath), memoPhotoFingerprint(a.localPath))
        assertNull(memoPhotoFingerprint(File(dir, "none.jpg").absolutePath))
    }

    @Test
    fun `a server list that comes in for a note with copies adds no photo`() = runTest {
        val db = db()
        val local = backend(db)
        val content = byteArrayOf(5, 5, 5, 5)
        val id = local.create("서버 합침", listOf(photoFile(content), photoFile(content)))
        val mine = db.note(id)!!
        // The server knows the note but not the photos (they are not uploaded): its newer list is empty.
        val server = mine.copy(attachments = emptyList(), revs = mine.revs.with(MemoBundle.Attach, MemoRev(now + 60_000, MEMO_DEVICE_SERVER, 9)))
        val merged = mergeMemoNote(mine, server)
        assertEquals(2, merged.attachments.size)
        assertEquals(1, memoDistinctPhotos(merged.attachments).size)
        assertEquals(mergeMemoNote(server, mine).attachments.map { it.key }, merged.attachments.map { it.key })
    }
}
