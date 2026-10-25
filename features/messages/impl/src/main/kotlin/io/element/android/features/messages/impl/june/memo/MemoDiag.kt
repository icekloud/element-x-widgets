/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.core.data.tryOrNull
import org.json.JSONObject

/*
 * 앱28 추가요청 1: the photo upload and proposal receive lines of the settings "진단" item. They only show what the sync
 * did (nothing here changes it). What is remembered lives in the `sync_state` key-value table (no new column), what is
 * counted comes from the existing columns (attachment tries / retry_at_ms / err, outbox next_try_ms / last_error).
 * Never a path, an address, a room or a user id: the reasons are exception names and step names, cleaned and cut.
 */

/** The longest reason a diagnosis line carries (step and detail together). */
internal const val MEMO_DIAG_REASON_CHARS = 80

internal const val MEMO_DIAG_NONE = "받은 적 없음"

/** `sync_state` keys of the diagnosis: one JSON object each, `{"at": ms, "n": count, "r": reason}`. */
internal const val MEMO_DIAG_PHOTO_TRY = "diag_photo_try"
internal const val MEMO_DIAG_INDEX = "diag_index"
internal const val MEMO_DIAG_INDEX_FAIL = "diag_index_fail"
internal const val MEMO_DIAG_PULL = "diag_pull"
internal const val MEMO_DIAG_PUSH = "diag_push"
internal const val MEMO_DIAG_RESULTS = "diag_results"

/** One remembered event of the diagnosis: when, how many, and the short result. */
internal data class MemoDiagMark(val atMs: Long, val count: Int? = null, val result: String? = null) {
    fun toJson(): String = JSONObject().put("at", atMs).apply {
        count?.let { put("n", it) }
        result?.let { put("r", it) }
    }.toString()

    companion object {
        fun parse(raw: String?): MemoDiagMark? {
            val o = raw?.let { tryOrNull { JSONObject(it) } } ?: return null
            val at = o.optLong("at", 0L).takeIf { it > 0 } ?: return null
            return MemoDiagMark(at, if (o.has("n")) o.optInt("n") else null, o.optString("r").takeIf { it.isNotEmpty() })
        }
    }
}

/** What the photo line shows. [nextTryMs] 0 = may be tried now, null = nothing waits. */
internal data class MemoPhotoDiag(
    val waiting: Int,
    val failed: Int,
    /** Not uploaded and without a phone original: never tried again (pendingAttachments needs the original). */
    val noSource: Int,
    val lastTry: MemoDiagMark?,
    val nextTryMs: Long?,
)

/** What the proposal lines show. */
internal data class MemoProposalDiag(
    val index: MemoDiagMark?,
    val indexFail: MemoDiagMark?,
    val pull: MemoDiagMark?,
    /** The last `sync_push` and whether its answer came ([MemoDiagMark.count]: the suggestion answers it carried). */
    val push: MemoDiagMark? = null,
    val localWaiting: Int,
    val localAnswered: Int,
    /** The suggestion answers still in the outbox, oldest first: each one's next try and reason. */
    val unsent: List<MemoUnsentAnswer>,
    val results: MemoDiagMark?,
)

/** One suggestion answer not confirmed by the server yet. [nextTryMs] 0 = now; [reason] null = never failed. */
internal data class MemoUnsentAnswer(val nextTryMs: Long, val tries: Int, val reason: String?)

/** How many unsent answers the line spells out one by one (the rest is a count). */
internal const val MEMO_DIAG_UNSENT_SHOWN = 3

/** The answer reader of the open memo screen (process wide, not stored). */
internal data class MemoReaderDiag(val state: String, val atMs: Long, val lastAnswerMs: Long? = null)

/** A time of the diagnosis in KST: `HH:mm:ss` on the day of [nowMs], `MM-dd HH:mm` otherwise, "없음" for none. */
internal fun memoDiagTime(ms: Long?, nowMs: Long): String {
    if (ms == null || ms <= 0) return "없음"
    val iso = memoIso(ms)
    return if (iso.take(10) == memoIso(nowMs).take(10)) iso.substring(11, 19) else iso.substring(5, 10) + " " + iso.substring(11, 16)
}

/**
 * `<step>: <detail>` in at most [MEMO_DIAG_REASON_CHARS] characters, one line, with paths, addresses and Matrix ids
 * replaced (an exception message may carry them). A missing detail gives the step alone.
 */
