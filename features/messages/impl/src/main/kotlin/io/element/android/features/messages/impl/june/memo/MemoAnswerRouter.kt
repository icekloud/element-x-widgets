/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where an answer of the memo bot goes. A call waits for its own `요청id` ([wait]); an answer that comes in while it
 * waits ends that call.
 *
 * 앱33 추가요청 1 ②: the answer that came back **after** its call had given up (the 30 s of
 * [MEMO_SYNC_TIMEOUT_MILLIS] ran out) used to be thrown away here, and the screen then kept showing the state of
 * before until a refresh. A look-up answer (`지시조회`, `대화조회`, `review_status`) is the server's truth whenever it
 * arrives, so it is handed to [onLate] instead; what the screen does with it is decided there.
 */
internal class MemoAnswerRouter {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<MemoResponse>>()
    private val late = CopyOnWriteArrayList<(MemoResponse) -> Unit>()

    /** The place the answer of [requestId] will be put; [drop] takes it away when the call ends. */
    fun wait(requestId: String): CompletableDeferred<MemoResponse> {
        val answer = CompletableDeferred<MemoResponse>()
        waiting[requestId] = answer
        return answer
    }

    fun drop(requestId: String) {
        waiting.remove(requestId)
    }

    /** Adds a reader of the answers nobody waits for any more; the returned block takes it off again. */
    fun onLate(handler: (MemoResponse) -> Unit): () -> Unit {
        late += handler
        return { late -= handler }
    }

    /** True when a call was waiting for [response] (it ends with it); false when it came too late and went to [onLate]. */
    fun deliver(response: MemoResponse): Boolean {
        val answer = waiting[response.requestId]
        if (answer != null && answer.complete(response)) return true
        late.forEach { it(response) }
        return false
    }
}
