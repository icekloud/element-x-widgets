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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱23: the place a photo was taken at, from the picture to the `첨부[]` item the server is sent
 * (규약-사진위치 1·2절). Every coordinate here is made up.
 */
class MemoPhotoLocationTest : RobolectricTest() {
    // 가짜 좌표만 쓴다 (요청문 5).
    private val lat = 11.111111
    private val lon = 22.222222

    @Test
    fun `위치가 없거나 읽히지 않으면 보내지 않는다`() {
        assertNull(memoPhotoLocationOf(null, lon))
        assertNull(memoPhotoLocationOf(lat, null))
        assertNull(memoPhotoLocationOf(null, null))
        assertNull(memoPhotoLocationOf(Double.NaN, lon))
        assertNull(memoPhotoLocationOf(lat, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `0,0 과 범위 밖은 보내지 않는다`() {
        // 안드로이드가 위치를 가린 사진이 0,0 으로 읽힌다 — 진짜 좌표로 보내면 안 된다.
        assertNull(memoPhotoLocationOf(0.0, 0.0))
        assertNull(memoPhotoLocationOf(90.000001, lon))
        assertNull(memoPhotoLocationOf(-90.000001, lon))
        assertNull(memoPhotoLocationOf(lat, 180.000001))
        assertNull(memoPhotoLocationOf(lat, -180.000001))
        // 경계와 한쪽만 0 인 값은 보낸다.
        assertEquals(90.0, memoPhotoLocationOf(90.0, 180.0)?.latitude)
        assertEquals(0.0, memoPhotoLocationOf(0.0, lon)?.latitude)
    }

    @Test
    fun `좌표는 소수 여섯 자리로 보낸다`() {
        val l = memoPhotoLocationOf(11.1111114999, 22.2222225)
        assertEquals(11.111111, l?.latitude)
        assertEquals(22.222223, l?.longitude)
    }

    @Test
    fun `촬영과 정확도는 쓸 수 있을 때만 붙인다`() {
        assertNull(memoPhotoLocationOf(lat, lon, takenAt = "   ")?.takenAt)
        assertNull(memoPhotoLocationOf(lat, lon, takenAt = "x".repeat(MEMO_PHOTO_TAKEN_CHARS + 1))?.takenAt)
        assertEquals("2026-10-10T18:59:30+09:00", memoPhotoLocationOf(lat, lon, takenAt = " 2026-10-10T18:59:30+09:00 ")?.takenAt)

        assertNull(memoPhotoLocationOf(lat, lon, accuracyM = -1.0)?.accuracyM)
        assertNull(memoPhotoLocationOf(lat, lon, accuracyM = MEMO_PHOTO_ACCURACY_MAX + 1)?.accuracyM)
        assertNull(memoPhotoLocationOf(lat, lon, accuracyM = Double.NaN)?.accuracyM)
        assertEquals(12.3, memoPhotoLocationOf(lat, lon, accuracyM = 12.34)?.accuracyM)
    }

    @Test
    fun `위치 칸은 규약대로 생긴다`() {
        val full = memoPhotoLocationOf(lat, lon, takenAt = "2026-10-10T18:59:30+09:00", accuracyM = 4.5)!!.toJson()
        assertEquals(lat, full.getDouble("위도"), 0.0000001)
        assertEquals(lon, full.getDouble("경도"), 0.0000001)
        assertEquals("2026-10-10T18:59:30+09:00", full.getString("촬영"))
        assertEquals(4.5, full.getDouble("정확도"), 0.0000001)

        val bare = memoPhotoLocationOf(lat, lon)!!.toJson()
        assertEquals(setOf("위도", "경도"), bare.keys().asSequence().toSet())

        // 읽어 들인 것이 보낸 것과 같다.
        assertEquals(memoPhotoLocationOf(lat, lon, takenAt = "2026-10-10T18:59:30+09:00", accuracyM = 4.5), memoPhotoLocationFromJson(full))
        assertNull(memoPhotoLocationFromJson(null))
        assertNull(memoPhotoLocationFromJson(JSONObject().put("위도", 0.0).put("경도", 0.0)))
        assertNull(memoPhotoLocationFromJson(JSONObject().put("위도", lat)))
    }

    @Test
    fun `위치는 첨부 행의 기존 칸에 담긴다`() {
        // 스키마를 바꾸지 않는다 (결정기록 2): 첨부의 file_json 안에 같이 둔다.
        val location = memoPhotoLocationOf(lat, lon)!!
        val a = MemoLocalAttachment(id = "p1", localPath = "/x/p1.jpg").withLocation(location)
        assertEquals(location, a.location)

        // 올리기 전에는 파일 정보가 없고, 올린 뒤에는 서버 사본 정보와 함께 남는다.
        val uploaded = a.copy(fileJson = memoFileJsonWithLocation("""{"url":"mxc://fake/1"}""", location), mxc = "mxc://fake/1")
        assertEquals(location, uploaded.location)
        assertEquals("mxc://fake/1", JSONObject(uploaded.fileJson!!).getString("url"))

        // 위치가 없으면 파일 정보를 건드리지 않는다.
        assertEquals("""{"url":"mxc://fake/1"}""", memoFileJsonWithLocation("""{"url":"mxc://fake/1"}""", null))
        assertNull(MemoLocalAttachment(id = "p2", localPath = "/x/p2.jpg").location)
    }

    @Test
    fun `보내는 file 칸에는 위치를 섞지 않는다`() {
        val location = memoPhotoLocationOf(lat, lon)!!
        val fileJson = JSONObject(memoFileJsonWithLocation("""{"url":"mxc://fake/1"}""", location)!!)
        val onWire = memoFileWithoutLocation(fileJson)
        assertFalse(onWire.has(MEMO_PHOTO_LOCATION_KEY))
        assertEquals("mxc://fake/1", onWire.getString("url"))
        // 원래 것은 그대로 (위치를 잃지 않는다).
        assertTrue(fileJson.has(MEMO_PHOTO_LOCATION_KEY))
    }

    @Test
    fun `위치만 보충은 빠진 것이 있을 때만 한다`() {
        val location = memoPhotoLocationOf(lat, lon)!!
        val uploaded = MemoLocalAttachment(id = "p1", localPath = "/x/p1.jpg", mxc = "mxc://fake/1", state = MEMO_ATTACH_DONE)
            .withLocation(location)

        val without = JSONObject().put("첨부", JSONArray().put(JSONObject().put("mxc", "mxc://fake/1")))
        assertTrue(memoNeedsLocationFill(without, listOf(uploaded)))

        val with = JSONObject().put(
            "첨부",
            JSONArray().put(JSONObject().put("mxc", "mxc://fake/1").put(MEMO_PHOTO_LOCATION_KEY, location.toJson())),
        )
        assertFalse(memoNeedsLocationFill(with, listOf(uploaded)))

        // 위치가 없는 사진은 보충을 부르지 않는다 (이미 올린 사진은 소급하지 않는다).
        val plain = MemoLocalAttachment(id = "p2", localPath = "/x/p2.jpg", mxc = "mxc://fake/2", state = MEMO_ATTACH_DONE)
        assertFalse(memoNeedsLocationFill(without, listOf(plain)))
        // 아직 올라가지 않은 사진도 부르지 않는다 (올라갈 때 함께 간다).
        assertFalse(memoNeedsLocationFill(without, listOf(MemoLocalAttachment(id = "p3", localPath = "/x/p3.jpg").withLocation(location))))
    }

    @Test
    fun `촬영 시각은 사진의 시간대로 적는다`() {
        assertEquals(
            "2026-10-10T18:59:30+09:00",
            memoExifTakenAt("2026:10:10 18:59:30", "+09:00") { "+00:00" },
        )
        // 사진에 시간대가 없으면 기기 시간대를 쓴다.
        assertEquals(
            "2026-10-10T18:59:30+09:00",
            memoExifTakenAt("2026:10:10 18:59:30", null) { "+09:00" },
        )
        assertEquals("2026-10-10T18:59:30Z", memoExifTakenAt("2026:10:10 18:59:30", "Z") { "+09:00" })
        // 못 읽는 것은 그 칸만 뺀다.
        assertNull(memoExifTakenAt(null, "+09:00") { "+09:00" })
        assertNull(memoExifTakenAt("2026:10:10", "+09:00") { "+09:00" })
        assertNull(memoExifTakenAt("오늘 찍은 사진입니다", "+09:00") { "+09:00" })
        // 시간대가 이상하면 기기 시간대로 적는다.
        assertEquals("2026-10-10T18:59:30+09:00", memoExifTakenAt("2026:10:10 18:59:30", "구시간") { "+09:00" })
    }

    @Test
    fun `기기 시간대는 더하기 빼기 모양으로 적는다`() {
        val seoul = java.util.TimeZone.getTimeZone("Asia/Seoul")
        val before = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(seoul)
            assertEquals("+09:00", memoDeviceOffset(0L))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/St_Johns"))
            assertEquals("-03:30", memoDeviceOffset(0L))
        } finally {
            java.util.TimeZone.setDefault(before)
        }
    }
}
