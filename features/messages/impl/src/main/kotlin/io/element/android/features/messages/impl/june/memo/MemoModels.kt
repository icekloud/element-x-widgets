/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import org.json.JSONArray
import org.json.JSONObject

/*
 * Element June memo bot: data exchanged with the memo bot server module.
 * Field names follow /opt/data/shared/설계/메모봇-구현/이벤트규약.md (v1) as is (Korean JSON keys).
 */

internal const val MEMO_INDEX_TYPE = "app.june.memo.index"
internal const val MEMO_OP_TYPE = "app.june.memo_op"
internal const val MEMO_LIST_TYPE = "app.june.memo.list"
internal const val MEMO_NOTE_TYPE = "app.june.memo.note"
internal const val MEMO_SEARCH_TYPE = "app.june.memo.search"
internal const val MEMO_TAGS_TYPE = "app.june.memo.tags"
internal const val MEMO_OK_TYPE = "app.june.memo.ok"
internal const val MEMO_ERROR_TYPE = "app.june.memo.error"
internal const val MEMO_SYNC_TYPE = "app.june.memo.sync"
internal const val MEMO_SYNC_ACK_TYPE = "app.june.memo.sync_ack"

internal val MEMO_RESPONSE_TYPES = listOf(
    MEMO_LIST_TYPE,
    MEMO_NOTE_TYPE,
    MEMO_SEARCH_TYPE,
    MEMO_TAGS_TYPE,
    MEMO_OK_TYPE,
    MEMO_ERROR_TYPE,
    MEMO_SYNC_TYPE,
    MEMO_SYNC_ACK_TYPE,
)

internal const val MEMO_INBOX = "받은편지함"
internal const val MEMO_ARCHIVE = "보관함"
internal const val MEMO_TRASH = "휴지통"

internal data class MemoFolder(
    val path: String,
    val icon: String,
    val count: Int,
    val unfiledCount: Int = 0,
    val pinnedCount: Int = 0,
    val recent: List<String> = emptyList(),
    val total: Int = count,
    val source: String = "기본",
) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = if ('/' in path) path.substringBeforeLast('/') else ""
    val isSpecial: Boolean get() = path == MEMO_INBOX || path == MEMO_ARCHIVE || path == MEMO_TRASH
    val isDimmed: Boolean get() = path == MEMO_ARCHIVE || path == MEMO_TRASH
}

internal data class MemoProposal(
    val id: String,
    val kind: String,
    val summary: String,
    val status: String,
    val expires: String? = null,
    val noteId: String? = null,
    val date: String? = null,
    val time: String? = null,
    val folder: String? = null,
    val targetCount: Int = 0,
) {
    val isSchedule: Boolean get() = kind == "일정추가" || kind == "일정변경" || kind == "계획반영" || kind == "알림"
    val isFolderCreate: Boolean get() = kind == "폴더생성"

    /** "재검토" = waiting for the planner again after "다른 날": no buttons. */
    val isWaiting: Boolean get() = status == "대기"
}

internal data class MemoIndex(
    val folders: List<MemoFolder>,
    val proposals: List<MemoProposal>,
    val updatedAt: String? = null,
    val splitKeys: List<String> = emptyList(),
) {
    fun folder(path: String): MemoFolder? = folders.firstOrNull { it.path == path }

    /** Number of proposals still waiting for an answer. */
    val pendingProposals: Int get() = proposals.count { it.isWaiting }

    companion object {
        val EMPTY = MemoIndex(emptyList(), emptyList())
    }
}

internal sealed interface MemoPreview {
    data class Text(val text: String) : MemoPreview
    data class Link(val url: String, val title: String?, val image: String?, val site: String?) : MemoPreview

    /**
     * [mxc] / [fileJson]: the first photo (the server list sends only that one). [photos]: the first ones of a local
     * note (앱6, at most [MEMO_CARD_PHOTOS]), each with its phone original when it is still there.
     */
    data class Photo(val mxc: String, val count: Int, val fileJson: String? = null, val photos: List<MemoAttachment> = emptyList()) : MemoPreview {
        /** The photos the record card draws in one row. */
        val cells: List<MemoAttachment> get() = photos.ifEmpty { listOf(MemoAttachment(mxc, fileJson)) }
    }
}

/** Photos drawn in one row of a record card (앱6 결정기록 3); more show as "+N" on the last one. */
internal const val MEMO_CARD_PHOTOS = 4

/** Photos of [p] that do not get their own cell on the record card (the "+N" of the last cell). */
internal fun memoHiddenPhotos(p: MemoPreview.Photo): Int = (p.count - p.cells.take(MEMO_CARD_PHOTOS).size).coerceAtLeast(0)

