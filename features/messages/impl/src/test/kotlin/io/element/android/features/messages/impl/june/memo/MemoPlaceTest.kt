/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱21 ②: the `장소` of a note header (서버 15차 규약 §2) and 추가요청 4 tag line. Made up places and coordinates. */
class MemoPlaceTest : RobolectricTest() {
    private val map = "https://www.google.com/maps/search/?api=1&query=test"

    private fun header(place: JSONObject?) = JSONObject().put("제목", "x").apply { place?.let { put(MEMO_FIELD_PLACE, it) } }

    private fun place(
        name: String = "가짜 꼬치집",
        kind: String = "음식점",
        intro: String = "꼬치튀김 전문점",
        address: String = "가짜시 가짜구 1-2-3",
        near: String? = null,
        approx: Boolean? = null,
        link: String? = map,
    ) = JSONObject()
        .put("이름", name)
        .put("종류", kind)
        .put("소개", intro)
        .put("주소", address)
        .put("위도", 10.5)
        .put("경도", 20.5)
        .put("출처", "사진GPS")
        .put("탐색", "2026-10-18T15:04:21+09:00")
        .apply {
            near?.let { put("근처", it) }
            approx?.let { put("근사", it) }
            link?.let { put("지도", it) }
        }

    @Test
    fun `no place, an old answer, or a place that is not an object draw nothing`() {
        assertNull(memoPlaceLine(null))
        assertNull(memoPlaceLine(JSONObject()))
        assertNull(memoPlaceLine(header(null)))
        assertNull(memoPlaceLine(JSONObject().put(MEMO_FIELD_PLACE, "가짜")))
        assertNull(memoPlaceLine(header(JSONObject())))
        assertNull(memoPlaceLine(header(place(name = "", intro = "", address = "", link = null))))
    }

    @Test
    fun `a restaurant shows the fork and its name, the address on the second line`() {
        // 앱23 추가요청 4: 소개는 칸에서 빼고 주소는 저장된 원문 그대로 둘째 줄에 둔다.
        val p = memoPlaceLine(header(place()))!!
        assertEquals("🍴 가짜 꼬치집", p.title)
        assertEquals("가짜시 가짜구 1-2-3", p.address)
        assertEquals(map, p.map)
        assertEquals("🍴 가짜 꼬치집, 가짜시 가짜구 1-2-3", p.description)
    }

    @Test
    fun `another kind shows the pin, a phone position adds (근처)`() {
        assertEquals("📍 가짜 탑", memoPlaceLine(header(place(name = "가짜 탑", kind = "관광", intro = "전망대")))!!.title)
        assertEquals("🍴 가짜 꼬치집 (근처)", memoPlaceLine(header(place(approx = true)))!!.title)
        assertEquals("🍴 가짜 꼬치집", memoPlaceLine(header(place(approx = false)))!!.title)
    }

    @Test
    fun `첫 줄은 서버가 준 표시이름이고 둘째 줄은 주소 원문이다`() {
        // 앱23 추가요청 3·5: 표시이름 → 이름 → 근처 순. 주소는 일본어라도 그대로 (가짜 값).
        val p = place(name = "カスガ ゴラクジョウ", address = "가짜현 가짜시 가짜구 3-2-19")
            .put(MEMO_PLACE_SHOWN_NAME, "카스가 고라쿠조오")
        val line = memoPlaceLine(header(p))!!
        assertEquals("🍴 카스가 고라쿠조오", line.title)
        assertEquals("가짜현 가짜시 가짜구 3-2-19", line.address)

        // 서버 21차 적용 전(표시이름이 없는 노트)은 이름으로 돌아간다 — 깨지지 않는다.
        assertEquals("🍴 カスガ ゴラクジョウ", memoPlaceLine(header(place(name = "カスガ ゴラクジョウ")))!!.title)
        // 표시이름이 비어 있어도 마찬가지.
        assertEquals("🍴 가짜 꼬치집", memoPlaceLine(header(place().put(MEMO_PLACE_SHOWN_NAME, "  ")))!!.title)
        // 폰 위치기록 기준이면 (근처)가 붙는다 (추가요청 6: 서버가 정황 대조로 확정하면 붙지 않는다).
        assertEquals(
            "🍴 카스가 고라쿠조오 (근처)",
            memoPlaceLine(header(place(approx = true).put(MEMO_PLACE_SHOWN_NAME, "카스가 고라쿠조오")))!!.title,
        )
    }

    @Test
    fun `without a name the area is shown as 근처`() {
        val p = memoPlaceLine(header(place(name = "", kind = "기타", intro = "", near = "가짜동", address = "")))!!
        assertEquals("📍 근처: 가짜동", p.title)
        assertEquals("", p.address)
    }

    @Test
    fun `without a web link the box has nothing to open`() {
        assertNull(memoPlaceLine(header(place(link = null)))!!.map)
        assertNull(memoPlaceLine(header(place(link = "")))!!.map)
        assertNull(memoPlaceLine(header(place(link = "intent://evil#Intent;end")))!!.map)
        assertNull(memoPlaceLine(header(place(link = "file:///sdcard/x")))!!.map)
        assertEquals("http://maps.example/x", memoPlaceMapLink(" http://maps.example/x "))
    }

    @Test
    fun `unknown parts of the place are ignored`() {
        val p = place().put("새칸", JSONObject().put("x", 1))
        assertEquals("🍴 가짜 꼬치집", memoPlaceLine(header(p))!!.title)
    }

    @Test
    fun `a press tries Google Maps first, then any app, and tells when none opens it`() {
        assertEquals(listOf(MEMO_MAPS_PACKAGE to map, null to map), memoPlaceTries(map))
        val tried = mutableListOf<Int>()
        assertTrue(memoFirstThatStarts(listOf(1, 2)) { tried += it })
        assertEquals(listOf(1), tried)
        tried.clear()
        assertTrue(
            memoFirstThatStarts(listOf(1, 2)) {
                tried += it
                if (it == 1) error("no maps")
            }
        )
        assertEquals(listOf(1, 2), tried)
        assertFalse(memoFirstThatStarts(listOf(1, 2)) { throw SecurityException("none") })
        assertEquals("지도를 열 앱이 없습니다", MEMO_PLACE_OPEN_FAILED)
    }

    @Test
    fun `the tag line of an open note opens the sheet, a note without tags offers to add one`() {
        assertEquals(listOf("#교토", "#빵집"), memoReadingTags(listOf("교토", "빵집"), trashed = false, selecting = false))
        assertEquals(listOf(MEMO_TAG_ADD), memoReadingTags(emptyList(), trashed = false, selecting = false))
        assertEquals("# 태그 추가", MEMO_TAG_ADD)
        assertNull(memoReadingTags(listOf("교토"), trashed = true, selecting = false))
        assertNull(memoReadingTags(emptyList(), trashed = false, selecting = true))
        val many = (1..22).map { "t$it" }
        val shown = memoReadingTags(many, trashed = false, selecting = false)!!
        assertEquals(21, shown.size)
        assertEquals("+2", shown.last())
    }
}
