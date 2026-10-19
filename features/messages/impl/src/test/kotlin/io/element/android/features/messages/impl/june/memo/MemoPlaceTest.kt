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
    fun `a restaurant shows the fork, its name and introduction, the address on the second line`() {
        val p = memoPlaceLine(header(place()))!!
        assertEquals("🍴 가짜 꼬치집 · 꼬치튀김 전문점", p.title)
        assertEquals("가짜시 가짜구 1-2-3", p.address)
        assertEquals(map, p.map)
        assertEquals("🍴 가짜 꼬치집 · 꼬치튀김 전문점, 가짜시 가짜구 1-2-3", p.description)
    }

    @Test
    fun `another kind shows the pin, a phone position adds (근처)`() {
        assertEquals("📍 가짜 탑 · 전망대", memoPlaceLine(header(place(name = "가짜 탑", kind = "관광", intro = "전망대")))!!.title)
        assertEquals("🍴 가짜 꼬치집 (근처) · 꼬치튀김 전문점", memoPlaceLine(header(place(approx = true)))!!.title)
        assertEquals("🍴 가짜 꼬치집 · 꼬치튀김 전문점", memoPlaceLine(header(place(approx = false)))!!.title)
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
        assertEquals("🍴 가짜 꼬치집 · 꼬치튀김 전문점", memoPlaceLine(header(p))!!.title)
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