/** Lines of body text on a record card (앱6 항목 5); longer text ends with "…". */
internal const val MEMO_CARD_LINES = 7

/** The body text a record card shows above its chips (앱6 항목 4), or null (empty, or only the link address). */
internal fun memoCardText(item: MemoItem): String? {
    val text = (item.text ?: (item.preview as? MemoPreview.Text)?.text)?.trim() ?: return null
    val link = (item.preview as? MemoPreview.Link)?.url
    return text.takeIf { it.isNotBlank() && it != link }
}

/**
 * The end of the small top line of a record card: the title, unless the body shown below already starts with it
 * (a note's title is its first line, 앱6: shown once, in the bigger body text); the kind when there is neither.
 */
/** 앱7 항목 1: the line a list shows for a note, the stored title or else the start of its body (not stored). */
internal fun memoDisplayTitle(title: String, preview: String, id: String): String =
    // 앱11 B: never the note id (it showed on the folder tab); the screens draw `#번호  날짜` instead ([memoItemLine]).
    title.ifEmpty { preview.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(MEMO_TITLE_CHARS) }.takeIf { it != id }.orEmpty()

/** Test tag of the photo upload mark of a record card (앱8). */
internal const val MEMO_PHOTO_UPLOAD_MARK_TAG = "memo_photo_upload_mark"

/** 앱8 항목 1 d: the small mark of a record card whose photos are not on the server yet, empty when all went up. */
internal fun memoPhotoUploadMark(item: MemoItem): String = when {
    item.photosFailed > 0 -> "⚠ 사진 ${item.photosFailed}장 못 올림"
    item.photosWaiting > 0 -> "⏳ 사진 ${item.photosWaiting}장 올리기 전"
    else -> ""
}

internal fun memoCardHeadTitle(item: MemoItem, text: String?): String = when {
    item.title.isBlank() -> if (text == null) item.kind else ""
    item.untitled -> if (text == null) item.kind else ""
    text != null && text.startsWith(item.title) -> ""
    else -> item.title
}

internal data class MemoItem(
    val id: String,
    val kind: String,
    val title: String,
    val preview: MemoPreview?,
    val date: String,
    val tags: List<String>,
    val folder: String,
    val pinned: Boolean = false,
    val pinSuggested: Boolean = false,
    val scheduleState: String? = null,
    val snippet: String? = null,
    val matchField: String? = null,
    /** Waiting bot suggestions of this note (A5 chip `🤖 제안 N`, 설계 §d-③). */
    val proposals: Int = 0,
    /** The start of the plain body of a local note (앱6): the record card shows it under photos and links too. */
    val text: String? = null,
    /** 앱7 항목 1: no title was written or accepted; [title] is then only the start of the body, for the screen. */
    val untitled: Boolean = false,
    /** 앱8 항목 1 d: attachments of a local note still waiting for their upload, and those that failed. */
    val photosWaiting: Int = 0,
    val photosFailed: Int = 0,
    /** 앱10: the note number the server gave (`#N`), 0 before the first sync. [date] is the date shown. */
    val number: Int = 0,
    /** 앱11 D: the photos of a note with text, drawn under its text on the cards (null: none, or [preview] is them). */
    val photos: MemoPreview.Photo? = null,
) {
    /** "10-11" from "2026-10-11T14:30:12+09:00". */
    val shortDate: String get() = if (date.length >= 10) date.substring(5, 10) else date

    val hasSchedule: Boolean get() = scheduleState == "제안있음" || scheduleState == "수락됨"
}

/**
 * An attachment as the screen draws it. [mxc] is the server copy, or the local file ([MEMO_LOCAL_FILE_PREFIX]) before
 * the upload. [localPath] is the phone original, drawn first while it is there (앱6: also after the upload).
 */
internal data class MemoAttachment(val mxc: String, val fileJson: String? = null, val localPath: String? = null)

internal data class MemoNote(
    val id: String,
    val header: JSONObject,
    val body: String,
    val attachments: List<MemoAttachment>,
    val truncated: Boolean,
) {
    val folder: String get() = header.optString("폴더")
    val tags: List<String> get() = header.optJSONArray("태그").strings()
    val created: String get() = header.optString("생성")
    val pinned: Boolean get() = header.optBoolean("고정")

    /** 앱10: the server number (0 = none yet) and the date shown (the user's last change, else the creation). */
    val number: Int get() = header.optInt(MEMO_FIELD_NUMBER, 0)
    val shownDate: String get() = memoShownDate(created, header.optString(MEMO_FIELD_USER_MODIFIED))
}

