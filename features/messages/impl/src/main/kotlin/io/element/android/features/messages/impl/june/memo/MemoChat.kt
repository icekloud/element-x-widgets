/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

/*
 * 앱22 (규약-대화.md v1): the input under the proposal area can be switched from 지시 to 대화. A word of the chat goes
 * as `op: "대화"`, the answers come with `op: "대화조회"` as bubbles into the same list, and `op: "요청조회"` tells what the
 * memo bot asked to have changed. No new screen and no new table: everything is kept in `sync_state`.
 */

internal const val MEMO_OP_CHAT = "대화"
internal const val MEMO_OP_CHAT_POLL = "대화조회"
internal const val MEMO_OP_REQUEST_POLL = "요청조회"

/** Longest word the server takes in the chat (규약-대화 §1), against [MEMO_DIRECTIVE_MAX_CHARS] of the instructions. */
internal const val MEMO_CHAT_MAX_CHARS = 2000

/** How many bubbles the area shows and the phone keeps. */
internal const val MEMO_CHAT_KEEP = 60

/** How many cleared bubble numbers the phone remembers, so a late answer for one is not drawn again. */
internal const val MEMO_CHAT_CLEARED_KEEP = 200

/** What the input sends (관리자 결정: one switch in front of the input, 지시 by default, kept across restarts). */
internal enum class MemoInputMode {
    Directive,
    Chat,
    ;

    /** The word on the switch. */
    val label: String get() = if (this == Chat) "대화" else "지시"

    val maxChars: Int get() = if (this == Chat) MEMO_CHAT_MAX_CHARS else MEMO_DIRECTIVE_MAX_CHARS

    val placeholder: String
        get() = if (this == Chat) "메모봇과 대화 (예: 3번 노트 본문을 다듬어 줘)" else "메모봇에 지시 (예: 책상, NAS 태그 삭제)"

    val hint: String
        get() = if (this == Chat) "메모봇과 대화합니다. 노트를 고치거나 규칙을 바꾸는 것도 말로 부탁할 수 있어요" else ""

    companion object {
        /** Reads what the phone kept; anything else is the mode of before (지시). */
        fun of(raw: String?): MemoInputMode = if (raw == Chat.name) Chat else Directive
    }
}

/** "N/500" under the input, the limit of the mode. */
internal fun memoInputCounter(text: String, mode: MemoInputMode): String = "${text.length}/${mode.maxChars}"

/** Which side said it (규약-대화 §2). */
internal object MemoChatSide {
    const val USER = "관리자"
    const val BOT = "메모봇"
    const val SERVER = "서버"

    /** What is drawn in front of an answer; the words of the user have no mark (they are on the right). */
    fun mark(side: String): String = when (side) {
        BOT -> "🤖 "
        SERVER -> "⚙ "
        else -> ""
    }
}

/** 규약-대화 §4: what the server would write over the body, shown before the "네". */
internal data class MemoCompare(val before: String, val after: String, val truncated: Boolean = false)

/**
 * 앱24 (규약-대화 v2 §3 `작업결과`): one thing the server did for a word of the chat — its kind (노트작성, 노트삭제,
 * 스킬생성, …), what it was done to (`#41` or a skill name), one line on the result, and whether "되돌려줘" takes it back.
 */
internal data class MemoWorkResult(val kind: String, val target: String, val result: String, val undoable: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("종류", kind).put("대상", target).put("결과", result).put("되돌리기", undoable)

    companion object {
        /** One card of the answer; null when it says nothing (no kind, no target and no result). */
        fun fromJson(o: JSONObject): MemoWorkResult? {
            val kind = o.optString("종류").trim()
            val target = o.optString("대상").trim()
            val result = o.optString("결과").trim()
            if (kind.isEmpty() && target.isEmpty() && result.isEmpty()) return null
            return MemoWorkResult(kind, target, result, o.optBoolean("되돌리기"))
        }
    }
}

/** The `작업결과` cards of a bubble, in the order the server sent them; empty without the field (서버 20차 and before). */
internal fun memoWorkResults(rows: JSONArray?): List<MemoWorkResult> =
    rows?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(MemoWorkResult::fromJson) } }.orEmpty()

