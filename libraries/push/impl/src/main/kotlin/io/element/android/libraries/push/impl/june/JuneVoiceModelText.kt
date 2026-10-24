/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import java.text.Normalizer

/** The language tag the multilingual model is given. */
private const val LANGUAGE_TAG = "ko"

/** Symbols the model does not read, replaced by what it does read. */
private val REPLACEMENTS = listOf(
    "\u2013" to "-",
    "\u2011" to "-",
    "\u2014" to "-",
    "_" to " ",
    "\u201C" to "\"",
    "\u201D" to "\"",
    "\u2018" to "'",
    "\u2019" to "'",
    "\u00B4" to "'",
    "`" to "'",
    "[" to " ",
    "]" to " ",
    "|" to " ",
    "/" to " ",
    "#" to " ",
    "\u2192" to " ",
    "\u2190" to " ",
)

private val DROPPED = Regex("[\u2665\u2606\u2661\u00A9\\\\]")
private val SPACE_BEFORE_PUNCTUATION = Regex(" ([,.!?;:'])")
private val REPEATED_SPACES = Regex("\\s+")
private val ENDS_SENTENCE = Regex("[.!?;:,'\"\u201C\u201D\u2018\u2019)\\]}\u2026\u3002\u300D\u300F\u3011\u3009\u300B\u203A\u00BB]$")

/**
 * Element June: writes one chunk the way the built-in voice model expects it, as the reference implementation of the model does.
 *
 * The text is normalised to NFKD first, which splits Korean syllables into the letters they are made of: the model counts those
 * letters, so without it a line would be read far too fast. Emojis and symbols it cannot read are dropped, a sentence that ends with
 * nothing gets a full stop, and the whole is wrapped in the language tags.
 */
internal fun juneVoiceModelText(chunk: String): String {
    var text = Normalizer.normalize(chunk, Normalizer.Form.NFKD)
    REPLACEMENTS.forEach { (from, to) -> text = text.replace(from, to) }
    text = DROPPED.replace(text, "")
    text = text.replace("@", " at ")
    text = SPACE_BEFORE_PUNCTUATION.replace(text, "$1")
    text = REPEATED_SPACES.replace(text, " ").trim()
    if (text.isEmpty()) return ""
    if (!ENDS_SENTENCE.containsMatchIn(text)) text += "."
    return "<$LANGUAGE_TAG>$text</$LANGUAGE_TAG>"
}
