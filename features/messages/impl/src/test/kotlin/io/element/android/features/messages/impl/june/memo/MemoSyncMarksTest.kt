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

/** 앱15 F: the small sync mark of each note (대기 · 동기화 중 · 동기화됨). Made up data. */
class MemoSyncMarksTest : RobolectricTest() {
    private var now = 1_760_000_000_000L

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    /** The memo bot room: takes every change, or answers nothing while [online] is false. Watches what is sending. */
    private class MarkRoom(var online: Boolean = true) : MemoSyncTransport {
        val sendingSeen = mutableListOf<Set<String>>()

        override suspend fun call(op: MemoOp): MemoResponse {
            if (!online) throw MemoSendException("off")
            return when (op.op) {
                "sync_pull" -> MemoResponse.Sync("r", JSONObject().put("노트", JSONArray()).put("기준", 1))
                "sync_push" -> {
                    sendingSeen += MemoSendingNotes.ids.value
                    val results = JSONArray()
                    val changes = op.args["변경"] as JSONArray
                    for (i in 0 until changes.length()) {
                        results.put(JSONObject().put("id", changes.getJSONObject(i).getString("id")).put("상태", "수락"))
                    }
                    MemoResponse.SyncAck("r", JSONObject().put("결과", results).put("제안결과", JSONArray()).put("서버순번", 1))
                }
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = if (online) emptyList() else null

        override suspend fun upload(bytes: ByteArray): String {
            sendingSeen += MemoSendingNotes.ids.value
            if (!online) throw MemoSendException("off")
            return "mxc://test/up"
        }

        override suspend fun maxUploadSize(): Long? = null
    }

    private fun runner(db: MemoDb, room: MarkRoom) = MemoSyncRunner(db, room, clock = { now }, readFile = { byteArrayOf(1, 2, 3) })

    private fun item(db: MemoDb, id: String) = db.list(MEMO_INBOX, emptyList(), null, null).items.first { it.id == id }

    private fun mark(db: MemoDb, id: String) = item(db, id).let { memoSyncMark(it.syncWaiting, it.id in MemoSendingNotes.ids.value) }

    @Test
    fun `the three marks and no mark when the state is not known`() {
        assertNull(memoSyncMark(null, sending = true))
        assertEquals(MemoSyncMark.Sending, memoSyncMark(waiting = true, sending = true))
        assertEquals(MemoSyncMark.Sending, memoSyncMark(waiting = false, sending = true))
        assertEquals(MemoSyncMark.Waiting, memoSyncMark(waiting = true, sending = false))
        assertEquals(MemoSyncMark.Synced, memoSyncMark(waiting = false, sending = false))
        // Each mark is described for the screen reader, the screen shows no text.
        assertEquals(listOf("동기화 대기", "동기화 중", "동기화됨"), MemoSyncMark.entries.map { it.description })
    }

    @Test
    fun `a photo not on the server keeps the note waiting, a failed one too`() {
        val up = MemoLocalAttachment(id = "a", localPath = "/p/a.jpg", state = MEMO_ATTACH_DONE)
        assertFalse(memoNoteWaiting("n", emptySet(), listOf(up)))
        assertTrue(memoNoteWaiting("n", setOf("n"), listOf(up)))
        for (state in listOf(MEMO_ATTACH_LOCAL, MEMO_ATTACH_UPLOADING, MEMO_ATTACH_FAILED)) {
            assertTrue(state, memoNoteWaiting("n", emptySet(), listOf(up, up.copy(id = "b", state = state))))
        }
    }

    @Test
    fun `waiting, then sending during the push, then synced after the ack`() = runTest {
        val db = db()
        val backend = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)
        val id = backend.create("운동 기록")
        assertEquals(MemoSyncMark.Waiting, mark(db, id))
        val room = MarkRoom()
        assertTrue(runner(db, room).push())
        // While the request was out the note was in the sending set; after the ack it is not.
        assertTrue(room.sendingSeen.single().contains(id))
        assertTrue(MemoSendingNotes.ids.value.isEmpty())
        assertEquals(MemoSyncMark.Synced, mark(db, id))
        // The open note says the same.
        val note = (backend.call(MemoOp.get(id), "g") as MemoResponse.Note).note
        assertEquals(false, note.syncWaiting)
        // A new local change: waiting again.
        backend.call(MemoOp.edit(MemoTarget(id), "운동 기록 2", null, null), "e")
        assertEquals(MemoSyncMark.Waiting, mark(db, id))
        assertEquals(true, (backend.call(MemoOp.get(id), "g2") as MemoResponse.Note).note.syncWaiting)
    }

    @Test
    fun `offline the note stays waiting through the retries, the sending set is emptied after a failure`() = runTest {
        val db = db()
        val backend = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)
        val id = backend.create("오프라인 메모")
        val room = MarkRoom(online = false)
        assertFalse(runner(db, room).push())
        assertTrue(MemoSendingNotes.ids.value.isEmpty())
        assertEquals(MemoSyncMark.Waiting, mark(db, id))
        room.online = true
        now += MemoSync.BACKOFF_MS[0] + 1
        assertTrue(runner(db, room).push())
        assertEquals(MemoSyncMark.Synced, mark(db, id))
    }

    @Test
    fun `a photo waiting for its upload keeps the note waiting, the upload shows as sending`() = runTest {
        val db = db()
        val backend = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)
        val id = backend.create("사진", listOf(MemoLocalAttachment(id = "a1", localPath = "/p/1.jpg", bytes = 3)))
        val room = MarkRoom()
        val r = runner(db, room)
        assertEquals(MemoSyncMark.Waiting, mark(db, id))
        assertTrue(r.pushAttachments())
        assertTrue(room.sendingSeen.first().contains(id))
        assertTrue(MemoSendingNotes.ids.value.isEmpty())
        // The photo is up, the note itself goes up again with its mxc.
        assertEquals(MemoSyncMark.Waiting, mark(db, id))
        assertTrue(r.push())
        assertEquals(MemoSyncMark.Synced, mark(db, id))
    }

    @Test
    fun `the sending set adds and removes only the notes asked`() {
        MemoSendingNotes.start(listOf("a", "b"))
        MemoSendingNotes.start(emptyList())
        MemoSendingNotes.end(listOf("a"))
        assertEquals(setOf("b"), MemoSendingNotes.ids.value)
        MemoSendingNotes.end(listOf("b", "c"))
        assertTrue(MemoSendingNotes.ids.value.isEmpty())
    }
}
