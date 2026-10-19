/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import io.element.android.libraries.core.data.tryOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Element June memo bot v2 (설계 §f, A3): what the composer of the memo room writes becomes a local note instead of a
 * room message. The memo screen attaches its room while it is shown; the composer and the voice recorder ask
 * [forRoom] at send time. Any other room (or the memo room shown as a timeline) gets null and sends as before.
 */
internal object MemoComposerInput {
    private data class Attached(val roomId: String, val sink: MemoInputSink)

    @Volatile
    private var attached: Attached? = null

    /** Called by the memo screen when it is shown; returns the function to call when it leaves. */
    fun attach(roomId: String, sink: MemoInputSink): () -> Unit {
        val a = Attached(roomId, sink)
        attached = a
        return { if (attached === a) attached = null }
    }

    /** Where the composer of [roomId] has to write, null to send to the room as usual. */
    fun forRoom(roomId: String): MemoInputSink? = attached?.takeIf { it.roomId == roomId }?.sink
}

/** Saves what was typed, picked or recorded in the memo room composer as local notes. */
internal interface MemoInputSink {
    suspend fun saveText(text: String): String

    /** Pictures (copied next to the database, uploaded later by A5) with the typed text, one note. */
    suspend fun savePictures(uris: List<Uri>, caption: String?): String

    /** A voice recording (copied, the recorder deletes its own file), one note. */
    suspend fun saveVoice(file: File, mimeType: String): String
}

/** The sink of the app: files go to `files/june_memo/`, notes to the local database through [backend]. */
internal class MemoLocalInputSink(
    private val context: Context,
    private val backend: MemoLocalBackend,
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoInputSink {
    override suspend fun saveText(text: String): String = backend.create(text)

    override suspend fun savePictures(uris: List<Uri>, caption: String?): String {
        val files = memoCopyPictures(context, uris, clock)
        return backend.create(caption.orEmpty(), attachments = files, kind = "사진")
    }

    override suspend fun saveVoice(file: File, mimeType: String): String {
        val copy = withContext(Dispatchers.IO) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: file.extension.ifEmpty { "ogg" }
            val out = memoFileTarget(context, "voice", clock(), 0, ext)
            file.copyTo(out, overwrite = true)
            MemoLocalAttachment(id = out.name, localPath = out.absolutePath, mime = mimeType, bytes = out.length())
        }
        // The transcript comes with the bot review (A5); until then the note says what it is.
        return backend.create(MEMO_VOICE_BODY, attachments = listOf(copy), kind = "음성")
    }
}

/**
 * Copies picked pictures next to the database (`files/june_memo/`), the phone originals the upload reads later. The
 * composer and, 앱10 D, the note editor use the same copy. A picture that cannot be read is left out.
 */
internal suspend fun memoCopyPictures(
    context: Context,
    uris: List<Uri>,
    clock: () -> Long = System::currentTimeMillis,
    /** 앱23: the picture's own location, read from the phone original before it is copied (결정기록 1). */
    readLocation: (Uri) -> MemoPhotoLocation? = { uri -> memoReadPhotoLocation(context, uri) },
): List<MemoLocalAttachment> =
    withContext(Dispatchers.IO) {
        val now = clock()
        uris.mapIndexedNotNull { index, uri ->
            tryOrNull {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val out = memoFileTarget(context, "photo", now, index, ext)
                context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }?.let {
                    // A picture whose location cannot be read (hidden, refused, none in it) is copied all the same.
                    val location = tryOrNull { readLocation(uri) }
                    MemoLocalAttachment(id = out.name, localPath = out.absolutePath, mime = mime, bytes = out.length())
                        .withLocation(location)
                }
            }
        }
    }

private fun memoFileTarget(context: Context, kind: String, nowMs: Long, index: Int, ext: String): File {
    val dir = File(context.filesDir, "june_memo").apply { mkdirs() }
    return File(dir, "$kind-$nowMs-$index.$ext")
}

internal const val MEMO_VOICE_BODY = "🎤 음성 메모"
