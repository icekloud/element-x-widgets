/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/** Longest text kept from what went wrong (the exception and its message), so the reason fits on one line of the settings. */
internal const val JUNE_VOICE_REASON_MAX_LENGTH = 80

/** Room asked for a copy whose size is not known (more than any voice file), and room always left on the phone. */
internal const val JUNE_VOICE_UNKNOWN_COPY_BYTES = 400L * 1024 * 1024
internal const val JUNE_VOICE_COPY_MARGIN_BYTES = 100L * 1024 * 1024

/** Suffix of a copy being written: it only gets its real name once it is complete. */
internal const val JUNE_VOICE_PARTIAL_SUFFIX = ".part"

/** Where the loading of the voice of the app stopped, as shown on the phone. */
internal enum class JuneVoiceLoadStep(val label: String) {
    Environment("환경 생성"),
    Copy("복사"),
    Memory("메모리"),
}

/** Why the voice of the app could not be loaded: the [step], the [file] and the [error]. */
internal class JuneVoiceLoadFailure(
    val step: JuneVoiceLoadStep,
    val file: String?,
    val error: Throwable,
) {
    /** For instance "복사 regular.ivc.smt · IOException: broken asset", never a path nor a user id. */
    val reason: String get() = juneVoiceLoadReason(this)
}

internal class JuneVoiceLoadException(val failure: JuneVoiceLoadFailure) : Exception(failure.reason, failure.error)

/** There is not enough room on the phone to copy a voice file. */
internal class JuneVoiceStorageException(val neededBytes: Long, val availableBytes: Long) :
    IOException("Not enough storage: $neededBytes bytes needed, $availableBytes available")

/**
 * True for what the loading can be stopped on and reported, the app going on with the phone engine: any exception but a cancellation,
 * a failed big allocation (the voice is then dropped), and a native library or class that cannot be used.
 * Every other error is let through.
 */
internal fun juneVoiceIsRecoverable(error: Throwable): Boolean = when (error) {
    is CancellationException -> false
    is Exception, is OutOfMemoryError, is LinkageError -> true
    else -> false
}

/** Runs [block] as the [step] of the loading for [file]: what goes wrong comes out as a [JuneVoiceLoadException] saying where. */
internal inline fun <T> juneVoiceStep(step: JuneVoiceLoadStep, file: String?, block: () -> T): T = try {
    block()
} catch (reported: JuneVoiceLoadException) {
    // Already says where it stopped
    throw reported
} catch (error: Throwable) {
    throw juneVoiceLoadException(step, file, error)
}

/** [error] as a [JuneVoiceLoadException] of the [step], or [error] itself when it must not be caught. */
internal fun juneVoiceLoadException(step: JuneVoiceLoadStep, file: String?, error: Throwable): Throwable =
    if (juneVoiceIsRecoverable(error)) JuneVoiceLoadException(JuneVoiceLoadFailure(step, file, error)) else error

/** The line that says why the voice could not be loaded, for instance "복사 regular.ivc.smt · IOException: broken asset". */
internal fun juneVoiceLoadReason(failure: JuneVoiceLoadFailure): String =
    "${juneVoiceStepText(failure)} · ${juneVoiceErrorText(failure.error)}"

private fun JuneVoiceLoadFailure.stepOrMemory(): JuneVoiceLoadStep = if (error is OutOfMemoryError) JuneVoiceLoadStep.Memory else step

private fun juneVoiceStepText(failure: JuneVoiceLoadFailure): String =
    listOfNotNull(failure.stepOrMemory().label, failure.file).joinToString(" ")

private const val MEGABYTE = 1024L * 1024

/** What went wrong, for instance "IOException: fstat failed" or "저장공간 부족(필요 350MB · 남음 120MB)", at most [JUNE_VOICE_REASON_MAX_LENGTH]. */
internal fun juneVoiceErrorText(error: Throwable): String {
    val text = if (error is JuneVoiceStorageException) {
        "저장공간 부족(필요 ${error.neededBytes / MEGABYTE}MB · 남음 ${error.availableBytes / MEGABYTE}MB)"
    } else {
        val name = error.javaClass.simpleName.ifEmpty { "오류" }
        val message = juneVoiceCleanMessage(error.message)
        if (message.isEmpty()) name else "$name: $message"
    }
    return if (text.length <= JUNE_VOICE_REASON_MAX_LENGTH) text else text.take(JUNE_VOICE_REASON_MAX_LENGTH - 1) + "…"
}

private val ADDRESS = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""")
private val MATRIX_ID = Regex("""[@!#][\w.=+-]+:[\w.-]+(:\d+)?""")
private val PATH = Regex("""(?<![\w.])(?:/[^\s/:'"()\[\]]+)+/?""")
private val SPACES = Regex("""\s+""")

/** The message of an exception without what must not be shown (addresses, paths, user ids) and on one line. */
internal fun juneVoiceCleanMessage(message: String?): String {
    if (message.isNullOrBlank()) return ""
    return message
        .replace(ADDRESS, "<주소>")
        .replace(MATRIX_ID, "<ID>")
        .replace(PATH, "<경로>")
        .replace(SPACES, " ")
        .trim()
}

/** Room asked before copying a file of [expectedLength] bytes (unknown when null). */
internal fun juneVoiceCopyNeededBytes(expectedLength: Long?): Long =
    (expectedLength?.takeIf { it > 0 } ?: JUNE_VOICE_UNKNOWN_COPY_BYTES) + JUNE_VOICE_COPY_MARGIN_BYTES

/**
 * Copies a voice file into [target] and returns it, unless a complete copy is already there. The copy is written under a temporary
 * name and only renamed once it is complete (and of [expectedLength] when it is known), so a copy cut in the middle is never used.
 * [usableSpace] is the room left where [target] is; there must be [juneVoiceCopyNeededBytes] of it, else [JuneVoiceStorageException].
 */
internal fun juneVoiceCopy(target: File, expectedLength: Long?, usableSpace: () -> Long, open: () -> InputStream): File {
    val known = expectedLength?.takeIf { it > 0 }
    if (target.isFile && target.length() > 0 && (known == null || target.length() == known)) return target
    val needed = juneVoiceCopyNeededBytes(known)
    val available = usableSpace()
    if (available < needed) throw JuneVoiceStorageException(needed, available)
    val partial = File(target.parentFile, target.name + JUNE_VOICE_PARTIAL_SUFFIX)
    try {
        partial.delete()
        open().use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
        if (known != null && partial.length() != known) {
            throw IOException("Copied ${partial.length()} of $known bytes")
        }
        target.delete()
        if (!partial.renameTo(target)) throw IOException("Cannot rename the copy")
    } catch (error: Throwable) {
        partial.delete()
        throw error
    }
    return target
}
