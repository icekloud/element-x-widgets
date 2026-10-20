/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Element June 앱15 B: the bot picker keeps [fixed] rooms of the chosen order in their places and fills [flexible]
 * places with rooms picked from the rest, the fixed ones left out. Settings shared by every session.
 * 앱16 A: which places are fixed is told by the screen ([BotPickerFixedSide]), not by the order number.
 */
data class BotPickerSlotCounts(val fixed: Int, val flexible: Int)

/** 앱16 A (관리자: "위에서부터는 지금 화면 기준 위에서부터", "커스텀 가능하게"): the fixed places are the highest or the lowest ones on screen. */
enum class BotPickerFixedSide {
    Top,
    Bottom,
}

/** When the badge of one room went up, and the badge count seen then (앱15 B, 추가요청 3). */
data class BotBadgeMark(val count: Long, val sinceMs: Long)

const val DEFAULT_BOT_PICKER_FIXED = 3
const val DEFAULT_BOT_PICKER_FLEXIBLE = 2
const val MAX_BOT_PICKER_FIXED = 8
const val MAX_BOT_PICKER_FLEXIBLE = 5

/**
 * What the picker needs to place its rooms once it knows the screen (앱16 A): the order, the rooms the flexible places
 * may take, the counts, the side of the fixed places and the badge marks.
 *
 * - [ordered]: the "펼쳐질 순서" list (the user's choice, or the default list when none was made).
 * - [candidates]: every room the flexible places may take (the one-to-one bot rooms except home-ops).
 * - [marks]: when each room's badge went up ([botBadgeMarks]).
 */
data class BotPickerPlan(
    val ordered: List<WidgetRoom>,
    val candidates: List<WidgetRoom>,
    val counts: BotPickerSlotCounts,
    val side: BotPickerFixedSide,
    val marks: Map<String, BotBadgeMark>,
) {
    /** Every room the picker may show (for the avatars). */
    val rooms: List<WidgetRoom> get() = (ordered + candidates).distinctBy { it.roomId }

    /** The rooms in their places, [screenOrder] as in [botPickerSlots]. */
    fun place(screenOrder: (Int) -> List<Int>): List<WidgetRoom> = botPickerSlots(ordered, candidates, counts, marks, side, screenOrder)
}

/**
 * The rooms of the picker, index by index along the arc (index i is drawn on the i-th node).
 *
 * [screenOrder] gives, for a fan of k nodes, the node indexes from the top of the screen to the bottom
 * ([botPickerScreenOrder] over the drawn centres): the arc turns with the place of the icon, so the order number alone
 * never tells which node is up (앱16 A).
 *
 * With [BotPickerSlotCounts.flexible] 0 the result is [ordered] as it is (the picker before 앱15). Otherwise:
 * 1. The fixed rooms are the order entries that come on the [side] places (the highest ones for Top) when the first
 *    fixed + flexible entries of the order are laid on the arc — with the order as long as the fan (the usual case)
 *    they are exactly the entries drawn on those places; entries after them only ever take a flexible place.
 * 2. The fan has the fixed rooms plus up to [BotPickerSlotCounts.flexible] more; the fixed rooms take the [side]
 *    places, in their order along the arc.
 * 3. The other places, read from the top of the screen down, take the rooms out of the candidates and the rest of
 *    [ordered]: rooms with a badge first, the badge that came last first; then the others by their latest message;
 *    equal or missing times by name. A fixed room is never taken twice.
 */
fun botPickerSlots(
    ordered: List<WidgetRoom>,
    candidates: Collection<WidgetRoom>,
    counts: BotPickerSlotCounts,
    marks: Map<String, BotBadgeMark>,
    side: BotPickerFixedSide,
    screenOrder: (Int) -> List<Int>,
): List<WidgetRoom> {
    if (counts.flexible <= 0) return ordered
    val order = ordered.distinctBy { it.roomId }
    val all = (order + candidates).distinctBy { it.roomId }
    // The frame: the order entries a fan of fixed + flexible places would show; its [side] places are the fixed ones.
    val frame = order.size.coerceAtMost(counts.fixed.coerceAtLeast(0) + counts.flexible)
    val fixedCount = counts.fixed.coerceIn(0, frame)
    val flexCount = counts.flexible.coerceAtMost(all.size - fixedCount)
    val fixed = side.ends(validOrder(screenOrder, frame), fixedCount).sorted().map { order[it] }
    val taken = fixed.mapTo(mutableSetOf()) { it.roomId }
    val flexible = flexibleRanking(all.filter { it.roomId !in taken }, marks).take(flexCount)
    val size = fixed.size + flexible.size
    val places = validOrder(screenOrder, size)
    val fixedPlaces = side.ends(places, fixed.size)
    val flexPlaces = when (side) {
        BotPickerFixedSide.Top -> places.drop(fixed.size)
        BotPickerFixedSide.Bottom -> places.dropLast(fixed.size)
    }
    val out = arrayOfNulls<WidgetRoom>(size)
    fixedPlaces.sorted().forEachIndexed { i, place -> out[place] = fixed[i] }
    flexPlaces.forEachIndexed { i, place -> out[place] = flexible[i] }
    return out.filterNotNull()
}