/** How many `작업결과` cards a bubble shows before "외 N건 더 보기". */
internal const val MEMO_WORK_CARDS = 5

/** The one line of a card: "노트작성 · #41 · 새 노트 '약국 영업시간' 를 받은편지함 에 만들었어요" (empty parts left out). */
internal fun memoWorkCardLine(w: MemoWorkResult): String = listOf(w.kind, w.target, w.result).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * Drawn at the right end of a card that "되돌려줘" takes back (규약-대화 v2 §3: the whole call goes back at once); it is
 * not part of the line, so a long result cut with "…" still shows it.
 */
internal const val MEMO_WORK_UNDO_MARK = "↩"

/** What the screen reader says of [MEMO_WORK_UNDO_MARK]. */
internal const val MEMO_WORK_UNDO_LABEL = "되돌릴 수 있음"

/** The cards drawn: the first [MEMO_WORK_CARDS] (all of them when [expanded]) and how many wait behind "외 N건". */
internal fun memoWorkShown(works: List<MemoWorkResult>, expanded: Boolean): Pair<List<MemoWorkResult>, Int> =
    if (expanded || works.size <= MEMO_WORK_CARDS) works to 0 else works.take(MEMO_WORK_CARDS) to works.size - MEMO_WORK_CARDS

/** The press under the first cards. */
internal fun memoWorkMoreText(hidden: Int): String = "외 ${hidden}건 더 보기"

/** The press that folds the cards again. */
internal const val MEMO_WORK_LESS = "접기"

/** One bubble of the chat. [localId] is the `메시지id` of a word of the user (a resend keeps it). */
internal data class MemoBubble(
    val seq: Int,
    val side: String,
    val text: String,
    val status: String,
    val timeIso: String = "",
    val sentMs: Long = 0,
    val localId: String = "",
    val summary: String = "",
    val requests: List<Int> = emptyList(),
    val compare: MemoCompare? = null,
    /** 앱24 (규약-대화 v2 `작업결과`): what the server did, one card each; empty for 서버 20차 and before. */
    val works: List<MemoWorkResult> = emptyList(),
) {
    val isUser: Boolean get() = side == MemoChatSide.USER
    val isOpen: Boolean get() = isUser && status in MemoDirectiveStatus.OPEN
    val canResend: Boolean get() = isUser && status == MemoDirectiveStatus.NOT_SENT

    /** 규약-대화 §5: the server waits for a "네" under this bubble. */
    val asksYes: Boolean get() = !isUser && status == MemoDirectiveStatus.QUESTION

    fun toJson(): JSONObject = JSONObject()
        .put("순번", seq)
        .put("쪽", side)
        .put("글", text)
        .put("상태", status)
        .put("시각", timeIso)
        .put("보낸시각", sentMs)
        .put("메시지id", localId)
        .put("요약", summary)
        .put("요청", JSONArray(requests))
        .put("비교", compare?.let { JSONObject().put("전", it.before).put("후", it.after).put("잘림", it.truncated) })
        .put("작업결과", works.takeIf { it.isNotEmpty() }?.let { JSONArray(it.map(MemoWorkResult::toJson)) })

    companion object {
        /** A bubble the phone kept. */
        fun fromJson(o: JSONObject): MemoBubble? {
            val side = o.optString("쪽").takeIf { it.isNotEmpty() } ?: return null
            return MemoBubble(
                seq = o.optInt("순번"),
                side = side,
                text = o.optString("글"),
                status = o.optString("상태"),
                timeIso = o.optString("시각"),
                sentMs = o.optLong("보낸시각"),
                localId = o.optString("메시지id"),
                summary = o.optString("요약"),
                requests = o.optJSONArray("요청").intList(),
                compare = o.optJSONObject("비교")?.let { MemoCompare(it.optString("전"), it.optString("후"), it.optBoolean("잘림")) },
                works = memoWorkResults(o.optJSONArray("작업결과")),
            )
        }

        /** A bubble of a `대화조회` answer (규약-대화 §2). */
        fun fromServer(o: JSONObject): MemoBubble? {
            val seq = o.optInt("순번").takeIf { it > 0 } ?: return null
            val side = o.optString("쪽").takeIf { it.isNotEmpty() } ?: return null
            val compare = o.optJSONObject("비교")?.let { MemoCompare(it.optString("전"), it.optString("후"), it.optBoolean("잘림")) }
            return MemoBubble(
                seq = seq,
                side = side,
                text = o.optString("글"),
                status = o.optString("상태").ifEmpty { MemoDirectiveStatus.DONE },
                timeIso = o.optString("시각"),
                summary = memoSummaryLine(o.optJSONObject("요약")),
                requests = o.optJSONArray("요청").intList(),
                compare = compare,
                works = memoWorkResults(o.optJSONArray("작업결과")),
            )
        }
    }
}

