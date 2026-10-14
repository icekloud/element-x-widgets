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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A4 sync engine: 이벤트규약 v2 §2·§3 requests and answers over the local database. */
class MemoSyncTest : RobolectricTest() {
    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private val t0 = 1_760_000_000_000L

    private fun note(
        id: String,
        body: String = "본문 $id",
        folder: String = "여행",
        rev: MemoRev = MemoRev(t0, "폰A", 1),
        tags: List<String> = emptyList(),
        deleted: Int = MEMO_DELETED_NONE,
    ) = MemoNoteRecord(
        id = id,
        folder = folder,
        title = "제목 $id",
        body = body,
        kind = "텍스트",
        createdMs = t0,
        revs = MemoRevs.all(rev),
        tags = tags,
        deleted = deleted,
    )

    // ---- time

    @Test
    fun `a protocol time is read back as the time memoIso wrote`() {
        listOf(t0, 0L, 1_000_000_000_000L, 2_000_000_000_000L).forEach { ms ->
            val seconds = Math.floorDiv(ms, 1000L) * 1000L
            assertEquals(seconds, memoParseIso(memoIso(ms)))
        }
    }

    @Test
    fun `a UTC time and a KST time of the same moment are read as the same millis`() {
        assertEquals(memoParseIso("2026-10-12T14:30:12+09:00"), memoParseIso("2026-10-12T05:30:12Z"))
    }

    @Test
    fun `text that is not a time is null`() {
        listOf(null, "", "어제", "2026-10-12").forEach { assertNull(memoParseIso(it)) }
    }

    // ---- sync_pull

    @Test
    fun `the pull request carries the base, the device and the segment summary`() {
        val db = db()
        db.putNote(note("20261012-120000-aaaa"))
        val args = MemoSync.pullArgs(db, "폰A")
        assertEquals(0L, args.getLong("기준"))
        assertEquals("폰A", args.getString("기기"))
        val summary = args.getJSONArray("구간요약")
        assertEquals(1, summary.length())
        val item = summary.getJSONObject(0)
        assertEquals(memoSegment("20261012-120000-aaaa").toString(), item.getString("구간"))
        assertEquals(1, item.getInt("수"))
        assertEquals(
            memoSegmentHash(listOf(memoSegmentLine("20261012-120000-aaaa", MemoRev(t0, "폰A", 1)))),
            item.getString("해시"),
        )
    }

    @Test
    fun `while a paged answer is running the summary is left out and the cursor is sent`() {
        val db = db()
        db.putNote(note("20261012-120000-aaaa"))
        db.setState(MemoSync.STATE_CURSOR, "증분:7")
        val args = MemoSync.pullArgs(db, "폰A")
        assertEquals("증분:7", args.getString("cursor"))
        assertFalse(args.has("구간요약"))
    }

    @Test
    fun `a note the phone does not have is stored and the base moves on`() {
        val db = db()
        val change = JSONObject()
            .put("id", "20261012-120000-bbbb")
            .put("폴더", "여행")
            .put("제목", "교토")
            .put("본문", "빵집 목록")
            .put("태그", JSONArray(listOf("빵집")))
            .put("종류", "텍스트")
            .put("생성", memoIso(t0))
            .put("수정", memoIso(t0 + 60_000))
            .put("기기", "서버")
            .put("순번", 4)
        val content = JSONObject()
            .put("v", 2)
            .put("노트", JSONArray().put(change))
            .put("기준", 42)
            .put("서버시각", memoIso(t0 + 60_000))
        val result = MemoSync.applySync(db, content, t0)
        assertEquals(1, result.applied)
        assertFalse(result.morePages)
        val stored = db.note("20261012-120000-bbbb")
        assertNotNull(stored)
        assertEquals("빵집 목록", stored!!.body)
        assertEquals(listOf("빵집"), stored.tags)
        assertEquals(42L, MemoSync.base(db))
        assertEquals(60_000L, MemoSync.skewMs(db))
        assertEquals(t0 + 60_000, MemoSync.correctedNow(db, t0))
    }

