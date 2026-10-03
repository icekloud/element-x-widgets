/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import kotlinx.coroutines.CompletableDeferred

class FakeJuneSpeechEngine(
    var block: JuneSpeakBlock? = null,
    /** Returned by [open]: null when ready. */
    var openResult: JuneSpeakResult? = null,
    /** Results of the successive focus requests, the last one is repeated. */
    var focusResults: List<Boolean> = listOf(true),
    /** When set, each line waits for this to complete before being done. */
    var gate: CompletableDeferred<Unit>? = null,
    var speakResult: Boolean = true,
    var speakFailure: Exception? = null,
    private val onSpeak: (String) -> Unit = {},
) : JuneSpeechEngine {
    val spoken = mutableListOf<String>()
    var openCount = 0
    var closeCount = 0
    var focusRequestCount = 0

    /** True while the audio focus is held: the music of other apps is lowered. */
    var hasFocus = false
        private set

    /** Whether the focus was held when each line was read. */
    val focusWhileSpeaking = mutableListOf<Boolean>()

    override fun currentBlock(): JuneSpeakBlock? = block

    override fun requestFocus(): Boolean {
        if (hasFocus) return true
        val result = focusResults.getOrElse(focusRequestCount) { focusResults.last() }
        focusRequestCount++
        hasFocus = result
        return result
    }

    override suspend fun open(): JuneSpeakResult? {
        openCount++
        return openResult
    }

    override suspend fun speak(text: String): Boolean {
        spoken += text
        focusWhileSpeaking += hasFocus
        onSpeak(text)
        speakFailure?.let { throw it }
        gate?.await()
        return speakResult
    }

    override fun close() {
        closeCount++
        hasFocus = false
    }
}
