/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

/*
 * Record tab: the room timeline drawn as cards. One card = the inputs of the admin (text, photos, link)
 * + the one line answer of the memo bot about them, parsed into chips.
 * Pure Kotlin so the grouping and the parsing are unit tested.
 */

/** What an input or an answer is made of. */
internal enum class MemoEntryKind { Text, Photo, Link, Voice, File, Other }

/** One timeline message, already reduced to what the record tab needs. [key] is the timeline item id. */
internal data class MemoEntry(
    val key: String,
    val eventId: String?,
    val isMine: Boolean,
    val timeMillis: Long,
    val kind: MemoEntryKind,
    val text: String,
    val replyToEventId: String? = null,
    /** Note id carried by the bot answer (`content["app.june.memo"].id`, 이벤트규약-변경 1), when the server adds it. */
    val noteId: String? = null,
)

internal enum class MemoReviewState(val label: String) {
    Reviewing("⏳ 플랜 검토 중"),
    Proposed("📅 제안 도착"),
    NotNeeded("검토 불필요"),
}

/** The bot one line answer (메모봇-AGENTS.md §8), parsed. */
internal data class MemoReplyInfo(
    val folder: String?,
    val tags: List<String>,
    val review: MemoReviewState?,
    val pinSuggested: Boolean,
    val inbox: Boolean,
    val confidence: Double?,
    val candidates: List<String>,
) {
    val isEmpty: Boolean get() = folder == null && tags.isEmpty() && review == null && !inbox && !pinSuggested
}

internal const val MEMO_MAX_CARD_TAGS = 6
private const val PHOTO_GROUP_WINDOW_MILLIS = 60_000L

private val CONFIDENCE = Regex("확신\\s*([0-9]*\\.?[0-9]+)")
private val TAG_WORD = Regex("#([^\\s#·]+)")

/** Parses "📁 여행 · #빵집 #교토 · 📅 플랜 검토 요청" / "📥 받은편지함(확신 0.4) · 후보: 아이디어 / 링크보관". Null if it is not such a line. */
internal fun parseMemoReply(text: String): MemoReplyInfo? {
    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("📁") || it.startsWith("📥") } ?: return null
    val segments = line.split('·').map { it.trim() }.filter { it.isNotEmpty() }
    var folder: String? = null
    var inbox = false
    var confidence: Double? = null
    val tags = mutableListOf<String>()
    var review: MemoReviewState? = null
    var pin = false
    var candidates = emptyList<String>()
    for (seg in segments) {
        when {
            seg.startsWith("📁") -> folder = seg.removePrefix("📁").trim().takeIf { it.isNotEmpty() }
            seg.startsWith("📥") -> {
                inbox = true
                folder = MEMO_INBOX
                confidence = CONFIDENCE.find(seg)?.groupValues?.get(1)?.toDoubleOrNull()
            }
            seg.startsWith("후보") -> candidates = seg.substringAfter(':', "").split('/', ',').map { it.trim() }.filter { it.isNotEmpty() }.take(2)
            seg.startsWith("📅") || seg.startsWith("⏳") || "검토" in seg -> review = when {
                "불필요" in seg -> MemoReviewState.NotNeeded
                "도착" in seg -> MemoReviewState.Proposed
                "제안" in seg && "요청" !in seg -> MemoReviewState.Proposed
                else -> MemoReviewState.Reviewing
            }
            seg.startsWith("📌") -> pin = true
            seg.startsWith("#") -> tags += TAG_WORD.findAll(seg).map { it.groupValues[1] }
            else -> {
                if ("확신" in seg && confidence == null) confidence = CONFIDENCE.find(seg)?.groupValues?.get(1)?.toDoubleOrNull()
            }
        }
    }
    return MemoReplyInfo(
        folder = folder,
        tags = tags.distinct().take(MEMO_MAX_CARD_TAGS),
        review = review,
        pinSuggested = pin,
        inbox = inbox,
        confidence = confidence,
        candidates = candidates,
    )
}

