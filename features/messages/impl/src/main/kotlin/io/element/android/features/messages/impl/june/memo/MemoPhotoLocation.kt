/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.core.data.tryOrNull
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * 앱23 (관리자: 폰에서 고른 사진의 원본 촬영 위치를 읽어 서버로 보낸다): the pure part of it. Nothing here touches the
 * phone: reading the EXIF is MemoPhotoLocationReader, this file only decides what a read result means and what goes
 * on the wire (규약-사진위치 1·2절).
 *
 * The coordinates never go to a log, an error message or a crash report (요청문 5): only counts are logged.
 */

/** One photo's own location, as the phone read it from the picture (규약-사진위치 1절). */
internal data class MemoPhotoLocation(
    val latitude: Double,
    val longitude: Double,
    /** ISO 8601 of `DateTimeOriginal` (+ `OffsetTimeOriginal`, else the device zone), null when it cannot be read. */
    val takenAt: String? = null,
    /** `GPSHPositioningError` in metres, null when it is missing or out of range. */
    val accuracyM: Double? = null,
)

/** Key of the location inside an attachment item of `첨부[]`, and inside the attachment's own `file_json`. */
internal const val MEMO_PHOTO_LOCATION_KEY = "위치"

private const val MEMO_LOC_LAT = "위도"
private const val MEMO_LOC_LON = "경도"
private const val MEMO_LOC_TAKEN = "촬영"
private const val MEMO_LOC_ACCURACY = "정확도"

/** Longest `촬영` the server keeps (규약-사진위치 1절). */
internal const val MEMO_PHOTO_TAKEN_CHARS = 40

/** Largest `정확도` the server keeps, in metres (규약-사진위치 1절). */
internal const val MEMO_PHOTO_ACCURACY_MAX = 100_000.0

/**
 * The location of a photo, or null when there is nothing to send (규약-사진위치 1절 "버리는 값"): no reading, a reading
 * that is not a number, out of range, or exactly `0, 0` — the value Android gives for a picture whose location it
 * hides, never a real coordinate we want to send.
 */
internal fun memoPhotoLocationOf(
    latitude: Double?,
    longitude: Double?,
    takenAt: String? = null,
    accuracyM: Double? = null,
): MemoPhotoLocation? {
    if (latitude == null || longitude == null) return null
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (abs(latitude) > 90.0 || abs(longitude) > 180.0) return null
    if (latitude == 0.0 && longitude == 0.0) return null
    return MemoPhotoLocation(
        latitude = memoRound6(latitude),
        longitude = memoRound6(longitude),
        takenAt = takenAt?.trim()?.takeIf { it.isNotEmpty() && it.length <= MEMO_PHOTO_TAKEN_CHARS },
        accuracyM = accuracyM?.takeIf { it.isFinite() && it >= 0.0 && it <= MEMO_PHOTO_ACCURACY_MAX }?.let { memoRound1(it) },
    )
}

/** Six decimals, like the server stores them (규약-사진위치 1절). */
private fun memoRound6(v: Double): Double = (v * 1_000_000.0).roundToLong() / 1_000_000.0

/** One decimal, like the server stores `정확도`. */
private fun memoRound1(v: Double): Double = (v * 10.0).roundToLong() / 10.0

/**
 * `DateTimeOriginal` ("2026:10:10 18:59:30") with `OffsetTimeOriginal` ("+09:00") as one ISO 8601 moment. Without the
 * offset the device zone is used, like the rest of the app's times. Null when the date cannot be read.
 */
internal fun memoExifTakenAt(
    dateTimeOriginal: String?,
    offsetTimeOriginal: String?,
    deviceOffset: () -> String = { memoDeviceOffset() },
): String? {
    val raw = dateTimeOriginal?.trim()?.takeIf { it.length >= 19 } ?: return null
    val date = raw.substring(0, 10).replace(':', '-')
    val time = raw.substring(11, 19)
    if (!memoLooksLikeDate(date) || !memoLooksLikeTime(time)) return null
    val offset = offsetTimeOriginal?.trim()?.takeIf { memoLooksLikeOffset(it) } ?: deviceOffset()
    return "${date}T$time$offset"
}

private fun memoLooksLikeDate(s: String): Boolean =
    s.length == 10 && s[4] == '-' && s[7] == '-' && s.filterIndexed { i, _ -> i != 4 && i != 7 }.all { it.isDigit() }

private fun memoLooksLikeTime(s: String): Boolean =
    s.length == 8 && s[2] == ':' && s[5] == ':' && s.filterIndexed { i, _ -> i != 2 && i != 5 }.all { it.isDigit() }

/** "+09:00", "-03:30" or "Z". */
private fun memoLooksLikeOffset(s: String): Boolean =
    s == "Z" || s.length == 6 && (s[0] == '+' || s[0] == '-') && s[3] == ':' && s.drop(1).filter { it != ':' }.all { it.isDigit() }

