/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

/**
 * Element June: cuts a line into the chunks given to the built-in voice, one after the other, so the first words are heard while the
 * next chunk is still being made. Same rules as the reference implementation of the model: paragraphs, then sentences, then commas,
 * then spaces.
 */

/** Longest chunk given to the model at once. Korean needs shorter chunks than the Latin languages (the reference uses 120 too). */
internal const val JUNE_VOICE_MAX_CHUNK = 120

/** Silence left between two chunks, like the reference implementation. */
internal const val JUNE_VOICE_CHUNK_SILENCE_SECONDS = 0.3f

private val PARAGRAPH = Regex("\\n\\s*\\n+")
private val SENTENCE_END = Regex("(?<=[.!?。])\\s+")
private val SPACES = Regex("\\s+")

/** Element June: [text] cut into chunks of at most [maxLength] characters. An empty or blank text gives no chunk. */
internal fun juneVoiceChunks(text: String, maxLength: Int = JUNE_VOICE_MAX_CHUNK): List<String> {
    val chunks = mutableListOf<String>()
    PARAGRAPH.split(text.trim())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { paragraph ->
            if (paragraph.length <= maxLength) {
                chunks += paragraph
            } else {
                chunks += cutParagraph(paragraph, maxLength)
            }
        }
    return chunks
}

private fun cutParagraph(paragraph: String, maxLength: Int): List<String> {
    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            chunks += current.toString().trim()
            current.setLength(0)
        }
    }

    fun append(part: String, separator: String) {
        if (current.isNotEmpty() && current.length + separator.length + part.length > maxLength) flush()
        if (current.isNotEmpty()) current.append(separator)
        current.append(part)
    }

    SENTENCE_END.split(paragraph)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { sentence ->
            if (sentence.length <= maxLength) {
                append(sentence, " ")
            } else {
                flush()
                chunks += cutSentence(sentence, maxLength)
            }
        }
    flush()
    return chunks
}

/** A sentence longer than [maxLength] is cut at its commas, and a part still too long at its spaces. A single long word is kept whole. */
private fun cutSentence(sentence: String, maxLength: Int): List<String> {
    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            chunks += current.toString().trim()
            current.setLength(0)
        }
    }

    fun append(part: String, separator: String) {
        if (current.isNotEmpty() && current.length + separator.length + part.length > maxLength) flush()
        if (current.isNotEmpty()) current.append(separator)
        current.append(part)
    }

    sentence.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { part ->
            if (part.length <= maxLength) {
                append(part, ", ")
            } else {
                flush()
                SPACES.split(part).filter { it.isNotEmpty() }.forEach { word -> append(word, " ") }
                flush()
            }
        }
    flush()
    return chunks
}
