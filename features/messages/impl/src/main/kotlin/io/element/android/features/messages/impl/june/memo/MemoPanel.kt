/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * 앱9: the proposal area of the memo bot room. It opens from the list icon of the top bar (only in the memo bot room)
 * instead of the folder tab cards. The pure parts live here (text, grouping, answers, the open state); the screen is in
 * MemoPanelView.kt.
 */

/** Status the local index gives a proposal answered here whose answer has not reached the server yet (앱9 C). */
internal const val MEMO_PROPOSAL_SENDING = "처리중"

private val LEADING_PROPOSAL = Regex("^\\s*(?:🤖\\s*)?제안\\s*[·:：\\-–—]\\s*")
private val KIND_PROPOSAL = Regex("^(새 폴더|새 태그|일정|제목|폴더|태그|분류)\\s*제안\\s*[:：·]\\s*")
private val SPACES = Regex("\\s+")

/**
 * The card line of a proposal without the word "제안" in front (관리자 요구 3): the area title already says it.
 * "제안 · 새 태그 #포켓몬카드 를 답니다" → "새 태그 #포켓몬카드 를 답니다", "새 폴더 제안: 영상" → "새 폴더: 영상".
 */
internal fun memoProposalText(summary: String): String {
    val noLead = LEADING_PROPOSAL.replace(summary.trim(), "")
    val noKind = KIND_PROPOSAL.replace(noLead) { it.groupValues[1] + ": " }
    return SPACES.replace(noKind, " ").trim()
}

/** A short mark of the kind, only where the text itself does not say it (새 태그 / 새 폴더 are in the text). */
internal fun memoProposalMark(p: MemoProposal): String? = when {
    p.isSchedule -> "📅"
    p.isFolderCreate -> "📁"
    p.kind == "제목" -> "🏷"
    else -> null
}

/** The whole card line: mark and text, the kind when the server sent no text. */
internal fun memoProposalLine(p: MemoProposal): String =
    listOfNotNull(memoProposalMark(p), memoProposalText(p.summary).ifEmpty { p.kind }).joinToString(" ")

/**
 * Proposals shown as one card (앱9 C): same note, same kind, same text and same state. The server merges them from
 * 서버 4차 on; the cards made before stay until they are answered.
 */
internal data class MemoProposalGroup(val proposals: List<MemoProposal>) {
    init {
        require(proposals.isNotEmpty())
    }

    val first: MemoProposal get() = proposals.first()
    val ids: List<String> get() = proposals.map { it.id }
    val key: String get() = "g-" + first.id
    val isWaiting: Boolean get() = first.isWaiting
    val isSending: Boolean get() = first.status == MEMO_PROPOSAL_SENDING

    /** 앱18 ②: the earliest time the phone got one of its proposals, 0 when none is known. */
    val seenMs: Long get() = proposals.map { it.seenMs }.filter { it > 0 }.minOrNull() ?: 0

    companion object {
        fun of(p: MemoProposal) = MemoProposalGroup(listOf(p))
    }
}

private fun groupKey(p: MemoProposal) = listOf(p.noteId.orEmpty(), p.kind, memoProposalText(p.summary), p.status, p.folder.orEmpty(), p.date.orEmpty())

/** The cards of the proposal area, in the order of the index. */
internal fun memoPanelGroups(index: MemoIndex): List<MemoProposalGroup> =
    index.proposals.groupBy(::groupKey).values.map(::MemoProposalGroup)

/** 앱18 ②: one row of the proposal area, a card or an instruction, and the time it is placed by (0 = not known). */
internal sealed interface MemoPanelEntry {
    val key: String
    val atMs: Long

    /** What changes the height of the row: the area follows it down when it is at the bottom. */
    val look: String

    data class Card(val group: MemoProposalGroup) : MemoPanelEntry {
        override val key: String get() = group.key
        override val atMs: Long get() = group.seenMs
        override val look: String get() = group.first.status
    }

