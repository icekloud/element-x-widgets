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

    /** Element June: bot picker ring radius in dp. */
    fun getBotPickerRadius(): Float = prefs.getFloat("botpicker_radius", DEFAULT_BOT_PICKER_RADIUS)

    fun saveBotPickerRadius(dp: Float) {
        prefs.edit().putFloat("botpicker_radius", dp).apply()
    }

    /** Element June: bot picker centre as screen fractions, or null to use the touched icon. */
    fun getBotPickerCenter(): Pair<Float, Float>? {
        val raw = prefs.getString("botpicker_center", null) ?: return null
        val parts = raw.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    fun saveBotPickerCenter(center: Pair<Float, Float>?) {
        if (center == null) {
            prefs.edit().remove("botpicker_center").apply()
        } else {
            prefs.edit().putString("botpicker_center", "${center.first},${center.second}").apply()
        }
    }

    /** Element June: room the "커맨더 음성" shortcut records for. */
    fun getVoiceShortcutRoomId(): String = prefs.getString("voice_shortcut_room", null) ?: DEFAULT_VOICE_SHORTCUT_ROOM_ID

    fun saveVoiceShortcutRoomId(roomId: String?) {
        if (roomId.isNullOrBlank()) {
            prefs.edit().remove("voice_shortcut_room").apply()
        } else {
            prefs.edit().putString("voice_shortcut_room", roomId.trim()).apply()
        }
    }

    /** Element June: short beep when the voice shortcut starts recording. */
    fun isVoiceShortcutToneEnabled(): Boolean = prefs.getBoolean("voice_shortcut_tone", true)

    fun saveVoiceShortcutToneEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("voice_shortcut_tone", enabled).apply()
    }

    /** Element June: length at which the voice shortcut stops recording by itself, without sending. */
    fun getVoiceShortcutMaxSeconds(): Int = prefs.getInt("voice_shortcut_max_seconds", DEFAULT_VOICE_SHORTCUT_MAX_SECONDS)

    fun saveVoiceShortcutMaxSeconds(seconds: Int) {
        prefs.edit().putInt("voice_shortcut_max_seconds", seconds.coerceIn(MIN_VOICE_SHORTCUT_MAX_SECONDS, MAX_VOICE_SHORTCUT_MAX_SECONDS)).apply()
    }

    /** Element June: the recording of the voice shortcut waiting to be sent again, or null when there is none. */
    fun getVoiceShortcutPending(): PendingVoiceRecording? {
        val raw = prefs.getString("voice_shortcut_pending", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val levels = json.optJSONArray("waveform")
            PendingVoiceRecording(
                path = json.getString("path"),
                mimeType = json.getString("mime"),
                durationMs = json.getLong("duration"),
                waveform = if (levels == null) emptyList() else List(levels.length()) { levels.getDouble(it).toFloat() },
            )
        }.getOrNull()
    }

    fun saveVoiceShortcutPending(path: String, mimeType: String, durationMs: Long, waveform: List<Float>) {
        val json = JSONObject()
            .put("path", path)
            .put("mime", mimeType)
            .put("duration", durationMs)
            .put("waveform", JSONArray(waveform.map { it.toDouble() }))
        prefs.edit().putString("voice_shortcut_pending", json.toString()).apply()
    }

    fun saveVoiceShortcutPending(pending: PendingVoiceRecording?) {
        if (pending == null) {
            prefs.edit().remove("voice_shortcut_pending").apply()
        } else {
            saveVoiceShortcutPending(pending.path, pending.mimeType, pending.durationMs, pending.waveform)
        }
    }

    private fun botPickerKey(sessionId: String) = "botpicker_$sessionId"
    private fun configKey(appWidgetId: Int) = "config_$appWidgetId"
    private fun cacheKey(sessionId: String) = "cache_$sessionId"
}

/**
 * Element June: a voice shortcut recording kept on the device after a failed send.
 */
data class PendingVoiceRecording(
    val path: String,
    val mimeType: String,
    val durationMs: Long,
    val waveform: List<Float>,
)

const val DEFAULT_BOT_PICKER_RADIUS = 170f

/** Element June: the commander room, where the voice shortcut sends its recordings by default. */
const val DEFAULT_VOICE_SHORTCUT_ROOM_ID = "!prKGIRoFJdYIfmejlv:kloud123.i234.me:6881"
const val DEFAULT_VOICE_SHORTCUT_MAX_SECONDS = 300
const val MIN_VOICE_SHORTCUT_MAX_SECONDS = 30
const val MAX_VOICE_SHORTCUT_MAX_SECONDS = 1_800
