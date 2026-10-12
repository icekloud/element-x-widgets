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

    /** Number of proposals still waiting for an answer (folder tab badge). */
    val pendingProposals: Int get() = proposals.count { it.isWaiting }

    companion object {
        val EMPTY = MemoIndex(emptyList(), emptyList())
    }
}

internal sealed interface MemoPreview {
    data class Text(val text: String) : MemoPreview
    data class Link(val url: String, val title: String?, val image: String?, val site: String?) : MemoPreview
    data class Photo(val mxc: String, val count: Int, val fileJson: String? = null) : MemoPreview
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
) {
    /** "10-11" from "2026-10-11T14:30:12+09:00". */
    val shortDate: String get() = if (date.length >= 10) date.substring(5, 10) else date

    val hasSchedule: Boolean get() = scheduleState == "제안있음" || scheduleState == "수락됨"
}

internal data class MemoAttachment(val mxc: String, val fileJson: String? = null)

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
    data class Ok(override val requestId: String, val op: String, val id: String?, val path: String?) : MemoResponse
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
        MEMO_OK_TYPE -> MemoResponse.Ok(rid, content.optString("op"), content.optStringOrNull("id"), content.optStringOrNull("경로"))
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
