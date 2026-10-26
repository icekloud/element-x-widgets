/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.june

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import io.element.android.libraries.core.data.tryOrNull

/**
 * Element June: user customisation kept on the device only (colours of the app and the widgets,
 * content and order of the composer "+" menu). Values are observable Compose state, so a change made
 * in the settings screen is applied to the running app immediately.
 */
object JuneSettings {
    private const val PREFS = "june_settings"
    private const val KEY_MENU_ORDER = "composer_menu_order"
    private const val KEY_MENU_HIDDEN = "composer_menu_hidden"
    private const val KEY_QUICK = "quick_commands"
    private const val KEY_APERTURE_ROOMS = "aperture_rooms"
    private const val KEY_BT_SPEAK = "bt_speak"
    private const val KEY_BT_SPEAK_STATUS = "bt_speak_status"
    private const val KEY_BT_SPEAK_ENGINE = "bt_speak_engine"
    private const val KEY_BT_SPEAK_SAMSUNG_VOICE = "bt_speak_samsung_voice"
    private const val KEY_BT_SPEAK_VOICE_NOTE = "bt_speak_voice_note"
    private const val KEY_MEMO_ROOM = "memo_room_id"
    private const val KEY_MEMO_FAKE = "memo_fake_data"
    private const val KEY_MEMO_BUTTON_SPOT = "memo_button_spot"

    /** 앱32 (추가요청 3): where the floating proposal button of the memo screen sits. */
    private const val KEY_MEMO_PANEL_FAB_SPOT = "memo_panel_fab_spot"
    private const val KEY_MEMO_DIAG = "memo_diag"

    /** Default room name keywords that get the Aperture (GLaDOS) chat theme. */
    const val DEFAULT_APERTURE_ROOMS = "GLaDOS, 글라도스"
    private const val QUICK_SEPARATOR = "\u001F"

    /** Default quick commands of the room top bar. */
    val DEFAULT_QUICK_COMMANDS = listOf(
        "지금 하는 작업 진행 상황 보고",
        "앞으로 해야 할 일들 정리",
        "남아 있는 결정사항 정리",
    )

    /** Customisable colours. The defaults give a light lavender look. */
    enum class ColorSlot(val key: String, val label: String, val defaultArgb: Long, val lightOnly: Boolean) {
        Accent("accent", "강조색 (버튼·아이콘·링크)", 0xFF7E57C2, false),
        Background("background", "화면 배경", 0xFFFAF7FF, true),
        TopBar("top_bar", "채팅방 상단 바", 0xFFF1EBFB, true),
        BubbleBorder("bubble_border", "말풍선 테두리", 0xFFD9CCF2, true),
        OwnBubble("own_bubble", "내 말풍선 배경", 0xFFF1EBFB, true),
        OtherBubble("other_bubble", "받은 말풍선 배경", 0x00000000, true),
        WidgetBackground("widget_background", "위젯 배경", 0xFFF6F1FF, false),
        WidgetText("widget_text", "위젯 글자", 0xFF2E2440, false),
        WidgetBadge("widget_badge", "위젯 숫자 배지", 0xFF7E57C2, false),
    }

    /** Items of the composer "+" menu, in their default order. */
    enum class ComposerMenuItem(val key: String, val label: String) {
        Gallery("gallery", "사진·동영상 보관함"),
        Files("files", "파일"),
        CameraPhoto("camera_photo", "사진 촬영"),
        CameraVideo("camera_video", "동영상 촬영"),
        Location("location", "위치"),
        Poll("poll", "투표"),
        TextFormatting("text_formatting", "텍스트 서식"),
    }

    /**
     * A whole number setting with a default and an allowed range ([min]..[max], moved by [step]). A saved value outside
     * the range, or saved with another type, is replaced by [default]. More gesture settings can be added the same way.
     */
    class IntSetting(val key: String, val default: Int, val min: Int, val max: Int, val step: Int = 1) {
        init {
            require(min <= default && default <= max && step > 0)
        }

        /** The value to use for a saved [raw] value: missing or outside the range gives the default. */
        fun sanitize(raw: Int?): Int = if (raw == null || raw < min || raw > max) default else raw

        /** A value picked by the user (for instance on a slider), rounded to [step] and kept in the range. */
        fun clamp(value: Float): Int {
            if (value.isNaN()) return default
            val inRange = value.coerceIn(min.toFloat(), max.toFloat())
            val rounded = min + Math.round((inRange - min) / step) * step
            return rounded.coerceIn(min, max)
        }

