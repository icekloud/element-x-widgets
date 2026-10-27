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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 앱32 (관리자: "검토요청 햇는데 검토 내용이 없는건지 아니면 반영이 안된건지 대기중엔 알수가 없음. 검토요청된 대상의 진행현황을
 * 알 수 있는 구조"): what the phone remembers about the reviews it asked for, so the state of a review does not go away
 * with the notice after the press. The review of a note used to be told only by a banner that lasted a moment while the
 * screen was open; after that nothing on the phone knew a review had ever been asked for.
 *
 * Nothing here starts a timer or talks to the server by itself (앱 원칙: no periodic poll): the entries change when the
 * 5 s follow-up of a fresh press looks (MemoReviewer.follow), when the proposal screen is opened, or when 새로고침 is
 * pressed ([memoReviewRefresh]).
 */

/** The `sync_state` key the log lives under (the key-value table the diagnosis also uses; no new table — 결정기록 D1). */
internal const val MEMO_REVIEW_LOG_KEY = "review_log"

/** How many reviews are remembered, the newest first (결정기록 D2). */
internal const val MEMO_REVIEW_LOG_MAX = 50

/** A note still 대기 and untouched for this long is a thing to look at, not a normal wait (결정기록 D8 b). */
internal const val MEMO_REVIEW_WAIT_LONG_MS = 180_000L

/** How many notes one 새로고침 asks about (요구 ⑥). */
internal const val MEMO_REVIEW_REFRESH_MAX = 30

/**
 * One review the phone asked for, as the phone last knew it. [state] empty = asked but never looked up yet;
 * [boot] = the `서버기동` of the answer to the press (a different one now means the server restarted since, 결정기록 D8 c);
 * [stopped] = the 5 minute follow-up gave up; [seen] = the user has seen the finished state (its card mark is gone).
 * 요구 ①: the words of a directive are never kept, only [withDirective].
 */
internal data class MemoReviewEntry(
    val id: String,
    val batch: String,
    val askedMs: Long,
    val withDirective: Boolean = false,
    val state: String = "",
    val result: String = "",
    /** 서버26 `지시결과`; empty = the server did not tell (옛 서버) — never read as "제안 없음" (요구 ④). */
    val directiveResult: String = "",
    /** 서버26 `대기순번`: 1.. while 대기, 0 while 검토중, null when the server did not tell. */
    val queuePos: Int? = null,
    /** 서버26 `마지막활동` (KST ISO); empty = the server did not tell. */
    val lastActivity: String = "",
    val checkedMs: Long = 0L,
    val boot: String = "",
    val stopped: Boolean = false,
    val seen: Boolean = false,
    /** Why it never went on the queue: a `건너뜀` reason of the press, or `보내지 못함`. Empty = it went. */
    val skipped: String = "",
)

/** What the small mark under the sync mark of a card shows, and what the 검토 현황 list draws (결정기록 D4). */
internal enum class MemoReviewPhase(val mark: String) {
    /** Asked for, not looked up yet. */
    Asked("요청 보냄"),
    Queued("서버 대기"),
    Working("검토 중"),
    Done("검토 완료"),

    /** 요구 ⑤: the server does not know it, it waits too long, the server restarted, the follow-up gave up, it was skipped. */
    Attention("확인 필요"),
}

/** True when the server that answers now is not the one the request went to (결정기록 D8 c). */
private fun restarted(e: MemoReviewEntry, boot: String): Boolean =
    e.boot.isNotEmpty() && boot.isNotEmpty() && e.boot != boot

/** The time the waiting is counted from: what the server last did with it, else the press (결정기록 D8 b). */
private fun waitingSince(e: MemoReviewEntry): Long = memoIsoMs(e.lastActivity) ?: e.askedMs

private fun waitingTooLong(e: MemoReviewEntry, nowMs: Long): Boolean =
    nowMs - waitingSince(e) > MEMO_REVIEW_WAIT_LONG_MS

/** The phase of [e] as the phone knows it now; [boot] is the `서버기동` of the last answer ("" = not known). */
internal fun memoReviewPhase(e: MemoReviewEntry, nowMs: Long, boot: String): MemoReviewPhase = when {
    e.skipped.isNotEmpty() -> MemoReviewPhase.Attention
    e.state == MemoReviewStates.DONE -> MemoReviewPhase.Done
    e.state == MemoReviewStates.NONE -> MemoReviewPhase.Attention
    e.stopped -> MemoReviewPhase.Attention
    restarted(e, boot) -> MemoReviewPhase.Attention
    e.state == MemoReviewStates.WAITING -> if (waitingTooLong(e, nowMs)) MemoReviewPhase.Attention else MemoReviewPhase.Queued
    e.state == MemoReviewStates.WORKING -> MemoReviewPhase.Working
    else -> MemoReviewPhase.Asked
}

/** " · 대기 3번째" while the server told a place in the queue (0 = being reviewed right now, nothing to say). */
private fun queueText(e: MemoReviewEntry): String = e.queuePos?.takeIf { it > 0 }?.let { " · 대기 ${it}번째" }.orEmpty()

/** The one line about where a review stands, the four cases of 요구 ⑤ among them (결정기록 D8). */
internal fun memoReviewStateText(e: MemoReviewEntry, nowMs: Long, boot: String): String = when {
    e.skipped.isNotEmpty() -> "보내지 못함 — ${e.skipped}"
    // (a) the request went out but the server has no such review
    e.state == MemoReviewStates.NONE -> "서버가 모름 — 요청이 가지 않았을 수 있습니다. 다시 요청해 주세요"
    e.state == MemoReviewStates.DONE -> "검토 완료"
    // (d) the 5 minute follow-up ran out or a look-up failed. 앱33 ④: the same words as the instructions use, with
    // when the last look-up that worked was — coming back to the screen also asks once on its own now.
    e.stopped -> MEMO_CHECK_STOPPED_TEXT + memoLastCheckText(e.checkedMs, nowMs)?.let { "(마지막 확인 $it)" }.orEmpty()
    // (c) another server answers now than the one the request went to
    restarted(e, boot) -> "서버가 다시 시작됐습니다. 다시 확인해 주세요"
    // (b) still only waiting, and nothing happened to it for a while
    e.state == MemoReviewStates.WAITING && waitingTooLong(e, nowMs) ->
        "서버 대기 ${(nowMs - waitingSince(e)) / 60_000L}분째${queueText(e)}"
    e.state == MemoReviewStates.WAITING -> "서버 대기${queueText(e)}"
    e.state == MemoReviewStates.WORKING -> "검토 중"
    else -> "요청 보냄"
}

/** 요구 ④: the review result ("제안 2건" / "제안 없음") on its own line; empty while the review is not finished. */
internal fun memoReviewResultText(e: MemoReviewEntry): String =
    if (e.state != MemoReviewStates.DONE) "" else "검토 결과: ${e.result.ifBlank { "알 수 없음" }}"

/**
 * 요구 ④: what became of the directive, apart from the review result. An old server tells nothing here, and that is
 * said as not knowing — never as "제안 없음" (the two were one sentence before 앱32).
 */
internal fun memoReviewDirectiveResultText(e: MemoReviewEntry): String =
    if (e.directiveResult.isNotBlank()) "지시 결과: ${e.directiveResult}" else "지시 결과 모름(서버가 알려 주지 않음)"

/** "방금" / "3분 전" / "2시간 전" / "4일 전" for a span of [ms]. */
internal fun memoReviewAgoText(ms: Long): String {
    val span = ms.coerceAtLeast(0L)
    return when {
        span < 60_000L -> "방금"
        span < 3_600_000L -> "${span / 60_000L}분 전"
        span < 86_400_000L -> "${span / 3_600_000L}시간 전"
        else -> "${span / 86_400_000L}일 전"
    }
}

// ---------------------------------------------------------------- the log, as text and as changes

private const val K_ID = "i"
private const val K_BATCH = "b"
private const val K_ASKED = "a"
private const val K_DIRECTIVE = "d"
private const val K_STATE = "s"
private const val K_RESULT = "r"
private const val K_DIRECTIVE_RESULT = "dr"
private const val K_QUEUE = "q"
private const val K_LAST = "la"
private const val K_CHECKED = "c"
private const val K_BOOT = "bt"
private const val K_STOPPED = "st"
private const val K_SEEN = "sn"
private const val K_SKIPPED = "sk"

/** The log as the one line kept in `sync_state` (short keys: it is written on every look-up). */
internal fun memoReviewLogJson(entries: List<MemoReviewEntry>): String {
    val out = JSONArray()
    entries.forEach { e ->
        val o = JSONObject()
            .put(K_ID, e.id)
            .put(K_BATCH, e.batch)
            .put(K_ASKED, e.askedMs)
        if (e.withDirective) o.put(K_DIRECTIVE, true)
        if (e.state.isNotEmpty()) o.put(K_STATE, e.state)
        if (e.result.isNotEmpty()) o.put(K_RESULT, e.result)
        if (e.directiveResult.isNotEmpty()) o.put(K_DIRECTIVE_RESULT, e.directiveResult)
        e.queuePos?.let { o.put(K_QUEUE, it) }
        if (e.lastActivity.isNotEmpty()) o.put(K_LAST, e.lastActivity)
        if (e.checkedMs != 0L) o.put(K_CHECKED, e.checkedMs)
        if (e.boot.isNotEmpty()) o.put(K_BOOT, e.boot)
        if (e.stopped) o.put(K_STOPPED, true)
        if (e.seen) o.put(K_SEEN, true)
        if (e.skipped.isNotEmpty()) o.put(K_SKIPPED, e.skipped)
        out.put(o)
    }
    return out.toString()
}

/** The log from its saved text; anything broken reads as no log at all (the state of a review is never worth a crash). */
internal fun memoReviewLogParse(raw: String?): List<MemoReviewEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = try {
        JSONArray(raw)
    } catch (e: org.json.JSONException) {
        return emptyList()
    }
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
        val id = o.optString(K_ID)
        if (id.isEmpty()) {
            null
        } else {
            MemoReviewEntry(
                id = id,
                batch = o.optString(K_BATCH),
                askedMs = o.optLong(K_ASKED),
                withDirective = o.optBoolean(K_DIRECTIVE),
                state = o.optString(K_STATE),
                result = o.optString(K_RESULT),
                directiveResult = o.optString(K_DIRECTIVE_RESULT),
                queuePos = if (o.has(K_QUEUE) && !o.isNull(K_QUEUE)) o.optInt(K_QUEUE) else null,
                lastActivity = o.optString(K_LAST),
                checkedMs = o.optLong(K_CHECKED),
                boot = o.optString(K_BOOT),
                stopped = o.optBoolean(K_STOPPED),
                seen = o.optBoolean(K_SEEN),
                skipped = o.optString(K_SKIPPED),
            )
        }
    }
}

