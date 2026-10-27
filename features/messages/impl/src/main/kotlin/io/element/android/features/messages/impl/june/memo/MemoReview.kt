/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

/*
 * 앱17 (관리자: "메모 카드를 좌측으로 슬라이드하면 해당 카드에 대해 ai검토를 즉시", "여러개 선택한 상태에서 선택대상들 검토",
 * "편집화면에서도 고정버튼 좌측에 검토 버튼"): the AI review a note gets at once when asked for it. `op: "review"` puts the
 * notes on the server's review queue right away (no waiting, no duplicate check); `op: "review_status"` tells how the
 * last asked review of each note ended. The rules are pure here; the screens are in MemoReviewViews.kt.
 */

internal const val MEMO_OP_REVIEW = "review"
internal const val MEMO_OP_REVIEW_STATUS = "review_status"

/** Most notes one `review` takes (규약: ids 1~30); more are sent in turns of this many. */
internal const val MEMO_REVIEW_MAX = 30

/** A second press for the same note within this time sends nothing (연타). */
internal const val MEMO_REVIEW_REPEAT_MS = 3_000L

/**
 * After a `review`, `review_status` is asked every [MEMO_REVIEW_POLL_MS] for at most [MEMO_REVIEW_FOLLOW_MS]. 앱29 ④: 5 s
 * for 5 minutes (60 look-ups at most; before 3 s for 2 minutes, 40), so a long review still ends with its pull.
 */
internal const val MEMO_REVIEW_POLL_MS = 5_000L
internal const val MEMO_REVIEW_FOLLOW_MS = 300_000L

/** 앱29 ④: one follow-up of a review: the notes and when it started (its 5 minutes count from there). */
internal data class MemoReviewFollow(val ids: List<String>, val startMs: Long)

/** 앱29 ④: a follow-up stopped by the screen going away is picked up again only this long after its start. */
internal const val MEMO_REVIEW_PAUSED_KEEP_MS = 30 * 60_000L

/** 앱29 ④: at most this many stopped follow-ups are kept for the next screen open. */
internal const val MEMO_REVIEW_PAUSED_MAX = 10

/** A card pushed to the left this part of its width or more asks for its review when let go. */
internal const val MEMO_REVIEW_SWIPE_FRACTION = 0.3f

/** The notice under the error banner stays this long (the request taken). */
internal const val MEMO_NOTICE_SHOW_MS = 4_000L

/** 앱18 ③: a result (검토 완료, 검토 실패, 검토 요청 실패) stays longer; a tap still closes it at once. */
internal const val MEMO_NOTICE_RESULT_SHOW_MS = 8_000L

/** 앱18 ③: the longest reason of "제안 없음" the notice shows (the banner has two lines). */
internal const val MEMO_REVIEW_REASON_CHARS = 60

/** What the screen reader offers on a card, and the label of the review button and the swipe hint. */
internal const val MEMO_REVIEW_ACTION = "AI 검토 요청"
internal const val MEMO_REVIEW_MARK = "✨"

/**
 * 앱21 ① (관리자: "노트 내에서 검토 버튼 클릭할때 입력창을 통해 메모봇에게 재검토 시 추가로 지시할 내용"): the optional `지시` of
 * a `review` (서버 15차 규약 §1). Without a usable [directive] the op is exactly the one of before (no `지시` key at all).
 */
internal fun memoReviewOp(ids: List<String>, directive: String? = null): MemoOp {
    val args = linkedMapOf<String, Any?>("v" to 1, "ids" to ids)
    memoReviewDirective(directive)?.let { args[MEMO_ARG_DIRECTIVE] = it }
    return MemoOp(MEMO_OP_REVIEW, args)
}

/** 앱21 ①: the argument name of the directive and its longest length (규약: the server keeps the first 500 too). */
internal const val MEMO_ARG_DIRECTIVE = "지시"
internal const val MEMO_REVIEW_DIRECTIVE_MAX = 500

/** 앱29 ⑤: the server's limit of the directives it adds up for one note (서버 24차); `잘림` of an answer means this one. */
internal const val MEMO_REVIEW_DIRECTIVE_MERGED_MAX = 2000

