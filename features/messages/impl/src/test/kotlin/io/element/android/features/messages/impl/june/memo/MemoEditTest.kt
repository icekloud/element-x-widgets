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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱12 (관리자 2026-10-15): the dates of an open note (A), the editor without a [저장] button (B·E), the long press
 * that starts it (C) and the draft that survives leaving the screen (D). All data is made up.
 */
class MemoEditTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    private suspend fun open(local: MemoLocalBackend, id: String) = (local.call(MemoOp.get(id), "g") as MemoResponse.Note).note

    /**
     * One save of the editor, the way the screen does it: the op of what changed goes to the backend, then the hub
     * keeps what the local note now holds. [finish] is the save that ends the editing (the photos go with it).
     */
    private suspend fun saveOnce(local: MemoLocalBackend, noteId: String, finish: Boolean, added: List<Map<String, Any?>> = emptyList()) {
        MemoEditHub.save(noteId, dropWhenDone = finish) { current ->
            val op = memoEditOp(noteId, current.saved, current.draft, added, includePhotos = finish)
            val saved = memoEditSaved(current.saved, current.draft, includePhotos = finish)
            when (val answer = op?.let { local.call(it, "e") }) {
                null -> MemoSaveResult(saved)
                is MemoResponse.Error -> MemoSaveResult(null, answer.reason)
                else -> MemoSaveResult(saved)
            }
        }
    }

    // ---------------------------------------------------------------- A: the dates of an open note

    @Test
    fun `a note never changed shows one line, a changed one shows two`() {
        val created = "2026-10-15T19:23:04+09:00"
        assertEquals(listOf("작성 2026-10-15 19:23"), memoNoteDateLines(created, null))
        assertEquals(listOf("작성 2026-10-15 19:23"), memoNoteDateLines(created, ""))
        assertEquals(
            listOf("작성 2026-10-15 19:23", "수정 2026-10-15 19:30"),
            memoNoteDateLines(created, "2026-10-15T19:30:00+09:00"),
        )
        // The times are read as they are stored: KST to the minute, no conversion of the offset.
        assertEquals("2026-10-15 19:23", memoMinuteTime(created))
        assertEquals("2026-01-02 00:05", memoMinuteTime("2026-01-02T00:05:59+09:00"))
    }

    @Test
    fun `an empty, a same minute and an earlier change show no 수정 line`() {
        val created = "2026-10-15T19:23:04+09:00"
        // Same minute: the second line would say nothing.
        assertEquals(1, memoNoteDateLines(created, "2026-10-15T19:23:50+09:00").size)
        // Odd value (a change earlier than the creation): left out, the stored value itself is untouched.
        assertEquals(1, memoNoteDateLines(created, "2026-10-14T08:00:00+09:00").size)
        // An old note without any date shows nothing; only a user change: that line alone.
        assertTrue(memoNoteDateLines("", null).isEmpty())
        assertEquals(listOf("수정 2026-10-15 19:30"), memoNoteDateLines("", "2026-10-15T19:30:00+09:00"))
        // A short, broken value is shown as it is instead of being cut.
        assertEquals(listOf("작성 2026-10"), memoNoteDateLines("2026-10", null))
    }

    @Test
    fun `the head of an open note is the number and the title, the date is no longer in it`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("교토 빵집 메모")
        val note = open(local, id)
        val head = memoNoteScreenHead(note)
        assertFalse(head.contains("2026"))
        assertEquals("메모", head)
        assertEquals(listOf("작성 " + memoMinuteTime(memoIso(now))), memoNoteDateLines(note))
    }

    // ---------------------------------------------------------------- C: long press on the reading area

    @Test
    fun `a long press on the reading area starts the editor, and only there`() {
        val reading = MemoLocation().openNote("n1")
        assertEquals(reading.startEditing(), memoReadingLongPress(reading, selecting = false))
        assertTrue(memoReadingLongPress(reading, selecting = false)!!.editing)
        // Already editing, no note open, or the selection mode on: nothing happens.
        assertNull(memoReadingLongPress(reading.startEditing(), selecting = false))
        assertNull(memoReadingLongPress(MemoLocation(), selecting = false))
        assertNull(memoReadingLongPress(reading, selecting = true))
        // The tab and the folder the note was opened from stay as they are (no extra back step).
        val fromFolder = MemoLocation().openFolder("여행").openNote("n1")
        val editing = memoReadingLongPress(fromFolder, selecting = false)!!
        assertEquals("여행", editing.folder)
        assertEquals(MemoTab.Folders, editing.tab)
    }

    // ---------------------------------------------------------------- B·D: what the auto save writes

    @Test
    fun `the op carries only what changed, and nothing at all when nothing did`() {
        val saved = MemoEditDraft(title = "교토", tags = "#여행", body = "빵집")
        assertNull(memoEditOp("n1", saved, saved))
        val typed = saved.copy(body = "빵집 7시")
        val op = memoEditOp("n1", saved, typed)!!
        assertEquals("edit", op.op)
        assertEquals("빵집 7시", op.args["본문"])
        assertNull(op.args["제목"])
        assertNull(op.args["태그"])
        assertNull(op.args[MEMO_ARG_PHOTOS_REMOVED])
        // Tags are parsed only when the typed line changed.
        val retagged = memoEditOp("n1", saved, saved.copy(tags = "#여행 #교토"))!!
        assertEquals(listOf("여행", "교토"), retagged.args["태그"])
        // An emptied body is a change like any other (never a delete).
        assertEquals("", memoEditOp("n1", saved, saved.copy(body = ""))!!.args["본문"])
    }

    @Test
    fun `photo marks go only with the save that ends the editing`() {
        val a = MemoAttachment("mxc://test/1")
        val b = MemoAttachment(MEMO_LOCAL_FILE_PREFIX + "/p/2.jpg", localPath = "/p/2.jpg")
        val saved = MemoEditDraft(body = "글", photos = MemoPhotoEdit(listOf(a, b)))
        val marked = saved.copy(photos = saved.photos.toggle(a).pick(listOf("content://x/9")))
        // While typing: the photos are left alone, so a removal can still be brought back.
        assertNull(memoEditOp("n1", saved, marked))
        val added = listOf(mapOf<String, Any?>("id" to "new.jpg", "경로" to "/p/new.jpg"))
        val op = memoEditOp("n1", saved, marked, added, includePhotos = true)!!
        assertEquals(listOf("mxc://test/1"), op.args[MEMO_ARG_PHOTOS_REMOVED])
        assertEquals(added, op.args[MEMO_ARG_PHOTOS_ADDED])
        // After that save the marks are gone and only the kept photo is left.
        val after = memoEditSaved(saved, marked, includePhotos = true)
        assertEquals(listOf(b), after.photos.existing)
        assertFalse(after.photos.changed)
        // A text save keeps the photo state waiting.
        assertEquals(saved.photos, memoEditSaved(saved, marked, includePhotos = false).photos)
    }

    @Test
    fun `the status line tells what the auto save is doing`() {
        val entry = MemoEditDraft(body = "글")
        val state = MemoEditState("n1", entry, entry, entry)
        assertEquals("", memoEditStatus(state))
        assertEquals("자동 저장 대기…", memoEditStatus(state.typed(entry.copy(body = "글 더"))))
        val written = state.copy(saved = entry.copy(body = "글 더"), draft = entry.copy(body = "글 더"))
        assertEquals("자동 저장됨", memoEditStatus(written))
        assertTrue(memoEditStatus(written.copy(failure = "디스크 가득 참")).startsWith("저장 못 함 · 디스크 가득 참"))
        val photos = state.typed(entry.copy(photos = MemoPhotoEdit(listOf(MemoAttachment("mxc://test/1"))).pick(listOf("content://x/9"))))
        assertEquals("사진은 편집을 마치면 반영됩니다", memoEditStatus(photos))
    }

    // ---------------------------------------------------------------- D: the draft outside the screen

    @Test
    fun `the draft survives leaving the screen and a note that comes in again`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("처음 글")
        val note = open(local, id)
        MemoEditHub.drop(id)
        MemoEditHub.open(id, memoEditDraftOf(note))
        MemoEditHub.typed(id, MemoEditHub.state(id)!!.draft.copy(body = "고치는 중"))
        // Leaving the editor for another tab and coming back: the same session, the typed text is still there.
        MemoEditHub.open(id, memoEditDraftOf(note))
        assertEquals("고치는 중", MemoEditHub.state(id)!!.draft.body)
        // A newer note (the server changed this one while the editor was open) does not take its place either.
        val serverNote = note.copy(body = "서버가 고친 글")
        MemoEditHub.open(id, memoEditDraftOf(serverNote))
        assertEquals("고치는 중", MemoEditHub.state(id)!!.draft.body)
        MemoEditHub.drop(id)
        assertNull(MemoEditHub.state(id))
    }

    @Test
    fun `typing while a save runs is not overwritten by it`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("처음 글")
        MemoEditHub.drop(id)
        val entry = memoEditDraftOf(open(local, id))
        MemoEditHub.open(id, entry)
        MemoEditHub.typed(id, entry.copy(body = "첫 입력"))
        MemoEditHub.save(id) { current ->
            // The user keeps typing while the save is on its way.
            MemoEditHub.typed(id, current.draft.copy(body = "첫 입력 그리고 더"))
            val op = memoEditOp(id, current.saved, current.draft)!!
            local.call(op, "e")
            MemoSaveResult(memoEditSaved(current.saved, current.draft, includePhotos = false))
        }
        val after = MemoEditHub.state(id)!!
        assertEquals("첫 입력 그리고 더", after.draft.body)
        assertEquals("첫 입력", after.saved.body)
        assertTrue(after.textDirty)
        MemoEditHub.drop(id)
    }

    @Test
    fun `a failed save keeps the text and is tried again`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("처음 글")
        MemoEditHub.drop(id)
        val entry = memoEditDraftOf(open(local, id))
        MemoEditHub.open(id, entry)
        MemoEditHub.typed(id, entry.copy(body = "못 저장할 글"))
        MemoEditHub.save(id) { MemoSaveResult(null, "디스크에 쓰지 못했습니다") }
        val failed = MemoEditHub.state(id)!!
        assertEquals("디스크에 쓰지 못했습니다", failed.failure)
        assertEquals("못 저장할 글", failed.draft.body)
        assertEquals("처음 글", failed.saved.body)
        // The next attempt (the next keystroke or leaving the screen) goes through and clears the line.
        saveOnce(local, id, finish = false)
        assertNull(MemoEditHub.state(id)!!.failure)
        assertEquals("못 저장할 글", db.note(id)!!.body)
        MemoEditHub.drop(id)
    }

    // ---------------------------------------------------------------- D: what reaches the database

    @Test
    fun `one note keeps one waiting upload however often the auto save writes`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("처음 글")
        // The first upload (the new note) goes through, so only the editing is counted below.
        db.outbox().forEach { db.dropOutbox(it.seq) }
        MemoEditHub.drop(id)
        val entry = memoEditDraftOf(open(local, id))
        MemoEditHub.open(id, entry)
        listOf("고", "고치", "고치는 중").forEach { typed ->
            now += 2_000
            MemoEditHub.typed(id, MemoEditHub.state(id)!!.draft.copy(body = typed))
            saveOnce(local, id, finish = false)
        }
        // 설계 §k-4 ②: one row per note — three saves do not make three uploads.
        val outbox = db.outbox().filter { it.kind == MEMO_OUTBOX_NOTE }
        assertEquals(1, outbox.size)
        assertEquals(id, outbox.single().target)
        assertEquals("고치는 중", db.note(id)!!.body)
        assertTrue(db.note(id)!!.dirty)
        // The date shown follows the user's change (앱10), and nothing was written twice.
        assertTrue(memoNoteMeta(db, id).userModifiedMs >= t0)
        MemoEditHub.drop(id)
    }

    @Test
    fun `an emptied note is still a note, and leaving writes it with its photos`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("지울 글")
        MemoEditHub.drop(id)
        val entry = memoEditDraftOf(open(local, id))
        MemoEditHub.open(id, entry)
        now += 1_000
        MemoEditHub.typed(id, entry.copy(title = "", tags = "", body = ""))
        // The save that ends the editing: the hub lets the session go when there is nothing left.
        saveOnce(local, id, finish = true)
        val emptied = db.note(id)!!
        assertEquals("", emptied.body)
        assertEquals("", emptied.title)
        assertEquals(MEMO_DELETED_NONE, emptied.deleted)
        assertEquals(MEMO_INBOX, emptied.folder)
        assertNull(MemoEditHub.state(id))
        assertNotNull(db.note(id))
    }

    @Test
    fun `cancel puts the note back to what it was when the editor opened`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("처음 글")
        MemoEditHub.drop(id)
        val entry = memoEditDraftOf(open(local, id))
        MemoEditHub.open(id, entry)
        now += 1_000
        MemoEditHub.typed(id, entry.copy(body = "자동 저장된 글", title = "새 제목"))
        saveOnce(local, id, finish = false)
        assertEquals("자동 저장된 글", db.note(id)!!.body)
        val before = db.note(id)!!.revs.body
        // [취소]: the auto save already wrote, so the undo is a normal change of its own.
        val state = MemoEditHub.state(id)!!
        assertTrue(state.revertable)
        val undo = memoEditCancelOp(id, state.saved, state.entry)!!
        now += 1_000
        local.call(undo, "c")
        MemoEditHub.drop(id)
        val back = db.note(id)!!
        assertEquals("처음 글", back.body)
        assertEquals("", back.title)
        assertTrue(back.revs.body > before)
        assertTrue(back.dirty)
        assertEquals(1, db.outbox().count { it.kind == MEMO_OUTBOX_NOTE && it.target == id })
        // Nothing typed and nothing written: there is nothing to undo and no question to ask.
        val fresh = MemoEditState(id, entry, entry, entry)
        assertFalse(fresh.revertable)
        assertNull(memoEditCancelOp(id, fresh.saved, fresh.entry))
    }

    @Test
    fun `a server change that arrives while editing does not touch what was typed`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("처음 글")
        MemoEditHub.drop(id)
        val entry = memoEditDraftOf(open(local, id))
        MemoEditHub.open(id, entry)
        MemoEditHub.typed(id, entry.copy(body = "폰에서 치는 중"))
        // The sync stores a newer server body for the same note (분류 and 본문 of the server side).
        val fromServer = db.note(id)!!.toChangeJson()
            .put("본문", "서버가 고친 글")
            .put("수정", memoIso(now + 60_000))
            .put("기기", MEMO_DEVICE_SERVER)
            .put("순번", 99)
        db.transaction { MemoSync.applyChange(db, fromServer) }
        assertEquals("폰에서 치는 중", MemoEditHub.state(id)!!.draft.body)
        // Leaving the editor writes what was typed; the editor never read the server text into its fields.
        now += 120_000
        saveOnce(local, id, finish = true)
        assertEquals("폰에서 치는 중", db.note(id)!!.body)
        assertNull(MemoEditHub.state(id))
    }
}