/** The batch name of one press (one press = one batch, however many turns of 30 it takes — 결정기록 D11). */
internal fun memoReviewBatchId(nowMs: Long): String = "b-$nowMs"

/**
 * The log after a press of [ids] (those [MemoReviewRepeat.admit] let through, 결정기록 D12): one line per note, the
 * newest first. A note asked for again rewrites its line instead of adding a second one (결정기록 D2).
 */
internal fun memoReviewLogAsked(
    old: List<MemoReviewEntry>,
    ids: List<String>,
    batch: String,
    nowMs: Long,
    withDirective: Boolean,
): List<MemoReviewEntry> {
    val fresh = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (fresh.isEmpty()) return old
    val added = fresh.map { MemoReviewEntry(id = it, batch = batch, askedMs = nowMs, withDirective = withDirective) }
    return (added + old.filter { it.id !in fresh }).take(MEMO_REVIEW_LOG_MAX)
}

/**
 * The log after the answer of the press [batch]: the notes the server skipped keep their reason, and the ones a
 * [failure] left unanswered are marked as never sent (요구 ⑦). [boot] = the `서버기동` of the answer, what a later
 * restart is told from.
 */
internal fun memoReviewLogSent(
    old: List<MemoReviewEntry>,
    batch: String,
    accepted: List<String>,
    skipped: List<MemoReviewSkip>,
    failure: String?,
    boot: String,
): List<MemoReviewEntry> {
    val reasons = skipped.associate { it.id to it.reason.ifBlank { "알 수 없음" } }
    val took = accepted.toSet()
    return old.map { e ->
        if (e.batch != batch) {
            e
        } else {
            e.copy(
                boot = boot.ifEmpty { e.boot },
                skipped = reasons[e.id] ?: if (failure != null && e.id !in took) "보내지 못함" else e.skipped,
            )
        }
    }
}

