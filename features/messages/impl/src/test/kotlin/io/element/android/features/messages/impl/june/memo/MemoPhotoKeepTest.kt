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
 * 앱11 E (관리자 2026-10-15 \"원래 #1에 사진이 있었는데 검토작업을 거치며 사라짐\"): a photo that went up must stay on the
 * phone whatever answer the server sends back. The server writes `첨부[]` items without a key as plain mxc strings
 * (`note_to_change(keys=None)`, every `sync_push` answer); the phone read only objects, so the list came in empty and
 * the 앱10 \"same stamp: only what both sides hold\" rule emptied the phone list. All data is made up.
 */
class MemoPhotoKeepTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    /** A made up EncryptedFile of the uploaded photo (no real key). */
    private val fakeKey = """{"url":"mxc://test/1","key":{"k":"fake"},"iv":"fakeiv","hashes":{"sha256":"fake"},"v":"v2"}"""

    /** A photo-only note whose one photo went up (mxc and key known), synced and clean: #1 at 3:27. */
    private suspend fun uploadedPhotoNote(db: MemoDb, text: String = ""): String {
        val id = backend(db).create(text, listOf(MemoLocalAttachment(id = "a1", localPath = "/p/1.jpg", mime = "image/jpeg", bytes = 3)))
        val n = db.note(id)!!
        now += 5_000
        val photo = n.attachments.single().copy(mxc = "mxc://test/1", fileJson = fakeKey, state = MEMO_ATTACH_DONE)
        val stamped = n.copy(attachments = listOf(photo), revs = n.revs.with(MemoBundle.Attach, db.nextRev(memoDeviceOf(db), now)), dirty = false)
        db.transaction {
            db.writeNote(stamped)
            db.outbox().forEach { db.dropOutbox(it.seq) }
        }
        now += 5_000
        return id
    }

    /** What the server sends for [n]: its `첨부` as plain mxc strings ([strings]) or objects with the key. */
    private fun serverCopy(n: MemoNoteRecord, strings: Boolean, withBody: Boolean = true): JSONObject {
        val o = n.toChangeJson().put(MEMO_FIELD_NUMBER, 1)
        if (strings) o.put("첨부", JSONArray(n.attachments.mapNotNull { it.mxc }))
        if (!withBody) o.put("본문", "")
        return o
    }

    /** The review moved the note and tagged it (server stamp on the `분류` bundle only). */
    private fun JSONObject.reviewed(): JSONObject {
        getJSONObject("rev").put("분류", JSONArray().put(memoIso(now + 60_000)).put("서버").put(40))
        return put("폴더", "집·생활").put("태그", JSONArray().put("사진")).put("수정", memoIso(now + 60_000))
            .put("기기", "서버").put("순번", 40)
    }

    /** The same EncryptedFile, whatever order its fields came back in. */
    private fun assertSameKey(json: String?) {
        assertTrue("key: $json", json != null && JSONObject(fakeKey).similar(JSONObject(json)))
    }

    /** A `sync_push` answer: the server merged the note ([change] is what it kept). */
    private fun merged(id: String, change: JSONObject) =
        JSONObject().put("결과", JSONArray().put(JSONObject().put("id", id).put("상태", "병합").put("노트", change)))

    private fun assertPhotoKept(db: MemoDb, id: String) {
        val a = db.note(id)!!.attachments
        assertEquals(listOf("mxc://test/1"), a.map { it.mxc })
        assertSameKey(a.single().fileJson)
        assertEquals("/p/1.jpg", a.single().localPath)
        val item = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.single { it.id == id }
        assertTrue(item.preview is MemoPreview.Photo)
    }

    /** The memo bot room: `sync_push` answers `병합` with the reviewed note, body-less, its photos as mxc strings. */
    private inner class ReviewedRoom : MemoSyncTransport {
        val pushed = mutableListOf<JSONObject>()

        override suspend fun call(op: MemoOp): MemoResponse = when (op.op) {
            "sync_pull" -> MemoResponse.Sync("r", JSONObject().put("노트", JSONArray()).put("기준", 0))
            "sync_push" -> {
                val changes = op.args["변경"] as JSONArray
                val results = JSONArray()
                for (i in 0 until changes.length()) {
                    val sent = changes.getJSONObject(i)
                    pushed += sent
                    val files = sent.getJSONArray("첨부")
                    val mxcs = JSONArray((0 until files.length()).map { files.getJSONObject(it).getString("mxc") })
                    val back = JSONObject(sent.toString()).put("첨부", mxcs).put("본문", "").reviewed()
                    results.put(JSONObject().put("id", sent.getString("id")).put("상태", "병합").put("노트", back))
                }
                MemoResponse.SyncAck("r", JSONObject().put("결과", results).put("제안결과", JSONArray()).put("서버순번", 0))
            }
            else -> MemoResponse.Error("r", op.op, "모름")
        }

        override suspend fun fetchProposals(): List<JSONObject> = emptyList()

        override suspend fun upload(bytes: ByteArray): String = error("no upload here")

        override suspend fun maxUploadSize(): Long? = null
    }

    // ---------------------------------------------------------------- reproduction (failed before 앱11)

    @Test
    fun `pinning a reviewed photo note keeps its photo after the merged push answer`() = runTest {
        val db = db()
        val id = uploadedPhotoNote(db)
        assertTrue(backend(db).call(MemoOp.pin(MemoTarget(id)), "p") is MemoResponse.Ok)
        val room = ReviewedRoom()
        assertTrue(MemoSyncRunner(db, room, clock = { now }).push(force = true))
        // The phone sent its photo with the key (an object), the server answered with the plain mxc.
        assertEquals(1, room.pushed.single().getJSONArray("첨부").length())
        assertPhotoKept(db, id)
        val n = db.note(id)!!
        assertTrue(n.pinned)
        assertEquals("집·생활", n.folder)
        assertFalse(n.dirty)
        assertEquals(0, db.outboxCount())
    }

    @Test
    fun `a pull with only the review classification keeps the photo, whatever form the list comes in`() = runTest {
        for (strings in listOf(true, false)) {
            val db = db()
            val id = uploadedPhotoNote(db)
            val change = serverCopy(db.note(id)!!, strings).reviewed()
            MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(change)).put("기준", 7), now)
            assertPhotoKept(db, id)
            assertEquals("집·생활", db.note(id)!!.folder)
            assertFalse(db.note(id)!!.dirty)
        }
    }

    @Test
    fun `a pull that only raised the sequence with the same list changes nothing`() = runTest {
        val db = db()
        val id = uploadedPhotoNote(db)
        val before = db.note(id)!!
        val change = serverCopy(before, strings = false).put("순번", 99).put("수정", memoIso(now + 1_000))
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(change)).put("기준", 8), now)
        assertPhotoKept(db, id)
        assertFalse(db.note(id)!!.dirty)
        assertEquals(0, db.outboxCount())
    }

    @Test
    fun `a newer server stamp on the attachment bundle with plain mxc keeps the key and the phone original`() = runTest {
        val db = db()
        val id = uploadedPhotoNote(db)
        val change = serverCopy(db.note(id)!!, strings = true)
        change.getJSONObject("rev").put("첨부", JSONArray().put(memoIso(now + 60_000)).put("서버").put(41))
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(change)).put("기준", 9), now)
        assertPhotoKept(db, id)
    }

    @Test
    fun `a photo with text keeps its photo after the review answer`() = runTest {
        val db = db()
        val id = uploadedPhotoNote(db, text = "영수증 사진")
        val change = serverCopy(db.note(id)!!, strings = true, withBody = false).reviewed()
        MemoSync.applyAck(db, merged(id, change), emptyList(), now)
        assertPhotoKept(db, id)
        assertEquals("영수증 사진", db.note(id)!!.body)
    }

    // ---------------------------------------------------------------- recovery of a note that lost its photo

    /** The note as the 앱10 phone left it: kind 사진, no photo, the same attachment stamp as the server. */
    private suspend fun lostPhotoNote(db: MemoDb): Pair<String, MemoNoteRecord> {
        val id = uploadedPhotoNote(db)
        val full = db.note(id)!!
        db.putNote(full.copy(attachments = emptyList()))
        return id to full
    }

    @Test
    fun `a lost photo comes back from the server copy`() = runTest {
        for (strings in listOf(false, true)) {
            val db = db()
            val (id, full) = lostPhotoNote(db)
            assertTrue(db.note(id)!!.attachments.isEmpty())
            MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(serverCopy(full, strings))).put("기준", 10), now)
            val a = db.note(id)!!.attachments
            assertEquals(listOf("mxc://test/1"), a.map { it.mxc })
            // The plain mxc has no key: the next answer that has it fills it in.
            if (strings) assertNull(a.single().fileJson) else assertSameKey(a.single().fileJson)
            assertFalse(db.note(id)!!.dirty)
            assertEquals(0, db.outboxCount())
        }
    }

    @Test
    fun `the one time full pull asks the server for every note again, once`() = runTest {
        val db = db()
        db.setState(MemoSync.STATE_BASE, "120")
        assertTrue(MemoSync.repairPhotosOnce(db))
        assertEquals(0L, MemoSync.base(db))
        assertEquals(0L, MemoSync.pullArgs(db, "폰").getLong("기준"))
        db.setState(MemoSync.STATE_BASE, "130")
        assertFalse(MemoSync.repairPhotosOnce(db))
        assertEquals(130L, MemoSync.base(db))
    }

    @Test
    fun `a photo the user removed stays removed after the answer and an older server copy`() = runTest {
        val db = db()
        val id = uploadedPhotoNote(db, text = "글")
        val withPhoto = serverCopy(db.note(id)!!, strings = true)
        // The editor removed the only photo: a new stamp, and the kind goes back to text.
        now += 10_000
        backend(db).call(MemoOp.edit(MemoTarget(id), body = null, title = null, tags = null, photosRemoved = listOf("mxc://test/1")), "e")
        val removed = db.note(id)!!
        assertTrue(removed.attachments.isEmpty())
        assertEquals("텍스트", removed.kind)
        // 서버 5차 takes the phone list (its stamp wins) and answers with it; an older copy of the server comes later.
        val answer = removed.toChangeJson().put("첨부", JSONArray())
        MemoSync.applyAck(db, merged(id, answer), db.outbox(), now)
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(withPhoto)).put("기준", 11), now)
        assertTrue(db.note(id)!!.attachments.isEmpty())
    }

    @Test
    fun `the merge with plain mxc items does not depend on the order`() {
        val base = MemoNoteRecord("n", MEMO_INBOX, "", "", "사진", t0, MemoRevs.all(MemoRev(t0, "A", 1)))
        val photo = MemoLocalAttachment("a1", mxc = "mxc://x/1", localPath = "/p/1.jpg", fileJson = "{}", state = MEMO_ATTACH_DONE)
        val phone = base.copy(attachments = listOf(photo))
        val server = memoChangeFromJson(phone.toChangeJson().put("첨부", JSONArray().put("mxc://x/1")))!!
        assertEquals(listOf("mxc://x/1"), server.attachments.map { it.mxc })
        val m = mergeMemoNote(phone, server)
        assertEquals(m, mergeMemoNote(server, phone))
        assertEquals("{}", m.attachments.single().fileJson)
        assertEquals("/p/1.jpg", m.attachments.single().localPath)
        // Lost on one side (photo kind, empty list, same stamp): the other side's list, in both orders.
        val lost = phone.copy(attachments = emptyList())
        assertEquals(listOf("mxc://x/1"), mergeMemoNote(lost, server).attachments.map { it.mxc })
        assertEquals(mergeMemoNote(lost, server), mergeMemoNote(server, lost))
    }
}
