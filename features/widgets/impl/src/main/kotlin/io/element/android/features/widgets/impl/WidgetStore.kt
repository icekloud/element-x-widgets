/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room data displayed by the widgets.
 */
data class WidgetRoom(
    val roomId: String,
    val name: String,
    val preview: String,
    val unread: Long,
    val avatarUrl: String?,
    val timestamp: Long?,
    // Element June: one-to-one room (bot conversation), used by the bot picker
    val direct: Boolean = false,
)

/**
 * Per widget configuration, stored only on the device.
 */
data class WidgetConfig(
    val sessionId: String,
    val roomIds: List<String>,
)

/**
 * Stores the widget configurations and a cache of the latest room data, so widgets can render without network.
 */
class WidgetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("june_widgets", Context.MODE_PRIVATE)

    fun getConfig(appWidgetId: Int): WidgetConfig? {
        val raw = prefs.getString(configKey(appWidgetId), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val rooms = json.getJSONArray("rooms")
            WidgetConfig(
                sessionId = json.getString("session"),
                roomIds = List(rooms.length()) { rooms.getString(it) },
            )
        }.getOrNull()
    }

    fun saveConfig(appWidgetId: Int, config: WidgetConfig) {
        val json = JSONObject()
            .put("session", config.sessionId)
            .put("rooms", JSONArray(config.roomIds))
        prefs.edit().putString(configKey(appWidgetId), json.toString()).apply()
    }

    fun deleteConfig(appWidgetId: Int) {
        prefs.edit().remove(configKey(appWidgetId)).apply()
    }

    fun getCachedRooms(sessionId: String): Map<String, WidgetRoom> {
        val raw = prefs.getString(cacheKey(sessionId), null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val o = array.getJSONObject(index)
                WidgetRoom(
                    roomId = o.getString("id"),
                    name = o.optString("name"),
                    preview = o.optString("preview"),
                    unread = o.optLong("unread"),
                    avatarUrl = o.optString("avatar").ifEmpty { null },
                    timestamp = if (o.has("ts")) o.getLong("ts") else null,
                    direct = o.optBoolean("dm"),
                )
            }.associateBy { it.roomId }
        }.getOrDefault(emptyMap())
    }

    fun saveCachedRooms(sessionId: String, rooms: Collection<WidgetRoom>) {
        val array = JSONArray()
        rooms.forEach { room ->
            val o = JSONObject()
                .put("id", room.roomId)
                .put("name", room.name)
                .put("preview", room.preview)
                .put("unread", room.unread)
                .put("avatar", room.avatarUrl.orEmpty())
                .put("dm", room.direct)
            room.timestamp?.let { o.put("ts", it) }
            array.put(o)
        }
        prefs.edit().putString(cacheKey(sessionId), array.toString()).apply()
    }

    /** Element June: rooms shown by the bot picker, in order; null means "not chosen yet" (use the default). */
    fun getBotPickerRoomIds(sessionId: String): List<String>? {
        val raw = prefs.getString(botPickerKey(sessionId), null) ?: return null
        return runCatching { JSONArray(raw).let { array -> List(array.length()) { array.getString(it) } } }.getOrNull()
    }

    fun saveBotPickerRoomIds(sessionId: String, roomIds: List<String>?) {
        if (roomIds == null) {
            prefs.edit().remove(botPickerKey(sessionId)).apply()
        } else {
            prefs.edit().putString(botPickerKey(sessionId), JSONArray(roomIds).toString()).apply()
        }
    }

    private fun botPickerKey(sessionId: String) = "botpicker_$sessionId"
    private fun configKey(appWidgetId: Int) = "config_$appWidgetId"
    private fun cacheKey(sessionId: String) = "cache_$sessionId"
}
