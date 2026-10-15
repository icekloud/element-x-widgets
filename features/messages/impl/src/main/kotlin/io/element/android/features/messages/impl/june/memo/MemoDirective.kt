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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/*
 * 앱9 D (규약-지시.md v1): an instruction typed under the proposal area goes to the memo bot as `op: "지시"`; the
 * server answers 접수 at once and works on it later, the phone asks with `op: "지시조회"`. The ids and the sentences are
 * kept in the local memo database (`sync_state`, no new table), so a restart goes on asking for the same ids.
 */

/** Longest sentence the server takes (규약-지시 흐름 1). */
internal const val MEMO_DIRECTIVE_MAX_CHARS = 500

/** How many instructions the area shows and the phone keeps (규약-지시 보존). */
internal const val MEMO_DIRECTIVE_KEEP = 20

/** Status names: the server ones of 규약-지시 and two of the phone (sending, could not send). */
internal object MemoDirectiveStatus {
    const val SENDING = "보내는중"
    const val NOT_SENT = "못보냄"
    const val RECEIVED = "접수"
    const val WORKING = "처리중"
    const val DONE = "완료"
    const val QUESTION = "질문"
    const val FAILED = "실패"
    const val REFUSED = "거절"

    /** Still to be asked about with `지시조회`. */
    val OPEN = setOf(RECEIVED, WORKING)
    private val SERVER = setOf(RECEIVED, WORKING, DONE, QUESTION, FAILED, REFUSED)

    fun isServer(status: String) = status in SERVER
}

internal data class MemoDirective(
    val id: String,
    val text: String,
    val status: String,
    val message: String = "",
    val sentMs: Long = 0,
) {
    val isOpen: Boolean get() = status in MemoDirectiveStatus.OPEN
    val canResend: Boolean get() = status == MemoDirectiveStatus.NOT_SENT

    fun toJson(): JSONObject = JSONObject().put("id", id).put("문장", text).put("상태", status).put("메시지", message).put("보낸시각", sentMs)

    companion object {
        fun fromJson(o: JSONObject): MemoDirective? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return MemoDirective(id, o.optString("문장"), o.optString("상태"), o.optString("메시지"), o.optLong("보낸시각"))
        }
    }
}

/**
 * The line under one instruction: the bot's message, or what the phone knows (never the word "제안"). 추가요청 3:
 * with [nowMs], an instruction still on its way shows how long it has been ("처리 중… (N초)"), and one older than the
 * automatic look-ups says that a refresh asks again.
 */
internal fun memoDirectiveAnswerText(d: MemoDirective, nowMs: Long? = null): String {
    val waiting = d.status == MemoDirectiveStatus.SENDING || d.isOpen
    if (nowMs == null || !waiting || d.sentMs <= 0) return memoDirectiveBaseText(d)
    val age = ((nowMs - d.sentMs).coerceAtLeast(0) / 1000).toInt()
    return when {
        age * 1000L >= LAST_POLL_MS -> "오래 걸려요 (${age}초) · 새로고침을 누르면 다시 확인해요"
        d.status == MemoDirectiveStatus.SENDING -> "보내는 중… (${age}초)"
        d.message.isNotEmpty() -> "${d.message} (${age}초)"
        d.status == MemoDirectiveStatus.RECEIVED -> "메모봇이 받았어요 · 처리 중… (${age}초)"
        else -> "처리 중… (${age}초)"
    }
}

private fun memoDirectiveBaseText(d: MemoDirective): String = when (d.status) {
    MemoDirectiveStatus.SENDING -> "보내는 중…"
    MemoDirectiveStatus.NOT_SENT -> "보내지 못했어요"
    MemoDirectiveStatus.RECEIVED -> d.message.ifEmpty { "메모봇이 받았어요 · 처리하는 중" }
    MemoDirectiveStatus.WORKING -> d.message.ifEmpty { "처리하는 중…" }
    MemoDirectiveStatus.DONE -> d.message.ifEmpty { "끝났어요" }
    MemoDirectiveStatus.QUESTION -> d.message.ifEmpty { "무엇을 바꿀지 다시 알려 주세요" }
    MemoDirectiveStatus.FAILED -> d.message.ifEmpty { "처리하지 못했어요" }
    MemoDirectiveStatus.REFUSED -> d.message.ifEmpty { "받지 않았어요" }
    else -> d.message
}