/** The log after one look-up: every note the answer tells about is written down, the others are left alone. */
internal fun memoReviewLogLooked(
    old: List<MemoReviewEntry>,
    states: List<MemoReviewProgress>,
    boot: String,
    nowMs: Long,
): List<MemoReviewEntry> {
    if (states.isEmpty()) return old
    val byId = states.associateBy { it.id }
    return old.map { e ->
        val row = byId[e.id] ?: return@map e
        e.copy(
            state = row.state,
            result = row.result,
            directiveResult = row.directiveResult.ifEmpty { e.directiveResult },
            queuePos = row.queuePos,
            lastActivity = row.lastActivity.ifEmpty { e.lastActivity },
            checkedMs = nowMs,
            stopped = false,
        )
    }
}

/** The log after a follow-up of [ids] gave up (요구 ⑤ d): only its own notes, and only those not finished. */
internal fun memoReviewLogGaveUp(old: List<MemoReviewEntry>, ids: List<String>): List<MemoReviewEntry> {
    val stop = ids.toSet()
    return old.map { e ->
        if (e.id in stop && e.state != MemoReviewStates.DONE && e.skipped.isEmpty()) e.copy(stopped = true) else e
    }
}

/** True when there is nothing more to wait for: the review ended, one way or another (결정기록 D5). */
private fun finished(e: MemoReviewEntry): Boolean =
    e.state == MemoReviewStates.DONE || e.state == MemoReviewStates.NONE || e.stopped || e.skipped.isNotEmpty()

