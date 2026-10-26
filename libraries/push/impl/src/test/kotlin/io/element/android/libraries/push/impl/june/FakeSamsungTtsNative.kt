/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

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