/**
 * 앱21 ①: the directive as it is sent: spaces and empty lines around it taken away, at most [MEMO_REVIEW_DIRECTIVE_MAX]
 * characters, null when nothing is left (then the review goes without it, as before). The lines inside stay.
 */
internal fun memoReviewDirective(text: String?): String? = text?.trim()?.take(MEMO_REVIEW_DIRECTIVE_MAX)?.trimEnd()?.takeIf { it.isNotEmpty() }

/** 앱21 ①: what the input field keeps of a new value: never more than [MEMO_REVIEW_DIRECTIVE_MAX] characters. */
internal fun memoReviewDirectiveInput(value: String): String = value.take(MEMO_REVIEW_DIRECTIVE_MAX)

/** 앱21 ①: the counter under the field, "12/500". */
internal fun memoReviewDirectiveCount(value: String): String = "${value.length}/$MEMO_REVIEW_DIRECTIVE_MAX"

internal fun memoReviewStatusOp(ids: List<String>) = MemoOp(MEMO_OP_REVIEW_STATUS, linkedMapOf("v" to 1, "ids" to ids))

/** The ids of one press in the turns they are sent in: each id once, at most [MEMO_REVIEW_MAX] a turn, in order. */
internal fun memoReviewBatches(ids: List<String>): List<List<String>> = ids.filter { it.isNotEmpty() }.distinct().chunked(MEMO_REVIEW_MAX)

// ---------------------------------------------------------------- A: the swipe of a card

/**
 * A card can be pushed to the left only on a list itself: not over an open note (or its editor), the tag manager, a
 * photo, or in the selection mode. The tab swipe of 앱11 추가요청 2 that sat here is gone (앱17 D): the cards take the
 * sideways move now. A card in the trash cannot be pushed (the server would skip it).
 */
internal fun memoReviewSwipeAllowed(location: MemoLocation, selecting: Boolean, viewer: Boolean, folder: String = ""): Boolean {
    val onList = location.note == null && !location.editing && !location.tagManager
    return onList && !(selecting || viewer) && folder != MEMO_TRASH
}

/** Where the card is after a move of [delta] px: only to the left, at most its whole width. */
internal fun memoReviewSwipeOffset(current: Float, delta: Float, widthPx: Float): Float = (current + delta).coerceIn(-widthPx.coerceAtLeast(0f), 0f)

/** True when the card is pushed far enough to ask for the review when let go. */
internal fun memoReviewSwipeReached(offsetPx: Float, widthPx: Float, fraction: Float = MEMO_REVIEW_SWIPE_FRACTION): Boolean =
    widthPx > 0f && -offsetPx >= widthPx * fraction

/**
 * The words behind a pushed card. 앱31 추가요청 1: letting go now opens the directive sheet instead of sending at once,
 * so the words say that ("놓으면 검토" would promise a send that no longer happens).
 */
internal fun memoReviewSwipeHint(reached: Boolean): String = if (reached) "$MEMO_REVIEW_MARK 놓으면 지시 입력" else "$MEMO_REVIEW_MARK 검토"

/** 앱31 추가요청 1 ②: a card pushed for a note asked for less than [MEMO_REVIEW_REPEAT_MS] ago opens nothing and says this. */
internal const val MEMO_REVIEW_RECENT_NOTICE = "방금 검토를 요청한 노트입니다"

// ---------------------------------------------------------------- C: the review button of an open note

/** The review button of an open note, and whether a press does anything now. */
internal data class MemoReviewButton(val enabled: Boolean)

/**
 * 앱17 C: the open note shows the review button left of its 📌 button. Not while editing (the reading head is gone then,
 * as the 📌 button), not on a note in the trash; in the selection mode it is shown but does nothing (as the 📌 button).
 */
internal fun memoReviewButton(trashed: Boolean, editing: Boolean, selecting: Boolean): MemoReviewButton? = when {
    editing || trashed -> null
    else -> MemoReviewButton(enabled = !selecting)
}

