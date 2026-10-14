/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱6: the photos that turned into grey boxes after a sync (원인분석_사진.md), the sync button, the photo row and the
 * longer text of the record card.
 */
class MemoPhotoSyncTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)

    /** A memo bot room that answers sync_pull with [pull] and accepts every push; [gate] holds the next call. */
    private class Room(var pull: JSONObject = JSONObject().put("노트", JSONArray()).put("기준", 0)) : MemoSyncTransport {
        var online = true
        var gate: CompletableDeferred<Unit>? = null
        val ops = mutableListOf<String>()
        val uploads = mutableListOf<ByteArray>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op.op
            gate?.await()
            if (!online) throw MemoSendException("off")
            return when (op.op) {
                "sync_pull" -> MemoResponse.Sync("r", pull)
                "sync_push" -> {
                    val changes = op.args["변경"] as JSONArray
                    val results = JSONArray()
                    for (i in 0 until changes.length()) results.put(JSONObject().put("id", changes.getJSONObject(i).getString("id")).put("상태", "수락"))
                    MemoResponse.SyncAck("r", JSONObject().put("결과", results).put("제안결과", JSONArray()).put("서버순번", 0))
                }
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = if (online) emptyList() else null

        override suspend fun upload(bytes: ByteArray): String {
            if (!online) throw MemoSendException("off")
            uploads += bytes
            return "mxc://test/${uploads.size}"
        }

        override suspend fun maxUploadSize(): Long? = null
    }

    /** The `file` object the server keeps and sends back: no `mimetype` (서버 첨부키 형태). */
    private fun serverFile(mxc: String, sealed: MemoEncryptedFile): JSONObject = sealed.fileJson(mxc, null)

    private fun serverNote(id: String, files: List<JSONObject>, attachRev: String = "2026-10-14T10:56:00+09:00", body: String = "사진\n") = JSONObject()
        .put("id", id)
        .put("폴더", MEMO_INBOX)
        .put("제목", "")
        .put("본문", body)
        .put("종류", "사진")
        .put("생성", "2026-10-14T10:55:00+09:00")
        .put("수정", attachRev)
        .put("기기", "서버")
        .put("순번", 9)
        .put("첨부", JSONArray(files.map { JSONObject().put("mxc", it.getString("url")).put("file", it) }))

    // ---------------------------------------------------------------- 원인 A: the json the SDK reads

    @Test
    fun `the server form of an attachment is wrapped under file so the SDK decrypts it`() {
        val plain = "jpeg 바이트".toByteArray()
        val sealed = memoEncryptAttachment(plain)
        val bare = serverFile("mxc://hs/a", sealed)
        assertFalse(bare.has("mimetype"))
        val wrapped = JSONObject(memoMediaSourceJson(bare.toString())!!)
        // ruma reads a MediaSource as encrypted only with a top level "file" (s/rust_probe.out); the bare object is a plain mxc.
        assertEquals(listOf("file"), wrapped.keys().asSequence().toList())
        val inner = wrapped.getJSONObject("file")
        assertEquals("mxc://hs/a", inner.getString("url"))
        val (key, iv) = memoFileKey(inner)
        assertArrayEquals(plain, memoAesCtr(sealed.cipher, key, iv))
        // Already wrapped, missing, or not an EncryptedFile: unchanged.
        assertEquals(wrapped.toString(), memoMediaSourceJson(wrapped.toString()))
        assertNull(memoMediaSourceJson(null))
        assertEquals("not json", memoMediaSourceJson("not json"))
        assertEquals("""{"url":"mxc://hs/b"}""", memoMediaSourceJson("""{"url":"mxc://hs/b"}"""))
    }

    @Test
    fun `a photo note pulled from the server gives the record card an mxc and a key it can draw`() = runTest {
        val db = db()
        val sealed = memoEncryptAttachment(byteArrayOf(1, 2, 3))
        val files = (1..3).map { serverFile("mxc://hs/p$it", sealed) }
        val room = Room(JSONObject().put("노트", JSONArray().put(serverNote("20261012-101010-aaaa", files))).put("기준", 3))
        assertTrue(MemoSyncRunner(db, room, clock = { now }).syncNow())
        val item = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.single()
        val p = item.preview as MemoPreview.Photo
        assertEquals(3, p.count)
        assertEquals(listOf("mxc://hs/p1", "mxc://hs/p2", "mxc://hs/p3"), p.cells.map { it.mxc })
        p.cells.forEach { a ->
            assertNull(a.localPath)
            val source = JSONObject(memoMediaSourceJson(a.fileJson)!!)
            assertEquals(a.mxc, source.getJSONObject("file").getString("url"))
            assertNotNull(source.getJSONObject("file").optJSONObject("key"))
        }
        // The opened note has the same three photos with their keys.
        val note = backend(db).call(MemoOp.get(item.id), "r") as MemoResponse.Note
        assertEquals(3, note.note.attachments.count { it.fileJson != null })
    }

    // ---------------------------------------------------------------- 원인 B: the phone original

    @Test
    fun `after the upload and the server answer the phone original is still drawn first`() = runTest {
        val db = db()
        val plain = "사진".toByteArray()
        val local = backend(db)
        val id = local.create("", (1..3).map { MemoLocalAttachment(id = "a$it", localPath = "/p/$it.jpg", mime = "image/jpeg", bytes = 2) })
        val room = Room()
        val files = (1..3).associate { "/p/$it.jpg" to plain }
        val runner = MemoSyncRunner(db, room, clock = { now }, readFile = { files[it] })
        assertTrue(runner.push(force = true))
        val uploaded = db.note(id)!!.attachments
        assertEquals(listOf("mxc://test/1", "mxc://test/2", "mxc://test/3"), uploaded.map { it.mxc })
        // The bot read the note: the server sends it back with a newer attachment stamp and its own file objects
        // (no mimetype, no phone path). Before 앱6 the server rows replaced ours and the card lost the original.
        now += 60_000
        val serverFiles = uploaded.map { JSONObject(it.fileJson!!).apply { remove("mimetype") } }
        val back = serverNote(id, serverFiles, attachRev = memoIso(now)).put("rev", JSONObject().put("첨부", JSONArray().put(memoIso(now)).put("서버").put(50)))
        room.pull = JSONObject().put("노트", JSONArray().put(back)).put("기준", 4)
        assertTrue(runner.syncNow())
        val after = db.note(id)!!.attachments
        assertEquals(listOf("/p/1.jpg", "/p/2.jpg", "/p/3.jpg"), after.map { it.localPath })
        assertEquals(listOf("mxc://test/1", "mxc://test/2", "mxc://test/3"), after.map { it.mxc })
        val p = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.single().preview as MemoPreview.Photo
        assertEquals(listOf("/p/1.jpg", "/p/2.jpg", "/p/3.jpg"), p.cells.map { it.localPath })
        // The server copy is still known, for when the original is cleaned away.
        assertTrue(p.cells.all { it.fileJson != null && it.mxc.startsWith("mxc://") })
    }

    @Test
    fun `the merge keeps the phone path whichever side wins the attachment bundle`() {
        val base = MemoNoteRecord("n", MEMO_INBOX, "", "", "사진", t0, MemoRevs.all(MemoRev(t0, "A", 1)))
        val phone = base.copy(attachments = listOf(MemoLocalAttachment("a1", mxc = "mxc://x/1", localPath = "/p/1.jpg", mime = "image/jpeg", bytes = 5)))
        val server = base.copy(
            attachments = listOf(MemoLocalAttachment("mxc://x/1", mxc = "mxc://x/1", fileJson = "{}", state = MEMO_ATTACH_DONE)),
            revs = base.revs.with(MemoBundle.Attach, MemoRev(t0 + 5_000, "서버", 2)),
        )
        val m = mergeMemoNote(phone, server)
        assertEquals(m, mergeMemoNote(server, phone))
        val a = m.attachments.single()
        assertEquals("/p/1.jpg", a.localPath)
        assertEquals("image/jpeg", a.mime)
        assertEquals(5L, a.bytes)
        assertEquals("{}", a.fileJson)
    }

    // ---------------------------------------------------------------- 항목 3·4·5: the record card

    @Test
    fun `the record card gets up to four photos and counts the rest`() = runTest {
        val db = db()
        val local = backend(db)
        local.create("", (1..6).map { MemoLocalAttachment(id = "a$it", localPath = "/p/$it.jpg") })
        local.create("", (1..3).map { MemoLocalAttachment(id = "b$it", localPath = "/q/$it.jpg") })
        // Same millisecond: the order is the random id, so the two notes are told apart by their photo count.
        val photos = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.map { it.preview as MemoPreview.Photo }
        val six = photos.single { it.count == 6 }
        val three = photos.single { it.count == 3 }
        assertEquals(MEMO_CARD_PHOTOS, six.cells.size)
        assertEquals(6, six.count)
        assertEquals(2, memoHiddenPhotos(six))
        assertEquals(listOf("/p/1.jpg", "/p/2.jpg", "/p/3.jpg", "/p/4.jpg"), six.cells.map { it.localPath })
        assertEquals(3, three.cells.size)
        assertEquals(0, memoHiddenPhotos(three))
        // The server list sends only the first photo: one cell and "+2".
        assertEquals(2, memoHiddenPhotos(MemoPreview.Photo("mxc://hs/x", 3)))
    }

    @Test
    fun `the record card text is the start of the body, long enough for seven lines, also under a photo or a link`() = runTest {
        val db = db()
        val local = backend(db)
        val long = (1..30).joinToString("\n") { "줄 $it 교토 빵집 메모" }
        local.create(long)
        local.create("사진 설명 한 줄", listOf(MemoLocalAttachment(id = "a1", localPath = "/p/1.jpg")))
        local.create("https://example.com/a")
        // Made in the same millisecond: the list order is the random id, so each note is picked by its kind.
        val items = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.associateBy { it.kind }
        val link = items.getValue("링크")
        val photo = items.getValue("사진")
        val text = items.getValue("텍스트")
        assertEquals(MEMO_CARD_TEXT_CHARS, memoCardText(text)!!.length)
        assertTrue(memoCardText(text)!!.lines().size > MEMO_CARD_LINES)
        assertEquals("사진 설명 한 줄", memoCardText(photo))
        assertTrue(photo.preview is MemoPreview.Photo)
        // A link note made on the phone has no link card yet: its address is shown once, in the body line, and the
        // small top line does not repeat it.
        assertEquals("https://example.com/a", memoCardText(link))
        assertEquals("", memoCardHeadTitle(link, memoCardText(link)))
        // With the link card the address line is the card's; the body line does not repeat it.
        val card = link.copy(preview = MemoPreview.Link("https://example.com/a", null, null, null))
        assertNull(memoCardText(card))
        // The small top line keeps a title the body does not start with.
        assertEquals("다른 제목", memoCardHeadTitle(text.copy(title = "다른 제목"), memoCardText(text)))
        assertEquals(7, MEMO_CARD_LINES)
    }

    // ---------------------------------------------------------------- 항목 2: the sync button

    @Test
    fun `the sync button pulls, then sends what waits at once, ignoring the back-off`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("올릴 메모")
        val room = Room()
        room.online = false
        val runner = MemoSyncRunner(db, room, clock = { now })
        assertEquals(MemoButtonSync.Failed, runner.syncByButton())
        assertTrue(runner.hasPendingUploads())
        // The failed try put the note on back-off; the button does not wait for it.
        room.online = true
        room.ops.clear()
        assertEquals(MemoButtonSync.Done, runner.syncByButton())
        assertEquals(listOf("sync_pull", "sync_push"), room.ops)
        assertFalse(runner.hasPendingUploads())
        assertFalse(db.note(id)!!.dirty)
        assertEquals("동기화했습니다", memoSyncButtonText(MemoButtonSync.Done))
        assertTrue(memoSyncButtonText(MemoButtonSync.Failed).startsWith("동기화하지 못했습니다"))
    }

    @Test
    fun `a second press while the sync runs does nothing and nothing runs twice`() = runTest {
        val db = db()
        val room = Room()
        val gate = CompletableDeferred<Unit>()
        room.gate = gate
        val runner = MemoSyncRunner(db, room, clock = { now })
        val first = async { runner.syncByButton() }
        yield()
        assertEquals(listOf("sync_pull"), room.ops)
        assertEquals(MemoButtonSync.Busy, runner.syncByButton())
        assertEquals(listOf("sync_pull"), room.ops)
        gate.complete(Unit)
        assertEquals(MemoButtonSync.Done, first.await())
        assertEquals(1, room.ops.count { it == "sync_pull" })
        // Free again afterwards.
        room.gate = null
        assertEquals(MemoButtonSync.Done, runner.syncByButton())
    }
}