private fun JSONArray?.intList(): List<Int> = this?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it > 0 } }.orEmpty()

/**
 * The `요약` object of a server bubble as one line ("영향노트 2 · 태그추가 장보기"); empty when there is nothing to say.
 * The parts are sorted by their name, so the line is the same whatever order the json keeps.
 */
internal fun memoSummaryLine(summary: JSONObject?): String {
    summary ?: return ""
    val keys = summary.keys().asSequence().sorted().toList()
    return keys.mapNotNull { key ->
        val text = when (val value = summary.opt(key)) {
            null, JSONObject.NULL -> null
            is JSONArray -> (0 until value.length()).joinToString(", ") { value.optString(it) }.takeIf { it.isNotEmpty() }
            else -> value.toString().takeIf { it.isNotEmpty() }
        }
        text?.let { "$key $it" }
    }.joinToString(" · ")
}

/**
 * The bubbles the screen draws: what the phone had, with the ones of the answer put in their place by their number
 * ([incoming] wins), without the ones that were cleared ([cleared]), oldest first, at most [MEMO_CHAT_KEEP]. A word of
 * the user still on its way has no number yet (0) and is kept at the end, until its own number comes back.
 */
internal fun memoBubblesMerge(kept: List<MemoBubble>, incoming: List<MemoBubble>, cleared: Set<Int> = emptySet()): List<MemoBubble> {
    val sending = kept.filter { it.seq <= 0 }
    val byLocalId = incoming.filter { it.isUser && it.localId.isNotEmpty() }.associateBy { it.localId }
    val numbered = LinkedHashMap<Int, MemoBubble>()
    kept.filter { it.seq > 0 }.forEach { numbered[it.seq] = it }
    incoming.filter { it.seq > 0 }.forEach { new ->
        val old = numbered[new.seq]
        numbered[new.seq] = if (old == null) new else new.copy(localId = new.localId.ifEmpty { old.localId }, sentMs = old.sentMs)
    }
    val stillSending = sending.filter { it.localId.isEmpty() || it.localId !in byLocalId }
    val rows = numbered.values.filter { it.seq !in cleared }.sortedBy { it.seq } + stillSending
    return rows.takeLast(MEMO_CHAT_KEEP)
}

/**
 * When the area asks for new bubbles. A word of the chat calls the memo bot, which takes 20~30 s or more, so this waits
 * longer than the instructions do (앱22 결정기록 3): the first look-up after 5 s, then every 4 s in the first minute,
 * every 8 s up to three minutes, every 20 s up to ten. Null = no more automatic look-ups (a refresh still asks).
 */
internal fun memoChatPollDelayMs(sentMs: Long, nowMs: Long): Long? {
    val age = (nowMs - sentMs).coerceAtLeast(0)
    return when {
        age < CHAT_FIRST_MS -> CHAT_FIRST_MS - age
        age < CHAT_QUICK_UNTIL_MS -> CHAT_QUICK_MS
        age < CHAT_FAST_UNTIL_MS -> CHAT_FAST_MS
        age < CHAT_LAST_MS -> CHAT_SLOW_MS
        else -> null
    }
}

private const val CHAT_FIRST_MS = 5_000L
private const val CHAT_QUICK_MS = 4_000L
private const val CHAT_QUICK_UNTIL_MS = 60_000L
private const val CHAT_FAST_MS = 8_000L
private const val CHAT_FAST_UNTIL_MS = 180_000L
private const val CHAT_SLOW_MS = 20_000L
private const val CHAT_LAST_MS = 600_000L