// ---------------------------------------------------------------- 연타: one send per note every 3 s

/** Remembers when each note was last asked for, so a fast second press sends nothing. */
internal class MemoReviewRepeat(private val clock: () -> Long = System::currentTimeMillis) {
    private val last = mutableMapOf<String, Long>()

    /** The ids of [ids] that may go now; they count as sent from this moment. */
    @Synchronized
    fun admit(ids: List<String>): List<String> {
        val now = clock()
        val fresh = ids.filter { it.isNotEmpty() }.distinct().filter { id -> last[id]?.let { now - it >= MEMO_REVIEW_REPEAT_MS } ?: true }
        fresh.forEach { last[it] = now }
        last.entries.removeAll { now - it.value >= MEMO_REVIEW_REPEAT_MS && it.key !in fresh }
        return fresh
    }

    /** 앱21 ①: true while [id] was sent less than [MEMO_REVIEW_REPEAT_MS] ago (its button does nothing then, as before). */
    @Synchronized
    fun recent(id: String): Boolean = last[id]?.let { clock() - it < MEMO_REVIEW_REPEAT_MS } ?: false
}

// ---------------------------------------------------------------- the answers

/** One note the server did not put on the queue, and why (`없음`, `휴지통`, `이미 대기 중`, `30건 초과`). */
internal data class MemoReviewSkip(val id: String, val reason: String)

/** What one press got: the notes taken, the ones skipped, and a failure that stopped the sends (null = none). */
internal data class MemoReviewSent(
    val accepted: List<String>,
    val skipped: List<MemoReviewSkip> = emptyList(),
    val failure: String? = null,
    /** 앱21 ①: what became of a directive sent with the press (null: none was sent). */
    val directive: MemoDirectiveAnswer? = null,
    /** 앱32: the `서버기동` of the answer — which run of the server took the request (결정기록 D8 c); empty: not told. */
    val boot: String = "",
)

/**
 * 앱21 ①: the `지시` object of a `review` answer (규약 §1): [cut] when the server kept only the first 500 characters.
 * [taken] false when a directive was sent and the answer had no `지시` object (서버 14차: it ignored the argument).
 */
internal data class MemoDirectiveAnswer(val taken: Boolean, val cut: Boolean = false, val applied: Int = 0)

/** The `review` answer of [sent]: the ids taken are those sent and not skipped (`접수` is their count). */
internal fun memoReviewAnswer(sent: List<String>, content: JSONObject, directiveSent: Boolean = false): MemoReviewSent {
    val rows = content.optJSONArray("건너뜀") ?: JSONArray()
    val skipped = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { o ->
        o.optString("id").takeIf { it.isNotEmpty() }?.let { MemoReviewSkip(it, o.optString("사유")) }
    }
    val out = skipped.map { it.id }.toSet()
    val directive = if (directiveSent) {
        content.optJSONObject(MEMO_ARG_DIRECTIVE)?.let { MemoDirectiveAnswer(true, it.optBoolean("잘림"), it.optInt("반영")) } ?: MemoDirectiveAnswer(false)
    } else {
        null
    }
    return MemoReviewSent(sent.filter { it !in out }, skipped, directive = directive)
}

/**
 * 앱21 ①: the words a directive adds to the notice after a press (empty: nothing to say). 앱31: with [accepted] notes
 * taken and the directive on fewer of them (one turn of 30 answered by a server that does not know `지시`), the notice
 * says how many it missed instead of telling the whole press failed. Said once for every turn together (앱31 ③).
 */
internal fun memoReviewDirectiveText(d: MemoDirectiveAnswer?, accepted: Int = 0): String = when {
    d == null -> ""
    !d.taken && d.applied == 0 -> " · 지시는 반영되지 않았습니다(서버가 지시를 모릅니다)"
    // 앱29 ⑤: the phone never sends more than 500 characters, so a cut is the server's limit of the directives it adds up
    // for the same note (서버 24차: 2000): the new one went in shortened or not at all, the older ones stay.
    d.cut -> " · 쌓인 지시가 ${MEMO_REVIEW_DIRECTIVE_MERGED_MAX}자를 넘어 이번 지시가 잘렸습니다"
    d.applied in 1 until accepted -> " · 지시 포함(${d.applied}건) · ${accepted - d.applied}건에는 지시가 닿지 않았습니다"
    else -> " · 지시 포함"
}

