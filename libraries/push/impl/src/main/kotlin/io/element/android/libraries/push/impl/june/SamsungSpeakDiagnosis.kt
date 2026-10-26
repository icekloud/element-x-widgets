/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import kotlinx.coroutines.ensureActive
import timber.log.Timber
import kotlin.coroutines.coroutineContext

private const val TAG = "SamsungVoice"

/**
 * 앱31: how far reading one line with the Samsung engine got, and why it stopped. The phone is the only place where the engine
 * really runs, so a failure has to be readable from the settings alone: [line] is shown there as one line.
 *
 * The text being read is never kept here, only how many characters it had.
 */
internal class SamsungSpeakTrace(
    /** Folder name of the voice, for instance "ko_KR_l08". */
    val voice: String,
    val sampleRate: Int,
    /** How many chunks the line was cut into. */
    val chunks: Int,
) {
    /** Which chunk is being read, counted from 1. */
    var chunkIndex: Int = 0

    /** How many characters that chunk has. */
    var characters: Int = 0

    /** What the engine answered to the last text it was given. */
    var inputCode: Int = SAMSUNG_TTS_OK

    /** How many times the audio callback came. */
    var callbacks: Int = 0

    /** How many samples came in all. */
    var samples: Int = 0

    /** True once the engine said one of the sentences was stopped ([SAMSUNG_AUDIO_STOPPED]). */
    var stopped: Boolean = false

    /** True once the audio of a chunk could be played, so a track was made. */
    var trackOpened: Boolean = false

    /** How long the engine spent making the audio. */
    var generationMillis: Long = 0

    /** Why the line gave no sound, or null when it was read. */
    var failure: String? = null

    /** For instance "열기 OK · ko_KR_l08 · 1/2청크 18자 · 입력 OK · 콜백 4회 · 샘플 9600개 · 트랙 OK · 24000Hz". */
    fun line(): String {
        val input = if (inputCode == SAMSUNG_TTS_OK) "OK" else "$inputCode(${samsungTtsErrorText(inputCode)})"
        val track = if (trackOpened) "OK" else "없음"
        return "열기 OK · $voice · $chunkIndex/${chunks}청크 ${characters}자 · 입력 $input · " +
            "콜백 ${callbacks}회 · 샘플 ${samples}개 · 트랙 $track · ${sampleRate}Hz"
    }
}

/**
 * Reads every chunk of a line with [native] and hands the samples of each one to [onSamples], which plays them and answers false
 * when it cannot. Fills [trace] as it goes, so the settings can say how far it got.
 *
 * Returns true when at least one chunk was played. The audio part is given as a function so this can be checked without a phone.
 *
 * What the engine does with a sentence it cannot say anything about (`ILanguage::Process` answering 0, the only place the library
 * sends [SAMSUNG_AUDIO_STOPPED]) is to **skip it and go on with the next one**, and `SMTInputText` still answers 0. So a stopped
 * sentence is only written down here; the line is a failure when no sample at all came out of it.
 */
internal suspend fun samsungReadChunks(
    native: SamsungTtsNative,
    chunks: List<String>,
    trace: SamsungSpeakTrace,
    onSamples: suspend (ShortArray) -> Boolean,
): Boolean {
    try {
        chunks.forEachIndexed { index, chunk ->
            coroutineContext.ensureActive()
            trace.chunkIndex = index + 1
            trace.characters = chunk.length
            val parts = mutableListOf<ShortArray>()
            val started = System.currentTimeMillis()
            val code = native.inputText(chunk) { samples, resultType ->
                trace.callbacks++
                if (resultType == SAMSUNG_AUDIO_STOPPED) trace.stopped = true
                if (samples.isNotEmpty()) parts += samples
            }
            trace.generationMillis += System.currentTimeMillis() - started
            trace.inputCode = code
            if (code != SAMSUNG_TTS_OK) {
                // The engine refused the text: nothing more will come, the rest of the line is left to the phone engine
                trace.failure = "입력 코드 $code(${samsungTtsErrorText(code)})"
                Timber.tag(TAG).w("The Samsung voice refused a chunk: %s", samsungTtsErrorText(code))
                return false
            }
            val samples = joinSamples(parts)
            trace.samples += samples.size
            if (samples.isEmpty()) return@forEachIndexed
            if (!onSamples(samples)) {
                trace.failure = "오디오 트랙 실패"
                return false
            }
            trace.trackOpened = true
        }
    } catch (error: OutOfMemoryError) {
        Timber.tag(TAG).e(error, "Not enough memory for the Samsung voice")
        trace.failure = "메모리 부족"
        return false
    }
    if (!trace.trackOpened) {
        trace.failure = if (trace.stopped) "콜백 중단(유형 8)" else "샘플 0개"
        return false
    }
    return true
}

/** The pieces the callback gave for one chunk, one after the other. */
private fun joinSamples(parts: List<ShortArray>): ShortArray {
    if (parts.isEmpty()) return ShortArray(0)
    if (parts.size == 1) return parts.first()
    val samples = ShortArray(parts.sumOf { it.size })
    var offset = 0
    parts.forEach { part ->
        part.copyInto(samples, offset)
        offset += part.size
    }
    return samples
}
