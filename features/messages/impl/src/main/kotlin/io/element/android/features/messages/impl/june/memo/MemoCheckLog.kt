/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 앱33 추가요청 1 ⑤: one line for every look-up of the memo bot (지시조회 · 대화조회 · 검토조회) — when it went out, what
 * came of it, how long it took and the first characters of its request id. It is there to tell two things apart the
 * next time something is not shown: an answer that came back too late to be taken (늦은 답), and a look-up that never
 * went out at all (no line at that time).
 *
 * What is never written: the sentence of an instruction, the words of a chat, the contents of a note, the room, the
 * address of the server, any token (앱8 항목 1 e). Only the words of this file and numbers.
 */

/** How many lines the file keeps; older ones go (추가요청 1 ⑤). */
internal const val MEMO_CHECK_LOG_MAX = 200

/** What was asked. */
internal object MemoCheckKind {
    const val DIRECTIVE = "지시조회"
    const val CHAT = "대화조회"
    const val REVIEW = "검토조회"
}

/** Why it was asked — never a timer of its own: 자동 is the look-up right after a send (3·5·15 s, at most 3 min). */
internal object MemoCheckCause {
    const val AUTO = "자동"
    const val OPEN = "열기"
    const val BACK = "복귀"
    const val SYNC = "동기화"
    const val REFRESH = "새로고침"
    const val LATE = "늦은답"
}

/** What came of it. */
internal object MemoCheckResult {
    const val OK = "답 받음"
    const val TIMEOUT = "시간 초과"
    const val FAILED = "보내지 못함"
    const val LATE_APPLIED = "늦은 답 반영"
}

private const val CHECK_ID_CHARS = 6

/**
 * One line: `10-28 00:31:02 지시조회 자동 답 받음 3.2초 ab12cd`. [elapsedMs] below zero and an empty [requestId] are
 * written as `-` (an answer that came in on its own has no time of its own).
 */
internal fun memoCheckLogLine(
    nowMs: Long,
    kind: String,
    cause: String,
    result: String,
    elapsedMs: Long = -1L,
    requestId: String = "",
): String {
    val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(nowMs))
    val took = if (elapsedMs < 0) "-" else String.format(Locale.ROOT, "%.1f초", elapsedMs / 1000.0)
    val id = requestId.filter { it.isLetterOrDigit() }.take(CHECK_ID_CHARS).ifEmpty { "-" }
    return "$time $kind $cause $result $took $id"
}

/** The lines of the file after [line] is put at its end: at most [MEMO_CHECK_LOG_MAX], the oldest go first. */
internal fun memoCheckLogTrim(old: List<String>, line: String): List<String> =
    (old.filter { it.isNotBlank() } + line).takeLast(MEMO_CHECK_LOG_MAX)

/**
 * Where the lines go. The screen code calls [add]; what writes them down is put in by [install] (the phone: a txt file
 * in the app's own storage, written off the screen thread — the tests: a list). Nothing reads it back in the app.
 */
internal object MemoCheckLog {
    @Volatile
    private var sink: ((String) -> Unit)? = null

    fun install(sink: (String) -> Unit) {
        this.sink = sink
    }

    /** Only for the tests: forgets where the lines go. */
    fun uninstall() {
        sink = null
    }

    fun add(
        kind: String,
        cause: String,
        result: String,
        elapsedMs: Long = -1L,
        requestId: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val out = sink ?: return
        out(memoCheckLogLine(nowMs, kind, cause, result, elapsedMs, requestId))
    }
}
