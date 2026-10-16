/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱15 B: the fixed places and the places that follow the badges in the bot picker. Made up rooms and times. */
class BotPickerSlotsTest : RobolectricTest() {
    private val now = 1_760_000_000_000L

    private fun room(
        name: String,
        unread: Long = 0,
        timestamp: Long? = null,
    ) = WidgetRoom(
        roomId = "!$name:test",
        name = name,
        preview = "",
        unread = unread,
        avatarUrl = null,
        timestamp = timestamp,
        direct = true,
    )

    private fun names(rooms: List<WidgetRoom>) = rooms.map { it.name }

    private fun mark(r: WidgetRoom, since: Long) = r.roomId to BotBadgeMark(r.unread, since)

    private val commander = room("커맨더", timestamp = now - 1_000)
    private val plan = room("플랜", timestamp = now - 2_000)
    private val memo = room("메모", timestamp = now - 3_000)
    private val research = room("리서치", timestamp = now - 4_000)
    private val briefing = room("브리핑", timestamp = now - 5_000)
    private val shopping = room("쇼핑", timestamp = now - 6_000)
    private val coder = room("코딩", timestamp = now - 7_000)
    private val ordered = listOf(commander, plan, memo, research, briefing)
    private val all = ordered + shopping + coder

    @Test
    fun `with no flexible places the picker is the order as it was`() {
        val loud = shopping.copy(unread = 3)
        val result = botPickerSlots(ordered, all - shopping + loud, BotPickerSlotCounts(fixed = 3, flexible = 0), emptyMap())
        assertEquals(ordered, result)
        assertEquals(ordered, botPickerSlots(ordered, all, BotPickerSlotCounts(fixed = 0, flexible = 0), emptyMap()))
    }

    @Test
    fun `the first rooms of the order stay, the badges fill the places below, the fixed ones are left out`() {
        val loudCoder = coder.copy(unread = 1)
        val loudShopping = shopping.copy(unread = 2)
        // The commander has a badge too but is fixed: it stays in its place and is not counted twice.
        val loudCommander = commander.copy(unread = 5)
        val order = listOf(loudCommander, plan, memo, research, briefing)
        val candidates = listOf(loudCommander, plan, memo, research, briefing, loudShopping, loudCoder)
        val marks = mapOf(mark(loudCoder, now - 100), mark(loudShopping, now - 500), mark(loudCommander, now - 10))
        val result = botPickerSlots(order, candidates, BotPickerSlotCounts(fixed = 3, flexible = 2), marks)
        assertEquals(listOf("커맨더", "플랜", "메모", "코딩", "쇼핑"), names(result))
    }

    @Test
    fun `when fewer rooms have a badge the rest go by their latest message`() {
        val loudCoder = coder.copy(unread = 1)
        val marks = mapOf(mark(loudCoder, now - 100))
        val result = botPickerSlots(ordered, all - coder + loudCoder, BotPickerSlotCounts(fixed = 3, flexible = 2), marks)
        // 코딩 (badge), then the newest message out of 리서치·브리핑·쇼핑
        assertEquals(listOf("커맨더", "플랜", "메모", "코딩", "리서치"), names(result))
    }

    @Test
    fun `a room read to 0 goes back to the message order`() {
        val result = botPickerSlots(ordered, all, BotPickerSlotCounts(fixed = 3, flexible = 2), mapOf(mark(coder, now - 100)))
        assertEquals(listOf("커맨더", "플랜", "메모", "리서치", "브리핑"), names(result))
    }

    @Test
    fun `equal times and rooms without a time keep a steady order by name`() {
        val a = room("가", timestamp = null)
        val b = room("나", timestamp = null)
        val c = room("다", timestamp = now)
        val d = room("라", timestamp = now)
        val result = botPickerSlots(emptyList(), listOf(b, d, a, c), BotPickerSlotCounts(fixed = 0, flexible = 4), emptyMap())
        assertEquals(listOf("다", "라", "가", "나"), names(result))
        val loudA = a.copy(unread = 1)
        val loudB = b.copy(unread = 1)
        val badged = botPickerSlots(emptyList(), listOf(loudB, loudA, c), BotPickerSlotCounts(fixed = 0, flexible = 2), emptyMap())
        assertEquals(listOf("가", "나"), names(badged))
    }

    @Test
    fun `more fixed places than the order has, and no fixed places at all`() {
        val loud = shopping.copy(unread = 1)
        val big = botPickerSlots(ordered, all - shopping + loud, BotPickerSlotCounts(fixed = 8, flexible = 2), emptyMap())
        assertEquals(names(ordered) + "쇼핑" + "코딩", names(big))
        val none = botPickerSlots(ordered, all - shopping + loud, BotPickerSlotCounts(fixed = 0, flexible = 2), emptyMap())
        assertEquals(listOf("쇼핑", "커맨더"), names(none))
    }