/**
 * The line under a bubble: what the phone knows, with the seconds while the memo bot is thinking. 앱33 추가요청 1 ④:
 * the same four cases as the instructions ([memoDirectiveAnswerText]) — while the look-ups work the line says when the
 * last one was, a look-up that did not get through says so, and once the automatic ones are over the line asks for a
 * refresh (the old "오래 걸려요" is gone).
 */
internal fun memoBubbleStatusText(b: MemoBubble, nowMs: Long? = null, check: MemoCheckState = MemoCheckState()): String {
    // 앱24: with `작업결과` cards (규약-대화 v2) the cards say what was done; the `요약` line would only say it again
    // (and a 노트작성 summary is a list of objects, not words).
    if (!b.isUser) return if (b.works.isEmpty()) b.summary else ""
    val waiting = b.status == MemoDirectiveStatus.SENDING || b.isOpen
    val age = if (nowMs != null && b.sentMs > 0) ((nowMs - b.sentMs).coerceAtLeast(0) / 1000).toInt() else -1
    val secs = if (waiting && age >= 0) " (${age}초)" else ""
    val looked = if (nowMs != null) memoLastCheckText(check.lastOkMs, nowMs) else null
    val tail = looked?.let { " · 마지막 확인 $it" }.orEmpty()
    val suffix = looked?.let { "(마지막 확인 $it)" }.orEmpty()
    return when {
        b.status == MemoDirectiveStatus.SENDING -> "보내는 중…$secs"
        b.status == MemoDirectiveStatus.NOT_SENT -> "보내지 못했어요"
        waiting && age * 1000L >= CHAT_LAST_MS -> MEMO_CHECK_STOPPED_TEXT + suffix
        waiting && check.failed -> MEMO_CHECK_FAILED_TEXT + suffix
        b.status == MemoDirectiveStatus.RECEIVED -> "메모봇이 받았어요$secs$tail"
        b.status == MemoDirectiveStatus.WORKING -> "메모봇이 생각하는 중…$secs$tail"
        b.status == MemoDirectiveStatus.FAILED -> "답을 받지 못했어요"
        else -> ""
    }
}

/** 규약-대화 §4: the short line over the two bodies of a 비교. */
internal fun memoCompareSummary(c: MemoCompare): String {
    val before = c.before.lines().size
    val after = c.after.lines().size
    val cut = if (c.truncated) " · 본문이 길어 일부만 보여요" else ""
    return "본문 ${before}줄 ${c.before.length}자 → ${after}줄 ${c.after.length}자$cut"
}

/** 규약-대화 §4·§5: what the phone says under a 비교 (the hold goes after ten minutes). */
internal const val MEMO_CHAT_COMPARE_NOTICE = "\"네\"라고 보내면 그대로 적용돼요 · 10분이 지나면 취소돼요"

/** 규약-대화 §5: the quick answer that lets the server run what it holds. */
internal const val MEMO_CHAT_YES = "네"

/** The question before "대화 초기화". */
internal const val MEMO_CHAT_RESET_ASK = "메모봇의 이어진 대화 기억이 새로 시작돼요. 화면 기록은 그대로예요"

/** 규약-대화 §0: the server says the feature is off; the phone then sends with 지시 again. */
internal fun memoChatOffText(reason: String): String? =
    if (reason.startsWith("기능 꺼짐")) "메모봇 대화 기능이 서버에서 꺼져 있어요 · \"지시\"로 바꿔 보내 주세요" else null

/** The question before "지우기" when the list has bubbles of the chat too (the server keeps its own record). */
internal fun memoPanelClearText(directives: List<MemoDirective>, bubbles: List<MemoBubble>): String {
    if (bubbles.isEmpty()) return memoDirectivesClearText(directives)
    val running = directives.count { it.status in MemoDirectiveStatus.RUNNING } + bubbles.count { it.isUser && it.status in MemoDirectiveStatus.RUNNING }
    val also = if (running > 0) "\n진행 중 ${running}건도 화면에서만 지워져요" else ""
    val parts = listOfNotNull(
        directives.size.takeIf { it > 0 }?.let { "지시 ${it}건" },
        bubbles.size.takeIf { it > 0 }?.let { "대화 ${it}줄" },
    )
    return "${parts.joinToString("과 ")}을 화면에서 지울까요? 메모봇이 하는 일과 서버 기록에는 영향이 없어요$also"
}

