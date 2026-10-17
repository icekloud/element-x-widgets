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

/** After a `review`, `review_status` is asked every [MEMO_REVIEW_POLL_MS] for at most [MEMO_REVIEW_FOLLOW_MS]. */
internal const val MEMO_REVIEW_POLL_MS = 3_000L
internal const val MEMO_REVIEW_FOLLOW_MS = 120_000L

/** A card pushed to the left this part of its width or more asks for its review when let go. */
internal const val MEMO_REVIEW_SWIPE_FRACTION = 0.3f

/** The notice under the error banner stays this long. */
internal const val MEMO_NOTICE_SHOW_MS = 4_000L

/** What the screen reader offers on a card, and the label of the review button and the swipe hint. */
internal const val MEMO_REVIEW_ACTION = "AI 검토 요청"
internal const val MEMO_REVIEW_MARK = "✨"

internal fun memoReviewOp(ids: List<String>) = MemoOp(MEMO_OP_REVIEW, linkedMapOf("v" to 1, "ids" to ids))

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

/** The words behind a pushed card. */
internal fun memoReviewSwipeHint(reached: Boolean): String = if (reached) "$MEMO_REVIEW_MARK 놓으면 검토" else "$MEMO_REVIEW_MARK 검토"

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
}

// ---------------------------------------------------------------- the answers

/** One note the server did not put on the queue, and why (`없음`, `휴지통`, `이미 대기 중`, `30건 초과`). */
internal data class MemoReviewSkip(val id: String, val reason: String)

/** What one press got: the notes taken, the ones skipped, and a failure that stopped the sends (null = none). */
internal data class MemoReviewSent(
    val accepted: List<String>,
    val skipped: List<MemoReviewSkip> = emptyList(),
    val failure: String? = null,
)

/** The `review` answer of [sent]: the ids taken are those sent and not skipped (`접수` is their count). */
internal fun memoReviewAnswer(sent: List<String>, content: JSONObject): MemoReviewSent {
    val rows = content.optJSONArray("건너뜀") ?: JSONArray()
    val skipped = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { o ->
        o.optString("id").takeIf { it.isNotEmpty() }?.let { MemoReviewSkip(it, o.optString("사유")) }
    }
    val out = skipped.map { it.id }.toSet()
    return MemoReviewSent(sent.filter { it !in out }, skipped)
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
        else -> "검토를 요청했습니다 (${r.accepted.size}건)$skips"
    }
}

/** The state of one note in a `review_status` answer. */
internal data class MemoReviewProgress(val id: String, val state: String, val result: String = "", val time: String = "")

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
        o.optString("id").takeIf { it.isNotEmpty() }?.let { MemoReviewProgress(it, o.optString("상태"), o.optString("결과"), o.optString("시각")) }
    }
}

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
 * note the proposals add up and the failed ones are counted beside them.
 */
internal fun memoReviewDoneText(ids: List<String>, states: List<MemoReviewProgress>): String {
    val done = states.filter { it.id in ids && it.state == MemoReviewStates.DONE }
    val failed = done.count { it.result.trim() == MemoReviewStates.FAILED || memoReviewProposals(it.result) == null }
    val proposals = done.sumOf { memoReviewProposals(it.result) ?: 0 }
    val ok = done.size - failed
    return when {
        ok == 0 -> "검토 실패"
        else -> (if (proposals > 0) "검토 완료: 제안 ${proposals}건" else "검토 완료: 제안 없음") + if (failed > 0) " (실패 ${failed}건)" else ""
    }
}

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

    /** Sends [ids] (already let through by [repeat]) in turns of [MEMO_REVIEW_MAX], one after the other. */
    suspend fun request(ids: List<String>): MemoReviewSent {
        val batches = memoReviewBatches(ids)
        if (batches.isEmpty()) return MemoReviewSent(emptyList())
        withContext(dispatcher) { prepare() }
        val accepted = mutableListOf<String>()
        val skipped = mutableListOf<MemoReviewSkip>()
        for (batch in batches) {
            log("검토 요청 ${batch.size}건")
            val failure = when (val r = callOrNull(memoReviewOp(batch))) {
                is MemoResponse.Ok -> {
                    val a = memoReviewAnswer(batch, r.content)
                    accepted += a.accepted
                    skipped += a.skipped
                    null
                }
                is MemoResponse.Error -> r.reason
                else -> "보내지 못했습니다. 연결을 확인해 주세요"
            }
            if (failure != null) {
                log("검토 요청 실패")
                return MemoReviewSent(accepted, skipped, failure)
            }
        }
        log("검토 접수 ${accepted.size}건 · 건너뜀 ${skipped.size}건")
        return MemoReviewSent(accepted, skipped)
    }

    /**
     * Asks `review_status` for [ids] every [MEMO_REVIEW_POLL_MS] until all are done; returns the notice then. Null when a
     * look-up fails or [MEMO_REVIEW_FOLLOW_MS] runs out: it stops quietly (the proposals still come to the proposal area).
     */
    suspend fun follow(ids: List<String>): String? {
        if (ids.isEmpty()) return null
        val start = clock()
        while (true) {
            val wait = memoReviewPollDelayMs(start, clock()) ?: return null.also { log("검토 결과 기다림 끝(시간)") }
            sleep(wait)
            val states = mutableListOf<MemoReviewProgress>()
            for (chunk in ids.chunked(MEMO_REVIEW_MAX)) {
                val r = callOrNull(memoReviewStatusOp(chunk)) as? MemoResponse.Ok ?: return null.also { log("검토 결과 조회 실패") }
                states += memoReviewStates(r.content)
            }
            if (memoReviewFinished(ids, states)) return memoReviewDoneText(ids, states).also { log("검토 결과 받음") }
        }
    }

    private suspend fun callOrNull(op: MemoOp): MemoResponse? = try {
        transport.call(op)
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        log("${op.op} 답 시간 초과")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("${op.op} 보내지 못함 (${e.javaClass.simpleName})")
        null
    }
}
