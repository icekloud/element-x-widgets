/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import io.element.android.libraries.core.data.tryOrNull
import java.io.InputStream

/*
 * 앱23 (결정기록 1): reads the original location of a picked picture. The system photo picker hides the EXIF location
 * of what it hands over; since the 2026-08 Mainline update an app may ask for it with
 * MediaStore.EXTRA_REQUEST_LOCATION_METADATA_ACCESS and the user allows or refuses it in the picker itself. Where the
 * picker does not know that extra (older devices), or where the picture simply has no location, the reading gives
 * null and the photo goes up without a location — never an error on the screen (규약-사진위치 6절).
 *
 * Coordinates never reach a log or an error message (요청문 5).
 */

/**
 * Intent extra that asks the Android photo picker to keep the location in the EXIF of the pictures the user selects
 * (`MediaStore.EXTRA_REQUEST_LOCATION_METADATA_ACCESS`, API 37). Written out so the app builds against older SDKs.
 */
internal const val MEMO_EXTRA_REQUEST_LOCATION_METADATA = "android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS"

/** The location of the picture at [uri], null when there is none to send (hidden, missing, unreadable, 0/0). */
internal fun memoReadPhotoLocation(context: Context, uri: Uri): MemoPhotoLocation? =
    tryOrNull { memoOpenOriginal(context, uri)?.use { memoReadExifLocation(it) } }

/**
 * Opens the picture for reading. A `content://media/...` address of the media store can be asked for the unredacted
 * original ([MediaStore.setRequireOriginal], needs `ACCESS_MEDIA_LOCATION`); a picker address cannot (it throws), so
 * it is opened as it is — the picker itself decided whether the location is in there (결정기록 1).
 */
private fun memoOpenOriginal(context: Context, uri: Uri): InputStream? {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !memoIsPickerUri(uri)) {
        val original = tryOrNull { MediaStore.setRequireOriginal(uri) }
        if (original != null) {
            tryOrNull { resolver.openInputStream(original) }?.let { return it }
        }
    }
    return resolver.openInputStream(uri)
}

/** True for the session addresses of the system photo picker (`content://media/picker/...`). */
internal fun memoIsPickerUri(uri: Uri): Boolean =
    uri.scheme == "content" && uri.authority?.endsWith("media") == true && uri.pathSegments.firstOrNull() == "picker"

/** The location in the EXIF of [stream] (규약-사진위치 1절 fields), null when there is none to send. */
internal fun memoReadExifLocation(stream: InputStream): MemoPhotoLocation? {
    val exif = tryOrNull { ExifInterface(stream) } ?: return null
    val latLong = exif.latLong ?: return null
    val accuracy = exif.getAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR)
        ?.let { tryOrNull { exif.getAttributeDouble(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, -1.0) } }
        ?.takeIf { it >= 0.0 }
    return memoPhotoLocationOf(
        latitude = latLong.getOrNull(0),
        longitude = latLong.getOrNull(1),
        takenAt = memoExifTakenAt(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
        ),
        accuracyM = accuracy,
    )
}

// `memoExifTakenAt`, `memoDeviceOffset`: MemoPhotoLocation.kt (아무것도 폰에 기대지 않는 부분).
