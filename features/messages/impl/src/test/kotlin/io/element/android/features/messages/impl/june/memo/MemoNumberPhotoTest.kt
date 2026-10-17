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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱10 (규약-번호사진 + 추가 결정 2026-10-15): the note number and the user's last change kept beside the note, the
 * `#번호  제목  날짜` head without author, and the photo edit of the note editor. All data is made up.
 */
class MemoNumberPhotoTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    private fun item(db: MemoDb, id: String) = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.single { it.id == id }

    private suspend fun open(local: MemoLocalBackend, id: String) = (local.call(MemoOp.get(id), "g") as MemoResponse.Note).note

    /** What the server sends back for [n]: the phone note with the top level number (and user time) of 서버 5차. */
    private fun serverCopy(n: MemoNoteRecord, number: Int, userModified: String? = null): JSONObject {
        val o = n.toChangeJson().put(MEMO_FIELD_NUMBER, number).put("머리말", JSONObject().put(MEMO_FIELD_NUMBER, number))
        userModified?.let { o.put(MEMO_FIELD_USER_MODIFIED, it) }
        return o
    }

    private fun ack(id: String, state: String, note: JSONObject?) = JSONObject()
        .put("결과", JSONArray().put(JSONObject().put("id", id).put("상태", state).apply { note?.let { put("노트", it) } }))
        .put("서버순번", 1)

    // ---------------------------------------------------------------- C: head line

    @Test
    fun `the head is number, title and date two spaces apart, without author`() {
        assertEquals("#12  교토 빵집  10-15 11:31", memoNoteHead(12, "교토 빵집", "10-15 11:31"))
        assertEquals("교토 빵집  10-15 11:31", memoNoteHead(0, "교토 빵집", "10-15 11:31"))
        assertEquals("#12  10-15 11:31", memoNoteHead(12, "  ", "10-15 11:31"))
        assertEquals("#3  제목", memoNumbered(3, "제목"))
        assertEquals("제목", memoNumbered(0, "제목"))
        val card = MemoItem("n", "텍스트", "교토 빵집", null, "2026-10-15T11:31:00+09:00", emptyList(), MEMO_INBOX, untitled = false, number = 7)
        val head = memoRecordHead(card, null)
        assertEquals("#7  교토 빵집  10-15 11:31", head)
        assertFalse(head.contains("나 ·"))
    }

    // ---------------------------------------------------------------- C: number

    @Test
    fun `a new note has no number, the push answer gives it, nothing goes up again`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("교토 빵집 메모")
        assertEquals(0, item(db, id).number)
        val rows = db.outbox()
        val n = db.note(id)!!
        val result = MemoSync.applyAck(db, ack(id, "수락", serverCopy(n, 51)), rows, now)
        assertEquals(1, result.accepted)
        assertEquals(51, item(db, id).number)
        assertEquals(51, open(local, id).number)
        assertEquals(0, db.outboxCount())
        assertFalse(db.note(id)!!.dirty)
        // The note row itself is the same: no header with the number, same hash and stamps.
        assertNull(db.note(id)!!.headerJson)
        assertEquals(n.contentHash, db.note(id)!!.contentHash)
        assertEquals(n.revs, db.note(id)!!.revs)
    }

    @Test
    fun `a pull that only brings a number stores it without dirty flag or upload, the server number wins`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("메모")
        MemoSync.applyAck(db, ack(id, "수락", null), db.outbox(), now)
        val before = db.note(id)!!
        assertEquals(0, db.outboxCount())
        val pull = { number: Int -> JSONObject().put("노트", JSONArray().put(serverCopy(before, number))).put("기준", number) }
        assertEquals(1, MemoSync.applySync(db, pull(30), now).applied)
        assertEquals(30, item(db, id).number)
        assertEquals(0, db.outboxCount())
        assertFalse(db.note(id)!!.dirty)
        assertEquals(before.copy(serverRev = db.note(id)!!.serverRev), db.note(id)!!)
        // The same again: nothing changes; a different number from the server replaces ours.
        assertEquals(0, MemoSync.applySync(db, pull(30), now).applied)
        MemoSync.applySync(db, pull(31), now)
        assertEquals(31, item(db, id).number)
        assertEquals(0, db.outboxCount())
        // An empty number does not remove the one we have.
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(before.toChangeJson())).put("기준", 40), now)
        assertEquals(31, item(db, id).number)
    }

    @Test
    fun `the upload never carries the number`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("메모")
        memoSetNoteMeta(db, id, MemoNoteMeta(number = 9))
        local.call(MemoOp.editTags(MemoTarget(id), listOf("빵집")), "t")
        assertFalse(JSONObject(db.outbox().single().payload).has(MEMO_FIELD_NUMBER))
    }

    @Test
    fun `a header stored by 앱9 with a number does not make the same note look different`() {
        val db = db()
        val n = MemoNoteRecord(
            id = "20261015-113100-abcd",
            folder = MEMO_INBOX,
            title = "",
            body = "본문",
            kind = "텍스트",
            createdMs = t0,
            revs = MemoRevs.all(MemoRev(t0, "폰A", 1)),
            headerJson = JSONObject().put(MEMO_FIELD_NUMBER, 4).put("메모", "x").toString(),
        )
        db.putNote(n)
        val sent = n.toChangeJson().put("머리말", JSONObject().put(MEMO_FIELD_NUMBER, 4).put("메모", "x")).put(MEMO_FIELD_NUMBER, 4)
        MemoSync.applyChange(db, sent)
        assertFalse(db.note(n.id)!!.dirty)
        assertEquals("""{"메모":"x"}""", db.note(n.id)!!.headerJson)
        assertEquals(0, db.outboxCount())
    }

    // ---------------------------------------------------------------- C: dates

    @Test
    fun `the date shown is the creation until the user changes text, title or photos`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("처음 글")
        val created = memoIso(t0)
        assertEquals(created, item(db, id).date)
        now = t0 + 3_600_000
        // Tags, folder, pin: not the user's change of the note (the date stays).
        local.call(MemoOp.editTags(MemoTarget(id), listOf("빵집")), "a")
        local.call(MemoOp.mkdir("여행", null), "b")
        local.call(MemoOp.move(MemoTarget(id), "여행"), "c")
        local.call(MemoOp.pin(MemoTarget(id)), "d")
        assertEquals(created, item(db, id).date)
        assertFalse(JSONObject(db.outbox().single { it.target == id }.payload).has(MEMO_FIELD_USER_MODIFIED))
        // The body: the date becomes this change, kept apart from the creation, and goes up as 사용자수정.
        now = t0 + 7_200_000
        local.call(MemoOp.edit(MemoTarget(id), body = "고친 글", title = null, tags = null), "e")
        assertEquals(memoIso(t0 + 7_200_000), item(db, id).date)
        assertEquals(t0, db.note(id)!!.createdMs)
        val note = open(local, id)
        assertEquals(created, note.created)
        assertEquals(memoIso(t0 + 7_200_000), note.shownDate)
        assertEquals(memoIso(t0 + 7_200_000), JSONObject(db.outbox().single { it.target == id }.payload).getString(MEMO_FIELD_USER_MODIFIED))
    }

    @Test
    fun `a newer user time from the server is taken, an older one is not`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("글")
        now = t0 + 60_000
        local.call(MemoOp.edit(MemoTarget(id), body = null, title = "제목", tags = null), "e")
        val n = db.note(id)!!
        MemoSync.applyAck(db, ack(id, "수락", serverCopy(n, 1, memoIso(t0 + 10_000))), db.outbox(), now)
        assertEquals(memoIso(t0 + 60_000), item(db, id).date)
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(serverCopy(n, 1, memoIso(t0 + 120_000)))).put("기준", 2), now)
        assertEquals(memoIso(t0 + 120_000), item(db, id).date)
        assertEquals(0, db.outboxCount())
    }

    // ---------------------------------------------------------------- D: photo edit

    private fun local(path: String) = MemoLocalAttachment(id = path.substringAfterLast('/'), localPath = path, mime = "image/jpeg", bytes = 3)

    private fun uploaded(n: Int) = MemoLocalAttachment(id = "mxc://test/$n", mxc = "mxc://test/$n", fileJson = null, state = MEMO_ATTACH_DONE)

    @Test
    fun `nothing changes before the save, a removed photo can be brought back`() {
        val a = MemoAttachment("mxc://test/1")
        val b = MemoAttachment(MEMO_LOCAL_FILE_PREFIX + "/p/2.jpg", localPath = "/p/2.jpg")
        val start = MemoPhotoEdit(listOf(a, b))
        assertFalse(start.changed)
        val removed = start.toggle(a)
        assertTrue(removed.isRemoved(a))
        assertEquals(1, removed.count)
        assertEquals(listOf("mxc://test/1"), removed.removedKeys)
        val undone = removed.toggle(a)
        assertFalse(undone.changed)
        assertEquals(2, undone.count)
        val picked = undone.pick(listOf("content://x/9", "content://x/9")).toggle(b)
        assertEquals(listOf("content://x/9"), picked.picked)
        assertEquals(listOf("/p/2.jpg"), picked.removedKeys)
        assertFalse(picked.unpick("content://x/9").toggle(b).changed)
    }

    @Test
    fun `adding a photo in the editor is a normal change, the photo goes up later, the body stays`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("사진 없는 글")
        MemoSync.applyAck(db, ack(id, "수락", null), db.outbox(), now)
        val before = db.note(id)!!
        now = t0 + 60_000
        val added = listOf(memoPhotoArg(local("/p/new.jpg")))
        assertTrue(local.call(MemoOp.edit(MemoTarget(id), body = null, title = null, tags = null, photosAdded = added), "e") is MemoResponse.Ok)
        val after = db.note(id)!!
        assertEquals(before.body, after.body)
        assertEquals(listOf("/p/new.jpg"), after.attachments.map { it.localPath })
        assertEquals(MEMO_ATTACH_LOCAL, after.attachments.single().state)
        assertTrue(after.revs.attach > before.revs.attach)
        assertEquals("사진", after.kind)
        assertEquals(memoIso(t0 + 60_000), item(db, id).date)
        // The note goes up first, without the photo (no mxc yet); the photo waits in the attachment queue.
        val payload = JSONObject(db.outbox().single().payload)
        assertEquals(0, payload.getJSONArray("첨부").length())
        assertEquals(1, db.pendingAttachments().size)
        // The server answer without the photo (it does not know it yet) keeps it on the phone.
        MemoSync.applyAck(db, ack(id, "병합", JSONObject(payload.toString())), db.outbox(), now)
        assertEquals(listOf("/p/new.jpg"), db.note(id)!!.attachments.map { it.localPath })
        assertEquals(1, open(local, id).attachments.size)
    }

    @Test
    fun `a removed photo stays removed after the push answer and the next pull`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("사진 셋", listOf(local("/p/1.jpg")))
        val server = db.note(id)!!.copy(attachments = listOf(uploaded(1), uploaded(2), uploaded(3)))
        db.putNote(server)
        val oldServer = server.toChangeJson()
        now = t0 + 60_000
        local.call(MemoOp.edit(MemoTarget(id), body = null, title = null, tags = null, photosRemoved = listOf("mxc://test/2")), "e")
        assertEquals(listOf("mxc://test/1", "mxc://test/3"), db.note(id)!!.attachments.map { it.mxc })
        val sent = JSONObject(db.outbox().single().payload)
        assertEquals(2, sent.getJSONArray("첨부").length())
        // A server before 서버 5차 adds the lists up but keeps our stamp: the photo does not come back.
        val union = JSONObject(sent.toString()).put("첨부", oldServer.getJSONArray("첨부"))
        MemoSync.applyAck(db, ack(id, "병합", union), db.outbox(), now)
        assertEquals(listOf("mxc://test/1", "mxc://test/3"), db.note(id)!!.attachments.map { it.mxc })
        // The older server copy in a later pull does not bring it back either.
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(oldServer)).put("기준", 5), now)
        assertEquals(listOf("mxc://test/1", "mxc://test/3"), db.note(id)!!.attachments.map { it.mxc })
        assertEquals(2, open(local, id).attachments.size)
    }

    @Test
    fun `removing every photo keeps the note and its text, the date moves on`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("사진 설명 글", listOf(local("/p/1.jpg"), local("/p/2.jpg")))
        val body = db.note(id)!!.body
        now = t0 + 60_000
        local.call(MemoOp.edit(MemoTarget(id), body = null, title = null, tags = null, photosRemoved = listOf("/p/1.jpg", "/p/2.jpg")), "e")
        val n = db.note(id)!!
        assertTrue(n.attachments.isEmpty())
        assertEquals(body, n.body)
        assertEquals(MEMO_DELETED_NONE, n.deleted)
        assertEquals("텍스트", n.kind)
        assertEquals(memoIso(t0 + 60_000), item(db, id).date)
        assertTrue(db.pendingAttachments().isEmpty())
    }

    @Test
    fun `a photo removed while it went up is still removed`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("", listOf(local("/p/1.jpg")))
        // The editor was opened before the upload: it removes the phone path, the stored photo has its mxc by now.
        db.putNote(db.note(id)!!.copy(attachments = listOf(local("/p/1.jpg").copy(mxc = "mxc://test/1", state = MEMO_ATTACH_DONE))))
        local.call(MemoOp.edit(MemoTarget(id), body = null, title = null, tags = null, photosRemoved = listOf("/p/1.jpg")), "e")
        assertTrue(db.note(id)!!.attachments.isEmpty())
    }

    @Test
    fun `editing the text of a photo note keeps its photos`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("원래 글", listOf(local("/p/1.jpg")))
        local.call(MemoOp.edit(MemoTarget(id), body = "고친 글", title = null, tags = null), "e")
        assertEquals("고친 글", db.note(id)!!.body)
        assertEquals(listOf("/p/1.jpg"), db.note(id)!!.attachments.map { it.localPath })
        assertEquals("사진", db.note(id)!!.kind)
    }
}