internal data class MemoTag(
    val name: String,
    val count: Int,
    val axis: String?,
    val source: String?,
    val synonyms: List<String>,
)

internal data class MemoPage(val items: List<MemoItem>, val nextCursor: String?)

/** A response event of the memo bot, matched to a request by [requestId]. */
internal sealed interface MemoResponse {
    val requestId: String

    data class List(override val requestId: String, val page: MemoPage) : MemoResponse
    data class Search(override val requestId: String, val page: MemoPage) : MemoResponse
    data class Note(override val requestId: String, val note: MemoNote) : MemoResponse
    data class Tags(override val requestId: String, val tags: kotlin.collections.List<MemoTag>) : MemoResponse
    /** [content] = the whole answer (앱9: `지시` / `지시조회` read `상태`, `메시지`, `결과[]` from it). */
    data class Ok(
        override val requestId: String,
        val op: String,
        val id: String?,
        val path: String?,
        val content: JSONObject = JSONObject(),
    ) : MemoResponse
    data class Error(override val requestId: String, val op: String, val reason: String) : MemoResponse

    /** [v2] `app.june.memo.sync` / `app.june.memo.sync_ack`: the raw content, applied by [MemoSync]. */
    data class Sync(override val requestId: String, val content: JSONObject) : MemoResponse
    data class SyncAck(override val requestId: String, val content: JSONObject) : MemoResponse
}

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { i -> optString(i) }.filter { it.isNotEmpty() }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun parseMemoFolder(o: JSONObject): MemoFolder? {
    val path = o.optString("경로").trim('/')
    if (path.isEmpty()) return null
    val count = o.optInt("개수")
    return MemoFolder(
        path = path,
        icon = o.optString("아이콘").ifEmpty { defaultMemoFolderIcon(path) },
        count = count,
        unfiledCount = o.optInt("폴더없음수"),
        pinnedCount = o.optInt("고정수"),
        recent = o.optJSONArray("최근").strings(),
        total = if (o.has("전체")) o.optInt("전체") else count,
        source = o.optString("출처").ifEmpty { "기본" },
    )
}

internal fun defaultMemoFolderIcon(path: String): String = when (path) {
    MEMO_INBOX -> "📥"
    MEMO_ARCHIVE -> "📦"
    MEMO_TRASH -> "🗑"
    else -> "📁"
}

internal fun parseMemoProposal(o: JSONObject): MemoProposal? {
    val id = o.optString("id")
    if (id.isEmpty()) return null
    return MemoProposal(
        id = id,
        kind = o.optString("종류"),
        summary = o.optString("요약"),
        status = o.optString("상태").ifEmpty { "대기" },
        expires = o.optStringOrNull("만료"),
        noteId = o.optStringOrNull("노트"),
        date = o.optStringOrNull("날짜"),
        time = o.optStringOrNull("시간"),
        folder = o.optStringOrNull("폴더"),
        targetCount = o.optInt("대상수"),
    )
}

/** Parses the main `app.june.memo.index` state content (state_key ""). An empty content gives an empty index. */
internal fun parseMemoIndex(content: JSONObject): MemoIndex {
    val folders = content.optJSONArray("폴더")
    val proposals = content.optJSONArray("제안")
    return MemoIndex(
        folders = folders?.let { a -> List(a.length()) { i -> a.optJSONObject(i)?.let(::parseMemoFolder) }.filterNotNull() }.orEmpty(),
        proposals = proposals?.let { a -> List(a.length()) { i -> a.optJSONObject(i)?.let(::parseMemoProposal) }.filterNotNull() }.orEmpty(),
        updatedAt = content.optStringOrNull("갱신시각"),
        splitKeys = if (content.optBoolean("분할")) content.optJSONArray("폴더키").strings() else emptyList(),
    )
}

/** Adds the folders of the split state keys (규약 §1 ②) to [main]. */
internal fun mergeMemoIndex(main: MemoIndex, parts: List<JSONObject>): MemoIndex {
    if (main.splitKeys.isEmpty()) return main
    val extra = parts.flatMap { parseMemoIndex(it).folders }
    val known = main.folders.map { it.path }.toSet()
    return main.copy(folders = main.folders + extra.filter { it.path !in known })
}

