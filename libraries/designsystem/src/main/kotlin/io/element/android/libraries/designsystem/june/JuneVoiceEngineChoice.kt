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
 * it has been chosen, so the reading does not change on its own. [Samsung] is the Korean voice shipped inside the app; when it
 * cannot read a line (files missing, out of memory, too slow), that line is read by the phone engine instead.
 *
 * The Supertonic voice that was also shipped in the app has been taken out: an install that had chosen it reads with [Samsung].
 *
 * [shortLabel] is the short name shown next to the setting and in the note saying what the last line did.
 */
enum class JuneVoiceEngineChoice(val key: String, val label: String, val shortLabel: String) {
    Phone("phone", "폰 기본", "폰 기본"),
    Samsung("samsung", "삼성 음성 (한국어)", "삼성");

    /** The next choice when the setting is pressed: 폰 기본 → 삼성 → 폰 기본. */
    fun next(): JuneVoiceEngineChoice = entries[(ordinal + 1) % entries.size]

    companion object {
        /** What the installs that had chosen the Supertonic voice, now taken out of the app, have saved. */
        private const val REMOVED_SUPERTONIC_KEY = "builtin"

        /**
         * The saved choice, [Phone] when nothing (or something broken) is saved. The Supertonic voice is read as [Samsung], the voice
         * of the app that is left, so such an install keeps reading with a voice of the app (and with the phone engine when it fails).
         */
        fun parse(key: String?): JuneVoiceEngineChoice =
            if (key == REMOVED_SUPERTONIC_KEY) Samsung else entries.firstOrNull { it.key == key } ?: Phone
    }
}
