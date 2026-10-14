/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

/** The four note kinds of the protocol, in the order of the filter chips. */
internal val MEMO_KINDS = listOf("사진", "링크", "텍스트", "음성")

internal sealed interface MemoPeriod {
    val label: String
    fun toJson(): Any

    data object Today : MemoPeriod {
        override val label = "오늘"
        override fun toJson(): Any = "오늘"
    }

    data object Days7 : MemoPeriod {
        override val label = "7일"
        override fun toJson(): Any = "7일"
    }

    data object Days30 : MemoPeriod {
        override val label = "30일"
        override fun toJson(): Any = "30일"
    }

    data class Range(val start: String, val end: String) : MemoPeriod {
        override val label get() = "${start.takeLast(5)}~${end.takeLast(5)}"
        override fun toJson(): Any = linkedMapOf("시작" to start, "끝" to end)
    }
}

private val TAG_TOKEN = Regex("(?<=^|\\s)#([^\\s#]+)(?=\\s|$)")

/**
 * The search tab query: free text + tag chips (AND) + filter chips. All three tag paths
 * (empty state chip, `#` autocomplete, result tag chip) end up in [tags].
 */
internal data class MemoSearchQuery(
    val text: String = "",
    val tags: List<String> = emptyList(),
    val kinds: Set<String> = emptySet(),
    val folder: String? = null,
    val period: MemoPeriod? = null,
    val schedule: Boolean = false,
    val pinned: Boolean = false,
    val includeHidden: Boolean = false,
) {
    /** Nothing to search: the tab shows its empty state. */
    val isEmpty: Boolean
        get() = text.isBlank() && tags.isEmpty() && kinds.isEmpty() && folder == null && period == null && !schedule && !pinned

    fun withTag(tag: String): MemoSearchQuery {
        val t = tag.removePrefix("#").trim()
        if (t.isEmpty() || t in tags) return this
        return copy(tags = tags + t)
    }

    fun withoutTag(tag: String) = copy(tags = tags - tag)

    fun toggleKind(kind: String) = copy(kinds = if (kind in kinds) kinds - kind else kinds + kind)

    /** Complete `#word` tokens typed in the text become tag chips; the unfinished last one stays (it is being typed). */
    fun normalized(keepTyping: Boolean = false): MemoSearchQuery {
        val typing = if (keepTyping) memoTagPrefix(text) else null
        val body = if (typing != null) text.dropLast(typing.length + 1) else text
        val found = TAG_TOKEN.findAll(body).map { it.groupValues[1] }.toList()
        if (found.isEmpty()) return this
        val rest = TAG_TOKEN.replace(body, "").replace(Regex("\\s+"), " ").trim()
        var q = copy(text = if (typing != null) "$rest #$typing".trimStart() else rest)
        found.forEach { q = q.withTag(it) }
        return q
    }

    /** The `filters` object of the search op (이벤트규약 §2). Empty filters are not sent. */
    fun filters(): Map<String, Any?> = linkedMapOf(
        "tags" to tags.takeIf { it.isNotEmpty() },
        "종류" to MEMO_KINDS.filter { it in kinds }.takeIf { it.isNotEmpty() },
        "폴더" to folder,
        "기간" to period?.toJson(),
        "일정연결" to true.takeIf { schedule },
        "고정" to true.takeIf { pinned },
        "포함" to listOf(MEMO_INBOX, MEMO_TRASH).takeIf { includeHidden },
    )

    /** The query sent to the server: free text only, tags go in the filters. */
    fun serverText(): String = normalized().text.trim()

    /** "#교토 AND "가와라마치"" for the result header. */
    fun describe(): String {
        val q = normalized()
        val parts = q.tags.map { "#$it" } + listOfNotNull(q.text.trim().takeIf { it.isNotEmpty() }?.let { "\"$it\"" })
        return parts.joinToString(" AND ")
    }
}

/** How many recent searches the empty search tab keeps. */
internal const val MEMO_RECENT_SEARCHES = 5

/**
 * The recent searches after [q] was submitted: [q] first, no duplicate, at most [limit]. A query without text and tags
 * (only filter chips, [MemoSearchQuery.describe] empty) is never kept — it would show as an empty line.
 */
