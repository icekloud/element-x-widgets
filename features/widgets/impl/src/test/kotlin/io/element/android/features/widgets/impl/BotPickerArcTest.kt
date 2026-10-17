/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱16 A: which picker node is high or low on the screen, for the icon at different places, and the fixed rooms put on
 * the high (or low) places. A made up phone screen of 1080 x 2340 pixels at 3 pixels per dp.
 */
class BotPickerArcTest {
    private val w = 1080f
    private val h = 2340f
    private val px: (Float) -> Float = { it * 3f }
    private val radiusDp = 170f

    private fun centers(x: Float, y: Float, n: Int) = botPickerArcCenters(botPickerArc(w, h, x, y, n, radiusDp, px), x, y)

    private fun topDown(x: Float, y: Float, n: Int) = botPickerScreenOrder(centers(x, y, n))

    private fun room(name: String, unread: Long = 0, timestamp: Long? = null) = WidgetRoom(
        roomId = "!$name:test",
        name = name,
        preview = "",
        unread = unread,
        avatarUrl = null,
        timestamp = timestamp,
        direct = true,
    )

    private val now = 1_760_000_000_000L

    // The order of the settings screen of the 관리자 picture: 1 브리핑 … 5 커맨더.
    private val briefing = room("브리핑", timestamp = now - 5_000)
    private val research = room("리서치", timestamp = now - 4_000)
    private val memo = room("메모", timestamp = now - 3_000)
    private val plan = room("플랜", timestamp = now - 2_000)
    private val commander = room("커맨더", timestamp = now - 1_000)
    private val strategist = room("전략가", timestamp = now - 500)
    private val shopping = room("쇼핑", timestamp = now - 9_000)
    private val ordered = listOf(briefing, research, memo, plan, commander)

    private fun slots(
        x: Float,
        y: Float,
        candidates: List<WidgetRoom>,
        counts: BotPickerSlotCounts = BotPickerSlotCounts(3, 2),
        side: BotPickerFixedSide = BotPickerFixedSide.Top,
        order: List<WidgetRoom> = ordered,
    ): List<WidgetRoom> = botPickerSlots(order, candidates, counts, emptyMap(), side) { k -> topDown(x, y, k) }

    /** The names from the top of the screen to the bottom, as the user reads the fan. */
    private fun readTopDown(x: Float, y: Float, rooms: List<WidgetRoom>) = topDown(x, y, rooms.size).map { rooms[it].name }

    @Test
    fun `the screen order sorts by height, then from the left, and keeps every index once`() {
        val c = listOf(10f to 50f, 30f to 10f, 5f to 10f, 0f to 90f)
        assertEquals(listOf(2, 1, 0, 3), botPickerScreenOrder(c))
        assertEquals(emptyList<Int>(), botPickerScreenOrder(emptyList()))
        // Less than a pixel apart counts as the same height: the left one first.
        assertEquals(listOf(1, 0), botPickerScreenOrder(listOf(20f to 100.2f, 10f to 99.9f)))
    }

    @Test
    fun `icon on the right side - the arc opens to the left, the first of the order is the lowest node`() {
        val x = w - 120f
        val y = h * 0.55f
        val order = topDown(x, y, 5)
        assertEquals(listOf(4, 3, 2, 1, 0), order)
        val cs = centers(x, y, 5)
        assertTrue(cs.all { it.first < x })
    }

    @Test
    fun `icon at the top left - the first of the order is the highest node`() {
        val order = topDown(150f, 300f, 5)
        assertEquals(0, order.first())
        assertEquals(4, order.last())
    }

    @Test
    fun `icon on the left side - the arc opens to the right, the first of the order is the highest node`() {
        val x = 120f
        val order = topDown(x, h * 0.55f, 5)
        assertEquals(listOf(0, 1, 2, 3, 4), order)
        assertTrue(centers(x, h * 0.55f, 5).all { it.first > x })
    }

    @Test
    fun `icon at the bottom middle - the middle of the arc is the highest, its two ends the lowest`() {
        val x = w / 2
        val y = h - 150f
        val order = topDown(x, y, 5)
        assertEquals(2, order.first())
        assertEquals(setOf(0, 4), order.takeLast(2).toSet())
        assertTrue(centers(x, y, 5).all { it.second < y })
    }

    @Test
    fun `icon at the top middle - the two ends are the highest, the middle the lowest`() {
        val x = w / 2
        val y = 150f
        val order = topDown(x, y, 5)
        assertEquals(setOf(0, 4), order.take(2).toSet())
        assertEquals(2, order.last())
    }