/**
 * When the area asks again after a send (규약-지시 흐름 3, at most 3 min). 추가요청 3: the bot answers most
 * instructions in about 20 s, so the first 30 s ask every 3 s (10 look-ups), then every 5 s up to one minute, then
 * every 15 s. Null = no more automatic look-ups (opening the area or a refresh still asks).
 */
internal fun memoDirectivePollDelayMs(sentMs: Long, nowMs: Long): Long? {
    val age = (nowMs - sentMs).coerceAtLeast(0)
    return when {
        age < FIRST_POLL_MS -> FIRST_POLL_MS - age
        age < QUICK_UNTIL_MS -> QUICK_POLL_MS
        age < FAST_UNTIL_MS -> FAST_POLL_MS
        age < LAST_POLL_MS -> SLOW_POLL_MS
        else -> null
    }
}

private const val FIRST_POLL_MS = 3_000L
private const val QUICK_POLL_MS = 3_000L
private const val QUICK_UNTIL_MS = 30_000L
private const val FAST_POLL_MS = 5_000L
private const val FAST_UNTIL_MS = 60_000L
private const val SLOW_POLL_MS = 15_000L
private const val LAST_POLL_MS = 180_000L

/** The server's answer for an id it never got (`지시조회`): the instruction did not arrive, it can go again. */
private const val NOT_FOUND_WORDS = "찾지 못"

private const val STATE_DIRECTIVES = "directives"
private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

/** `<device>-<yyyyMMdd-HHmmss>-<4 chars>`: within the server id rule `[A-Za-z0-9._-]{1,64}`. */
internal fun memoNewDirectiveId(device: String, nowMs: Long, random: (Int) -> Int = { java.security.SecureRandom().nextInt(it) }): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(nowMs))
    val tail = (1..4).map { ID_CHARS[random(ID_CHARS.length)] }.joinToString("")
    return "${device.filter { it.isLetterOrDigit() }.take(12)}-$stamp-$tail"
}