internal fun memoDiagReason(step: String, detail: String?): String {
    val clean = detail?.let(::memoDiagScrub)?.takeIf { it.isNotEmpty() }
    val text = if (clean == null) step else "$step: $clean"
    return if (text.length <= MEMO_DIAG_REASON_CHARS) text else text.take(MEMO_DIAG_REASON_CHARS - 1) + "…"
}

internal fun memoDiagScrub(text: String): String = text
    .replace(DIAG_ADDRESS, "<주소>")
    .replace(DIAG_MATRIX_ID, "<id>")
    .replace(DIAG_PATH, "<경로>")
    .replace(DIAG_SPACES, " ")
    .trim()

private val DIAG_ADDRESS = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""")
private val DIAG_MATRIX_ID = Regex("""[@!#$][\w.=+-]+:[\w.-]+(:\d+)?""")
private val DIAG_PATH = Regex("""(?<![\w.])(?:/[^\s/:'"()\[\]]+)+/?""")
private val DIAG_SPACES = Regex("""\s+""")

/** The two photo lines: the counts, then the last try and the next one. */
internal fun memoPhotoDiagLines(d: MemoPhotoDiag, nowMs: Long): List<String> {
    val counts = "사진 올리기: 대기 ${d.waiting} · 실패 ${d.failed}" + if (d.noSource > 0) " · 원본 없음 ${d.noSource}" else ""
    val last = d.lastTry?.let { "마지막 시도 ${memoDiagTime(it.atMs, nowMs)} ${it.result ?: "결과 모름"}" } ?: "시도 기록 없음"
    val next = when {
        d.nextTryMs == null -> "다음 시도 없음"
        d.nextTryMs <= nowMs -> "다음 시도 지금 가능"
        else -> "다음 시도 ${memoDiagTime(d.nextTryMs, nowMs)}"
    }
    return listOf(counts, "$last · $next")
}

/** The proposal lines: the index state, the pull it waits for, the phone side, the results of the server. */
internal fun memoProposalDiagLines(d: MemoProposalDiag, reader: MemoReaderDiag?, nowMs: Long): List<String> {
    val index = d.index?.let { "제안 목록(방 상태): ${memoDiagTime(it.atMs, nowMs)} 받음 ${it.count ?: 0}건" } ?: "제안 목록(방 상태): $MEMO_DIAG_NONE"
    val indexFail = d.indexFail?.takeIf { f -> d.index == null || f.atMs > d.index.atMs }
        ?.let { " · 읽기 실패 ${memoDiagTime(it.atMs, nowMs)}" }.orEmpty()
    // The index is read only after a pull got its answer (MemoSyncRunner.pullLocked): its last result says why not.
    val pull = d.pull?.let { "받기(sync_pull): ${memoDiagTime(it.atMs, nowMs)} ${it.result ?: ""}".trimEnd() } ?: "받기(sync_pull): 기록 없음"
    val push = d.push?.let { "올리기(sync_push): ${memoDiagTime(it.atMs, nowMs)} ${it.result ?: ""}".trimEnd() } ?: "올리기(sync_push): 기록 없음"
    val local = "폰 제안: 대기 ${d.localWaiting} · 답함 ${d.localAnswered} · 서버에 못 전한 답 ${d.unsent.size}"
    val unsent = d.unsent.take(MEMO_DIAG_UNSENT_SHOWN).map { u ->
        val next = if (u.nextTryMs <= nowMs) "지금" else memoDiagTime(u.nextTryMs, nowMs)
        val why = if (u.reason == null) (if (u.tries == 0) "아직 보내지 않음" else "사유 없음") else memoDiagReason("시도 ${u.tries}번", u.reason)
        " · 다음 시도 $next · $why"
    } + listOfNotNull((d.unsent.size - MEMO_DIAG_UNSENT_SHOWN).takeIf { it > 0 }?.let { " · 외 ${it}건" })
    val results = d.results?.let { "제안결과: ${memoDiagTime(it.atMs, nowMs)} ${it.count ?: 0}건" } ?: "제안결과: $MEMO_DIAG_NONE"
    val readerLine = reader?.let { r ->
        "응답 읽기: ${r.state} ${memoDiagTime(r.atMs, nowMs)}" + (r.lastAnswerMs?.let { " · 마지막 응답 ${memoDiagTime(it, nowMs)}" } ?: " · 받은 응답 없음")
    } ?: "응답 읽기: 이번 실행에서 시작 안 함"
    return listOf(index + indexFail, pull, push, local) + unsent + listOf(results, readerLine)
}

/** The counts of the photo line, read from the existing attachment columns. */
internal fun memoPhotoDiagOf(db: MemoDb): MemoPhotoDiag {
    val pending = db.pendingAttachments()
    return MemoPhotoDiag(
        waiting = pending.size,
        failed = db.failedAttachmentCount(),
        noSource = db.attachmentsWithoutSource(),
        lastTry = MemoDiagMark.parse(db.state(MEMO_DIAG_PHOTO_TRY)),
        nextTryMs = pending.minOfOrNull { it.second.retryAtMs },
    )
}

/** The counts of the proposal lines, from the suggestion table, the outbox and the remembered marks. */
internal fun memoProposalDiagOf(db: MemoDb): MemoProposalDiag {
    val suggestions = db.suggestions()
    val answers = db.outbox().filter { it.kind == MEMO_OUTBOX_PROPOSAL }
    val marks = db.states(listOf(MEMO_DIAG_INDEX, MEMO_DIAG_INDEX_FAIL, MEMO_DIAG_PULL, MEMO_DIAG_PUSH, MEMO_DIAG_RESULTS))
    return MemoProposalDiag(
        index = MemoDiagMark.parse(marks[MEMO_DIAG_INDEX]),
        indexFail = MemoDiagMark.parse(marks[MEMO_DIAG_INDEX_FAIL]),
        pull = MemoDiagMark.parse(marks[MEMO_DIAG_PULL]),
        push = MemoDiagMark.parse(marks[MEMO_DIAG_PUSH]),
        localWaiting = suggestions.count { it.status == MEMO_SUGGESTION_WAITING },
        localAnswered = suggestions.count { it.status != MEMO_SUGGESTION_WAITING },
        unsent = answers.map { MemoUnsentAnswer(it.nextTryMs, it.tries, it.lastError) },
        results = MemoDiagMark.parse(marks[MEMO_DIAG_RESULTS]),
    )
}

/** The whole text of the settings item: when it was made, then the photo and the proposal lines. */
internal fun memoDiagText(photo: MemoPhotoDiag, proposals: MemoProposalDiag, reader: MemoReaderDiag?, nowMs: Long): String =
    (listOf("기준 ${memoDiagTime(nowMs, nowMs)}") + memoPhotoDiagLines(photo, nowMs) + memoProposalDiagLines(proposals, reader, nowMs))
        .joinToString("\n")

/** The answer reader of the memo screen as the diagnosis shows it (set by MemoSyncEffects). */
internal object MemoReaderStatus {
    @Volatile
    var current: MemoReaderDiag? = null
        private set

    fun set(state: String, nowMs: Long = System.currentTimeMillis()) {
        current = MemoReaderDiag(state, nowMs, current?.lastAnswerMs)
    }

    fun answered(nowMs: Long = System.currentTimeMillis()) {
        current = (current ?: MemoReaderDiag("준비됨", nowMs)).copy(lastAnswerMs = nowMs)
    }
}

/**
 * 앱29 ③: why a call to the memo bot room got no usable answer, in the words of the diagnosis line. [Send]: the request
 * itself did not go out (HTTP refused, network, no session); [Timeout]: it went out and no answer came in time (an answer
 * the SDK could not decrypt looks the same from here); [Read]: an answer came that is not the one asked for; [Other]: the
 * server refused it (its reason) or another exception (its name).
 */
internal enum class MemoCallFailKind(val words: String) {
    Send("보내기 실패"),
    Timeout("응답 시간 초과"),
    Read("복호화·읽기 실패"),
    Other("기타"),
}

/** One failed call: its kind, the HTTP status when the send was refused, a short detail (exception name, reason). */
internal data class MemoCallFailure(val kind: MemoCallFailKind, val httpCode: Int? = null, val detail: String? = null)

/** 보내기 실패(HTTP 502) / 응답 시간 초과 / 기타(사유): one line, the detail cleaned of paths, addresses and ids. */
internal fun memoCallFailureText(f: MemoCallFailure): String {
    val inside = listOfNotNull(f.httpCode?.let { "HTTP $it" }, f.detail?.let(::memoDiagScrub)?.takeIf { it.isNotEmpty() })
    return if (inside.isEmpty()) f.kind.words else "${f.kind.words}(${inside.joinToString(" · ")})"
}

/** 앱28 진단: the steps of one photo upload the last try line names. */
internal const val MEMO_DIAG_STEP_READ = "읽기"
internal const val MEMO_DIAG_STEP_ENCRYPT = "암호화"
internal const val MEMO_DIAG_STEP_UPLOAD = "올리기"
