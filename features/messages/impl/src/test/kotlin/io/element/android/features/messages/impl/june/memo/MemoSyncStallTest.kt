/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱8 항목 1: the sync button that only spun and the photo notes that never went up. The answer wait of a call ends
 * with a time out (a cancellation), which ended the whole sync before its `sync_push`; a stuck photo upload held the
 * lock and the notes behind it.
 */
class MemoSyncStallTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)

    /**
     * A memo bot room. [pullTimesOut]: the pull answer never comes and the wait ends like the room's own 30 s limit.
     * [uploadHangs]: the photo upload never ends. [uploadTimesOut]: the upload ends with its own time out.
     */
    private class Room : MemoSyncTransport {
        var pullTimesOut = false
        var pushTimesOut = false
        var uploadHangs = false
        var uploadTimesOut = false
        var uploadFails = false
        val ops = mutableListOf<String>()
        val pushed = mutableListOf<JSONObject>()
        val uploads = mutableListOf<ByteArray>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op.op
            return when (op.op) {
                "sync_pull" -> {
                    if (pullTimesOut) withTimeout(1) { awaitCancellation() }
                    MemoResponse.Sync("r", JSONObject().put("노트", JSONArray()).put("기준", 0))
                }
                "sync_push" -> {
                    if (pushTimesOut) withTimeout(1) { awaitCancellation() }
                    val changes = op.args["변경"] as JSONArray
                    val results = JSONArray()
                    for (i in 0 until changes.length()) {
                        pushed += changes.getJSONObject(i)
                        results.put(JSONObject().put("id", changes.getJSONObject(i).getString("id")).put("상태", "수락"))
                    }
                    MemoResponse.SyncAck("r", JSONObject().put("결과", results).put("제안결과", JSONArray()).put("서버순번", 0))
                }
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = emptyList()

        override suspend fun upload(bytes: ByteArray): String {
            if (uploadHangs) awaitCancellation()
            if (uploadTimesOut) withTimeout(1) { awaitCancellation() }
            if (uploadFails) throw MemoSendException("off")
            uploads += bytes
            return "mxc://test/${uploads.size}"
        }

        override suspend fun maxUploadSize(): Long? = null
    }

    private fun runner(db: MemoDb, room: Room, log: MutableList<String> = mutableListOf()) = MemoSyncRunner(
        db,
        room,
        clock = { now },
        readFile = { byteArrayOf(1, 2, 3) },
        fileSize = { 3L },
        log = { log += it },
    )

    /** The phone of 2026-10-14 18:58: two photo notes made after the last push, their photos only on the phone. */
    private suspend fun twoPhotoNotes(db: MemoDb): List<String> = listOf(
        backend(db).create("", listOf(MemoLocalAttachment(id = "a1", localPath = "/p/1.jpg", mime = "image/jpeg", bytes = 3))),
        backend(db).create("", listOf(MemoLocalAttachment(id = "a2", localPath = "/p/2.jpg", mime = "image/jpeg", bytes = 3))),
    )

    // ---------------------------------------------------------------- 원인: a timed out answer ended the whole sync

    @Test
    fun `a pull whose answer times out still sends the notes and the press ends with a line`() = runTest {
        val db = db()
        val ids = twoPhotoNotes(db)
        val room = Room().apply { pullTimesOut = true }
        val report = runner(db, room).syncByButtonReport()
        // Before 앱8 the time out of the pull answer was rethrown as a cancel: no sync_push at all, no line.
        assertTrue(room.ops.contains("sync_push"))
        assertTrue(ids.all { id -> room.pushed.any { it.getString("id") == id } })
        assertEquals(MemoButtonSync.Failed, report.result)
        assertTrue(memoSyncReportText(report).isNotEmpty())
    }

    @Test
    fun `a push whose answer times out is a failed push, held back, and the button is free again`() = runTest {
        val db = db()
        backend(db).create("글")
        val room = Room().apply { pushTimesOut = true }
        val r = runner(db, room)
        assertEquals(MemoButtonSync.Failed, r.syncByButton())
        assertTrue(r.hasPendingUploads())
        room.pushTimesOut = false
        assertEquals(MemoButtonSync.Done, r.syncByButton())
        assertFalse(r.hasPendingUploads())
    }

    @Test
    fun `the screen sync also survives a timed out answer`() = runTest {
        val db = db()
        backend(db).create("글")
        val room = Room().apply { pullTimesOut = true }
        assertFalse(runner(db, room).syncNow())
        // The automatic push after typing still sends it.
        assertTrue(runner(db, room).push())
        assertEquals(1, room.pushed.size)
    }

    // ---------------------------------------------------------------- notes before photos

    @Test
    fun `the notes go up before their photos, and again with the mxc once the photos are up`() = runTest {
        val db = db()
        val ids = twoPhotoNotes(db)
        val room = Room()
        val report = runner(db, room).syncByButtonReport()
        assertEquals(MemoButtonSync.Done, report.result)
        // First push without the photos (no mxc yet), then the same notes with them.
        val first = room.pushed.take(2)
        assertTrue(first.all { it.getJSONArray("첨부").length() == 0 })
        val again = room.pushed.drop(2)
        assertEquals(ids.toSet(), again.map { it.getString("id") }.toSet())
        assertTrue(again.all { it.getJSONArray("첨부").getJSONObject(0).getString("mxc").startsWith("mxc://test/") })
        assertEquals(4, report.sent)
        assertEquals(0, report.photosLeft)
        assertEquals(listOf("sync_pull", "sync_push", "sync_push"), room.ops)
    }

    @Test
    fun `a stuck photo upload no longer holds the notes, the press ends in time and frees the lock`() = runTest {
        val db = db()
        val ids = twoPhotoNotes(db)
        val room = Room().apply { uploadHangs = true }
        val r = runner(db, room)
        val report = r.syncByButtonReport()
        // The notes reached the server without their photos; the photos wait for a later try.
        assertTrue(ids.all { id -> room.pushed.any { it.getString("id") == id } })
        assertEquals(MemoButtonSync.Partial, report.result)
        assertEquals(2, report.photosLeft)
        assertEquals("노트 2건을 올렸습니다 · 사진 2장은 나중에 다시 시도합니다", memoSyncReportText(report))
        // Not busy any more: the lock and the button are free.
        room.uploadHangs = false
        val next = r.syncByButtonReport()
        assertEquals(MemoButtonSync.Done, next.result)
        assertTrue(db.pendingAttachments().isEmpty())
    }

    @Test
    fun `an upload that times out is a failed try with its next time, the notes are still up`() = runTest {
        val db = db()
        val ids = twoPhotoNotes(db)
        val room = Room().apply { uploadTimesOut = true }
        val r = runner(db, room)
        assertFalse(r.push())
        assertTrue(ids.all { id -> room.pushed.any { it.getString("id") == id } })
        val a = db.pendingAttachments().first().second
        assertEquals(1, a.tries)
        assertEquals(MEMO_ATTACH_LOCAL, a.state)
    }

    @Test
    fun `a failing photo upload says how many photos wait, and a note whose photo failed still goes up`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("", listOf(MemoLocalAttachment(id = "a1", localPath = "/p/1.jpg", bytes = 3)))
        val room = Room().apply { uploadFails = true }
        val r = runner(db, room)
        repeat(MEMO_ATTACH_MAX_TRIES) { r.push(force = true) }
        assertEquals(MEMO_ATTACH_FAILED, db.note(id)!!.attachments.single().state)
        // Edited later: the note goes up although its photo is 실패.
        room.pushed.clear()
        now += 1_000
        local.call(MemoOp.edit(MemoTarget(id), "설명 추가", null, null), "rid")
        assertTrue(r.push(force = true))
        assertTrue(room.pushed.any { it.getString("id") == id })
        val item = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.single()
        assertEquals(1, item.photosFailed)
        assertEquals("⚠ 사진 1장 못 올림", memoPhotoUploadMark(item))
    }

    // ---------------------------------------------------------------- the card mark and the lines

    @Test
    fun `a photo card shows a mark until its photo is on the server`() = runTest {
        val db = db()
        twoPhotoNotes(db)
        val before = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items
        assertTrue(before.all { memoPhotoUploadMark(it) == "⏳ 사진 1장 올리기 전" })
        runner(db, Room()).syncByButton()
        val after = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items
        assertTrue(after.all { memoPhotoUploadMark(it).isEmpty() })
    }

    @Test
    fun `every press ends with a line`() {
        assertEquals("동기화했습니다 · 바뀐 것 없음", memoSyncReportText(MemoSyncReport(MemoButtonSync.Done)))
        assertEquals("동기화했습니다 · 올림 2건 · 받음 1건", memoSyncReportText(MemoSyncReport(MemoButtonSync.Done, 2, 1)))
        assertEquals("노트 1건만 올렸습니다 · 서버에 닿지 못함", memoSyncReportText(MemoSyncReport(MemoButtonSync.Failed, 1)))
        assertTrue(memoSyncReportText(MemoSyncReport(MemoButtonSync.Failed)).startsWith("동기화하지 못했습니다"))
        assertTrue(memoSyncReportText(MemoSyncReport(MemoButtonSync.TimedOut)).contains("90초"))
        MemoButtonSync.entries.forEach { assertTrue(memoSyncReportText(MemoSyncReport(it)).isNotBlank()) }
    }

    @Test
    fun `a press right after the screen pull does not pull twice`() = runTest {
        val db = db()
        val room = Room()
        val r = runner(db, room)
        r.syncNow()
        r.syncByButton()
        assertEquals(1, room.ops.count { it == "sync_pull" })
        now += MemoSyncRunner.RECENT_PULL_MS
        r.syncByButton()
        assertEquals(2, room.ops.count { it == "sync_pull" })
    }

    @Test
    fun `the step lines carry no address, token or key`() = runTest {
        val db = db()
        twoPhotoNotes(db)
        val log = mutableListOf<String>()
        runner(db, Room(), log).syncByButton()
        assertTrue(log.isNotEmpty())
        assertTrue(log.none { it.contains("mxc://") || it.contains("http") || it.contains("\"k\"") || it.contains("Bearer") })
    }
}