/** The line over the input: how many instructions and how many bubbles the screen holds. */
internal fun memoPanelHeadText(directives: Int, bubbles: Int): String = when {
    bubbles <= 0 -> "메모봇에 한 지시 ${directives}건"
    directives <= 0 -> "메모봇과의 대화 ${bubbles}줄"
    else -> "메모봇에 한 지시 ${directives}건 · 대화 ${bubbles}줄"
}

/** One change request of the memo bot (규약-대화 §6). The phone only shows them. */
internal data class MemoChangeRequest(
    val number: Int,
    val kind: String,
    val target: String,
    val summary: String,
    val status: String,
    val refused: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject): MemoChangeRequest? {
            val number = o.optInt("번호").takeIf { it > 0 } ?: return null
            return MemoChangeRequest(
                number = number,
                kind = o.optString("종류"),
                target = o.optString("대상"),
                summary = o.optString("요약"),
                status = o.optString("상태"),
                refused = o.optString("반려사유"),
            )
        }
    }
}

/** The card of one request: its number and summary, then what it is about and where it stands. */
internal fun memoRequestCardLine(r: MemoChangeRequest): String =
    listOf("${r.number}번", r.summary.ifEmpty { r.kind }).joinToString(" · ")

/** The second line of the card: the kind, the file it is about and the state (반려 says why). */
internal fun memoRequestCardDetail(r: MemoChangeRequest): String {
    val state = if (r.status == "반려" && r.refused.isNotEmpty()) "반려 · ${r.refused}" else r.status.ifEmpty { "검토대기" }
    return listOf(r.kind.ifEmpty { "변경" }, r.target.ifEmpty { "(대상 없음)" }, state).joinToString(" · ")
}

/** The title over the cards (none is drawn when the server has no request). */
internal fun memoRequestHeadText(requests: List<MemoChangeRequest>): String = "메모봇 변경 요청 ${requests.size}건"

private const val STATE_CHAT_ID = "chat_id"
private const val STATE_CHAT_BUBBLES = "chat_bubbles"
private const val STATE_CHAT_SEQ = "chat_last_seq"
private const val STATE_CHAT_CLEARED = "chat_cleared"
private const val STATE_INPUT_MODE = "panel_input_mode"

/** How many times one look-up follows `더있음` before it waits for the next one. */
private const val CHAT_POLL_ROUNDS = 5

