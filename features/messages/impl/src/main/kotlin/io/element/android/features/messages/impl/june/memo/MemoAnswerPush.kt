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
 * 앱25 (관리자: "수락,거부 등 작업내용 승인 시는 동기화 타이밍과 관계없이 즉시 반영해야함"): the answer of a proposal card
 * goes up right after it is written, not after the input pause of the automatic push.
 */

/** The line of a card whose answer is on its way; [failed]: the last immediate push did not reach the server. */
internal fun memoProposalLockedText(failed: Boolean): String =
    if (failed) "처리 중 · 서버에 닿지 못함 · 다시 시도해요" else "처리 중 · 메모봇에 전하는 중"

/**
 * The proposal ids whose immediate push ([MemoSyncRunner.pushAnswers]) did not reach the server. The answer stays in
 * the outbox and goes up with the usual retry; meanwhile the card says so. Cleared when a push gets the server's
 * answer for it. Process wide, like [MemoSendingNotes]: the cards read it once, no timer.
 */
internal object MemoAnswerFailures {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun failed(proposalIds: Collection<String>) {
        if (proposalIds.isNotEmpty()) _ids.update { it + proposalIds }
    }

    fun clear(proposalIds: Collection<String>) {
        if (proposalIds.isNotEmpty()) _ids.update { it - proposalIds.toSet() }
    }
}

/**
 * One press of a proposal card: writes [ops] locally (one per proposal id, [memoGroupOps]), refreshes the index, then
 * runs the immediate push ([push], null without a server, e.g. the fake data). The push waits neither for the input
 * pause nor for the editor to close: an answer is the user's decision, not a draft. Nothing goes up when no answer
 * could be written. [onError] gets the reason of a failed local write.
 */
internal suspend fun memoSendAnswers(store: MemoStore, ops: List<MemoOp>, push: (suspend () -> Unit)?, onError: (String) -> Unit) {
    var written = 0
    for (op in ops) {
        val r = store.request(op)
        if (r is MemoResult.Failed) {
            onError(r.reason)
            break
        }
        written++
    }
    store.refreshIndex()
    if (written > 0 && push != null) push()
}
