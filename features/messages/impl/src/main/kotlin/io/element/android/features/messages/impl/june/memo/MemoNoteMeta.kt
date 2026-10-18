/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import org.json.JSONException
import org.json.JSONObject

/*
 * 앱10 (규약-번호사진 + 추가 결정 2026-10-15): the note number the server gives (`번호`) and the time the user last
 * changed the text, title or photos (`사용자수정`). Both are kept beside the note, in `sync_state` (no new column), so
 * they never take part in the merge, the "same note" test, the content hash or the compare sync: a number that comes
 * in changes no note row, makes nothing dirty and queues no upload.
 */

/** [number] 0 = none yet. [userModifiedMs] 0 = never changed by the user (the screen shows the creation date). */
internal data class MemoNoteMeta(val number: Int = 0, val userModifiedMs: Long = 0) {
    val isEmpty: Boolean get() = number <= 0 && userModifiedMs <= 0
}

internal const val MEMO_FIELD_NUMBER = "번호"
internal const val MEMO_FIELD_USER_MODIFIED = "사용자수정"

private const val STATE_NOTE_META = "note_meta:"

internal fun memoNoteMeta(db: MemoDb, id: String): MemoNoteMeta = db.state(STATE_NOTE_META + id)?.let(::parseMeta) ?: MemoNoteMeta()

/** The kept values of [ids] (the notes without any are left out). */
internal fun memoNoteMetas(db: MemoDb, ids: List<String>): Map<String, MemoNoteMeta> =
    db.states(ids.map { STATE_NOTE_META + it }).mapNotNull { (key, value) -> parseMeta(value)?.let { key.removePrefix(STATE_NOTE_META) to it } }.toMap()

internal fun memoSetNoteMeta(db: MemoDb, id: String, meta: MemoNoteMeta) {
    db.setState(STATE_NOTE_META + id, JSONObject().put(MEMO_FIELD_NUMBER, meta.number).put(MEMO_FIELD_USER_MODIFIED, meta.userModifiedMs).toString())
}

/** The user changed the text, title or photos of [id] at [atMs] (the corrected phone time). */
internal fun memoMarkUserModified(db: MemoDb, id: String, atMs: Long) {
    val old = memoNoteMeta(db, id)
    if (atMs > old.userModifiedMs) memoSetNoteMeta(db, id, old.copy(userModifiedMs = atMs))
}

/**
 * Keeps the top level `번호` and `사용자수정` of a `노트변경` the server sent. The number is the server's (a different
 * one replaces ours, an empty one is ignored); the user time keeps the newer of both sides. Returns true when a kept
 * value changed (the screen reads the notes again). Writes `sync_state` only.
 */
internal fun memoApplyNoteMeta(db: MemoDb, change: JSONObject): Boolean {
    val id = change.optString("id").takeIf { it.isNotEmpty() } ?: return false
    val number = change.optInt(MEMO_FIELD_NUMBER, 0)
    val user = memoParseIso(change.optString(MEMO_FIELD_USER_MODIFIED)) ?: 0L
    if (number <= 0 && user <= 0) return false
    val old = memoNoteMeta(db, id)
    val next = MemoNoteMeta(number = if (number > 0) number else old.number, userModifiedMs = maxOf(old.userModifiedMs, user))
    if (next == old) return false
    memoSetNoteMeta(db, id, next)
    return true
}

/**
 * The server header without the two values kept apart: the server writes them in its header too, and the header
 * follows the body bundle — a number there would make the same note look different (규약 추가 결정 1).
 */
internal fun memoHeaderWithoutMeta(header: JSONObject?): JSONObject? {
    if (header == null) return null
    if (!header.has(MEMO_FIELD_NUMBER) && !header.has(MEMO_FIELD_USER_MODIFIED)) return header
    val rest = JSONObject(header.toString()).apply {
        remove(MEMO_FIELD_NUMBER)
        remove(MEMO_FIELD_USER_MODIFIED)
    }
    // Nothing else in it: no header, like a note that never had one (the number alone must not make it differ).
    return rest.takeIf { it.length() > 0 }
}

/** The same for a header kept as text in the database (notes stored by 앱9 may still carry a number there). */
internal fun memoHeaderJsonWithoutMeta(json: String?): String? {
    if (json == null) return null
    if (MEMO_FIELD_NUMBER !in json && MEMO_FIELD_USER_MODIFIED !in json) return json
    return try {
        memoHeaderWithoutMeta(JSONObject(json))?.toString()
    } catch (e: JSONException) {
        json
    }
}

private fun parseMeta(value: String): MemoNoteMeta? = try {
    val o = JSONObject(value)
    MemoNoteMeta(o.optInt(MEMO_FIELD_NUMBER, 0), o.optLong(MEMO_FIELD_USER_MODIFIED, 0L)).takeIf { !it.isEmpty }
} catch (e: JSONException) {
    null
}

/**
 * The head line of a note (관리자 2026-10-15): `#번호  제목  날짜`, two spaces apart. No number yet: `제목  날짜`; no
 * title: `#번호  날짜`. The author ("나") is gone.
 */
internal fun memoNoteHead(number: Int, title: String, date: String): String =
    listOfNotNull(number.takeIf { it > 0 }?.let { "#$it" }, title.trim().takeIf { it.isNotEmpty() }, date.takeIf { it.isNotEmpty() })
        .joinToString(MEMO_HEAD_GAP)

/** [title] with the note number in front (tiles, list rows, search rows). */
internal fun memoNumbered(number: Int, title: String): String = if (number > 0) "#$number$MEMO_HEAD_GAP$title" else title

internal const val MEMO_HEAD_GAP = "  "

/** The date a screen shows: the last change of the user, else the creation (`2026-10-15T11:31:00+09:00`). */
internal fun memoShownDate(createdIso: String, userModifiedIso: String?): String = userModifiedIso?.takeIf { it.isNotEmpty() } ?: createdIso

/** `2026-10-15T19:23:04+09:00` → `2026-10-15 19:23` (KST, to the minute). A shorter value is shown as it is. */
internal fun memoMinuteTime(iso: String): String = if (iso.length >= MEMO_MINUTE_CHARS) iso.take(MEMO_MINUTE_CHARS).replace('T', ' ') else iso

private const val MEMO_MINUTE_CHARS = 16

/**
 * The date block of an open note (앱12 A, 관리자: "첫 작성일과 최근 수정일 둘다 2줄에 걸쳐"): `작성 …` always, `수정 …`
 * under it only when the user changed the note later. A `수정` that is not after the creation (the same minute, or an
 * odd earlier value) is left out; a note without a creation date shows only what it has.
 */
internal fun memoNoteDateLines(createdIso: String, userModifiedIso: String?): List<String> {
    val created = memoMinuteTime(createdIso)
    val modified = memoMinuteTime(userModifiedIso.orEmpty())
    return buildList {
        if (created.isNotEmpty()) add("작성 $created")
        if (modified.isNotEmpty() && modified > created) add("수정 $modified")
    }
}

internal fun memoNoteDateLines(note: MemoNote): List<String> =
    memoNoteDateLines(note.created, note.header.optString(MEMO_FIELD_USER_MODIFIED))

/**
 * 앱13 A (관리자: "작성, 수정일자 표시방식이 이 화면에서는 반영안됨"): the record card shows the same two lines as the open
 * note. An item that knows only the date shown (the fake backend, an old server list) shows that one as `작성`.
 */
internal fun memoItemDateLines(item: MemoItem): List<String> = memoNoteDateLines(item.created.ifEmpty { item.date }, item.userModified)
