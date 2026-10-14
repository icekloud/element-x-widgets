/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The widget id of the in-app answer reader (no WebView, no URL is ever loaded). */
internal const val MEMO_READER_WIDGET_ID = "june-memo-answers"

/** The widget URL the SDK asks for; it is only parsed, never opened. */
internal const val MEMO_READER_WIDGET_URL = "about:blank"

private const val READ_EVENT = "org.matrix.msc2762.receive.event"

/** How long the first sync of the screen waits for the answer reader before it sends anyway. */
internal const val MEMO_READER_WAIT_MS = 5_000L

/**
 * Runs a [MemoAnswerReader] on [driver] until cancelled, completes [ready] with its readiness (false when it stops
 * before) and closes the driver at the end.
 */
internal suspend fun runMemoAnswerReader(
    driver: MatrixWidgetDriver,
    onAnswer: (MemoResponse) -> Unit,
    ready: CompletableDeferred<Boolean>,
    log: (String) -> Unit = {},
) {
    val reader = MemoAnswerReader(driver, onAnswer, ready = ready, log = log)
    try {
        reader.run()
    } finally {
        ready.complete(false)
        driver.close()
    }
}

/**
 * 진단앱수신: the memo bot answers of an encrypted room, read the way Element Call reads its custom events.
 *
 * The room timeline of the SDK never makes an item of a custom event type (`default_event_filter`: anything that is
 * not a known message, sticker, poll or call event is left out, after decryption too), so the answers never reached
 * the waiting sync call and every `sync_pull`/`sync_push` ended with its 30 s time out. The `/messages` poll cannot
 * read them either: the homeserver only sees `m.room.encrypted`.
 *
 * The SDK widget driver forwards the room events it receives in the sync, decrypted, for the types the widget may
 * read. This class plays the widget side of that protocol (MSC2762) without a WebView: it asks to read
 * [eventTypes] only (the room side grants nothing else, nothing to send), answers every request of the driver and
 * hands each memo answer to [onAnswer].
 *
 * Only events that arrive while it runs are seen, which is what the waiting call needs: it is ready ([ready]) before
 * the screen sends its first request.
 */
internal class MemoAnswerReader(
    private val driver: MatrixWidgetDriver,
    private val onAnswer: (MemoResponse) -> Unit,
    private val eventTypes: List<String> = MEMO_RESPONSE_TYPES,
    private val widgetId: String = MEMO_READER_WIDGET_ID,
    /** Completed once the driver answered the capability request: true when reading was granted. */
    val ready: CompletableDeferred<Boolean> = CompletableDeferred(),
    /** One line per step: event types and counts only, never a room, a user, a token or a key. */
    private val log: (String) -> Unit = {},
) {

    /** Runs until cancelled. The driver is created with `initAfterContentLoad`, so nothing is missed before this listens. */
    suspend fun run() = coroutineScope {
        // Listening first, then the driver starts; the capability exchange only begins with content_loaded.
        launch(start = CoroutineStart.UNDISPATCHED) { driver.incomingMessages.collect { handle(it) } }
        driver.run()
        driver.send(
            JSONObject()
                .put("api", "fromWidget")
                .put("widgetId", widgetId)
                .put("requestId", "content-loaded")
                .put("action", "content_loaded")
                .put("data", JSONObject())
                .toString()
        )
    }

    /** One message of the driver: a request to the widget is answered; the answers to our own requests are ignored. */
    internal suspend fun handle(raw: String) {
        val msg = tryOrNull { JSONObject(raw) } ?: return
        if (msg.optString("api") != "toWidget" || msg.has("response")) return
        val response = when (msg.optString("action")) {
            "capabilities" -> JSONObject().put("capabilities", JSONArray(eventTypes.map { "$READ_EVENT:$it" }))
            "notify_capabilities" -> {
                val approved = msg.optJSONObject("data")?.optJSONArray("approved") ?: JSONArray()
                val granted = (0 until approved.length()).count { approved.optString(it).startsWith("$READ_EVENT:") }
                log("응답 읽기 준비 ${if (granted > 0) "됨" else "안 됨"} (읽기 ${granted}종)")
                ready.complete(granted > 0)
                JSONObject()
            }
            "send_event" -> {
                val event = msg.optJSONObject("data")
                val answer = parseMemoResponseEvent(event?.toString())
                if (answer != null) {
                    log("응답 받음 ${event?.optString("type").orEmpty()}")
                    onAnswer(answer)
                }
                JSONObject()
            }
            // update_state, openid_credentials, …: nothing to do, but every request gets its answer.
            else -> JSONObject()
        }
        driver.send(msg.put("response", response).toString())
    }
}