    data class Order(val directive: MemoDirective) : MemoPanelEntry {
        override val key: String get() = "d-" + directive.id
        override val atMs: Long get() = directive.sentMs
        override val look: String get() = directive.status + directive.message.length
    }
}

/**
 * 앱18 ② (관리자: "제안탭 진입 시 채팅스크롤이 제일 하단부에 위치하도록 되어 제일 최근 내용부터"): the cards and the instructions
 * in one list, oldest on top and newest at the bottom, like a chat. A card is placed by the time the phone got it, an
 * instruction by the time it was sent. A row without a time (a card of the fake data, or one from before 앱18 without
 * its row time) goes on top. Rows of the same time keep their order: the cards in the order of the index, then the
 * instructions in the order they were sent.
 */
internal fun memoPanelTimeline(groups: List<MemoProposalGroup>, directives: List<MemoDirective>): List<MemoPanelEntry> =
    (groups.map { MemoPanelEntry.Card(it) } + directives.map { MemoPanelEntry.Order(it) }).sortedBy { it.atMs }

/** 앱18 ②: why the rows of the area changed or the area asks to be at the bottom. */
internal enum class MemoPanelScroll {
    /** The area was opened. */
    Open,

    /** "새로고침" was pressed. */
    Refresh,

    /** An instruction was sent from the input. */
    Send,

    /** A row came, went or changed on its own (a sync, an answer of the bot, "지우기"). */
    Change,
}

/**
 * 앱18 ②: whether the area goes down to its newest row. Opening, a refresh and a send of the user always do; a change on
 * its own only when the list was at the bottom ([atBottom]), so it never takes the list from the user reading above.
 */
internal fun memoPanelFollows(cause: MemoPanelScroll, atBottom: Boolean): Boolean = cause != MemoPanelScroll.Change || atBottom

/** The number on the list icon: the cards still waiting for an answer (0 = no badge). */
internal fun memoPanelWaitingCount(index: MemoIndex): Int = memoPanelGroups(index).count { it.isWaiting }

internal enum class MemoAnswer { Accept, Dismiss, Defer }

/**
 * The answers one press sends (앱9 C): every proposal id of the card exactly once. The same content is applied only
 * once: [MemoAnswer.Accept] (and "다른 날") goes to the first id, the copies are dismissed. [MemoAnswer.Dismiss] and a
 * plain [MemoAnswer.Defer] go to every id. Only waiting proposals get an answer (nothing for a card being sent).
 */
internal fun memoGroupOps(group: MemoProposalGroup, answer: MemoAnswer, date: String? = null, folder: String? = null): List<MemoOp> {
    val ids = group.proposals.filter { it.isWaiting }.map { it.id }
    if (ids.isEmpty()) return emptyList()
    val copies = ids.drop(1).map { MemoOp.dismiss(it) }
    return when (answer) {
        MemoAnswer.Accept -> listOf(MemoOp.accept(ids.first(), folder)) + copies
        MemoAnswer.Dismiss -> ids.map { MemoOp.dismiss(it) }
        MemoAnswer.Defer -> if (date != null) listOf(MemoOp.defer(ids.first(), date)) + copies else ids.map { MemoOp.defer(it, null) }
    }
}

/** Which memo bot room has its proposal area open, and the badge number of each room (top bar ↔ memo screen). */
internal object MemoPanelHub {
    private val open = MutableStateFlow<String?>(null)
    private val counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    val openRoom: StateFlow<String?> = open.asStateFlow()
    val waiting: StateFlow<Map<String, Int>> = counts.asStateFlow()

    fun open(roomId: String) {
        open.value = roomId
    }

    fun close() {
        open.value = null
    }

    fun setCount(roomId: String, count: Int) {
        counts.update { it + (roomId to count.coerceAtLeast(0)) }
    }

    /** The memo screen went away: the area closes with it. */
    fun leave(roomId: String) {
        if (open.value == roomId) open.value = null
        counts.update { it - roomId }
    }
}