internal fun parseMemoPreview(o: JSONObject?): MemoPreview? {
    if (o == null) return null
    return when (o.optString("형식")) {
        "텍스트" -> MemoPreview.Text(o.optString("글"))
        "링크" -> MemoPreview.Link(
            url = o.optString("url"),
            title = o.optStringOrNull("제목"),
            image = o.optStringOrNull("이미지"),
            site = o.optStringOrNull("사이트"),
        )
        "사진" -> {
            val mxc = o.optString("mxc")
            if (mxc.isEmpty()) null else MemoPreview.Photo(mxc, o.optInt("장수", 1).coerceAtLeast(1), o.optJSONObject("file")?.toString())
        }
        else -> null
    }
}

internal fun parseMemoItem(o: JSONObject): MemoItem? {
    val id = o.optString("id")
    if (id.isEmpty()) return null
    return MemoItem(
        id = id,
        kind = o.optString("종류"),
        title = o.optString("제목").ifEmpty { id },
        preview = parseMemoPreview(o.optJSONObject("미리보기")),
        date = o.optString("날짜"),
        tags = o.optJSONArray("태그").strings(),
        folder = o.optString("폴더"),
        pinned = o.optBoolean("고정"),
        pinSuggested = o.optBoolean("고정제안"),
        scheduleState = o.optStringOrNull("일정상태"),
        snippet = o.optStringOrNull("일치구간"),
        matchField = o.optStringOrNull("일치필드"),
    )
}

private fun parseMemoPage(content: JSONObject): MemoPage {
    val items = content.optJSONArray("항목")
    return MemoPage(
        items = if (items == null) emptyList() else List(items.length()) { i -> items.optJSONObject(i)?.let(::parseMemoItem) }.filterNotNull(),
        nextCursor = content.optStringOrNull("다음cursor"),
    )
}

private fun parseAttachments(arr: JSONArray?): List<MemoAttachment> {
    if (arr == null) return emptyList()
    return List(arr.length()) { i ->
        val o = arr.optJSONObject(i)
        if (o != null) {
            o.optString("mxc").takeIf { it.isNotEmpty() }?.let { MemoAttachment(it, o.optJSONObject("file")?.toString()) }
        } else {
            arr.optString(i).takeIf { it.isNotEmpty() }?.let { MemoAttachment(it) }
        }
    }.filterNotNull()
}

/** Parses a response event of [type] (one of [MEMO_RESPONSE_TYPES]); null when it is not a memo response. */
internal fun parseMemoResponse(type: String, content: JSONObject): MemoResponse? {
    val rid = content.optString("요청id")
    if (rid.isEmpty()) return null
    return when (type) {
        MEMO_LIST_TYPE -> MemoResponse.List(rid, parseMemoPage(content))
        MEMO_SEARCH_TYPE -> MemoResponse.Search(rid, parseMemoPage(content))
        MEMO_NOTE_TYPE -> MemoResponse.Note(
            rid,
            MemoNote(
                id = content.optString("id"),
                header = content.optJSONObject("머리말") ?: JSONObject(),
                body = content.optString("본문"),
                attachments = parseAttachments(content.optJSONArray("첨부")),
                truncated = content.optBoolean("잘림"),
            ),
        )
        MEMO_TAGS_TYPE -> {
            val arr = content.optJSONArray("태그")
            val tags = if (arr == null) {
                emptyList()
            } else {
                List(arr.length()) { i ->
                    arr.optJSONObject(i)?.let { o ->
                        MemoTag(
                            name = o.optString("이름"),
                            count = o.optInt("수"),
                            axis = o.optStringOrNull("축"),
                            source = o.optStringOrNull("출처"),
                            synonyms = o.optJSONArray("동의어").strings(),
                        )
                    }
                }.filterNotNull().filter { it.name.isNotEmpty() }
            }
            MemoResponse.Tags(rid, tags)
        }
        MEMO_OK_TYPE -> MemoResponse.Ok(rid, content.optString("op"), content.optStringOrNull("id"), content.optStringOrNull("경로"), content)
        MEMO_ERROR_TYPE -> MemoResponse.Error(rid, content.optString("op"), content.optString("사유").ifEmpty { "알 수 없는 오류" })
        MEMO_SYNC_TYPE -> MemoResponse.Sync(rid, content)
        MEMO_SYNC_ACK_TYPE -> MemoResponse.SyncAck(rid, content)
        else -> null
    }
}

/** Parses a whole Matrix event JSON (`{"type": …, "content": {…}}`) into a memo response, or null. */
internal fun parseMemoResponseEvent(eventJson: String?): MemoResponse? {
    if (eventJson.isNullOrEmpty()) return null
    return try {
        val o = JSONObject(eventJson)
        val type = o.optString("type")
        if (type !in MEMO_RESPONSE_TYPES) return null
        parseMemoResponse(type, o.optJSONObject("content") ?: return null)
    } catch (e: Exception) {
        null
    }
}
