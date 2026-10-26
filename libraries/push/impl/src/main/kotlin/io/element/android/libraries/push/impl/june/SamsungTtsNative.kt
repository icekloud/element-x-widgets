/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

/** Name of the Samsung engine shipped in `jniLibs/arm64-v8a`; it is only there for that one architecture. */
internal const val SAMSUNG_TTS_LIBRARY = "samsungtts"

/** Every call of the engine answers 0 when it worked. */
internal const val SAMSUNG_TTS_OK = 0

/** Sample rate used when the engine does not say which one it reads at (its configuration says 24000). */
internal const val SAMSUNG_TTS_DEFAULT_SAMPLE_RATE = 24_000

/** The kind of result given to the audio callback: 0 plain audio, 1 the whole text is done, 2 end of a sentence, 6 last chunk, 8 stopped or failed. */
internal const val SAMSUNG_AUDIO_FAILED = 8

/**
 * 앱30: what the app uses of the Samsung engine. The real one is [JnaSamsungTtsNative]; the tests use their own, so the order of
 * the calls and what is done with a failure can be checked without an arm64 phone.
 *
 * There is only one engine inside the library (every entry point works on the same global), so only one text can be read at a
 * time; [SamsungJuneSpeechEngine] is the one making sure of that.
 */
internal interface SamsungTtsNative {
    /** Loads the library. Throws when it cannot be loaded, for instance on a phone that is not arm64. */
    fun load()

    /**
     * Tells the engine which voice to read with and where its files are. [dataDir] is the folder holding the voice folder, without
     * a trailing slash. Returns 0 or the code of the engine.
     */
    fun setVoice(voice: SamsungVoice, dataDir: String): Int

    /** Opens the voice (this is where the 44 MB of voice data are read). Returns 0 or the code of the engine. */
    fun initialize(): Int

    /** How many samples a second the engine gives, or 0 when it does not know. */
    fun samplingRate(): Int

    /**
     * Reads [text] (UTF-8) and gives the audio to [onAudio] as 16 bit mono samples, called from this very thread before this call
     * comes back. [onAudio] must not block. Returns 0 or the code of the engine.
     */
    fun inputText(text: String, onAudio: (samples: ShortArray, resultType: Int) -> Unit): Int

    /** Stops the text being read. Can be called from another thread. */
    fun stop()

    /** Closes the engine. Nothing else may be called after it but [load]. */
    fun close()
}

/** What the engine answered, as shown in the settings of the phone. */
internal fun samsungTtsErrorText(code: Int): String = when (code) {
    SAMSUNG_TTS_OK -> "성공"
    7 -> "중단됨"
    10 -> "인자 이상(음성 이름)"
    11 -> "음성 파일 없음"
    227 -> "cfg 열기 실패"
    228 -> "설정 적재 실패"
    191 -> "화자 바꾸기 실패"
    1_000_493 -> "신경망 엔진 초기화 실패"
    1_000_494 -> "엔진 초기화 안 됨"
    else -> "오류 $code"
}