/** The device's current UTC offset as "+09:00" (the zone the rest of the app writes its times in). */
internal fun memoDeviceOffset(nowMs: Long = System.currentTimeMillis()): String {
    val minutes = java.util.TimeZone.getDefault().getOffset(nowMs) / 60_000
    val sign = if (minutes < 0) "-" else "+"
    val abs = kotlin.math.abs(minutes)
    return "%s%02d:%02d".format(sign, abs / 60, abs % 60)
}

/** The `위치` object of an attachment item: the fields that have a value, in the order of the 규약. */
internal fun MemoPhotoLocation.toJson(): JSONObject {
    val o = JSONObject().put(MEMO_LOC_LAT, latitude).put(MEMO_LOC_LON, longitude)
    takenAt?.let { o.put(MEMO_LOC_TAKEN, it) }
    accuracyM?.let { o.put(MEMO_LOC_ACCURACY, it) }
    return o
}

/** Reads a `위치` object back (what this app wrote, or what the server sent down); null when it is not usable. */
internal fun memoPhotoLocationFromJson(o: JSONObject?): MemoPhotoLocation? {
    o ?: return null
    val lat = o.opt(MEMO_LOC_LAT) as? Number ?: return null
    val lon = o.opt(MEMO_LOC_LON) as? Number ?: return null
    return memoPhotoLocationOf(
        latitude = lat.toDouble(),
        longitude = lon.toDouble(),
        takenAt = o.optString(MEMO_LOC_TAKEN).takeIf { it.isNotEmpty() },
        accuracyM = (o.opt(MEMO_LOC_ACCURACY) as? Number)?.toDouble(),
    )
}

/**
 * 결정기록 2: the location waits inside the attachment's own `file_json` (no new column). Before the upload that
 * field holds nothing else, after it the `EncryptedFile` of the server copy; the location rides along in both.
 */
internal fun memoAttachmentLocation(fileJson: String?): MemoPhotoLocation? =
    memoPhotoLocationFromJson(tryOrNull { JSONObject(fileJson.orEmpty()) }?.optJSONObject(MEMO_PHOTO_LOCATION_KEY))

/**
 * The `file` object as it goes on the wire: the `EncryptedFile` of the server copy without the location this app
 * keeps beside it ([MEMO_PHOTO_LOCATION_KEY] travels as its own field of the attachment item, 규약-사진위치 1절).
 */
internal fun memoFileWithoutLocation(file: JSONObject): JSONObject {
    if (!file.has(MEMO_PHOTO_LOCATION_KEY)) return file
    val copy = JSONObject(file.toString())
    copy.remove(MEMO_PHOTO_LOCATION_KEY)
    return copy
}

/** [fileJson] with [location] inside it (null location: the field is left as it is). */
internal fun memoFileJsonWithLocation(fileJson: String?, location: MemoPhotoLocation?): String? {
    location ?: return fileJson
    val o = tryOrNull { JSONObject(fileJson.orEmpty()) } ?: JSONObject()
    return o.put(MEMO_PHOTO_LOCATION_KEY, location.toJson()).toString()
}

/** The attachment with [location] kept beside its file information (결정기록 2). */
internal fun MemoLocalAttachment.withLocation(location: MemoPhotoLocation?): MemoLocalAttachment =
    if (location == null) this else copy(fileJson = memoFileJsonWithLocation(fileJson, location))

/** The location this attachment carries, null when it has none. */
internal val MemoLocalAttachment.location: MemoPhotoLocation? get() = memoAttachmentLocation(fileJson)

/** The locations of the `첨부[]` items of a `노트변경` payload: the photo's mxc to the location it carried. */
internal fun memoLocationsOfChange(change: JSONObject): Map<String, MemoPhotoLocation> {
    val files = change.optJSONArray("첨부") ?: return emptyMap()
    return (0 until files.length())
        .mapNotNull { files.optJSONObject(it) }
        .mapNotNull { f ->
            val mxc = f.optString("mxc").takeIf { it.isNotEmpty() }
            val location = memoPhotoLocationFromJson(f.optJSONObject(MEMO_PHOTO_LOCATION_KEY))
            if (mxc != null && location != null) mxc to location else null
        }
        .toMap()
}

/**
 * 규약-사진위치 2절 ③ ("위치만 보충"): true when the note has to go up once more because an uploaded photo holds a
 * location that the payload just sent did not carry (the note was queued before the location was known). The note is
 * then queued again as it stands — no new rev, no new content hash: the server answers `같음` and only stores the
 * location. A photo without a location, or one whose location already went up unchanged, asks for nothing.
 */
internal fun memoNeedsLocationFill(sentChange: JSONObject, attachments: List<MemoLocalAttachment>): Boolean {
    val sent = memoLocationsOfChange(sentChange)
    return attachments.any { a ->
        val mxc = a.mxc ?: return@any false
        val location = a.location ?: return@any false
        sent[mxc] != location
    }
}