/**
 * The log after the user saw where the review of [id] stands (결정기록 D5): a finished review loses its card mark, one
 * still running keeps it (that mark is the whole point while it runs).
 */
internal fun memoReviewLogSeen(old: List<MemoReviewEntry>, id: String, nowMs: Long): List<MemoReviewEntry> =
    old.map { if (it.id == id && finished(it) && !it.seen) it.copy(seen = true) else it }

/** The mark of the second line of the card of [id] (요구 ②); null = no line at all (never asked for, or already seen). */
internal fun memoReviewMarkOf(entries: List<MemoReviewEntry>, id: String, nowMs: Long, boot: String): MemoReviewPhase? {
    val e = entries.firstOrNull { it.id == id } ?: return null
    if (e.seen) return null
    return memoReviewPhase(e, nowMs, boot)
}

/** How many reviews are still on their way. */
internal fun memoReviewRunningCount(entries: List<MemoReviewEntry>, nowMs: Long, boot: String): Int =
    entries.count { memoReviewPhase(it, nowMs, boot) in RUNNING }

private val RUNNING = setOf(MemoReviewPhase.Asked, MemoReviewPhase.Queued, MemoReviewPhase.Working)

/** The closed line of the 검토 현황 area; empty when nothing was ever asked for (the area is hidden then — 결정기록 D6). */
internal fun memoReviewHeadText(entries: List<MemoReviewEntry>, nowMs: Long, boot: String): String {
    if (entries.isEmpty()) return ""
    val phases = entries.map { memoReviewPhase(it, nowMs, boot) }
    val running = phases.count { it in RUNNING }
    val done = phases.count { it == MemoReviewPhase.Done }
    val attention = phases.count { it == MemoReviewPhase.Attention }
    return "검토 현황 · 진행 $running · 완료 $done" + if (attention > 0) " · 확인 필요 $attention" else ""
}

/** The notes one 새로고침 asks about: those still running, the newest press first, at most [MEMO_REVIEW_REFRESH_MAX]. */
internal fun memoReviewLogRunningIds(entries: List<MemoReviewEntry>, nowMs: Long): List<String> =
    entries.filter { it.state != MemoReviewStates.DONE && it.skipped.isEmpty() }
        .map { it.id }
        .take(MEMO_REVIEW_REFRESH_MAX)

/** A line of the 검토 현황 list: one note, or one press of several notes folded into a line that opens (결정기록 D11). */
internal sealed interface MemoReviewRow {
    data class Note(val entry: MemoReviewEntry) : MemoReviewRow
    data class Group(val batch: String, val entries: List<MemoReviewEntry>) : MemoReviewRow
}

