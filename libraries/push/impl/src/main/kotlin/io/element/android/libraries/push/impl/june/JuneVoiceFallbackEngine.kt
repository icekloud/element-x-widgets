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

/** 앱28: an engine that can tell how its model was opened, so a failure can be understood from the settings of the phone. */
interface JuneVoiceLoadReport {
    /** Why the last [JuneSpeechEngine.open] gave [JuneSpeakResult.EngineNotReady], for instance "세션 생성 vocoder.onnx · IOException". */
    val lastLoadFailure: String?

    /** What is worth knowing about how the model was opened when it worked (for instance "복사 방식"), or null. */
    val lastLoadNote: String?
}

/**
 * 앱31: an engine that can tell why the last line gave no sound, so a failure can be told apart from the settings of the phone
 * alone. An engine that does not report anything keeps the old note ("폰 기본 대체(생성 실패)").
 */
interface JuneVoiceSpeakReport {
    /** Why the last [JuneSpeechEngine.speak] gave false, for instance "샘플 0개" or "입력 코드 7(중단됨)", or null. */
    val lastSpeakFailure: String?

    /** How far the last line got, for instance "열기 OK · ko_KR_l08 · 1/2청크 18자 · 입력 OK · 콜백 4회 · 샘플 0개 · 트랙 없음 · 24000Hz". */
    val lastSpeakTrace: String?
}

/**
 * Element June: reads the 🔊 lines with the engine chosen in the settings, and falls back to the phone engine for the lines the
 * chosen voice cannot read (model missing, out of memory, failure, too slow). A failure never changes the setting: the chosen
 * voice is tried again on the next line.
 *
 * 앱30: [samsung] is the second voice shipped in the app. It is left out (null) where it is not available, and the chosen voice
 * then falls back to the phone engine as any other failure does.
 */
internal class JuneVoiceFallbackEngine(
    private val builtIn: JuneSpeechEngine,
    private val phone: JuneSpeechEngine,
    private val chosenEngine: () -> JuneVoiceEngineChoice,
    private val onNote: (String) -> Unit,
    private val samsung: JuneSpeechEngine? = null,
) : JuneSpeechEngine {
    /** The voice of the app that is being read with (the chosen one, opened), or null while the phone engine reads. */
    private var voice: JuneSpeechEngine? = null

    /** The short name of [voice], for the notes shown in the settings. */
    private var voiceLabel = JuneVoiceEngineChoice.BuiltIn.shortLabel
    private var phoneOpen = false

    /** The voice of the app for [choice], or null when it is the phone engine or when that voice is not available. */
    private fun engineOf(choice: JuneVoiceEngineChoice): JuneSpeechEngine? = when (choice) {
        JuneVoiceEngineChoice.Phone -> null
        JuneVoiceEngineChoice.BuiltIn -> builtIn
        JuneVoiceEngineChoice.Samsung -> samsung
    }

    /** The audio state and the audio focus are the ones of the phone: both engines play on the same output. */
    override fun currentBlock(): JuneSpeakBlock? = phone.currentBlock()

    override fun requestFocus(): Boolean = phone.requestFocus()

    override suspend fun open(): JuneSpeakResult? {
        val choice = chosenEngine()
        val chosen = engineOf(choice)
        if (chosen != null) {
            voiceLabel = choice.shortLabel
            var reason: String? = null
            val failure = runCatchingExceptions { chosen.open() }.getOrElse { error ->
                Timber.tag(TAG).e(error, "The %s voice could not be opened", choice.key)
                reason = juneVoiceErrorText(error)
                JuneSpeakResult.EngineNotReady
            }
            val report = chosen as? JuneVoiceLoadReport
            if (failure == null) {
                voice = chosen
                report?.lastLoadNote?.let { note("$voiceLabel 준비: $it") }
                return null
            }
            // The phone engine reads everything until the next time the lines are read. The reason is shown, so a failure on the phone
            // can be understood without its logs.
            val shown = reason ?: report?.lastLoadFailure
            note(if (shown == null) "폰 기본 대체($voiceLabel 준비 실패)" else "폰 기본 대체 · $voiceLabel 준비 실패: $shown")
        } else if (choice != JuneVoiceEngineChoice.Phone) {
            // The chosen voice is not available in this build
            note("폰 기본 대체(${choice.shortLabel} 없음)")
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
        val voice = voice
        if (voice != null) {
            val start = System.currentTimeMillis()
            val spoken = withTimeoutOrNull(JUNE_VOICE_LINE_TIMEOUT) {
                runCatchingExceptions { voice.speak(text) }.getOrElse { error ->
                    Timber.tag(TAG).e(error, "The %s voice could not read the line", voiceLabel)
                    false
                }
            }
            if (spoken == true) {
                note(readNote(text, System.currentTimeMillis() - start))
                return true
            }
            note(if (spoken == null) "폰 기본 대체(시간 초과)" else speakFailureNote(voice))
        }
        // The line is never lost: the phone engine reads it
        if (openPhone() != null) return false
        return runCatchingExceptions { phone.speak(text) }.getOrDefault(false)
    }

    /**
     * 앱31: why the chosen voice gave no sound, and how far it got, for instance
     * "폰 기본 대체(삼성 생성 실패: 샘플 0개) · 열기 OK · ko_KR_l08 · 1/1청크 5자 · 입력 OK · 콜백 0회 · 샘플 0개 · 트랙 없음 · 24000Hz".
     * An engine that reports nothing keeps the old note, so the line of a failure stays the same as before.
     */
    private fun speakFailureNote(voice: JuneSpeechEngine): String {
        val report = voice as? JuneVoiceSpeakReport
        val reason = report?.lastSpeakFailure ?: return "폰 기본 대체(생성 실패)"
        val head = "폰 기본 대체($voiceLabel 생성 실패: $reason)"
        return report.lastSpeakTrace?.let { "$head · $it" } ?: head
    }

    /** For instance "내장 · 42자 · 생성 6.1초 · 재생 5.8초", or the same without the audio length when the engine does not give it. */
    private fun readNote(text: String, elapsedMillis: Long): String {
        val voice = voice
        val stats = (voice as? JuneVoiceStatsSource)?.lastLineStats
        val generation = stats?.generationMillis ?: elapsedMillis
        val head = "$voiceLabel · ${text.length}자 · 생성 ${seconds(generation)}초"
        val loadNote = (voice as? JuneVoiceLoadReport)?.lastLoadNote?.let { " · $it" }.orEmpty()
        val audioMillis = stats?.audioMillis ?: return head + loadNote
        val slower = if (generation > audioMillis) " · 실시간보다 느림" else ""
        return "$head · 재생 ${seconds(audioMillis)}초$slower$loadNote"
    }

    private fun seconds(millis: Long): String = "%.1f".format(millis / 1000.0)

    private fun note(text: String) {
        Timber.tag(TAG).d(text)
        runCatchingExceptions { onNote(text) }.onFailure { Timber.tag(TAG).e(it, "Cannot save the voice note") }
    }

    override fun close() {
        voice = null
        phoneOpen = false
        runCatchingExceptions { builtIn.close() }.onFailure { Timber.tag(TAG).e(it, "Cannot close the built-in voice") }
        samsung?.let { runCatchingExceptions { it.close() }.onFailure { error -> Timber.tag(TAG).e(error, "Cannot close the Samsung voice") } }
        runCatchingExceptions { phone.close() }.onFailure { Timber.tag(TAG).e(it, "Cannot close the phone engine") }
    }
}