/** The instructions of one memo bot room: send, send again, ask for the results. */
internal class MemoDirectives(
    private val db: MemoDb,
    private val transport: MemoSyncTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
    private val newId: (String, Long) -> String = { device, now -> memoNewDirectiveId(device, now) },
    /** Step names and counts only: never the sentence or the answer (앱8 항목 1 e). */
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<MemoDirective>>(emptyList())

    /** Oldest first (a conversation), at most [MEMO_DIRECTIVE_KEEP]. */
    val items: StateFlow<List<MemoDirective>> = _items.asStateFlow()

    private var loaded = false

    /** 추가요청 3: ids whose send is running right now. Any other "보내는중" was cut short (screen closed). */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Open on the server, or not known: a send cut short or one that timed out may have arrived all the same. */
    private fun MemoDirective.asked(): Boolean = isOpen || cutShort() || status == MemoDirectiveStatus.NOT_SENT

    private fun MemoDirective.cutShort(): Boolean = status == MemoDirectiveStatus.SENDING && id !in inFlight

    /**
     * Reads what the database keeps (after a restart). The first time, an instruction left "보내는중" by a process that
     * ended during the send becomes "못보냄": it can be sent again with its id.
     */
    suspend fun load(): List<MemoDirective> = mutex.withLock {
        withContext(dispatcher) {
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

    fun hasOpen(): Boolean = _items.value.any { it.isOpen }

    /** Next automatic look-up, from the newest open (or cut short) instruction; null when none is due any more. */
    fun nextPollInMs(): Long? =
        _items.value.filter { it.isOpen || it.cutShort() }.maxOfOrNull { it.sentMs }
            ?.let { memoDirectivePollDelayMs(it, clock()) }

    /**
     * Sends [text] (cut to [MEMO_DIRECTIVE_MAX_CHARS]). The same sentence as the last one that could not be sent goes
     * again with its id: the server handles an id once. Returns the instruction as it ended (접수, 거절, 못보냄...).
     */
    suspend fun send(text: String): MemoDirective? {
        val sentence = text.trim().take(MEMO_DIRECTIVE_MAX_CHARS)
        if (sentence.isEmpty()) return null
        val d = mutex.withLock {
            withContext(dispatcher) {
                val all = read()
                val again = all.lastOrNull()?.takeIf { it.canResend && it.text == sentence }
                val now = clock()
                val d = (again ?: MemoDirective(newId(memoDeviceOf(db), now), sentence, MemoDirectiveStatus.SENDING))
                    .copy(status = MemoDirectiveStatus.SENDING, sentMs = now)
                write(all.filter { it.id != d.id } + d)
                d
            }
        }
        return deliver(d)
    }

    /** "다시 보내기" of an instruction that could not be sent: the same id, so the server runs it at most once. */
    suspend fun resend(id: String): MemoDirective? {
        val d = mutex.withLock {
            withContext(dispatcher) {
                val all = read()
                val d = all.firstOrNull { it.id == id && it.canResend } ?: return@withContext null
                d.copy(status = MemoDirectiveStatus.SENDING, sentMs = clock()).also { s -> write(all.map { if (it.id == id) s else it }) }
            }
        } ?: return null
        return deliver(d)
    }

    private suspend fun deliver(d: MemoDirective): MemoDirective {
        log("지시 보냄")
        inFlight += d.id
        val r = try {
            callOrNull(MemoOp.directive(d.id, d.text))
        } finally {
            // Cancelled (the area closed): it stays "보내는중" and the next look-up asks the server by its id.
            inFlight -= d.id
        }
        val ended = when (r) {
            is MemoResponse.Ok -> {
                val status = r.content.optString("상태").takeIf(MemoDirectiveStatus::isServer) ?: MemoDirectiveStatus.RECEIVED
                d.copy(status = status, message = r.content.optString("메시지").ifEmpty { r.content.optString("사유") })
            }
            // The server refused it (empty, too long, a bad id): it will not take it later either.
            is MemoResponse.Error -> d.copy(status = MemoDirectiveStatus.REFUSED, message = r.reason)
            else -> d.copy(status = MemoDirectiveStatus.NOT_SENT)
        }
        log("지시 답 ${ended.status}")
        update(listOf(ended), keepSentMs = false)
        return ended
    }

    /**
     * Asks for the open instructions (규약-지시 흐름 3). Returns how many became 완료 with this call, so the screen
     * syncs once to bring the changed notes down. Nothing is asked when nothing is open.
     */
    suspend fun poll(): Int {
        val open = withContext(dispatcher) { read() }.filter { it.asked() }.takeLast(MEMO_DIRECTIVE_KEEP)
        if (open.isEmpty()) return 0
        log("지시조회 ${open.size}건")
        val r = callOrNull(MemoOp.directivePoll(open.map { it.id })) as? MemoResponse.Ok ?: return 0
        val rows = r.content.optJSONArray("결과") ?: JSONArray()
        val byId = open.associateBy { it.id }
        val changed = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { o ->
            val mine = byId[o.optString("id")] ?: return@mapNotNull null
            val status = o.optString("상태").takeIf(MemoDirectiveStatus::isServer) ?: return@mapNotNull null
            val message = o.optString("메시지")
            // 추가요청 3: a send cut short that never reached the server can be sent again (same id), not "실패".
            val unsent = !mine.isOpen && status == MemoDirectiveStatus.FAILED && NOT_FOUND_WORDS in message
            when {
                unsent && mine.status == MemoDirectiveStatus.NOT_SENT -> null
                unsent -> mine.copy(status = MemoDirectiveStatus.NOT_SENT, message = "")
                else -> mine.copy(status = status, message = message)
            }
        }
        update(changed, keepSentMs = true)
        val done = changed.count { it.status == MemoDirectiveStatus.DONE }
        log("지시조회 답 ${changed.size}건 · 완료 $done")
        return done
    }

    private suspend fun update(changed: List<MemoDirective>, keepSentMs: Boolean) {
        if (changed.isEmpty()) return
        mutex.withLock {
            withContext(dispatcher) {
                val byId = changed.associateBy { it.id }
                write(read().map { old -> byId[old.id]?.let { if (keepSentMs) it.copy(sentMs = old.sentMs) else it } ?: old })
            }
        }
    }

    private fun read(): List<MemoDirective> {
        val raw = db.state(STATE_DIRECTIVES) ?: return emptyList()
        val a = try {
            JSONArray(raw)
        } catch (e: org.json.JSONException) {
            return emptyList()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(MemoDirective::fromJson) }
    }

    private fun write(all: List<MemoDirective>) {
        val kept = all.takeLast(MEMO_DIRECTIVE_KEEP)
        db.setState(STATE_DIRECTIVES, JSONArray(kept.map { it.toJson() }).toString())
        _items.value = kept
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