/** The lines of the 검토 현황 list, the newest press first. */
internal fun memoReviewRows(entries: List<MemoReviewEntry>, nowMs: Long, boot: String): List<MemoReviewRow> =
    entries.groupBy { it.batch }.entries
        .sortedByDescending { (_, rows) -> rows.maxOf { it.askedMs } }
        .map { (batch, rows) -> if (rows.size > 1) MemoReviewRow.Group(batch, rows) else MemoReviewRow.Note(rows.first()) }

/** "3개 중 1개 완료" for a press of several notes. */
internal fun memoReviewGroupText(group: MemoReviewRow.Group): String =
    "${group.entries.size}개 중 ${group.entries.count { it.state == MemoReviewStates.DONE }}개 완료"

// ---------------------------------------------------------------- where the log is kept

/** Where the log is written down (the phone database; the tests put it in memory). */
internal interface MemoReviewLogStore {
    fun read(): List<MemoReviewEntry>
    fun write(entries: List<MemoReviewEntry>)
}

/** The log in the `sync_state` key-value table of the memo database — no new table, no schema change (결정기록 D1). */
internal class MemoDbReviewLog(private val db: MemoDb) : MemoReviewLogStore {
    override fun read(): List<MemoReviewEntry> = memoReviewLogParse(db.state(MEMO_REVIEW_LOG_KEY))

    override fun write(entries: List<MemoReviewEntry>) = db.setState(MEMO_REVIEW_LOG_KEY, memoReviewLogJson(entries))
}

/**
 * Keeps the review log of one memo bot room: the screen reads [entries], the presses and the look-ups write. Every
 * change goes to [store] at once, so the state of a review outlives the screen (요구 ①). It never asks the server
 * anything by itself and starts no coroutine (앱 원칙: no periodic poll — 결정기록 D9).
 */
internal class MemoReviewLogKeeper(
    private val store: MemoReviewLogStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _entries = MutableStateFlow<List<MemoReviewEntry>>(emptyList())
    val entries: StateFlow<List<MemoReviewEntry>> = _entries.asStateFlow()

    private var loaded = false

    fun now(): Long = clock()

    /** Reads what was written down before (once per keeper; it is the only writer of its store). */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        _entries.value = store.read()
    }

    @Synchronized
    private fun put(next: List<MemoReviewEntry>) {
        if (next == _entries.value) return
        _entries.value = next
        store.write(next)
    }

    fun asked(ids: List<String>, batch: String, withDirective: Boolean) {
        put(memoReviewLogAsked(_entries.value, ids, batch, clock(), withDirective))
    }

    fun sent(batch: String, accepted: List<String>, skipped: List<MemoReviewSkip>, failure: String?, boot: String) {
        put(memoReviewLogSent(_entries.value, batch, accepted, skipped, failure, boot))
    }

    fun looked(states: List<MemoReviewProgress>, boot: String) {
        put(memoReviewLogLooked(_entries.value, states, boot, clock()))
    }

    fun gaveUp(ids: List<String>) {
        put(memoReviewLogGaveUp(_entries.value, ids))
    }

    fun seen(id: String) {
        put(memoReviewLogSeen(_entries.value, id, clock()))
    }

    /** The `서버기동` of the last answer: what tells a restart since the press (결정기록 D8 c). */
    var boot: String = ""
        private set

    fun sawBoot(boot: String) {
        if (boot.isNotEmpty()) this.boot = boot
    }
}

/**
 * 요구 ⑥: one 새로고침 — the notes still running are asked about **once** (never a timer). Returns how many notes were
 * asked about, 0 when there was nothing to ask or the look-up failed.
 */
internal suspend fun memoReviewRefresh(
    keeper: MemoReviewLogKeeper,
    reviewer: MemoReviewer?,
    /** 앱33 ⑤: what made this one look-up happen (열기 · 복귀 · 새로고침) — for the diagnosis file. */
    cause: String = MemoCheckCause.REFRESH,
): Int {
    if (reviewer == null) return 0
    keeper.load()
    val ids = memoReviewLogRunningIds(keeper.entries.value, keeper.now())
    if (ids.isEmpty()) return 0
    val look = reviewer.lookUp(ids, cause) ?: return 0
    keeper.sawBoot(look.boot)
    keeper.looked(look.states, look.boot)
    return ids.size
}
