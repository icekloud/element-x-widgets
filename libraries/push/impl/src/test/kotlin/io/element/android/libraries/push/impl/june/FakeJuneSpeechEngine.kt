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
    var canOpen: Boolean = true,
    /** When set, each line waits for this to complete before being done. */
    var gate: CompletableDeferred<Unit>? = null,
    private val onSpeak: (String) -> Unit = {},
) : JuneSpeechEngine {
    val spoken = mutableListOf<String>()
    var openCount = 0
    var closeCount = 0

    override fun currentBlock(): JuneSpeakBlock? = block

    override suspend fun open(): Boolean {
        openCount++
        return canOpen
    }

    override suspend fun speak(text: String): Boolean {
        spoken += text
        onSpeak(text)
        gate?.await()
        return true
    }

    override fun close() {
        closeCount++
    }
}
