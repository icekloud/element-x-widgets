/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 앱23: the photo's place on the wire — up with the note change, back down from the server, and never lost on the way
 * (규약-사진위치 1·3절). Made-up coordinates only.
 */
class MemoPhotoLocationSyncTest : RobolectricTest() {
    private val lat = 11.111111
    private val lon = 22.222222
    private val location = memoPhotoLocationOf(lat, lon, takenAt = "2026-10-10T18:59:30+09:00")!!

    private fun uploaded(mxc: String = "mxc://fake/1", withLocation: Boolean = true) =
        MemoLocalAttachment(
            id = "p1",
            localPath = "/x/p1.jpg",
            mxc = mxc,
            mime = "image/jpeg",
            state = MEMO_ATTACH_DONE,
            fileJson = """{"url":"$mxc"}""",
        ).let { if (withLocation) it.withLocation(location) else it }

    private val t0 = 1_700_000_000_000L

    private fun record(attachments: List<MemoLocalAttachment>, rev: MemoRev = MemoRev(t0, "A", 1)) = MemoNoteRecord(
        id = "n1",
        folder = MEMO_INBOX,
        title = "사진 한 장",
        body = "",
        kind = "사진",
        createdMs = t0,
        revs = MemoRevs.all(rev),
        tags = listOf("사진"),
        attachments = attachments,
    )

    @Test
    fun `노트 변경에 사진 위치가 함께 간다`() {
        val change = record(listOf(uploaded())).toChangeJson()
        val item = change.getJSONArray("첨부").getJSONObject(0)

        assertEquals("mxc://fake/1", item.getString("mxc"))
        val sent = item.getJSONObject(MEMO_PHOTO_LOCATION_KEY)
        assertEquals(lat, sent.getDouble("위도"), 0.0000001)
        assertEquals(lon, sent.getDouble("경도"), 0.0000001)
        assertEquals("2026-10-10T18:59:30+09:00", sent.getString("촬영"))
        // 서버가 쓰는 file 칸에는 섞이지 않는다.
        assertFalse(item.getJSONObject("file").has(MEMO_PHOTO_LOCATION_KEY))
        assertEquals("mxc://fake/1", item.getJSONObject("file").getString("url"))
    }

    @Test
    fun `위치가 없는 사진은 위치 칸 없이 간다`() {
        val item = record(listOf(uploaded(withLocation = false))).toChangeJson().getJSONArray("첨부").getJSONObject(0)
        assertFalse(item.has(MEMO_PHOTO_LOCATION_KEY))
        assertEquals("mxc://fake/1", item.getString("mxc"))
    }

    @Test
    fun `서버가 보낸 위치는 그대로 다시 올라간다`() {
        // get 답에 실려 내려온 사진 (규약-사진위치 3절).
        val header = JSONObject()
            .put("폴더", "메모")
            .put("제목", "사진 한 장")
            .put("태그", JSONArray(listOf("사진")))
            .put("종류", "메모")
        val files = JSONArray().put(
            JSONObject()
                .put("mxc", "mxc://fake/1")
                .put("file", JSONObject().put("url", "mxc://fake/1"))
                .put(MEMO_PHOTO_LOCATION_KEY, location.toJson()),
        )
        val answer = JSONObject()
            .put("요청id", "r1")
            .put("id", "n1")
            .put("머리말", header)
            .put("본문", "")
            .put("첨부", files)
        val note = (parseMemoResponse(MEMO_NOTE_TYPE, answer) as MemoResponse.Note).note
        assertEquals(1, note.attachments.size)
        assertEquals(location, memoAttachmentLocation(note.attachments[0].fileJson))
        val item = memoChangeOfNote(note).getJSONArray("첨부").getJSONObject(0)
        assertEquals(location, memoPhotoLocationFromJson(item.getJSONObject(MEMO_PHOTO_LOCATION_KEY)))
        assertFalse(item.getJSONObject("file").has(MEMO_PHOTO_LOCATION_KEY))
    }

    @Test
    fun `사진 자리 정보는 올린 뒤에도 남는다`() {
        // 올리기 전: 위치만 들어 있다. 올린 뒤: 서버 사본 정보와 위치가 함께 있다 (결정기록 2).
        val picked = MemoLocalAttachment(id = "p1", localPath = "/x/p1.jpg").withLocation(location)
        assertNotNull(picked.location)
        assertNull(picked.mxc)

        val afterUpload = picked.copy(
            mxc = "mxc://fake/1",
            state = MEMO_ATTACH_DONE,
            fileJson = memoFileJsonWithLocation("""{"url":"mxc://fake/1","iv":"가짜"}""", picked.location),
        )
        assertEquals(location, afterUpload.location)
        assertEquals("가짜", JSONObject(afterUpload.fileJson!!).getString("iv"))
    }

    @Test
    fun `편집에서 고른 사진은 위치를 품고 노트로 간다`() {
        // 편집 화면이 만든 사진 인자에 위치가 실려야 저장될 때 함께 들어간다.
        val picked = MemoLocalAttachment(id = "p1", localPath = "/x/p1.jpg", mime = "image/jpeg", bytes = 10L)
            .withLocation(location)
        val back = memoPhotosOf(listOf(memoPhotoArg(picked)))
        assertEquals(1, back.size)
        assertEquals(location, back[0].location)
        assertEquals("/x/p1.jpg", back[0].localPath)

        // 위치가 없는 사진도 그대로 오간다.
        val plain = MemoLocalAttachment(id = "p2", localPath = "/x/p2.jpg")
        assertNull(memoPhotosOf(listOf(memoPhotoArg(plain)))[0].location)
    }

    @Test
    fun `합칠 때 폰이 읽은 위치를 잃지 않는다`() {
        // 서버가 이긴 첨부에는 위치가 없다 — 폰이 읽어 둔 위치를 다시 얹는다 (결정기록 2).
        val phone = record(listOf(uploaded()))
        val server = record(listOf(uploaded(withLocation = false)), rev = MemoRev(t0 + 100_000L, "B", 2))
        val merged = mergeMemoNote(phone, server)
        assertEquals(1, merged.attachments.size)
        assertEquals(location, merged.attachments[0].location)
    }
}