// ---------------------------------------------------------------- 앱31 B: the review of the selected notes

/**
 * 앱31 (관리자: "노트 여러개 선택 후 검토 시에도 지시 입력 가능하게끔"): the notes the 검토 button of the selection bar
 * sends, or null when nothing opens: no server (the review controls are not drawn then) or nothing selected (the button
 * is dimmed). Empty ids are dropped; the rest keep the order of the selection ([memoReviewBatches] splits them later).
 */
internal fun memoBulkReviewIds(ids: List<String>, hasReviewer: Boolean): List<String>? {
    if (!hasReviewer) return null
    return ids.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
}

/** 앱31 ①: the line of the directive sheet that tells how many notes it goes to; empty for a single note (앱21 ① sheet). */
internal fun memoBulkReviewTargetText(count: Int): String = if (count > 1) "선택한 ${count}개 노트에 함께 적용됩니다" else ""

/** 앱31 ④: the notes of one press, each once, the empty ones dropped (what [MemoReviewRepeat.admit] may let through). */
internal fun memoReviewAskedCount(ids: List<String>): Int = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().size

/**
 * 앱31 ④: the words for the notes the 연타 guard held back ([MemoReviewRepeat.admit]): only when more than one note was
 * asked for — a single note (the button of an open note, a card pushed to the left) says nothing, exactly as before.
 * A directive goes only to the notes that were sent, so it is said when one came with the press.
 */
internal fun memoReviewHeldBackText(asked: Int, fresh: Int, withDirective: Boolean): String {
    val held = asked - fresh
    if (asked <= 1 || held <= 0) return ""
    return " · 방금 보낸 ${held}건은 빼고 보냈습니다" + if (withDirective) "(지시도 가지 않습니다)" else ""
}

/** "휴지통 1건, 이미 대기 중 2건": the reasons of [skipped] in the order they first came. */
internal fun memoReviewSkipText(skipped: List<MemoReviewSkip>): String =
    skipped.groupBy { it.reason.ifEmpty { "알 수 없음" } }.entries.joinToString(", ") { (reason, rows) -> "$reason ${rows.size}건" }

/** The notice right after a press. */
internal fun memoReviewSentText(r: MemoReviewSent): String {
    val skips = memoReviewSkipText(r.skipped).takeIf { it.isNotEmpty() }?.let { " · 건너뜀: $it" }.orEmpty()
    return when {
        r.failure != null && r.accepted.isEmpty() -> "검토 요청 실패: ${r.failure}$skips"
        r.failure != null -> "검토를 요청했습니다 (${r.accepted.size}건) · 나머지는 보내지 못했습니다$skips"
        r.accepted.isEmpty() -> "검토를 요청하지 않았습니다$skips"
        else -> "검토를 요청했습니다 (${r.accepted.size}건)${memoReviewDirectiveText(r.directive, r.accepted.size)}$skips"
    }
}

/**
 * The state of one note in a `review_status` answer. 앱18 ③: [reason] is the server's one line on why a review ended
 * with "제안 없음" (서버 14차 `사유`); empty when the server does not send it (서버 13차) or for another result.
 */
internal data class MemoReviewProgress(
    val id: String,
    val state: String,
    val result: String = "",
    val time: String = "",
    val reason: String = "",
    /** 서버26 `지시결과`: the one line on what became of a directive; empty when the server does not send it (옛 서버). */
    val directiveResult: String = "",
    /** 서버26 `대기순번`: 1.. while 대기, 0 while 검토중, null when the server does not send it. */
    val queuePos: Int? = null,
    /** 서버26 `마지막활동`: when the server last did something with this review (KST ISO); empty when not sent. */
    val lastActivity: String = "",
)

