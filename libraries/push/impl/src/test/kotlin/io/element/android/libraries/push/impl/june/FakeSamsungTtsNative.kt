/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

/**
 * 앱31: what the engine answers for one text: the code of [SamsungTtsNative.inputText] and the pieces the audio callback gives,
 * each with its result type (0 plain audio, 2 end of a sentence, [SAMSUNG_AUDIO_STOPPED] a sentence that was skipped).
 */
internal data class SamsungFakeRead(
    val code: Int = SAMSUNG_TTS_OK,
    val pieces: List<Pair<ShortArray, Int>> = emptyList(),
)

/**
 * 앱30: the Samsung engine as the tests see it. Nothing native is loaded, so the order of the calls, what is given to them and what
 * is done with a failure can be checked on any machine.
 */
internal class FakeSamsungTtsNative(
    /** Thrown by [load] when the library cannot be used, as a phone that is not arm64 would. */
    var loadFailure: Throwable? = null,
    var setVoiceCode: Int = SAMSUNG_TTS_OK,
    var initializeCode: Int = SAMSUNG_TTS_OK,
    var sampleRate: Int = 24_000,
    var inputTextCode: Int = SAMSUNG_TTS_OK,
    /** The audio given back for each read text, and the kind of result that comes with it. */
    var audio: ShortArray = ShortArray(240),
    var audioResultType: Int = 0,
) : SamsungTtsNative {
    /** 앱31: an answer for each text in order; [audio] is used for the texts this does not cover. */
    val answers = mutableListOf<SamsungFakeRead>()

    val calls = mutableListOf<String>()
    var dataDir: String? = null
    var voice: SamsungVoice? = null
    val spoken = mutableListOf<String>()
    var loadCount = 0
    var closeCount = 0
    var stopCount = 0

    override fun load() {
        loadCount++
        calls += "load"
        loadFailure?.let { throw it }
    }

    override fun setVoice(voice: SamsungVoice, dataDir: String): Int {
        calls += "setVoice"
        this.voice = voice
        this.dataDir = dataDir
        return setVoiceCode
    }

    override fun initialize(): Int {
        calls += "initialize"
        return initializeCode
    }

    override fun samplingRate(): Int {
        calls += "samplingRate"
        return sampleRate
    }

    override fun inputText(text: String, onAudio: (ShortArray, Int) -> Unit): Int {
        calls += "inputText"
        spoken += text
        val answer = answers.getOrNull(spoken.size - 1)
        if (answer != null) {
            answer.pieces.forEach { (samples, resultType) -> onAudio(samples, resultType) }
            return answer.code
        }
        if (audio.isNotEmpty()) onAudio(audio, audioResultType)
        return inputTextCode
    }

    override fun stop() {
        stopCount++
        calls += "stop"
    }

    override fun close() {
        closeCount++
        calls += "close"
    }
}
