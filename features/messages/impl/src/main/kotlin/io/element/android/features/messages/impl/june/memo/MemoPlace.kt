/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import org.json.JSONObject

/*
 * 앱21 ② (관리자: "위치정보를 본문 하단부의 고정영역(편집버튼 왼쪽에)에 추가하고(음식점이면 위치,음식점정보 기재하는 식으로) 해당영역
 * 누르면 구글맵의 해당 장소 링크 열 수 있게끔"): the `장소` of the note header (서버 15차 규약 §2), read only. The server
 * owns the value: the app keeps the header as it came (headerJson) and sends it back unchanged. The view is in
 * MemoPlaceView.kt; this file has no Android or Compose code (the local JVM tests compile it).
 */

/** 앱21 ②: the header field and its kind of a restaurant (규약 §2, the names as the server writes them). */
internal const val MEMO_FIELD_PLACE = "장소"
internal const val MEMO_PLACE_KIND_FOOD = "음식점"

/**
 * 앱21 ②: what the place box shows. [title] is the first line (📍/🍴, the name or `근처: …`, `(근처)` for a phone
 * position, ` · ` and the short introduction), [address] the second (empty: one line). [map] is the link a press opens,
 * null when there is none to open (the box is then drawn without a press).
 */
internal data class MemoPlaceLine(val title: String, val address: String, val map: String?) {
    val description: String get() = listOf(title, address).filter { it.isNotEmpty() }.joinToString(", ")
}

/**
 * 앱21 ②: the place box of a note [header], or null (no `장소`, not an object, nothing to show: the box is not drawn
 * and the 편집 box stays where it was). Unknown parts are ignored (규약: "앱은 모르는 칸을 무시한다").
 */
internal fun memoPlaceLine(header: JSONObject?): MemoPlaceLine? {
    val p = header?.optJSONObject(MEMO_FIELD_PLACE) ?: return null
    val name = p.optString("이름").trim()
    val near = p.optString("근처").trim()
    val intro = p.optString("소개").trim()
    val address = p.optString("주소").trim()
    val map = memoPlaceMapLink(p.optString("지도"))
    val label = when {
        name.isNotEmpty() -> name + if (p.optBoolean("근사")) " (근처)" else ""
        near.isNotEmpty() -> "근처: $near"
        address.isNotEmpty() || intro.isNotEmpty() || map != null -> "장소"
        else -> return null
    }
    val mark = if (p.optString("종류") == MEMO_PLACE_KIND_FOOD) "🍴" else "📍"
    val title = "$mark $label" + if (intro.isNotEmpty()) " · $intro" else ""
    return MemoPlaceLine(title, address, map)
}

/**
 * 앱21 ②: the `지도` link as it is opened (규약: "앱은 이 문자열을 그대로 … 연다"), only when it is a web link: a value
 * that is not http(s) (an `intent:` or a file) is never handed to another app.
 */
internal fun memoPlaceMapLink(value: String?): String? {
    val v = value?.trim().orEmpty()
    return v.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
}

/** 앱21 ②: Google Maps opens the link itself when it is on the phone; otherwise any app that opens web links. */
internal const val MEMO_MAPS_PACKAGE = "com.google.android.apps.maps"

/** 앱21 ②: the one line shown when no app on the phone opens the link. */
internal const val MEMO_PLACE_OPEN_FAILED = "지도를 열 앱이 없습니다"

/**
 * 앱21 ②: the tries of a press in order, as (package, link): Google Maps first, then whatever opens the link (null
 * package, the browser). The view makes an ACTION_VIEW intent of each.
 */
internal fun memoPlaceTries(map: String): List<Pair<String?, String>> = listOf(MEMO_MAPS_PACKAGE to map, null to map)

/**
 * 앱21 ②: runs [tries] until one of them starts ([start] throws when the phone has no app for it); false when none did
 * (the caller shows [MEMO_PLACE_OPEN_FAILED]).
 */
internal fun <T> memoFirstThatStarts(tries: List<T>, start: (T) -> Unit): Boolean {
    for (t in tries) {
        @Suppress("SwallowedException")
        try {
            start(t)
            return true
        } catch (e: RuntimeException) {
            // ActivityNotFoundException or SecurityException: the next try
        }
    }
    return false
}

/**
 * 앱21 추가요청 4: the chips of the tag line of an open note when a press opens the tag sheet: `#태그` each, or
 * [MEMO_TAG_ADD] when it has none. Null (the line is shown as before, without a press): a note in the trash, or the
 * selection mode.
 */
internal fun memoReadingTags(tags: List<String>, trashed: Boolean, selecting: Boolean): List<String>? = when {
    trashed || selecting -> null
    tags.isEmpty() -> listOf(MEMO_TAG_ADD)
    // the same 20 and "+N" as the line shown before (MemoTagLine)
    else -> {
        val rest = tags.size - MEMO_READING_TAGS_MAX
        tags.take(MEMO_READING_TAGS_MAX).map { "#$it" } + if (rest > 0) listOf("+$rest") else emptyList()
    }
}

internal const val MEMO_TAG_ADD = "# 태그 추가"
private const val MEMO_READING_TAGS_MAX = 20
