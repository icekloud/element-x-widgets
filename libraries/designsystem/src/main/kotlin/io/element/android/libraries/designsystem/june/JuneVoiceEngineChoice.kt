/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.june

/**
 * 앱27: the voice that reads the 🔊 lines aloud.
 *
 * [Phone] is the engine of the phone (the one of its text to speech settings) and the default: the built-in voice is only used once
 * it has been chosen, so the reading does not change on its own. [BuiltIn] is the Korean voice shipped inside the app; when it cannot
 * read a line (model missing, out of memory, too slow), that line is read by the phone engine instead.
 */
enum class JuneVoiceEngineChoice(val key: String, val label: String) {
    Phone("phone", "폰 기본"),
    BuiltIn("builtin", "내장 (슈퍼토닉3 F1)");

    companion object {
        /** The saved choice, [Phone] when nothing (or something broken) is saved. */
        fun parse(key: String?): JuneVoiceEngineChoice = entries.firstOrNull { it.key == key } ?: Phone
    }
}
