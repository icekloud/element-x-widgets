/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.file

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Element June: every file the app saves goes to Download/element. */
const val JUNE_DOWNLOAD_FOLDER = "element"

/** MediaStore relative path of the Element June download folder (Android 10+). */
val juneDownloadRelativePath: String
    get() = Environment.DIRECTORY_DOWNLOADS + File.separator + JUNE_DOWNLOAD_FOLDER

/** The Element June download folder for Android 9 and older, created when missing. */
@Suppress("DEPRECATION")
fun juneLegacyDownloadDir(): File =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), JUNE_DOWNLOAD_FOLDER).apply { mkdirs() }

/** Copies [source] to Download/element as [filename]; a name already taken gets a suffix. */
@WorkerThread
fun saveToJuneDownloads(context: Context, source: File, filename: String, mimeType: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val outputUri = saveWithUniqueFileName(filename) { displayName ->
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, juneDownloadRelativePath)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } ?: throw IOException("Unable to create the destination file")
        source.inputStream().use { input ->
            val output = resolver.openOutputStream(outputUri) ?: throw IOException("Unable to open the destination file")
            output.use { input.copyTo(it, DEFAULT_BUFFER_SIZE) }
        }
    } else {
        source.inputStream().use { input ->
            FileOutputStream(File(juneLegacyDownloadDir(), filename)).use { input.copyTo(it) }
        }
    }
}
