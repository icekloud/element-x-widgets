/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.june

/**
 * 앱32: which of the Korean voices of the Samsung engine reads the 🔊 lines, when that engine is the chosen one
 * ([JuneVoiceEngineChoice.Samsung]).
 *
 * The four voices are shipped inside the app and are shown **by their number only** ([label] is "삼성 여성 N"): nothing else about
 * them is put on the screen, in a note or in a log. [key] is the name the voice has inside the engine, and is what gets saved, so
 * the saved choice still means the same voice if the order ever changes.
 *
 * [Voice1] is the voice the app read with before this setting existed, and stays the default.
 */
enum class JuneSamsungVoiceChoice(val key: String, val number: Int) {
    Voice1("l08", 1),
    Voice2("l01", 2),
    Voice3("l04", 3),
    Voice4("l05", 4);

    /** The whole name shown in the settings, for instance "삼성 여성 2". */
    val label: String get() = "삼성 여성 $number"

    /** The short name shown next to the setting and in the notes, for instance "여성 2". */
    val shortLabel: String get() = "여성 $number"

    /** The next voice when the setting is pressed: 1 → 2 → 3 → 4 → 1. */
    fun next(): JuneSamsungVoiceChoice = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The saved voice, [Voice1] when nothing (or something broken) is saved. */
        fun parse(key: String?): JuneSamsungVoiceChoice = entries.firstOrNull { it.key == key } ?: Voice1
    }
}
