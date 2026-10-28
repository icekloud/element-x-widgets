/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

/**
 * 앱34: what the last audio focus request answered. The code is the one of android.media.AudioManager, kept as is so it can be read on
 * the phone without a log.
 */
enum class JuneFocusOutcome(val code: String) {
    Granted("GRANTED"),
    Failed("FAILED"),
    Delayed("DELAYED"),
    NotAsked("없음"),
    Unknown("확인 불가"),
}

/** 앱34: how important the process is for the system while a 🔊 line is read (ActivityManager importance). */
enum class JuneAppState(val label: String) {
    Top("앱 앞"),
    ForegroundService("앱 서비스"),
    Visible("앱 보임"),
    Perceptible("앱 느껴짐"),
    Background("앱 뒤"),
    Cached("앱 캐시"),
    Unknown("앱 ?"),
}

// Values of android.app.ActivityManager.RunningAppProcessInfo IMPORTANCE_ constants, written here so the pure part needs no Android.
private const val IMPORTANCE_FOREGROUND = 100
private const val IMPORTANCE_FOREGROUND_SERVICE = 125
private const val IMPORTANCE_VISIBLE = 200
private const val IMPORTANCE_PERCEPTIBLE = 230
private const val IMPORTANCE_CANT_SAVE_STATE = 350

/** 앱34: the [JuneAppState] of an ActivityManager [importance] value. A value of 0 means it could not be read. */
internal fun juneAppStateOf(importance: Int): JuneAppState = when {
    importance <= 0 -> JuneAppState.Unknown
    importance <= IMPORTANCE_FOREGROUND -> JuneAppState.Top
    importance <= IMPORTANCE_FOREGROUND_SERVICE -> JuneAppState.ForegroundService
    importance <= IMPORTANCE_VISIBLE -> JuneAppState.Visible
    importance <= IMPORTANCE_PERCEPTIBLE -> JuneAppState.Perceptible
    importance <= IMPORTANCE_CANT_SAVE_STATE -> JuneAppState.Background
    else -> JuneAppState.Cached
}

/** 앱34: what the phone was doing when a 🔊 line was read. Never the message text. */
data class JunePhoneState(
    val appState: JuneAppState,
    val screenOn: Boolean,
    val musicActive: Boolean,
)

/**
 * 앱34: the diagnosis of one reading, shown under the setting so it can be told from the phone alone why a line was read without the
 * music being lowered, or why it was not read at all. Never the message text, the room or the account.
 */
data class JuneSpeakDiag(
    val focus: JuneFocusOutcome = JuneFocusOutcome.Unknown,
    val focusAttempts: Int = 0,
    val phone: JunePhoneState? = null,
    /** Time between the push arriving in the app and the line being read, or null when the push is not known (a reply, a test). */
    val pushToSpeakMillis: Long? = null,
    /** Time [JuneSpeechEngine.open] took, or null when the engine was not opened. */
    val engineOpenMillis: Long? = null,
)

private fun seconds(millis: Long): String = "%.1f".format(millis.coerceAtLeast(0L) / 1000.0)

/**
 * 앱34: the diagnosis of [diag] as at most two lines, for instance
 * "포커스 FAILED 3회 · 앱 뒤 · 화면 꺼짐 · 음악 재생" and "도착→읽기 1.2초 · 엔진 0.6초".
 * Empty when there is nothing worth showing.
 */
internal fun juneSpeakDiagLines(diag: JuneSpeakDiag?): List<String> {
    if (diag == null) return emptyList()
    val lines = mutableListOf<String>()
    val state = mutableListOf<String>()
    if (diag.focus != JuneFocusOutcome.NotAsked || diag.focusAttempts > 0) {
        val attempts = if (diag.focusAttempts > 0) " ${diag.focusAttempts}회" else ""
        state += "포커스 ${diag.focus.code}$attempts"
    }
    diag.phone?.let { phone ->
        state += phone.appState.label
        state += if (phone.screenOn) "화면 켜짐" else "화면 꺼짐"
        state += if (phone.musicActive) "음악 재생" else "음악 없음"
    }
    if (state.isNotEmpty()) lines += state.joinToString(" · ")
    val times = mutableListOf<String>()
    diag.pushToSpeakMillis?.let { times += "도착→읽기 ${seconds(it)}초" }
    diag.engineOpenMillis?.let { times += "엔진 ${seconds(it)}초" }
    if (times.isNotEmpty()) lines += times.joinToString(" · ")
    return lines
}

/**
 * 앱34: what is saved as the last outcome, for instance "읽음(포커스 없이) 19:36" followed by the diagnosis lines of [diag].
 * The first line is the one of the earlier versions, so an old saved value still reads the same way.
 */
internal fun juneSpeakStatusText(result: JuneSpeakResult, epochMillis: Long, diag: JuneSpeakDiag?): String =
    (listOf(juneSpeakResultText(result, epochMillis)) + juneSpeakDiagLines(diag)).joinToString("\n")

/**
 * 앱34: when each push arrived in the app, by event id, so the time it took until the 🔊 line was read can be shown. Only times are
 * kept, never the message. The oldest ones are dropped over [maxSize]; an id is only given back once.
 */
internal class JunePushArrivalLog(private val maxSize: Int = 50) {
    private val arrivals = LinkedHashMap<String, Long>()

    @Synchronized
    fun mark(eventId: String, epochMillis: Long) {
        arrivals.remove(eventId)
        arrivals[eventId] = epochMillis
        while (arrivals.size > maxSize) {
            arrivals.remove(arrivals.keys.first())
        }
    }

    /** The time the push of [eventId] arrived, and forgets it. Null when it was not seen (another device, a reply, a test). */
    @Synchronized
    fun take(eventId: String): Long? = arrivals.remove(eventId)

    @Synchronized
    fun clear() = arrivals.clear()

    @get:Synchronized
    val size: Int get() = arrivals.size
}

/** 앱34: the one arrival log of the process: the push handler writes in it, the reader reads from it. */
internal val JunePushArrivals = JunePushArrivalLog()