internal object MemoReviewStates {
    const val WAITING = "대기"
    const val WORKING = "검토중"
    const val DONE = "완료"
    const val NONE = "없음"
    const val FAILED = "실패"
    const val NO_PROPOSAL = "제안 없음"
}

internal fun memoReviewStates(content: JSONObject): List<MemoReviewProgress> {
    val rows = content.optJSONArray("상태") ?: JSONArray()
    return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { o ->
        o.optString("id").takeIf { it.isNotEmpty() }?.let {
            MemoReviewProgress(
                id = it,
                state = o.optString("상태"),
                result = o.optString("결과"),
                time = o.optString("시각"),
                reason = o.optString("사유"),
                // 앱32 (서버26 추가요청 1): the three progress fields. A server that does not send them (서버 25차 and
                // before) leaves them empty/null here, and the screen says it does not know rather than making it up.
                directiveResult = o.optString("지시결과"),
                queuePos = if (o.has("대기순번") && !o.isNull("대기순번")) o.optInt("대기순번") else null,
                lastActivity = o.optString("마지막활동"),
            )
        }
    }
}

/** 서버25 도장 `서버기동`: which run of the server answered; empty when it does not send one (앱32 결정기록 D8 c). */
internal fun memoReviewServerBoot(content: JSONObject): String = content.optString("서버기동")

/** 앱32: what one `review_status` look-up got: the rows, and the server run that answered. */
internal data class MemoReviewLook(val states: List<MemoReviewProgress>, val boot: String = "")

/** True when every note of [ids] is done (완료) or has nothing to wait for (없음). A missing note is still waited for. */
internal fun memoReviewFinished(ids: List<String>, states: List<MemoReviewProgress>): Boolean {
    val byId = states.associateBy { it.id }
    return ids.all { byId[it]?.state == MemoReviewStates.DONE || byId[it]?.state == MemoReviewStates.NONE }
}

private val PROPOSALS = Regex("""제안\s*(\d+)\s*건""")