/** The chat of one memo bot room: the mode of the input, the bubbles, the change requests. */
internal class MemoChat(
    private val db: MemoDb,
    private val transport: MemoSyncTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
    private val newId: (String, Long) -> String = { device, now -> memoNewDirectiveId(device, now) },
    /** Step names and counts only: never the words of the chat (앱8 항목 1 e). */
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<MemoBubble>>(emptyList())
    private val _mode = MutableStateFlow(MemoInputMode.Directive)
    private val _requests = MutableStateFlow<ImmutableList<MemoChangeRequest>>(persistentListOf())
    private val _off = MutableStateFlow<String?>(null)

    /** Oldest first, at most [MEMO_CHAT_KEEP]. */
    val items: StateFlow<List<MemoBubble>> = _items.asStateFlow()

    /** What the input sends; kept in the database, so it is the same after a restart. */
    val mode: StateFlow<MemoInputMode> = _mode.asStateFlow()

    val requests: StateFlow<ImmutableList<MemoChangeRequest>> = _requests.asStateFlow()

    /** Not null when the server answered "기능 꺼짐" (규약-대화 §0). */
    val off: StateFlow<String?> = _off.asStateFlow()

    private val _check = MutableStateFlow(MemoCheckState())

    /** 앱33 ④: how the look-ups themselves are going, for the line under a bubble. */
    val check: StateFlow<MemoCheckState> = _check.asStateFlow()

    private var loaded = false

    /** 앱33 ⑤: why the last call brought nothing back ([MemoCheckResult.TIMEOUT] or [MemoCheckResult.FAILED]). */
    @Volatile
    private var lastFail = MemoCheckResult.TIMEOUT

    /** Reads the mode and the bubbles the phone keeps. A word left on its way by a closed process can go again. */
    suspend fun load(): List<MemoBubble> = mutex.withLock {
        withContext(dispatcher) {
            _mode.value = MemoInputMode.of(db.state(STATE_INPUT_MODE))
            val all = read()
            if (!loaded && all.any { it.status == MemoDirectiveStatus.SENDING }) {
                write(all.map { if (it.status == MemoDirectiveStatus.SENDING) it.copy(status = MemoDirectiveStatus.NOT_SENT) else it })
            } else {
                _items.value = all
            }
            loaded = true
            _items.value
        }
    }

    suspend fun setMode(value: MemoInputMode) {
        withContext(dispatcher) { db.setState(STATE_INPUT_MODE, value.name) }
        _mode.value = value
    }

    fun hasOpen(): Boolean = _items.value.any { it.isOpen || it.status == MemoDirectiveStatus.SENDING }

    /** Next automatic look-up, from the newest word still being worked on; null when none is due. */
    fun nextPollInMs(): Long? =
        _items.value.filter { it.isOpen }.maxOfOrNull { it.sentMs }?.let { memoChatPollDelayMs(it, clock()) }

    /** Sends [text] (cut to [MEMO_CHAT_MAX_CHARS]); null when there is nothing to send. */
    suspend fun send(text: String): MemoBubble? {
        val sentence = text.trim().take(MEMO_CHAT_MAX_CHARS)
        if (sentence.isEmpty()) return null
        val row = mutex.withLock {
            withContext(dispatcher) {
                val all = read()
                val again = all.lastOrNull()?.takeIf { it.canResend && it.text == sentence }
                val now = clock()
                val made = (again ?: MemoBubble(0, MemoChatSide.USER, sentence, MemoDirectiveStatus.SENDING, localId = newId(memoDeviceOf(db), now)))
                    .copy(status = MemoDirectiveStatus.SENDING, sentMs = now)
                write(all.filter { it.localId != made.localId || made.localId.isEmpty() } + made)
                made
            }
        }
        return deliver(row)
    }

    /** "다시 보내기": the same `메시지id`, so the server handles the word once (규약-대화 §1). */
    suspend fun resend(localId: String): MemoBubble? {
        val row = mutex.withLock {
            withContext(dispatcher) {
                val all = read()
                val mine = all.firstOrNull { it.localId == localId && it.canResend } ?: return@withContext null
                mine.copy(status = MemoDirectiveStatus.SENDING, sentMs = clock())
                    .also { sent -> write(all.map { if (it.localId == localId) sent else it }) }
            }
        } ?: return null
        return deliver(row)
    }

    /** "대화 초기화" (규약-대화 §1 `초기화`): the memo bot starts its memory again, the screen keeps its bubbles. */
    suspend fun reset(): Boolean {
        val answer = callOrNull(MemoOp.chat(chatId(), null, newId(memoDeviceOf(db), clock()), reset = true))
        log("대화 초기화 ${answer?.let { it::class.simpleName } ?: "보내지 못함"}")
        if (answer is MemoResponse.Error) {
            noteError(answer.reason)
            return false
        }
        return answer is MemoResponse.Ok
    }

    private suspend fun deliver(row: MemoBubble): MemoBubble {
        log("대화 보냄")
        val answer = callOrNull(MemoOp.chat(chatId(), row.text, row.localId))
        val ended = when (answer) {
            is MemoResponse.Ok -> row.copy(
                seq = answer.content.optInt("순번").takeIf { it > 0 } ?: row.seq,
                status = answer.content.optString("상태").takeIf(MemoDirectiveStatus::isServer) ?: MemoDirectiveStatus.RECEIVED,
            )
            is MemoResponse.Error -> {
                noteError(answer.reason)
                row.copy(status = MemoDirectiveStatus.REFUSED, summary = answer.reason)
            }
            else -> row.copy(status = MemoDirectiveStatus.NOT_SENT)
        }
        log("대화 답 ${ended.status}")
        update(listOf(ended))
        return ended
    }

    /**
     * Asks for the bubbles after the last number the phone has (규약-대화 §2), following `더있음` a few times. Returns
     * how many server bubbles arrived that may have changed notes, so the screen syncs once.
     */
    suspend fun poll(cause: String = MemoCheckCause.AUTO): Int {
        var arrived = 0
        var rounds = 0
        while (rounds++ < CHAT_POLL_ROUNDS) {
            val startMs = clock()
            val answer = callOrNull(MemoOp.chatPoll(chatId(), lastSeq()))
            val now = clock()
            // 앱33 ⑤: one line per look-up (never the words of the chat, only what came of the call).
            MemoCheckLog.add(
                kind = MemoCheckKind.CHAT,
                cause = cause,
                result = if (answer is MemoResponse.Ok) MemoCheckResult.OK else lastFail,
                elapsedMs = now - startMs,
                requestId = (answer as? MemoResponse.Ok)?.requestId.orEmpty(),
                nowMs = now,
            )
            if (answer == null) {
                _check.value = _check.value.copy(failed = true)
                return arrived
            }
            if (answer is MemoResponse.Error) {
                noteError(answer.reason)
                return arrived
            }
            val ok = answer as? MemoResponse.Ok ?: return arrived
            _check.value = MemoCheckState(lastOkMs = now, failed = false)
            arrived += applyAnswer(ok)
            if (!ok.content.optBoolean("더있음")) break
        }
        log("대화조회 답 ${arrived}줄")
        return arrived
    }

    /**
     * 앱33 추가요청 1 ②: a `대화조회` answer nobody was waiting for any more (its call had given up). The bubbles come
     * with their numbers, so writing the same answer down twice changes nothing ([memoBubblesMerge] keeps one row per
     * number). Returns how many bubbles of the server arrived with it.
     */
    suspend fun applyLate(response: MemoResponse): Int {
        val ok = response as? MemoResponse.Ok ?: return 0
        if (ok.op != MEMO_OP_CHAT_POLL) return 0
        val arrived = applyAnswer(ok)
        val now = clock()
        MemoCheckLog.add(
            kind = MemoCheckKind.CHAT,
            cause = MemoCheckCause.LATE,
            result = MemoCheckResult.LATE_APPLIED,
            requestId = ok.requestId,
            nowMs = now,
        )
        _check.value = MemoCheckState(lastOkMs = now, failed = false)
        return arrived
    }

    /** Writes down one `대화조회` answer: [poll] and [applyLate] both come here (앱33 ②). */
    private suspend fun applyAnswer(ok: MemoResponse.Ok): Int {
        val rows = ok.content.optJSONArray("말풍선") ?: JSONArray()
        val fresh = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull(MemoBubble::fromServer)
        val states = ok.content.optJSONArray("상태목록") ?: JSONArray()
        val byNumber = (0 until states.length()).mapNotNull { states.optJSONObject(it) }
            .associate { it.optInt("순번") to it.optString("상태") }
        apply(fresh, byNumber)
        if (fresh.any { it.requests.isNotEmpty() }) loadRequests()
        return fresh.count { !it.isUser }
    }

    /** 규약-대화 §6: the change requests of the memo bot (read only; the phone cannot change their state). */
    suspend fun loadRequests(): ImmutableList<MemoChangeRequest> {
        val answer = callOrNull(MemoOp.requestPoll())
        if (answer is MemoResponse.Error) {
            noteError(answer.reason)
            return _requests.value
        }
        val ok = answer as? MemoResponse.Ok ?: return _requests.value
        val rows = ok.content.optJSONArray("요청") ?: JSONArray()
        _requests.value = (0 until rows.length())
            .mapNotNull { rows.optJSONObject(it) }
            .mapNotNull(MemoChangeRequest::fromJson)
            .toImmutableList()
        return _requests.value
    }

    /**
     * 앱18 ①, the same rule for the bubbles: "지우기" only tidies the screen. The numbers that go are remembered
     * ([MEMO_CHAT_CLEARED_KEEP]), so a look-up still on its way does not draw them again. The server keeps its record.
     */
    suspend fun clear(): Int = mutex.withLock {
        withContext(dispatcher) {
            val all = read()
            if (all.isNotEmpty()) {
                val gone = all.map { it.seq }.filter { it > 0 }
                val ids = (readCleared() + gone).distinct().takeLast(MEMO_CHAT_CLEARED_KEEP)
                db.setState(STATE_CHAT_CLEARED, JSONArray(ids).toString())
                write(emptyList())
            }
            all.size
        }
    }.also { log("대화 지움 ${it}줄") }

    private suspend fun apply(fresh: List<MemoBubble>, states: Map<Int, String>) = mutex.withLock {
        withContext(dispatcher) {
            val cleared = readCleared().toSet()
            val merged = memoBubblesMerge(read(), fresh, cleared).map { row ->
                val state = states[row.seq]
                if (row.isUser && state != null && MemoDirectiveStatus.isServer(state)) row.copy(status = state) else row
            }
            write(merged)
            val top = merged.filter { it.seq > 0 }.maxOfOrNull { it.seq } ?: 0
            if (top > lastSeq()) db.setState(STATE_CHAT_SEQ, top.toString())
        }
    }

    private suspend fun update(changed: List<MemoBubble>) = mutex.withLock {
        withContext(dispatcher) {
            val all = read().map { old ->
                changed.firstOrNull { it.localId.isNotEmpty() && it.localId == old.localId } ?: old
            }
            write(all)
        }
    }

    private fun noteError(reason: String) {
        _off.value = memoChatOffText(reason)
    }

    private fun chatId(): String = db.state(STATE_CHAT_ID) ?: memoNewChatId(memoDeviceOf(db)).also { db.setState(STATE_CHAT_ID, it) }

    private fun lastSeq(): Int = db.state(STATE_CHAT_SEQ)?.toIntOrNull() ?: 0

    private fun readCleared(): List<Int> {
        val raw = db.state(STATE_CHAT_CLEARED) ?: return emptyList()
        val a = try {
            JSONArray(raw)
        } catch (e: org.json.JSONException) {
            return emptyList()
        }
        return (0 until a.length()).map { a.optInt(it) }.filter { it > 0 }
    }

    private fun read(): List<MemoBubble> {
        val raw = db.state(STATE_CHAT_BUBBLES) ?: return emptyList()
        val a = try {
            JSONArray(raw)
        } catch (e: org.json.JSONException) {
            return emptyList()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(MemoBubble::fromJson) }
    }

    private fun write(all: List<MemoBubble>) {
        val kept = all.takeLast(MEMO_CHAT_KEEP)
        db.setState(STATE_CHAT_BUBBLES, JSONArray(kept.map { it.toJson() }).toString())
        _items.value = kept
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

private val MEMO_ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$")

/** The `시각` of a bubble as milliseconds (the other way round of [memoIso]); null when it is not a time. */
internal fun memoIsoMs(iso: String): Long? {
    val m = MEMO_ISO.matchEntire(iso.trim()) ?: return null
    val parts = m.groupValues
    val year = parts[1].toInt()
    val month = parts[2].toInt()
    val day = parts[3].toInt()
    if (month !in 1..12 || day !in 1..31) return null
    // days from civil (H. Hinnant), the other way round of memoIso
    val shifted = if (month <= 2) year - 1 else year
    val era = Math.floorDiv(shifted.toLong(), 400L)
    val yoe = shifted - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146_097 + doe - 719_468
    val secs = days * 86_400L + parts[4].toInt() * 3_600L + parts[5].toInt() * 60L + parts[6].toInt()
    val zone = m.groupValues[7]
    val offset = when {
        zone.isEmpty() || zone == "Z" -> 0L
        else -> {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            sign * (digits.take(2).toLong() * 3_600L + digits.drop(2).toLong() * 60L)
        }
    }
    return (secs - offset) * 1_000L
}

/**
 * When each bubble is placed in the list of the area: its own `시각`, and for one without a readable time the time of
 * the word before it (a bubble still on its way has the time it was sent). The order of the numbers is kept.
 */
internal fun memoBubbleTimes(bubbles: List<MemoBubble>): List<Pair<MemoBubble, Long>> {
    var last = 0L
    return bubbles.map { b ->
        val own = memoIsoMs(b.timeIso) ?: b.sentMs.takeIf { it > 0 } ?: 0L
        last = maxOf(last, own)
        b to last
    }
}

/** `<device>-c-<6 chars>`: inside the server id rule `[A-Za-z0-9._-]{1,64}` (규약-대화 §1). */
internal fun memoNewChatId(device: String, random: (Int) -> Int = { java.security.SecureRandom().nextInt(it) }): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val tail = (1..6).map { chars[random(chars.length)] }.joinToString("")
    return "${device.filter { it.isLetterOrDigit() }.take(12)}-c-$tail"
}