internal data class MemoCard(
    val key: String,
    val inputs: List<MemoEntry>,
    val replies: List<MemoEntry>,
) {
    /** The first answer that parses as a memo line. */
    val info: MemoReplyInfo? by lazy { replies.firstNotNullOfOrNull { parseMemoReply(it.text) } }

    val isBotOnly: Boolean get() = inputs.isEmpty()
    val photoCount: Int get() = inputs.count { it.kind == MemoEntryKind.Photo }
    val timeMillis: Long get() = (inputs.firstOrNull() ?: replies.firstOrNull())?.timeMillis ?: 0L

    /** What note ops of this card point at: the note id of the answer, or the first input event. */
    val target: MemoTarget
        get() = MemoTarget(
            noteId = replies.firstNotNullOfOrNull { it.noteId },
            eventId = inputs.firstNotNullOfOrNull { it.eventId },
        )

    /** "https://… " / "사진 2장" / "\"환전 10만엔 더\"" for the grey header line. */
    fun headline(): String {
        val photos = photoCount
        val text = inputs.firstOrNull { it.kind == MemoEntryKind.Text || it.kind == MemoEntryKind.Link || it.kind == MemoEntryKind.Voice }
        return when {
            photos > 0 && text != null -> "사진 ${photos}장 · ${text.text.take(40)}"
            photos > 0 -> "사진 ${photos}장"
            text?.kind == MemoEntryKind.Link -> text.text.trim().take(60)
            text?.kind == MemoEntryKind.Voice -> "🎤 ${text.text.take(40)}"
            text != null -> "\"${text.text.trim().take(40)}\""
            else -> inputs.firstOrNull()?.text?.take(40).orEmpty()
        }
    }
}

/**
 * Groups [entries] (oldest first) into cards:
 * - an input of the admin opens a new card, except a photo coming within 60 s after a photo of a card
 *   that has no answer yet (same result as the June photo groups);
 * - an answer goes to the card of the input it replies to (m.relates_to), otherwise to the oldest card
 *   without an answer, otherwise it is a card of its own (proposal notices, etc.).
 */
internal fun buildMemoCards(entries: List<MemoEntry>): List<MemoCard> {
    data class Building(val key: String, val inputs: MutableList<MemoEntry>, val replies: MutableList<MemoEntry>)

    val cards = mutableListOf<Building>()
    val byEvent = mutableMapOf<String, Building>()
    for (e in entries) {
        if (e.isMine) {
            val last = cards.lastOrNull()
            val lastInput = last?.inputs?.lastOrNull()
            val joins = last != null && last.replies.isEmpty() && e.kind == MemoEntryKind.Photo &&
                lastInput?.kind == MemoEntryKind.Photo && e.timeMillis - lastInput.timeMillis in 0..PHOTO_GROUP_WINDOW_MILLIS
            val card = if (joins) last else Building(e.key, mutableListOf(), mutableListOf()).also { cards += it }
            card.inputs += e
            e.eventId?.let { byEvent[it] = card }
        } else {
            val target = e.replyToEventId?.let { byEvent[it] }
                ?: cards.firstOrNull { it.inputs.isNotEmpty() && it.replies.isEmpty() }
            if (target != null) {
                target.replies += e
            } else {
                cards += Building(e.key, mutableListOf(), mutableListOf(e))
            }
        }
    }
    return cards.map { MemoCard(it.key, it.inputs.toList(), it.replies.toList()) }
}

/** The chip actions of a card, mapped to memo ops (요구 B1). */
internal sealed interface MemoCardAction {
    data object OpenFolderSheet : MemoCardAction
    data object OpenTagSheet : MemoCardAction
    data object Pin : MemoCardAction
    data class MoveTo(val folder: String) : MemoCardAction
    data object Trash : MemoCardAction
    data object Unpin : MemoCardAction
}

/** The op sent for [action] on [card]; null for the actions that first open a sheet. */
internal fun memoCardOp(card: MemoCard, action: MemoCardAction): MemoOp? {
    val target = card.target
    if (!target.isKnown) return null
    return when (action) {
        MemoCardAction.OpenFolderSheet, MemoCardAction.OpenTagSheet -> null
        MemoCardAction.Pin -> MemoOp.pin(target)
        MemoCardAction.Unpin -> MemoOp.unpin(target)
        MemoCardAction.Trash -> MemoOp.trash(target)
        is MemoCardAction.MoveTo -> MemoOp.move(target, action.folder)
    }
}
