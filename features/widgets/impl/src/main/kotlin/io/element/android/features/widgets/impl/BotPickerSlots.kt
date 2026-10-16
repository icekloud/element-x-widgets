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
 * Element June 앱15 B: the bot picker shows the first [fixed] rooms of the chosen order as they are (위쪽 고정), then
 * [flexible] rooms picked from the rest (아래쪽 유동), the fixed ones left out. Settings shared by every session.
 */
data class BotPickerSlotCounts(val fixed: Int, val flexible: Int)

/** When the badge of one room went up, and the badge count seen then (앱15 B, 추가요청 3). */
data class BotBadgeMark(val count: Long, val sinceMs: Long)

const val DEFAULT_BOT_PICKER_FIXED = 3
const val DEFAULT_BOT_PICKER_FLEXIBLE = 2
const val MAX_BOT_PICKER_FIXED = 8
const val MAX_BOT_PICKER_FLEXIBLE = 5

/**
 * The rooms of the picker, in their places.
 *
 * - [ordered]: the "펼쳐질 순서" list (the user's choice, or the default list when none was made).
 * - [candidates]: every room the flexible places may take (the one-to-one bot rooms except home-ops).
 * - [marks]: when each room's badge went up ([botBadgeMarks]).
 *
 * With [BotPickerSlotCounts.flexible] 0 the result is [ordered] as it is (the picker before 앱15). Otherwise the first
 * fixed rooms of [ordered], then the flexible ones out of the candidates and the rest of [ordered]: rooms with a badge
 * first, the badge that came last first; then the others by their latest message; equal or missing times by name.
 */
fun botPickerSlots(
    ordered: List<WidgetRoom>,
    candidates: Collection<WidgetRoom>,
    counts: BotPickerSlotCounts,
    marks: Map<String, BotBadgeMark>,
): List<WidgetRoom> {
    if (counts.flexible <= 0) return ordered
    val fixed = ordered.take(counts.fixed.coerceIn(0, ordered.size))
    val taken = fixed.mapTo(mutableSetOf()) { it.roomId }
    val pool = (ordered.drop(fixed.size) + candidates).distinctBy { it.roomId }.filter { it.roomId !in taken }
    val (badged, quiet) = pool.partition { it.unread > 0 }
    val byBadge = badged.sortedWith(
        compareByDescending<WidgetRoom> { marks[it.roomId]?.sinceMs ?: it.timestamp ?: 0L }.then(byName),
    )
    val byMessage = quiet.sortedWith(compareByDescending<WidgetRoom> { it.timestamp ?: 0L }.then(byName))
    return fixed + (byBadge + byMessage).take(counts.flexible)
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
