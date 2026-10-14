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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fixed values the server interoperability test checks (A4 ⑤). The server modules compute the same `내용해시`,
 * segment number and segment hash from the same input, so these numbers may only change together with the protocol.
 *
 * The matching server side lives in `/opt/data/shared/배포/메모봇-서버/` and is run by
 * `projects/메모봇-구현앱2/s/맞물림시험.py`, which feeds the request JSON of this app into `june_memo_sync.py` /
 * `june_memo_ops.py` and compares the values below.
 */
class MemoSyncVectorsTest : RobolectricTest() {
    private val id = "20261012-120000-abcd"
    private val t0 = 1_760_000_000_000L

    @Test
    fun `the content hash of a body without attachments`() {
        assertEquals("8a41887a4a7353d1c1ba48ffb579ead9c2f19c8a", memoContentHash("빵집 목록\n교토", emptyList()))
    }

    @Test
    fun `the content hash with uploaded attachments`() {
        assertEquals(
            "536e2921c7a111bbe92d5e18e16b7d6951391bd2",
            memoContentHash("사진", listOf("mxc://hs/aaa", "mxc://hs/bbb")),
        )
    }

    @Test
    fun `the note content hash is the one the server writes, whatever the trailing newlines`() {
        // 이벤트규약 §5 v2.1: the server hashes the stored body (one trailing newline) on every path.
        // 924e26d9… = sha1("빵집 목록\n교토\n"), the value 맞물림시험.py reads back from the server file.
        listOf("빵집 목록\n교토", "빵집 목록\n교토\n", "빵집 목록\n교토\n\n").forEach { body ->
            val note = MemoNoteRecord(
                id = id,
                folder = "여행",
                title = "교토",
                body = body,
                kind = "텍스트",
                createdMs = t0,
                revs = MemoRevs.all(MemoRev(t0, "폰A", 3)),
            )
            assertEquals("924e26d903a4034797c2a44aea83ba91ecf1ef06", note.contentHash)
        }
        assertEquals("빵집 목록\n교토\n", memoStoredBody("빵집 목록\n교토\n\n"))
    }

    @Test
    fun `the segment of a note id`() {
        assertEquals(5, memoSegment(id))
    }

    @Test
    fun `the segment hash of one line`() {
        assertEquals("7ca079d734b8", memoSegmentHash(listOf(memoSegmentLine(id, MemoRev(t0, "폰A", 3)))))
    }

    @Test
    fun `the protocol time of the fixed instant`() {
        assertEquals("2025-10-09T17:53:20+09:00", memoIso(t0))
    }

    @Test
    fun `a body that came back with the newline the server file has is the same note`() {
        val change = JSONObject()
            .put("id", id)
            .put("본문", "빵집 목록\n교토\n")
            .put("수정", memoIso(t0))
            .put("기기", "폰A")
            .put("순번", 3)
        assertEquals("빵집 목록\n교토", memoChangeFromJson(change)!!.body)
        assertEquals("빵집 목록\n교토", memoNormalizedBody("빵집 목록\n교토\n\n"))
    }

    @Test
    fun `a change made here is read back unchanged`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        val db = MemoDb(driver)
        val note = MemoNoteRecord(
            id = id,
            folder = "여행",
            title = "교토",
            body = "빵집 목록\n교토",
            kind = "텍스트",
            createdMs = t0,
            revs = MemoRevs.all(MemoRev(t0, "폰A", 3)),
            tags = listOf("빵집", "교토"),
        )
        db.putNote(note)
        val roundTrip = memoChangeFromJson(JSONObject(note.toChangeJson().toString()))!!
        assertEquals(note.id, roundTrip.id)
        assertEquals(note.folder, roundTrip.folder)
        assertEquals(note.body, roundTrip.body)
        assertEquals(note.tags, roundTrip.tags)
        assertEquals(note.contentHash, roundTrip.contentHash)
        assertEquals(note.revs, roundTrip.revs)
    }
}
