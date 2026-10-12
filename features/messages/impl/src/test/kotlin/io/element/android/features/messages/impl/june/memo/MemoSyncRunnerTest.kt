/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A4 screen sync and A5 attachment queue / suggestions over a fake memo bot room. */
class MemoSyncRunnerTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    /** The memo bot room: answers sync_pull with [pull], accepts every pushed note, records every call. */
    private class FakeMemoSyncTransport(
        var online: Boolean = true,
        var pull: JSONObject = JSONObject().put("노트", JSONArray()).put("기준", 0),
        var proposals: List<JSONObject> = emptyList(),
        var uploadFails: Boolean = false,
    ) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()
        val uploads = mutableListOf<ByteArray>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            if (!online) throw MemoSendException("off")
            return when (op.op) {
                "sync_pull" -> MemoResponse.Sync("r", pull)
                "sync_push" -> {
                    val changes = op.args["변경"] as JSONArray
                    val results = JSONArray()
                    for (i in 0 until changes.length()) results.put(JSONObject().put("id", changes.getJSONObject(i).getString("id")).put("상태", "수락"))
                    val answers = JSONArray()
                    (op.args["제안응답"] as? JSONArray)?.let { a ->
                        for (i in 0 until a.length()) {
                            answers.put(
                                JSONObject().put("id", a.getJSONObject(i).getString("id")).put("상태", a.getJSONObject(i).getString("응답"))
                            )
                        }
                    }
                    MemoResponse.SyncAck("r", JSONObject().put("결과", results).put("제안결과", answers).put("서버순번", 0))
                }
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = if (online) proposals else null

        override suspend fun upload(bytes: ByteArray): String {
            if (!online || uploadFails) throw MemoSendException("off")
            uploads += bytes
            return "mxc://test/${uploads.size}"
        }

        fun pushed(): List<JSONObject> = ops.filter { it.op == "sync_push" }.flatMap { op ->
            val a = op.args["변경"] as JSONArray
            (0 until a.length()).map { a.getJSONObject(it) }
        }
    }

    private fun runner(db: MemoDb, room: FakeMemoSyncTransport, files: Map<String, ByteArray> = emptyMap()) =
        MemoSyncRunner(db, room, clock = { now }, readFile = { files[it] })

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)

    @Test
    fun `a note typed on the phone goes up with one sync_push and is clean afterwards`() = runTest {
        val db = db()
        val room = FakeMemoSyncTransport()
        val id = backend(db).create("장보기 목록")
        val r = runner(db, room)
        assertTrue(r.hasPendingUploads())
        assertTrue(r.push())
        assertEquals(listOf(id), room.pushed().map { it.getString("id") })
        assertFalse(r.hasPendingUploads())
        assertFalse(db.note(id)!!.dirty)
    }

    @Test
    fun `with the server off nothing is lost, nothing throws and the note stays local`() = runTest {
        val db = db()
        val room = FakeMemoSyncTransport(online = false)
        val id = backend(db).create("오프라인 메모")
        val r = runner(db, room)
        assertFalse(r.syncNow())
        assertFalse(r.push())
        assertNotNull(db.note(id))
        assertTrue(r.hasPendingUploads())
        // The failed row waits for its back-off: a push right away sends nothing.
        val pushes = room.ops.count { it.op == "sync_push" }
        room.online = true
        r.push()
        assertEquals(pushes, room.ops.count { it.op == "sync_push" })
        assertNotNull(r.nextRetryInMs())
        // After the back-off it goes up.
        now += MemoSync.BACKOFF_MS[0] + 1
        assertTrue(r.push())
        // (the fake records the tries made while it was off too: only the last one counts here)
        assertEquals(id, room.pushed().last().getString("id"))
        assertFalse(r.hasPendingUploads())
    }

    @Test
    fun `a pull stores the server notes and the waiting suggestions`() = runTest {
        val db = db()
        val change = JSONObject()
            .put("id", "20261013-020000-abcd")
            .put("폴더", "여행")
            .put("제목", "서버 노트")
            .put("본문", "서버 본문\n")
            .put("수정", "2026-10-13T02:00:00+09:00")
            .put("기기", "서버")
            .put("순번", 3)
        val proposal = JSONObject().put("id", "p1").put("종류", "분류").put("요약", "태그 #여행").put("상태", "대기")
            .put("노트", "20261013-020000-abcd").put("제안내용", JSONObject().put("태그", JSONArray(listOf("여행"))))
        val room = FakeMemoSyncTransport(pull = JSONObject().put("노트", JSONArray().put(change)).put("기준", 7), proposals = listOf(proposal))
        assertTrue(runner(db, room).syncNow())
        assertEquals("서버 본문", db.note("20261013-020000-abcd")!!.body)
        assertEquals(7L, MemoSync.base(db))
        assertEquals(listOf("p1"), db.suggestions().map { it.id })
        assertEquals(1, backend(db).fetchIndex().pendingProposals)
        // Only one pull, nothing pushed back (the note is the server's).
        assertEquals(1, room.ops.count { it.op == "sync_pull" })
        assertTrue(room.pushed().isEmpty())
    }

    @Test
    fun `an accepted suggestion is applied on the phone first and its answer goes up with the next push`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("오사카 일정")
        val proposal = JSONObject().put("id", "p1").put("종류", "분류").put("요약", "태그 #여행").put("상태", "대기")
            .put("노트", id).put("제안내용", JSONObject().put("태그", JSONArray(listOf("여행"))).put("폴더", "여행"))
        val room = FakeMemoSyncTransport(proposals = listOf(proposal))
        val r = runner(db, room)
        r.syncNow()
        assertEquals(1, db.suggestions().size)
        val answer = local.call(MemoOp.accept("p1"), "rid")
        assertTrue(answer is MemoResponse.Ok)
        assertEquals(listOf("여행"), db.note(id)!!.tags)
        assertEquals("여행", db.note(id)!!.folder)
        assertEquals(0, local.fetchIndex().pendingProposals)
        now += 10_000
        r.push()
        val push = room.ops.last { it.op == "sync_push" }
        val answers = push.args["제안응답"] as JSONArray
        assertEquals("p1", answers.getJSONObject(0).getString("id"))
        assertEquals("수락", answers.getJSONObject(0).getString("응답"))
        // The server took it: the suggestion is gone here too, and a later pull does not bring it back as waiting.
        assertTrue(db.suggestions().isEmpty())
        assertFalse(r.hasPendingUploads())
    }

    @Test
    fun `a dismissed suggestion changes nothing on the note`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("메모")
        val before = db.note(id)!!
        val proposal = JSONObject().put("id", "p2").put("종류", "분류").put("노트", id).put("제안내용", JSONObject().put("태그", JSONArray(listOf("x"))))
        runner(db, FakeMemoSyncTransport(proposals = listOf(proposal))).syncNow()
        local.call(MemoOp.dismiss("p2"), "rid")
        assertEquals(before.tags, db.note(id)!!.tags)
        assertEquals(MEMO_SUGGESTION_DISMISSED, db.suggestion("p2")!!.status)
    }

    @Test
    fun `a photo kept on the phone is encrypted, uploaded, and its note goes up with the mxc and the key`() = runTest {
        val db = db()
        val plain = "사진 바이트".toByteArray()
        val id = backend(db).create("", listOf(MemoLocalAttachment(id = "a1", localPath = "/p/1.jpg", mime = "image/jpeg", bytes = plain.size.toLong())))
        val room = FakeMemoSyncTransport()
        val r = runner(db, room, files = mapOf("/p/1.jpg" to plain))
        assertTrue(r.push())
        val a = db.note(id)!!.attachments.single()
        assertEquals(MEMO_ATTACH_DONE, a.state)
        assertEquals("mxc://test/1", a.mxc)
        // The bytes in the media repository are not the photo, and the key in the note reads them back.
        assertFalse(room.uploads.single().contentEquals(plain))
        val file = JSONObject(a.fileJson!!)
        val (key, iv) = memoFileKey(file)
        assertArrayEquals(plain, memoAesCtr(room.uploads.single(), key, iv))
        assertEquals("mxc://test/1", file.getString("url"))
        // The pushed note carries the attachment (mxc + file), as the server expects it (규약 §5 첨부).
        val sent = room.pushed().last { it.getString("id") == id }.getJSONArray("첨부").getJSONObject(0)
        assertEquals("mxc://test/1", sent.getString("mxc"))
        assertNotNull(sent.optJSONObject("file"))
        assertFalse(r.hasPendingUploads())
    }

    @Test
    fun `an attachment that keeps failing is marked failed after ten tries and can be tried again`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("", listOf(MemoLocalAttachment(id = "a1", localPath = "/p/1.jpg")))
        val room = FakeMemoSyncTransport(uploadFails = true)
        val r = runner(db, room, files = mapOf("/p/1.jpg" to byteArrayOf(1, 2, 3)))
        repeat(MEMO_ATTACH_MAX_TRIES) { r.push(force = true) }
        assertEquals(MEMO_ATTACH_FAILED, db.note(id)!!.attachments.single().state)
        assertEquals(1, local.failedAttachments())
        local.retryFailedAttachments()
        assertEquals(0, local.failedAttachments())
        room.uploadFails = false
        r.push(force = true)
        assertEquals(MEMO_ATTACH_DONE, db.note(id)!!.attachments.single().state)
    }

    @Test
    fun `the clean button only removes originals that were uploaded`() = runTest {
        val db = db()
        val local = backend(db)
        local.create("", listOf(MemoLocalAttachment(id = "up", localPath = "/p/up.jpg", bytes = 10)))
        local.create("", listOf(MemoLocalAttachment(id = "only", localPath = "/p/only.jpg", bytes = 20)))
        val room = FakeMemoSyncTransport()
        // Only the first one can be read; the second has no file any more, so it is 실패 and its original is kept.
        runner(db, room, files = mapOf("/p/up.jpg" to byteArrayOf(1))).push(force = true)
        assertEquals(1 to 10L, local.uploadedOriginals())
        val deleted = mutableListOf<String>()
        assertEquals(1, local.cleanUploadedOriginals {
            deleted += it
            true
        })
        assertEquals(listOf("/p/up.jpg"), deleted)
        assertEquals(0 to 0L, local.uploadedOriginals())
    }

    @Test
    fun `folder rows alone do not count as something to upload (no background job for them)`() = runTest {
        val db = db()
        backend(db).call(MemoOp.mkdir("새폴더", null), "rid")
        assertFalse(runner(db, FakeMemoSyncTransport()).hasPendingUploads())
        assertNull(runner(db, FakeMemoSyncTransport()).nextRetryInMs())
    }

    @Test
    fun `the storage warning threshold is 1 GB or 5 percent of the disk, whichever is smaller`() {
        val gb = 1L shl 30
        assertTrue(memoLowStorage(gb, 100 * gb))
        assertFalse(memoLowStorage(gb + 1, 100 * gb))
        assertTrue(memoLowStorage(400L shl 20, 8 * gb))
        assertFalse(memoLowStorage(500L shl 20, 8 * gb))
        assertEquals("1.5GB", memoSizeText(gb + gb / 2))
        assertEquals("350MB", memoSizeText(350L shl 20))
    }
}