        /** The saved value, or null when there is none or it is broken (outside the range, saved with another type). */
        fun read(prefs: SharedPreferences): Int? {
            val raw = tryOrNull { if (prefs.contains(key)) prefs.getInt(key, default) else null } ?: return null
            return raw.takeIf { it in min..max }
        }

        fun write(prefs: SharedPreferences, value: Int) {
            prefs.edit().putInt(key, clamp(value.toFloat())).apply()
        }

        /** Position (0..1) of [value] on a slider going from [min] to [max]. */
        fun toSlider(value: Int): Float = (sanitize(value) - min).toFloat() / (max - min)

        /** The value for a slider position (0..1), rounded to [step]. */
        fun fromSlider(position: Float): Int = clamp(min + position.coerceIn(0f, 1f) * (max - min))

        /** Number of stops between both ends of the slider, one for each [step]. */
        val sliderSteps: Int get() = (max - min) / step - 1
    }

    /** How far (percent of the row width) a queued message has to be pushed to the left to steer it. */
    val SWIPE_STEER_PERCENT = IntSetting(key = "swipe_steer_percent", default = 40, min = 20, max = 70, step = 5)

    private val INT_SETTINGS = listOf(SWIPE_STEER_PERCENT)

    @Volatile private var loaded = false
    private val ints = mutableStateMapOf<String, Int>()
    private val colors = mutableStateMapOf<ColorSlot, Color>()
    private val menuOrder = mutableStateListOf<ComposerMenuItem>()
    private val menuHidden = mutableStateListOf<ComposerMenuItem>()
    private val quick = mutableStateListOf<String>()
    private val apertureRooms = mutableStateOf(DEFAULT_APERTURE_ROOMS)
    private val btSpeak = mutableStateOf(true)
    private val btSpeakStatus = mutableStateOf<String?>(null)
    private val btSpeakEngine = mutableStateOf(JuneVoiceEngineChoice.Phone)
    private val btSpeakSamsungVoice = mutableStateOf(JuneSamsungVoiceChoice.Voice1)
    private val btSpeakVoiceNote = mutableStateOf<String?>(null)
    private val memoRoomId = mutableStateOf<String?>(null)
    private val memoFake = mutableStateOf(false)
    private val memoButtonSpot = mutableStateOf<JuneMemoButtonSpot?>(null)
    private val memoPanelFabSpot = mutableStateOf<JuneMemoButtonSpot?>(null)
    private val memoDiag = mutableStateOf<String?>(null)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(context)
            ColorSlot.entries.forEach { slot ->
                if (p.contains(slot.key)) colors[slot] = Color(p.getInt(slot.key, 0))
            }
            val byKey = ComposerMenuItem.entries.associateBy { it.key }
            val saved = p.getString(KEY_MENU_ORDER, null).orEmpty().split(',').mapNotNull { byKey[it] }
            menuOrder.clear()
            menuOrder.addAll(saved + ComposerMenuItem.entries.filter { it !in saved })
            menuHidden.clear()
            menuHidden.addAll(p.getString(KEY_MENU_HIDDEN, null).orEmpty().split(',').mapNotNull { byKey[it] })
            quick.clear()
            val savedQuick = p.getString(KEY_QUICK, null)
            quick.addAll(if (savedQuick == null) DEFAULT_QUICK_COMMANDS else savedQuick.split(QUICK_SEPARATOR).filter { it.isNotBlank() })
            apertureRooms.value = p.getString(KEY_APERTURE_ROOMS, null) ?: DEFAULT_APERTURE_ROOMS
            INT_SETTINGS.forEach { setting ->
                setting.read(p)?.let { ints[setting.key] = it }
            }
            btSpeak.value = tryOrNull { p.getBoolean(KEY_BT_SPEAK, true) } ?: true
            btSpeakStatus.value = tryOrNull { p.getString(KEY_BT_SPEAK_STATUS, null) }
            btSpeakEngine.value = JuneVoiceEngineChoice.parse(tryOrNull { p.getString(KEY_BT_SPEAK_ENGINE, null) })
            btSpeakSamsungVoice.value = JuneSamsungVoiceChoice.parse(tryOrNull { p.getString(KEY_BT_SPEAK_SAMSUNG_VOICE, null) })
            btSpeakVoiceNote.value = tryOrNull { p.getString(KEY_BT_SPEAK_VOICE_NOTE, null) }
            memoRoomId.value = tryOrNull { p.getString(KEY_MEMO_ROOM, null) }?.takeIf { it.isNotBlank() }
            memoFake.value = tryOrNull { p.getBoolean(KEY_MEMO_FAKE, false) } ?: false
            memoButtonSpot.value = JuneMemoButtonSpot.parse(tryOrNull { p.getString(KEY_MEMO_BUTTON_SPOT, null) })
            memoPanelFabSpot.value = JuneMemoButtonSpot.parse(tryOrNull { p.getString(KEY_MEMO_PANEL_FAB_SPOT, null) })
            memoDiag.value = tryOrNull { p.getString(KEY_MEMO_DIAG, null) }
            loaded = true
        }
    }

    fun colorOf(context: Context, slot: ColorSlot): Color {
        ensureLoaded(context)
        return colors[slot] ?: Color(slot.defaultArgb)
    }

    @Composable
    fun color(slot: ColorSlot): Color = colorOf(LocalContext.current, slot)

    fun isCustomised(slot: ColorSlot): Boolean = colors.containsKey(slot)

    fun setColor(context: Context, slot: ColorSlot, color: Color) {
        ensureLoaded(context)
        colors[slot] = color
        prefs(context).edit().putInt(slot.key, color.toArgb()).apply()
    }

    fun resetColor(context: Context, slot: ColorSlot) {
        ensureLoaded(context)
        colors.remove(slot)
        prefs(context).edit().remove(slot.key).apply()
    }

    fun resetAllColors(context: Context) {
        ColorSlot.entries.forEach { resetColor(context, it) }
    }

    /** All menu items in the user's order (hidden ones included), for the settings screen. */
    fun menuItems(context: Context): List<ComposerMenuItem> {
        ensureLoaded(context)
        return menuOrder.toList()
    }

    fun isHidden(item: ComposerMenuItem): Boolean = item in menuHidden

    /** Visible menu items in the user's order, for the composer "+" menu. */
    @Composable
    fun visibleComposerMenu(): List<ComposerMenuItem> {
        ensureLoaded(LocalContext.current)
        return menuOrder.filter { it !in menuHidden }
    }

    fun moveMenuItem(context: Context, item: ComposerMenuItem, delta: Int) {
        ensureLoaded(context)
        val from = menuOrder.indexOf(item)
        val to = (from + delta).coerceIn(0, menuOrder.size - 1)
        if (from < 0 || from == to) return
        menuOrder.removeAt(from)
        menuOrder.add(to, item)
        saveMenu(context)
    }

    fun setMenuItemVisible(context: Context, item: ComposerMenuItem, visible: Boolean) {
        ensureLoaded(context)
        if (visible) menuHidden.remove(item) else if (item !in menuHidden) menuHidden.add(item)
        saveMenu(context)
    }

    fun resetMenu(context: Context) {
        ensureLoaded(context)
        menuOrder.clear()
        menuOrder.addAll(ComposerMenuItem.entries)
        menuHidden.clear()
        saveMenu(context)
    }

    private fun saveMenu(context: Context) {
        prefs(context).edit()
            .putString(KEY_MENU_ORDER, menuOrder.joinToString(",") { it.key })
            .putString(KEY_MENU_HIDDEN, menuHidden.joinToString(",") { it.key })
            .apply()
    }

    /** Quick commands in the user's order (observable). */
    fun quickCommands(context: Context): List<String> {
        ensureLoaded(context)
        return quick.toList()
    }

    fun addQuickCommand(context: Context, text: String) {
        ensureLoaded(context)
        val t = text.trim()
        if (t.isEmpty()) return
        quick.add(t)
        saveQuick(context)
    }

    fun updateQuickCommand(context: Context, index: Int, text: String) {
        ensureLoaded(context)
        val t = text.trim()
        if (index !in quick.indices || t.isEmpty()) return
        quick[index] = t
        saveQuick(context)
    }

    fun removeQuickCommand(context: Context, index: Int) {
        ensureLoaded(context)
        if (index in quick.indices) quick.removeAt(index)
        saveQuick(context)
    }

    fun moveQuickCommand(context: Context, index: Int, delta: Int) {
        ensureLoaded(context)
        val to = (index + delta).coerceIn(0, quick.size - 1)
        if (index !in quick.indices || index == to) return
        val item = quick.removeAt(index)
        quick.add(to, item)
        saveQuick(context)
    }

    fun resetQuickCommands(context: Context) {
        ensureLoaded(context)
        quick.clear()
        quick.addAll(DEFAULT_QUICK_COMMANDS)
        saveQuick(context)
    }

    private fun saveQuick(context: Context) {
        prefs(context).edit().putString(KEY_QUICK, quick.joinToString(QUICK_SEPARATOR)).apply()
    }

    /** Comma separated room name keywords that use the Aperture chat theme (observable). */
    fun apertureRooms(context: Context): String {
        ensureLoaded(context)
        return apertureRooms.value
    }

    fun setApertureRooms(context: Context, text: String) {
        ensureLoaded(context)
        apertureRooms.value = text
        prefs(context).edit().putString(KEY_APERTURE_ROOMS, text).apply()
    }

    /** Current value of [setting] (observable): the saved one, or the default. */
    fun intValue(context: Context, setting: IntSetting): Int {
        ensureLoaded(context)
        return setting.sanitize(ints[setting.key])
    }

    fun isCustomised(setting: IntSetting): Boolean = ints.containsKey(setting.key)

    fun setInt(context: Context, setting: IntSetting, value: Int) {
        ensureLoaded(context)
        val v = setting.clamp(value.toFloat())
        ints[setting.key] = v
        setting.write(prefs(context), v)
    }

    fun resetInt(context: Context, setting: IntSetting) {
        ensureLoaded(context)
        ints.remove(setting.key)
        prefs(context).edit().remove(setting.key).apply()
    }

    /** Part of the row width (0.2..0.7) a queued message has to be pushed to the left to steer it (observable). */
    fun swipeSteerFraction(context: Context): Float = intValue(context, SWIPE_STEER_PERCENT) / 100f

    /** Whether the 🔊 line of incoming messages is read aloud when a Bluetooth audio output is connected (observable, on by default). */
    fun btSpeakEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return btSpeak.value
    }

    fun setBtSpeakEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        btSpeak.value = enabled
        prefs(context).edit().putBoolean(KEY_BT_SPEAK, enabled).apply()
    }

    /** Outcome of the last attempt to read a 🔊 line, for instance "읽음 19:36" (observable, null when none yet). Never the message text. */
    fun btSpeakStatus(context: Context): String? {
        ensureLoaded(context)
        return btSpeakStatus.value
    }

    fun setBtSpeakStatus(context: Context, status: String?) {
        ensureLoaded(context)
        btSpeakStatus.value = status
        prefs(context).edit().putString(KEY_BT_SPEAK_STATUS, status).apply()
    }

    /**
     * 앱27: which voice reads the 🔊 lines (observable). The phone engine by default: the Samsung voice of the app is only used once it
     * has been chosen in the settings. A saved choice of the Supertonic voice, taken out of the app, reads as the Samsung voice.
     */
    fun btSpeakEngine(context: Context): JuneVoiceEngineChoice {
        ensureLoaded(context)
        return btSpeakEngine.value
    }

    fun setBtSpeakEngine(context: Context, choice: JuneVoiceEngineChoice) {
        ensureLoaded(context)
        btSpeakEngine.value = choice
        prefs(context).edit().putString(KEY_BT_SPEAK_ENGINE, choice.key).apply()
    }

    /**
     * 앱32: which of the four Korean voices of the Samsung engine reads the 🔊 lines (observable), used when that engine is the
     * chosen one. The first voice (the one the app read with before) by default.
     */
    fun samsungVoice(context: Context): JuneSamsungVoiceChoice {
        ensureLoaded(context)
        return btSpeakSamsungVoice.value
    }

    fun setSamsungVoice(context: Context, choice: JuneSamsungVoiceChoice) {
        ensureLoaded(context)
        btSpeakSamsungVoice.value = choice
        prefs(context).edit().putString(KEY_BT_SPEAK_SAMSUNG_VOICE, choice.key).apply()
    }

    /**
     * 앱27: what the voice of the app did with the last line, for instance "삼성 · 42자 · 생성 6.1초 · 재생 5.8초" or
     * "폰 기본 대체(시간 초과)" (observable, null when none yet). Never the message text.
     */
    fun btSpeakVoiceNote(context: Context): String? {
        ensureLoaded(context)
        return btSpeakVoiceNote.value
    }

    fun setBtSpeakVoiceNote(context: Context, note: String?) {
        ensureLoaded(context)
        btSpeakVoiceNote.value = note
        prefs(context).edit().putString(KEY_BT_SPEAK_VOICE_NOTE, note).apply()
    }

    /** Room shown as the memo bot screen (기록 · 폴더 · 검색) instead of the chat timeline, null = none (observable). */
    fun memoRoomId(context: Context): String? {
        ensureLoaded(context)
        return memoRoomId.value
    }

    fun setMemoRoomId(context: Context, roomId: String?) {
        ensureLoaded(context)
        val id = roomId?.takeIf { it.isNotBlank() }
        memoRoomId.value = id
        prefs(context).edit().apply { if (id == null) remove(KEY_MEMO_ROOM) else putString(KEY_MEMO_ROOM, id) }.apply()
    }

    fun isMemoRoom(context: Context, roomId: String): Boolean = memoRoomId(context) == roomId

    /** The memo screen shows built-in sample data instead of asking the memo bot (to try the screens without the server). */
    fun memoFakeData(context: Context): Boolean {
        ensureLoaded(context)
        return memoFake.value
    }

    fun setMemoFakeData(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        memoFake.value = enabled
        prefs(context).edit().putBoolean(KEY_MEMO_FAKE, enabled).apply()
    }

    /**
     * 앱19 추가요청 1: where the memo button of the home screen was moved to (observable), null = the default place
     * (bottom centre). A broken saved value reads as null.
     */
    fun memoButtonSpot(context: Context): JuneMemoButtonSpot? {
        ensureLoaded(context)
        return memoButtonSpot.value
    }

    fun setMemoButtonSpot(context: Context, spot: JuneMemoButtonSpot) {
        ensureLoaded(context)
        val kept = JuneMemoButtonSpot(spot.x.coerceIn(0f, 1f), spot.y.coerceIn(0f, 1f))
        memoButtonSpot.value = kept
        prefs(context).edit().putString(KEY_MEMO_BUTTON_SPOT, kept.encode()).apply()
    }

    /**
     * 앱32 (추가요청 3): where the floating proposal button of the memo screen was moved to (observable), null = the
     * default place. Kept as parts of the screen, like the home screen button, so it stays on the screen when the
     * phone turns. A broken saved value reads as null.
     */
    fun memoPanelFabSpot(context: Context): JuneMemoButtonSpot? {
        ensureLoaded(context)
        return memoPanelFabSpot.value
    }

    fun setMemoPanelFabSpot(context: Context, spot: JuneMemoButtonSpot) {
        ensureLoaded(context)
        val kept = JuneMemoButtonSpot(spot.x.coerceIn(0f, 1f), spot.y.coerceIn(0f, 1f))
        memoPanelFabSpot.value = kept
        prefs(context).edit().putString(KEY_MEMO_PANEL_FAB_SPOT, kept.encode()).apply()
    }

    /** 추가요청 2: "메모 버튼 위치 초기화" of the settings screen: the saved spot goes, the button is back at the bottom centre. */
    fun resetMemoButtonSpot(context: Context) {
        ensureLoaded(context)
        memoButtonSpot.value = null
        prefs(context).edit().remove(KEY_MEMO_BUTTON_SPOT).apply()
    }

    /**
     * 앱28 추가요청 1: the photo upload and proposal receive lines of the memo sync, as the sync made them last
     * (observable, null when no sync ran yet). Counts, times and short reasons only: never a path, an address or an id.
     */
    fun memoDiag(context: Context): String? {
        ensureLoaded(context)
        return memoDiag.value
    }

    fun setMemoDiag(context: Context, text: String?) {
        ensureLoaded(context)
        if (memoDiag.value == text) return
        memoDiag.value = text
        prefs(context).edit().putString(KEY_MEMO_DIAG, text).apply()
    }

    /** True if [roomName] contains one of the Aperture keywords (case insensitive). */
    fun isApertureRoom(context: Context, roomName: String?): Boolean {
        if (roomName.isNullOrBlank()) return false
        return apertureRooms(context).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .any { roomName.contains(it, ignoreCase = true) }
    }
}