/** The [count] places at this end of a top-to-bottom list. */
private fun BotPickerFixedSide.ends(topDown: List<Int>, count: Int): List<Int> = when (this) {
    BotPickerFixedSide.Top -> topDown.take(count)
    BotPickerFixedSide.Bottom -> topDown.takeLast(count)
}

/** [screenOrder] of [k] nodes as a full set of indexes (anything missing is put at the end, in index order). */
private fun validOrder(screenOrder: (Int) -> List<Int>, k: Int): List<Int> {
    val given = screenOrder(k).filter { it in 0 until k }.distinct()
    return given + (0 until k).filter { it !in given }
}

/** The flexible rooms, best first: a badge (latest badge first), then the latest message; then by name. */
private fun flexibleRanking(pool: List<WidgetRoom>, marks: Map<String, BotBadgeMark>): List<WidgetRoom> {
    val (badged, quiet) = pool.partition { it.unread > 0 }
    val byBadge = badged.sortedWith(
        compareByDescending<WidgetRoom> { marks[it.roomId]?.sinceMs ?: it.timestamp ?: 0L }.then(byName),
    )
    val byMessage = quiet.sortedWith(compareByDescending<WidgetRoom> { it.timestamp ?: 0L }.then(byName))
    return byBadge + byMessage
}

/** 앱16 A: the settings screen line that tells how the places are filled now. */
fun botPickerSlotsHint(counts: BotPickerSlotCounts, side: BotPickerFixedSide): String {
    if (counts.flexible <= 0) return "유동 0개: 펼쳐질 순서를 그대로 펼칩니다."
    val (fixedEnd, flexEnd) = when (side) {
        BotPickerFixedSide.Top -> "위쪽" to "아래쪽"
        BotPickerFixedSide.Bottom -> "아래쪽" to "위쪽"
    }
    val fixedPart = if (counts.fixed > 0) "화면 $fixedEnd ${counts.fixed}자리 고정, " else "고정 없음, "
    return fixedPart + "$flexEnd ${counts.flexible}자리는 알림이 최근에 온 순서입니다."
}

private val byName: Comparator<WidgetRoom> = compareBy<WidgetRoom> { it.name.lowercase() }.thenBy { it.roomId }

/**
 * The badge marks after a fresh room list came in at [nowMs]. A badge that went up is stamped with the time of the
 * room's latest message (a notification that came while the app was off gets its own time, not the time we noticed),
 * never later than now. A badge that stayed or went down (some messages read) keeps its stamp; a room read to 0 loses it.
 */
fun botBadgeMarks(old: Map<String, BotBadgeMark>, rooms: Collection<WidgetRoom>, nowMs: Long): Map<String, BotBadgeMark> {
    val out = mutableMapOf<String, BotBadgeMark>()
    rooms.filter { it.unread > 0 }.forEach { room ->
        val before = old[room.roomId]
        out[room.roomId] = if (before != null && room.unread <= before.count) {
            before.copy(count = room.unread)
        } else {
            BotBadgeMark(room.unread, minOf(nowMs, room.timestamp ?: nowMs))
        }
    }
    return out
}

/**
 * What the picker shows once the fresh list came in. Before the user touched the screen it is the fresh list; after
 * that the places stay as they are (the room under the finger never changes) and only the badges of the same rooms are
 * brought up to date.
 */
fun botPickerRefresh(shown: List<WidgetRoom>, fresh: List<WidgetRoom>, touched: Boolean): List<WidgetRoom> {
    if (!touched || shown.isEmpty()) return fresh
    val byId = fresh.associateBy { it.roomId }
    return shown.map { room -> byId[room.roomId]?.let { room.copy(unread = it.unread) } ?: room }
}

/** The badge marks as stored text (`{"<room>": [count, since], …}`). */
fun botBadgeMarksToJson(marks: Map<String, BotBadgeMark>): String {
    val o = JSONObject()
    marks.forEach { (id, mark) -> o.put(id, JSONArray().put(mark.count).put(mark.sinceMs)) }
    return o.toString()
}

/** Reads [botBadgeMarksToJson]; anything that cannot be read is left out. */
fun botBadgeMarksFromJson(raw: String?): Map<String, BotBadgeMark> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val o = try {
        JSONObject(raw)
    } catch (e: JSONException) {
        return emptyMap()
    }
    val out = mutableMapOf<String, BotBadgeMark>()
    o.keys().forEach { id ->
        val pair = o.optJSONArray(id) ?: return@forEach
        if (pair.length() == 2) out[id] = BotBadgeMark(pair.optLong(0), pair.optLong(1))
    }
    return out
}