/** The proposals a `결과` tells: "제안 2건" = 2, "제안 없음" = 0, anything else = null. */
internal fun memoReviewProposals(result: String): Int? = when {
    result.trim() == MemoReviewStates.NO_PROPOSAL -> 0
    else -> PROPOSALS.find(result)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * The notice when the reviews of [ids] are done: "검토 완료: 제안 2건" / "검토 완료: 제안 없음" / "검토 실패". With more than one
 * note the proposals add up and the failed ones are counted beside them. 앱18 ③: one note without a proposal tells the
 * server's reason ("검토 완료: 제안 없음 — <사유>", cut to [MEMO_REVIEW_REASON_CHARS]); more notes without any proposal
 * only count them ("검토 완료: 제안 없음 2건"), the reasons would not fit.
 */
internal fun memoReviewDoneText(ids: List<String>, states: List<MemoReviewProgress>): String {
    val done = states.filter { it.id in ids && it.state == MemoReviewStates.DONE }
    val failed = done.count { it.result.trim() == MemoReviewStates.FAILED || memoReviewProposals(it.result) == null }
    val proposals = done.sumOf { memoReviewProposals(it.result) ?: 0 }
    val ok = done.size - failed
    val failedText = if (failed > 0) " (실패 ${failed}건)" else ""
    // 앱24 (추가요청 1): the line of a review with a directive (서버 20차) is shown even when other cards came too.
    val directive = memoReviewDirectiveLine(done)
    return when {
        ok == 0 -> "검토 실패"
        proposals > 0 -> "검토 완료: 제안 ${proposals}건$directive$failedText"
        ok > 1 -> "검토 완료: 제안 없음 ${ok}건$directive$failedText"
        else -> {
            val reason = memoReviewReasonText(done.first { memoReviewProposals(it.result) == 0 }.reason)
            "검토 완료: 제안 없음" + (if (reason.isEmpty()) "" else " — $reason") + failedText
        }
    }
}

/** 서버 20차: the heads a reason starts with when the review had a directive (`june_memo_directive.HEADS`). */
internal val MEMO_REVIEW_DIRECTIVE_HEADS = listOf("지시 반영:", "지시를 반영하지 못했어요:", "지시 확인:")

/** True when [reason] is the line of a review with a directive (one of [MEMO_REVIEW_DIRECTIVE_HEADS] at its start). */
internal fun memoReviewIsDirectiveReason(reason: String): Boolean = MEMO_REVIEW_DIRECTIVE_HEADS.any { reason.trimStart().startsWith(it) }

/**
 * 앱24 (추가요청 1): " — <지시 반영 줄>" for the notice when it would not tell the reason otherwise (proposals came, or
 * more than one note): the first reviewed note whose reason is a directive line, cut like any reason
 * ([memoReviewReasonText]); more such notes are counted (" 외 N건"). Empty when no reason is one (서버 19차 and before).
 */
private fun memoReviewDirectiveLine(done: List<MemoReviewProgress>): String {
    val lines = done.map { it.reason }.filter(::memoReviewIsDirectiveReason)
    if (lines.isEmpty()) return ""
    val more = if (lines.size > 1) " 외 ${lines.size - 1}건" else ""
    return " — ${memoReviewReasonText(lines.first())}$more"
}

private val DONE_WITH_PROPOSALS = Regex("""^검토 완료: 제안 (\d+)건""")

/**
 * 앱19 ① (관리자: "제안 생겼다는 알림오는 경우 바로 제안탭 새로고침"): true when the notice of [memoReviewDoneText] tells
 * one proposal or more ("검토 완료: 제안 2건", also with "(실패 K건)"). "제안 없음", "검토 실패" and no notice: false.
 */
internal fun memoReviewSyncWanted(notice: String?): Boolean =
    notice?.let { DONE_WITH_PROPOSALS.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.let { it > 0 } ?: false

/**
 * 앱19 ① / 앱29 ①: the end of one review follow-up. The notice is shown first; then [sync] runs once for the reviewed
 * [ids], whatever the result (관리자 2026-10-26: a title the review changed comes with no card and stayed old on the
 * phone) — `withCards` true when the notice tells proposals (the screen then also waits for the new cards, 앱19 ①).
 * No notice (the follow-up ran out of time or a look-up failed): nothing, the next screen open pulls. A sync that fails
 * or throws changes nothing of the notice or the screen; a cancel (the screen went away) ends the follow-up without it.
 */
internal suspend fun memoReviewFinish(
    notice: String?,
    ids: List<String>,
    show: (String) -> Unit,
    sync: (suspend (ids: List<String>, withCards: Boolean) -> Unit)?,
) {
    notice ?: return
    show(notice)
    if (sync == null || ids.isEmpty()) return
    try {
        sync(ids, memoReviewSyncWanted(notice))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Nothing to show: the cards still come with the next sync (screen shown, 새로고침).
    }
}

/**
 * 앱19 ①: true when a proposal of one of the reviewed notes [noteIds] is new or changed in [after] (id and content) since
 * [before]. A proposal id is the note id, or the note id and a tail (`<id>.cls`, `<id>.t2.cls`, `<id>.ttl`: 서버 플랜검토
 * `SUGGEST_SUFFIX`); an older card of the same note still waiting is not a new one.
 */
internal fun memoReviewCardsArrived(noteIds: List<String>, before: Collection<Pair<String, String>>, after: Collection<Pair<String, String>>): Boolean {
    val old = before.toSet()
    return after.any { p -> p !in old && noteIds.any { n -> n.isNotEmpty() && (p.first == n || p.first.startsWith("$n.")) } }
}

/** The reason on one line, at most [MEMO_REVIEW_REASON_CHARS] (a longer one ends with "…"). */
internal fun memoReviewReasonText(reason: String): String {
    val line = reason.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    return if (line.length <= MEMO_REVIEW_REASON_CHARS) line else line.take(MEMO_REVIEW_REASON_CHARS - 1).trimEnd() + "…"
}

/** 앱18 ③: how long the notice after a press stays; a failed request is a result and stays as long as one. */
internal fun memoReviewSentShowMs(r: MemoReviewSent): Long = if (r.failure != null) MEMO_NOTICE_RESULT_SHOW_MS else MEMO_NOTICE_SHOW_MS

/** When to ask again: [MEMO_REVIEW_POLL_MS] while the follow-up is younger than [MEMO_REVIEW_FOLLOW_MS], then null (stop). */
internal fun memoReviewPollDelayMs(startMs: Long, nowMs: Long): Long? =
    if (nowMs - startMs + MEMO_REVIEW_POLL_MS <= MEMO_REVIEW_FOLLOW_MS) MEMO_REVIEW_POLL_MS else null

// ---------------------------------------------------------------- the sends

/**
 * The review requests of one memo bot room. [prepare] runs before a send (the screen: the changes still waiting go up
 * first, so the server reviews what the phone shows). There is no offline queue for ops (the outbox only carries the
 * sync rows): a send that cannot reach the server fails at once and says so (결정기록 앱17).
 */
internal class MemoReviewer(
    private val transport: MemoSyncTransport,
    private val prepare: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
    /** The wait between two look-ups (tests move a clock instead). */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Step names and counts only: never a note id or text (앱8 항목 1 e). */
    private val log: (String) -> Unit = {},
) {
    val repeat = MemoReviewRepeat(clock)

    /** 앱33 ⑤: why the last call brought nothing back ([MemoCheckResult.TIMEOUT] or [MemoCheckResult.FAILED]). */
    @Volatile
    private var lastFail = MemoCheckResult.TIMEOUT

    /** 앱29 ④: a follow-up of [ids] starting now. */
    fun followOf(ids: List<String>): MemoReviewFollow = MemoReviewFollow(ids, clock())

    /** 앱29 ④: the follow-ups the screen stopped when it went away (this reviewer lives as long as the process). */
    private val paused = mutableListOf<MemoReviewFollow>()

    /** 앱29 ④: keeps [follows] for the next time the screen is shown (the newest [MEMO_REVIEW_PAUSED_MAX]). */
    @Synchronized
    fun pause(follows: List<MemoReviewFollow>) {
        paused += follows
        while (paused.size > MEMO_REVIEW_PAUSED_MAX) paused.removeAt(0)
    }

    /**
     * 앱29 ④: the stopped follow-ups to pick up again (each once), without those stopped more than
     * [MEMO_REVIEW_PAUSED_KEEP_MS] after their start: the pull of the screen open brings what they changed anyway.
     */
    @Synchronized
    fun takePaused(): List<MemoReviewFollow> {
        val now = clock()
        return paused.filter { now - it.startMs <= MEMO_REVIEW_PAUSED_KEEP_MS }.also { paused.clear() }
    }

    /**
     * Sends [ids] (already let through by [repeat]) in turns of [MEMO_REVIEW_MAX], one after the other. 앱21 ①: a
     * [directive] goes with every turn ([memoReviewOp]); it is kept nowhere. The log tells only its length.
     */
    suspend fun request(ids: List<String>, directive: String? = null): MemoReviewSent {
        val batches = memoReviewBatches(ids)
        if (batches.isEmpty()) return MemoReviewSent(emptyList())
        withContext(dispatcher) { prepare() }
        val accepted = mutableListOf<String>()
        val skipped = mutableListOf<MemoReviewSkip>()
        val sentDirective = memoReviewDirective(directive)
        var answer: MemoDirectiveAnswer? = null
        // 앱32: which run of the server took the press, so a restart before the review ends can be told (결정기록 D8 c).
        var boot = ""
        for (batch in batches) {
            log("검토 요청 ${batch.size}건" + (sentDirective?.let { " · 지시 있음(${it.length}자)" } ?: ""))
            val failure = when (val r = callOrNull(memoReviewOp(batch, sentDirective))) {
                is MemoResponse.Ok -> {
                    val a = memoReviewAnswer(batch, r.content, directiveSent = sentDirective != null)
                    accepted += a.accepted
                    skipped += a.skipped
                    if (boot.isEmpty()) boot = memoReviewServerBoot(r.content)
                    answer = a.directive?.let { d -> answer?.let { MemoDirectiveAnswer(it.taken && d.taken, it.cut || d.cut, it.applied + d.applied) } ?: d }
                    null
                }
                is MemoResponse.Error -> r.reason
                else -> "보내지 못했습니다. 연결을 확인해 주세요"
            }
            if (failure != null) {
                log("검토 요청 실패")
                return MemoReviewSent(accepted, skipped, failure, answer, boot)
            }
        }
        log("검토 접수 ${accepted.size}건 · 건너뜀 ${skipped.size}건")
        return MemoReviewSent(accepted, skipped, directive = answer, boot = boot)
    }

    /**
     * Asks `review_status` for [ids] every [MEMO_REVIEW_POLL_MS] until all are done; returns the notice then. Null when a
     * look-up fails or [MEMO_REVIEW_FOLLOW_MS] (from [startMs]) runs out: it stops quietly (the proposals still come to
     * the proposal area). [lookFirst]: one look-up before the first wait, even when the time is already out (앱29 ④).
     */
    suspend fun follow(
        ids: List<String>,
        startMs: Long = clock(),
        lookFirst: Boolean = false,
        /** 앱32: every look-up, so the review log keeps what the phone last knew (null: nobody is writing it down). */
        onLook: ((MemoReviewLook) -> Unit)? = null,
        /** 앱32 요구 ⑤ d: the follow-up stopped without an answer (time out or a failed look-up). */
        onGiveUp: (() -> Unit)? = null,
    ): String? {
        if (ids.isEmpty()) return null
        // 앱29 ④: a follow-up picked up again after the screen came back asks once at once, then goes on in its own time.
        var first = lookFirst
        while (true) {
            if (!first) {
                val wait = memoReviewPollDelayMs(startMs, clock()) ?: return null.also {
                    log("검토 결과 기다림 끝(시간)")
                    onGiveUp?.invoke()
                }
                sleep(wait)
            }
            first = false
            val look = lookUp(ids) ?: return null.also {
                log("검토 결과 조회 실패")
                onGiveUp?.invoke()
            }
            onLook?.invoke(look)
            if (memoReviewFinished(ids, look.states)) return memoReviewDoneText(ids, look.states).also { log("검토 결과 받음") }
        }
    }

    /**
     * 앱32 요구 ⑥: asks `review_status` about [ids] **once** (in turns of [MEMO_REVIEW_MAX]); null when a turn fails.
     * This is the only place a look-up is sent from: the 5 s follow-up and the 새로고침 both come here.
     */
    suspend fun lookUp(ids: List<String>, cause: String = MemoCheckCause.AUTO): MemoReviewLook? {
        if (ids.isEmpty()) return null
        val states = mutableListOf<MemoReviewProgress>()
        var boot = ""
        for (chunk in ids.chunked(MEMO_REVIEW_MAX)) {
            val startMs = clock()
            val answer = callOrNull(memoReviewStatusOp(chunk))
            val r = answer as? MemoResponse.Ok
            val now = clock()
            // 앱33 ⑤: one line per look-up — the note ids and their titles are never written down.
            MemoCheckLog.add(
                kind = MemoCheckKind.REVIEW,
                cause = cause,
                result = if (r != null) MemoCheckResult.OK else lastFail,
                elapsedMs = now - startMs,
                requestId = r?.requestId.orEmpty(),
                nowMs = now,
            )
            if (r == null) return null
            states += memoReviewStates(r.content)
            if (boot.isEmpty()) boot = memoReviewServerBoot(r.content)
        }
        return MemoReviewLook(states, boot)
    }

    private suspend fun callOrNull(op: MemoOp): MemoResponse? = try {
        transport.call(op)
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        lastFail = MemoCheckResult.TIMEOUT
        log("${op.op} 답 시간 초과")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        lastFail = MemoCheckResult.FAILED
        log("${op.op} 보내지 못함 (${e.javaClass.simpleName})")
        null
    }
}