    @Test
    fun `the picture of the 관리자 - the 3 high places keep 커맨더 플랜 메모, the 2 low ones follow the badges`() {
        // The picture: the icon right of the middle, the fan to the left, 1 브리핑 at the bottom and 5 커맨더 on top.
        val x = w - 120f
        val y = h * 0.45f
        val loudStrategist = strategist.copy(unread = 1)
        val result = slots(x, y, ordered + loudStrategist + shopping)
        // 전략가 by its badge, then 리서치 (a newer message than 브리핑).
        assertEquals(listOf("커맨더", "플랜", "메모", "전략가", "리서치"), readTopDown(x, y, result))
        // The fixed ones stay on their own places of the order (index 2, 3, 4), nothing else moved there.
        assertEquals(listOf("메모", "플랜", "커맨더"), result.drop(2).map { it.name })
    }

    @Test
    fun `fixed at the bottom - the 3 low places keep 메모 리서치 브리핑, the 2 high ones follow the badges`() {
        val x = w - 120f
        val y = h * 0.45f
        val loudShopping = shopping.copy(unread = 2)
        val result = slots(x, y, ordered + strategist + loudShopping, side = BotPickerFixedSide.Bottom)
        assertEquals(listOf("쇼핑", "전략가", "메모", "리서치", "브리핑"), readTopDown(x, y, result))
    }

    @Test
    fun `the same settings with the icon on the left keep the high places, which are now the first of the order`() {
        val x = 120f
        val y = h * 0.55f
        val result = slots(x, y, ordered + strategist)
        assertEquals(listOf("브리핑", "리서치", "메모"), readTopDown(x, y, result).take(3))
        assertEquals(listOf("전략가", "커맨더"), readTopDown(x, y, result).drop(3))
    }

    @Test
    fun `a fixed room never comes back on a flexible place, whatever its badge`() {
        val x = w - 120f
        val y = h * 0.45f
        val loudCommander = commander.copy(unread = 9)
        val loudPlan = plan.copy(unread = 3)
        val order = listOf(briefing, research, memo, loudPlan, loudCommander)
        val result = slots(x, y, order + strategist + shopping, order = order)
        assertEquals(result.map { it.roomId }.distinct(), result.map { it.roomId })
        assertEquals(listOf("커맨더", "플랜", "메모", "전략가", "리서치"), readTopDown(x, y, result))
    }

    @Test
    fun `other counts - 1 fixed and 4 flexible, 5 fixed and 1 flexible, 0 fixed`() {
        val x = w - 120f
        val y = h * 0.45f
        val all = ordered + strategist + shopping
        val one = slots(x, y, all, BotPickerSlotCounts(1, 4))
        assertEquals("커맨더", readTopDown(x, y, one).first())
        assertEquals(5, one.size)
        val five = slots(x, y, all, BotPickerSlotCounts(5, 1))
        assertEquals(listOf("커맨더", "플랜", "메모", "리서치", "브리핑", "전략가"), readTopDown(x, y, five))
        val none = slots(x, y, all, BotPickerSlotCounts(0, 2))
        assertEquals(listOf("전략가", "커맨더"), readTopDown(x, y, none))
    }

    @Test
    fun `no flexible place keeps the order as it was, wherever the icon is`() {
        assertEquals(ordered, slots(w - 120f, h * 0.45f, ordered + strategist, BotPickerSlotCounts(3, 0)))
        assertEquals(ordered, slots(120f, 300f, ordered + strategist, BotPickerSlotCounts(3, 0), BotPickerFixedSide.Bottom))
    }

    @Test
    fun `an order longer or shorter than the fan`() {
        val x = w - 120f
        val y = h * 0.45f
        // 7 in the order, 3 + 2 shown: the first 5 laid on the fan give the fixed ones (the 3 high places), the 2 after
        // them only compete for the flexible places (전략가 has the newest message).
        val long = ordered + strategist + shopping
        val longResult = slots(x, y, long, order = long)
        assertEquals(listOf("커맨더", "플랜", "메모", "전략가", "리서치"), readTopDown(x, y, longResult))
        // 2 in the order: both are fixed (on the 2 high places), 2 flexible below.
        val short = listOf(briefing, research)
        val shortResult = slots(x, y, short + memo + plan, order = short)
        assertEquals(listOf("리서치", "브리핑", "플랜", "메모"), readTopDown(x, y, shortResult))
        // Fewer rooms than places: no empty place.
        assertEquals(listOf("리서치", "브리핑"), readTopDown(x, y, slots(x, y, short, order = short)))
    }
}