internal fun memoRecentWith(recent: List<MemoSearchQuery>, q: MemoSearchQuery, limit: Int = MEMO_RECENT_SEARCHES): List<MemoSearchQuery> {
    val kept = recent.filter { it.describe().isNotEmpty() }
    if (q.describe().isEmpty()) return kept.take(limit)
    return (listOf(q) + kept.filter { it != q }).take(limit)
}

/** The tag being typed at the end of [text] ("가와라마치 #이" → "이"), or null when the last word is not a `#` word. */
internal fun memoTagPrefix(text: String): String? {
    val last = text.substringAfterLast(' ').substringAfterLast('\n')
    if (!last.startsWith("#")) return null
    val prefix = last.substring(1)
    return if ('#' in prefix) null else prefix
}

/** Replaces the `#prefix` being typed with the chip [tag]. */
internal fun MemoSearchQuery.applyTagSuggestion(tag: String): MemoSearchQuery {
    val prefix = memoTagPrefix(text)
    val newText = if (prefix != null) text.dropLast(prefix.length + 1).trimEnd() else text
    return copy(text = newText).withTag(tag)
}

/** Autocomplete: partial match on name or synonym, names starting with [prefix] first, then by use count. */
internal fun suggestMemoTags(all: List<MemoTag>, prefix: String, selected: List<String>, limit: Int = 8): List<MemoTag> {
    val p = prefix.trim()
    return all.asSequence()
        .filter { it.name !in selected }
        .filter { p.isEmpty() || it.name.contains(p, ignoreCase = true) || it.synonyms.any { s -> s.contains(p, ignoreCase = true) } }
        .sortedWith(compareByDescending<MemoTag> { p.isNotEmpty() && it.name.startsWith(p, ignoreCase = true) }.thenByDescending { it.count })
        .take(limit)
        .toList()
}

/** Tags of the empty state, grouped by axis (주제·장소·시간·행동·상태 first, then the others), most used first. */
internal fun memoTagsByAxis(all: List<MemoTag>): List<Pair<String, List<MemoTag>>> {
    val order = listOf("주제", "장소", "시간", "행동", "상태")
    return all.groupBy { it.axis ?: "기타" }
        .map { (axis, tags) -> axis to tags.sortedByDescending { it.count } }
        .sortedBy { (axis, _) -> order.indexOf(axis).let { if (it < 0) order.size else it } }
}

/** A piece of a search snippet: [marked] parts were inside `<mark>…</mark>`. */
internal data class MemoSnippetPart(val text: String, val marked: Boolean)

/** Splits the server snippet (HTML-escaped text with only `<mark>` tags) into plain and highlighted parts. */
internal fun parseMemoSnippet(snippet: String): List<MemoSnippetPart> {
    val parts = mutableListOf<MemoSnippetPart>()
    var rest = snippet
    while (rest.isNotEmpty()) {
        val start = rest.indexOf("<mark>")
        val end = if (start < 0) -1 else rest.indexOf("</mark>", start + 6)
        rest = when {
            start < 0 -> {
                parts += MemoSnippetPart(unescapeHtml(rest), false)
                ""
            }
            end < 0 -> {
                parts += MemoSnippetPart(unescapeHtml(rest.substring(0, start)), false)
                parts += MemoSnippetPart(unescapeHtml(rest.substring(start + 6)), true)
                ""
            }
            else -> {
                parts += MemoSnippetPart(unescapeHtml(rest.substring(0, start)), false)
                parts += MemoSnippetPart(unescapeHtml(rest.substring(start + 6, end)), true)
                rest.substring(end + 7)
            }
        }
    }
    return parts.filter { it.text.isNotEmpty() }
}

/** "사진 글자: " etc. in front of the snippet, from the match field. */
internal fun memoMatchPrefix(field: String?): String = when (field) {
    "사진글자" -> "사진 글자: "
    "음성" -> "음성: "
    "링크" -> "링크: "
    else -> ""
}

private fun unescapeHtml(s: String): String = s
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&#x27;", "'")
    .replace("&amp;", "&")