    @Test
    fun `the base only moves on the last page`() {
        val db = db()
        val content = JSONObject().put("노트", JSONArray()).put("기준", 7).put("다음cursor", "증분:7")
        val result = MemoSync.applySync(db, content, t0)
        assertTrue(result.morePages)
        assertEquals(0L, MemoSync.base(db))
        assertEquals("증분:7", MemoSync.cursor(db))
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray()).put("기준", 9), t0)
        assertEquals(9L, MemoSync.base(db))
        assertNull(MemoSync.cursor(db))
    }

    @Test
    fun `a rewound server drops the base and keeps every note`() {
        val db = db()
        db.putNote(note("20261012-120000-aaaa"))
        db.setState(MemoSync.STATE_BASE, "900")
        val result = MemoSync.applySync(db, JSONObject().put("노트", JSONArray()).put("기준", 3).put("되감김", true), t0)
        assertTrue(result.rewound)
        assertEquals(0L, MemoSync.base(db))
        assertNotNull(db.note("20261012-120000-aaaa"))
    }

    @Test
    fun `the newer side wins bundle by bundle and the note is not sent back when nothing of ours changed`() {
        val db = db()
        val id = "20261012-120000-cccc"
        db.putNote(note(id, body = "폰 본문", folder = "여행", rev = MemoRev(t0, "폰A", 1)))
        // The server body is newer, our folder is newer: each bundle keeps its own winner (관리자 확정 Q4).
        val change = JSONObject()
            .put("id", id)
            .put("폴더", "받은편지함")
            .put("제목", "제목 $id")
            .put("본문", "서버 본문")
            .put("종류", "텍스트")
            .put("생성", memoIso(t0))
            .put("수정", memoIso(t0 + 10_000))
            .put("기기", "서버")
            .put("순번", 2)
            .put(
                "rev",
                JSONObject()
                    .put("본문", JSONArray().put(memoIso(t0 + 10_000)).put("서버").put(2))
                    .put("분류", JSONArray().put(memoIso(t0 - 10_000)).put("서버").put(1))
                    .put("표식", JSONArray().put(memoIso(t0 - 10_000)).put("서버").put(1))
                    .put("첨부", JSONArray().put(memoIso(t0 - 10_000)).put("서버").put(1)),
            )
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(change)).put("기준", 1), t0)
        val merged = db.note(id)!!
        assertEquals("서버 본문", merged.body)
        assertEquals("여행", merged.folder)
        assertTrue(merged.dirty)
    }

    @Test
    fun `a note that is already what the server sent is not written again`() {
        val db = db()
        val id = "20261012-120000-dddd"
        val mine = note(id, rev = MemoRev(t0, "폰A", 1))
        db.putNote(mine)
        val change = mine.toChangeJson()
        val result = MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(change)).put("기준", 1), t0)
        assertEquals(0, result.applied)
        val after = db.note(id)!!
        assertFalse(after.dirty)
        assertEquals(mine.contentHash, after.serverRev)
    }

    @Test
    fun `a delete that is later than our edit wins and a later edit brings the note back`() {
        val db = db()
        val id = "20261012-120000-eeee"
        db.putNote(note(id, rev = MemoRev(t0, "폰A", 1)))
        val tombstone = JSONObject()
            .put("id", id)
            .put("삭제", MEMO_DELETED_TOMBSTONE)
            .put("수정", memoIso(t0 + 5_000))
            .put("기기", "서버")
            .put("순번", 3)
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(tombstone)).put("기준", 1), t0)
        assertEquals(MEMO_DELETED_TOMBSTONE, db.note(id)!!.deleted)

        val revived = note(id, body = "되살림", rev = MemoRev(t0 + 9_000, "폰B", 4)).toChangeJson()
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray().put(revived)).put("기준", 2), t0)
        val back = db.note(id)!!
        assertEquals(MEMO_DELETED_NONE, back.deleted)
        assertEquals("되살림", back.body)
    }

    // ---- compare sync

    @Test
    fun `a mismatched segment asks for the newer server notes and offers ours`() {
        val db = db()
        val mineOnly = "20261012-120000-aaaa"
        val both = "20261012-120000-bbbb"
        db.putNote(note(mineOnly, rev = MemoRev(t0, "폰A", 1)))
        db.putNote(note(both, rev = MemoRev(t0, "폰A", 1)))
        val rows = JSONArray()
            .put(JSONObject().put("id", both).put("수정", memoIso(t0 + 60_000)).put("기기", "서버").put("순번", 9))
            .put(JSONObject().put("id", "20261012-120000-zzzz").put("수정", memoIso(t0)).put("기기", "서버").put("순번", 2))
        val segments = JSONArray().put(JSONObject().put("구간", memoSegment(both).toString()).put("서버", rows))
        val diff = MemoSync.mismatchedIds(db, segments)
        assertTrue(both in diff.wanted)
        assertTrue("20261012-120000-zzzz" in diff.wanted)
        assertFalse(both in diff.push)
    }

    @Test
    fun `a note of the segment the server did not list is offered for upload`() {
        val db = db()
        val id = "20261012-120000-aaaa"
        db.putNote(note(id, rev = MemoRev(t0, "폰A", 1)))
        val segments = JSONArray().put(JSONObject().put("구간", memoSegment(id).toString()).put("서버", JSONArray()))
        val diff = MemoSync.mismatchedIds(db, segments)
        assertEquals(listOf(id), diff.push)
        assertTrue(diff.wanted.isEmpty())
    }

    @Test
    fun `an older server note is offered for upload, not asked for`() {
        val db = db()
        val id = "20261012-120000-bbbb"
        db.putNote(note(id, rev = MemoRev(t0 + 60_000, "폰A", 5)))
        val rows = JSONArray().put(JSONObject().put("id", id).put("수정", memoIso(t0)).put("기기", "서버").put("순번", 1))
        val segments = JSONArray().put(JSONObject().put("구간", memoSegment(id).toString()).put("서버", rows))
        val diff = MemoSync.mismatchedIds(db, segments)
        assertEquals(listOf(id), diff.push)
        assertTrue(diff.wanted.isEmpty())
    }

    // ---- sync_push

    @Test
    fun `nothing waiting means no request is made`() {
        assertNull(MemoSync.pushArgs(emptyList(), "폰A"))
    }

    @Test
    fun `a newer change of the same note replaces the waiting one`() {
        val db = db()
        val id = "20261012-120000-aaaa"
        MemoSync.enqueueNote(db, note(id, body = "처음"), t0)
        MemoSync.enqueueNote(db, note(id, body = "나중"), t0 + 2_500)
        val rows = MemoSync.dueUploads(db, t0 + 3_000)
        assertEquals(1, rows.size)
        val args = MemoSync.pushArgs(rows, "폰A")!!
        assertEquals("폰A", args.getString("기기"))
        assertEquals("나중", args.getJSONArray("변경").getJSONObject(0).getString("본문"))
    }

    @Test
    fun `an accepted upload leaves the outbox and the note is clean`() {
        val db = db()
        val id = "20261012-120000-aaaa"
        db.putNote(note(id).copy(dirty = true))
        MemoSync.enqueueNote(db, note(id), t0)
        val rows = MemoSync.dueUploads(db, t0)
        val ack = JSONObject()
            .put("결과", JSONArray().put(JSONObject().put("id", id).put("상태", "수락")))
            .put("서버순번", 12)
            .put("서버시각", memoIso(t0))
        val result = MemoSync.applyAck(db, ack, rows, t0)
        assertEquals(1, result.accepted)
        assertEquals(0, db.outboxCount())
        assertFalse(db.note(id)!!.dirty)
        assertTrue(result.pullAgain)
        assertTrue(MemoSync.needsPull(db))
    }

    @Test
    fun `a merged answer stores the note the server sent back`() {
        val db = db()
        val id = "20261012-120000-bbbb"
        db.putNote(note(id, body = "폰 본문"))
        MemoSync.enqueueNote(db, note(id, body = "폰 본문"), t0)
        val rows = MemoSync.dueUploads(db, t0)
        val merged = note(id, body = "서버가 합친 본문", rev = MemoRev(t0 + 30_000, "서버", 8)).toChangeJson()
        val ack = JSONObject()
            .put("결과", JSONArray().put(JSONObject().put("id", id).put("상태", "병합").put("노트", merged)))
            .put("서버순번", 3)
        val result = MemoSync.applyAck(db, ack, rows, t0)
        assertEquals(1, result.merged)
        assertEquals("서버가 합친 본문", db.note(id)!!.body)
        assertEquals(0, db.outboxCount())
    }

    @Test
    fun `a rejected upload waits instead of being retried at once`() {
        val db = db()
        val id = "20261012-120000-cccc"
        MemoSync.enqueueNote(db, note(id), t0)
        val rows = MemoSync.dueUploads(db, t0)
        val ack = JSONObject()
            .put("결과", JSONArray().put(JSONObject().put("id", id).put("상태", "거절").put("사유", "쓸 수 없는 폴더")))
            .put("서버순번", 1)
        val result = MemoSync.applyAck(db, ack, rows, t0)
        assertEquals(1, result.rejected)
        assertEquals(1, db.outboxCount())
        assertTrue(MemoSync.dueUploads(db, t0).isEmpty())
        assertEquals(1, MemoSync.dueUploads(db, t0 + MemoSync.BACKOFF_MS[0]).size)
        assertEquals("쓸 수 없는 폴더", db.outbox().first().lastError)
    }

    @Test
    fun `a server that is off makes every waiting upload wait longer`() {
        val db = db()
        MemoSync.enqueueNote(db, note("20261012-120000-aaaa"), t0)
        var rows = MemoSync.dueUploads(db, t0)
        MemoSync.failUploads(db, rows, t0, "연결 없음")
        assertTrue(MemoSync.dueUploads(db, t0 + 1_000).isEmpty())
        rows = MemoSync.dueUploads(db, t0 + MemoSync.BACKOFF_MS[0])
        assertEquals(1, rows.size)
        MemoSync.failUploads(db, rows, t0 + MemoSync.BACKOFF_MS[0], "연결 없음")
        // the second try waits longer than the first
        assertTrue(MemoSync.dueUploads(db, t0 + MemoSync.BACKOFF_MS[0] * 2).isEmpty())
    }

    @Test
    fun `the back-off stops growing at the last step`() {
        val last = MemoSync.BACKOFF_MS.last()
        assertEquals(t0 + last, MemoSync.nextTryMs(MemoSync.BACKOFF_MS.size, t0))
        assertEquals(t0 + last, MemoSync.nextTryMs(MemoSync.BACKOFF_MS.size + 5, t0))
    }

    // ---- developer log

    @Test
    fun `the log keeps the last fifty lines and never holds note content`() {
        val db = db()
        repeat(MEMO_SYNC_LOG_ROWS + 5) { i -> db.log(t0 + i, "sync_pull", i, 5, "끝") }
        val rows = db.logs()
        assertEquals(MEMO_SYNC_LOG_ROWS, rows.size)
        assertEquals(t0 + MEMO_SYNC_LOG_ROWS + 4, rows.first().atMs)
        val secret = "비밀 본문"
        db.putNote(note("20261012-120000-aaaa", body = secret))
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray()).put("기준", 1), t0)
        assertTrue(db.logs().none { (it.result ?: "").contains(secret) || it.kind.contains(secret) })
    }

    @Test
    fun `every sync request writes one log line`() {
        val db = db()
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray()).put("기준", 1), t0)
        MemoSync.enqueueNote(db, note("20261012-120000-aaaa"), t0)
        val rows = MemoSync.dueUploads(db, t0)
        MemoSync.applyAck(db, JSONObject().put("결과", JSONArray()).put("서버순번", 1), rows, t0)
        assertEquals(listOf("sync_push", "sync_pull"), db.logs().map { it.kind })
    }
}
