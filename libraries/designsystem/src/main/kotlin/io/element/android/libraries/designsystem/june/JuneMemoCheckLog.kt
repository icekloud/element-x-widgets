/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.june

import android.content.Context
import java.io.File

/**
 * Element June 앱33 추가요청 1 ⑤: the txt file the memo look-ups write their lines into, in the app's own storage
 * (`filesDir`, no permission, not readable by other apps). This object only reads and writes the file — what a line
 * says and how many are kept is decided by the memo code that calls it.
 *
 * Nothing in the app reads these lines back: the only way out is the "진단 기록 내보내기" item of the settings, which
 * shares the text with another app. The lines never carry a sentence, a note, a room, an address or a token.
 */
object JuneMemoCheckLog {
    private const val FILE_NAME = "june_memo_check.txt"

    private val lock = Any()

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** The lines written so far, oldest first; empty when nothing was ever written. */
    fun read(context: Context): List<String> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().filter { it.isNotBlank() }
        } catch (e: java.io.IOException) {
            emptyList()
        }
    }

    /** Writes [lines] as the whole file (the caller has already cut it to its limit). */
    fun write(context: Context, lines: List<String>) = synchronized(lock) {
        try {
            file(context).writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
        } catch (e: java.io.IOException) {
            // A diagnosis line is never worth breaking anything for.
        }
    }

    /** The whole file as one text, for the share of the settings item. */
    fun text(context: Context): String = read(context).joinToString("\n")
}
