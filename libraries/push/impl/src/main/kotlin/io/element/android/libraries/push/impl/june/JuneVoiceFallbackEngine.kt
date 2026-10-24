/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.june.JuneVoiceEngineChoice
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

private const val TAG = "JuneVoice"

/**
 * Longest time the built-in voice may spend on one line. The line timeout of the reader is 60 seconds, so what is left after this is
 * enough for the phone engine to read the same line.
 */
internal val JUNE_VOICE_LINE_TIMEOUT = 45.seconds

/** What the built-in voice did with the last line, to show the speed on the phone. */
data class JuneVoiceLineStats(
    val characters: Int,
    val generationMillis: Long,
    val audioMillis: Long,
)

/** An engine that can tell how long the last line took, so the speed can be shown in the settings. */
interface JuneVoiceStatsSource {
    val lastLineStats: JuneVoiceLineStats?
}

/**
 * Element June: reads the 🔊 lines with the engine chosen in the settings, and falls back to the phone engine for the lines the
 * built-in voice cannot read (model missing, out of memory, failure, too slow). A failure never changes the setting: the built-in
 * voice is tried again on the next line.
 */
internal class JuneVoiceFallbackEngine(
    private val builtIn: JuneSpeechEngine,
    private val phone: JuneSpeechEngine,
    private val chosenEngine: () -> JuneVoiceEngineChoice,
    private val onNote: (String) -> Unit,
) : JuneSpeechEngine {
    /** True while the built-in voice is the chosen one and its model could be opened. */
    private var builtInOpen = false
    private var phoneOpen = false

    /** The audio state and the audio focus are the ones of the phone: both engines play on the same output. */
    override fun currentBlock(): JuneSpeakBlock? = phone.currentBlock()

    override fun requestFocus(): Boolean = phone.requestFocus()

    override suspend fun open(): JuneSpeakResult? {
        if (chosenEngine() == JuneVoiceEngineChoice.BuiltIn) {
            val failure = runCatchingExceptions { builtIn.open() }.getOrElse { error ->
                Timber.tag(TAG).e(error, "The built-in voice could not be opened")
                JuneSpeakResult.EngineNotReady
            }
            if (failure == null) {
                builtInOpen = true
                return null
            }
            // The phone engine reads everything until the next time the lines are read
            note("폰 기본 대체(내장 준비 실패)")
        }
        return openPhone()
    }

    private suspend fun openPhone(): JuneSpeakResult? {
        if (phoneOpen) return null
        val failure = runCatchingExceptions { phone.open() }.getOrElse { error ->
            Timber.tag(TAG).e(error, "The phone engine could not be opened")
            JuneSpeakResult.EngineNotReady
        }
        phoneOpen = failure == null
        return failure
    }

    override suspend fun speak(text: String): Boolean {
        if (builtInOpen) {
            val start = System.currentTimeMillis()
            val spoken = withTimeoutOrNull(JUNE_VOICE_LINE_TIMEOUT) {
                runCatchingExceptions { builtIn.speak(text) }.getOrElse { error ->
                    Timber.tag(TAG).e(error, "The built-in voice could not read the line")
                    false
                }
            }
            if (spoken == true) {
                note(readNote(text, System.currentTimeMillis() - start))
                return true
            }
            note(if (spoken == null) "폰 기본 대체(시간 초과)" else "폰 기본 대체(생성 실패)")
        }
        // The line is never lost: the phone engine reads it
        if (openPhone() != null) return false
        return runCatchingExceptions { phone.speak(text) }.getOrDefault(false)
    }

    /** For instance "내장 · 42자 · 생성 6.1초 · 재생 5.8초", or the same without the audio length when the engine does not give it. */
    private fun readNote(text: String, elapsedMillis: Long): String {
        val stats = (builtIn as? JuneVoiceStatsSource)?.lastLineStats
        val generation = stats?.generationMillis ?: elapsedMillis
        val head = "내장 · ${text.length}자 · 생성 ${seconds(generation)}초"
        val audioMillis = stats?.audioMillis ?: return head
        val slower = if (generation > audioMillis) " · 실시간보다 느림" else ""
        return "$head · 재생 ${seconds(audioMillis)}초$slower"
    }

    private fun seconds(millis: Long): String = "%.1f".format(millis / 1000.0)

    private fun note(text: String) {
        Timber.tag(TAG).d(text)
        runCatchingExceptions { onNote(text) }.onFailure { Timber.tag(TAG).e(it, "Cannot save the voice note") }
    }

    override fun close() {
        builtInOpen = false
        phoneOpen = false
        runCatchingExceptions { builtIn.close() }.onFailure { Timber.tag(TAG).e(it, "Cannot close the built-in voice") }
        runCatchingExceptions { phone.close() }.onFailure { Timber.tag(TAG).e(it, "Cannot close the phone engine") }
    }
}
