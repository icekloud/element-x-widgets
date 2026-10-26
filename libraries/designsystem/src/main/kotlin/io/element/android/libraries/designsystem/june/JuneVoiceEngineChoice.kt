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
 * [Phone] is the engine of the phone (the one of its text to speech settings) and the default: another voice is only used once
 * it has been chosen, so the reading does not change on its own. [BuiltIn] and [Samsung] are the two Korean voices shipped inside
 * the app; when the chosen one cannot read a line (files missing, out of memory, too slow), that line is read by the phone engine
 * instead.
 *
 * [shortLabel] is the short name shown next to the setting and in the note saying what the last line did.
 */
enum class JuneVoiceEngineChoice(val key: String, val label: String, val shortLabel: String) {
    Phone("phone", "폰 기본", "폰 기본"),
    BuiltIn("builtin", "내장 (슈퍼토닉3 F1)", "내장"),
    Samsung("samsung", "삼성 음성 (한국어 l08)", "삼성");

    /** The next choice when the setting is pressed: 폰 기본 → 내장 → 삼성 → 폰 기본. */
    fun next(): JuneVoiceEngineChoice = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The saved choice, [Phone] when nothing (or something broken) is saved. */
        fun parse(key: String?): JuneVoiceEngineChoice = entries.firstOrNull { it.key == key } ?: Phone
    }
}