    @Test
    fun `not enough rooms leaves no empty place, the order behind the fixed ones may come back below`() {
        val result = botPickerSlots(ordered, emptyList(), BotPickerSlotCounts(fixed = 3, flexible = 5), emptyMap())
        assertEquals(listOf("커맨더", "플랜", "메모", "리서치", "브리핑"), names(result))
        assertEquals(emptyList<WidgetRoom>(), botPickerSlots(emptyList(), emptyList(), BotPickerSlotCounts(3, 2), emptyMap()))
    }

    @Test
    fun `a badge that came while the app was off gets the time of its latest message`() {
        val loud = shopping.copy(unread = 2, timestamp = now - 60_000)
        val marks = botBadgeMarks(emptyMap(), listOf(loud, commander), now)
        assertEquals(mapOf(loud.roomId to BotBadgeMark(2, now - 60_000)), marks)
    }

    @Test
    fun `a badge that goes up is stamped again, one that stays or goes down keeps its time, one read to 0 is forgotten`() {
        val first = botBadgeMarks(emptyMap(), listOf(shopping.copy(unread = 1, timestamp = now - 500)), now - 400)
        assertEquals(BotBadgeMark(1, now - 500), first[shopping.roomId])
        // One more message: the time moves on (never past now, even when the clocks differ).
        val up = botBadgeMarks(first, listOf(shopping.copy(unread = 2, timestamp = now + 5_000)), now)
        assertEquals(BotBadgeMark(2, now), up[shopping.roomId])
        val down = botBadgeMarks(up, listOf(shopping.copy(unread = 1, timestamp = now + 5_000)), now + 9_000)
        assertEquals(BotBadgeMark(1, now), down[shopping.roomId])
        val read = botBadgeMarks(down, listOf(shopping.copy(unread = 0)), now + 10_000)
        assertTrue(read.isEmpty())
        // A room with a badge but no time at all is stamped with now.
        val noTime = botBadgeMarks(emptyMap(), listOf(coder.copy(unread = 1, timestamp = null)), now)
        assertEquals(BotBadgeMark(1, now), noTime[coder.roomId])
    }

    @Test
    fun `the badge that came last wins the first flexible place, a room without a mark uses its latest message`() {
        val oldBadge = shopping.copy(unread = 4, timestamp = now - 10)
        val newBadge = coder.copy(unread = 1, timestamp = now - 20)
        val marks = mapOf(mark(oldBadge, now - 900), mark(newBadge, now - 30))
        val order = listOf(commander)
        val result = botPickerSlots(order, listOf(commander, oldBadge, newBadge), BotPickerSlotCounts(1, 2), marks)
        assertEquals(listOf("커맨더", "코딩", "쇼핑"), names(result))
        val unmarked = botPickerSlots(order, listOf(commander, oldBadge, newBadge), BotPickerSlotCounts(1, 2), emptyMap())
        assertEquals(listOf("커맨더", "쇼핑", "코딩"), names(unmarked))
    }

    @Test
    fun `the marks are stored and read back, broken text gives none`() {
        val marks = mapOf("!a:test" to BotBadgeMark(3, now), "!b:test" to BotBadgeMark(1, now - 5))
        assertEquals(marks, botBadgeMarksFromJson(botBadgeMarksToJson(marks)))
        assertEquals(emptyMap<String, BotBadgeMark>(), botBadgeMarksFromJson("{not json"))
        assertEquals(emptyMap<String, BotBadgeMark>(), botBadgeMarksFromJson(null))
        assertEquals(mapOf("!a:test" to BotBadgeMark(2, 7)), botBadgeMarksFromJson("""{"!a:test":[2,7],"!b:test":[1]}"""))
    }

    @Test
    fun `the fresh list replaces the first one once, unless the user already touched the screen`() {
        val first = listOf(commander, plan, shopping)
        val fresh = listOf(commander, plan, coder.copy(unread = 1))
        assertEquals(fresh, botPickerRefresh(first, fresh, touched = false))
        // Touched: the same places, the badges of the same rooms brought up to date.
        val freshBadge = listOf(commander.copy(unread = 2), coder.copy(unread = 1), plan)
        assertEquals(
            listOf(commander.copy(unread = 2), plan, shopping),
            botPickerRefresh(first, freshBadge, touched = true),
        )
        // Nothing shown yet (no cache): the fresh list even when touched.
        assertEquals(fresh, botPickerRefresh(emptyList(), fresh, touched = true))
    }
}
