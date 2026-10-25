/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * The A4 sync of the memo screen (설계 §k-4), only while the screen is visible:
 * - shown (opened, back from the background): one `sync_pull` round, then what waits goes up
 * - a local change: `sync_push` once the input stopped for [MemoSyncRunner.PUSH_DELAY_MS]
 * - a failed send is tried again after its back-off, still only while visible
 * - 앱29: a failed pull is tried again twice (15 s, 60 s), only while visible; then the top bar says 받기 필요
 * - hidden (ON_STOP) with attachments left to upload: one one-time job that runs when the network is there. It uploads
 *   the attachments only; the notes and the suggestion answers go up at the next screen open (관리자 결정 1-1 (가))
 *
 * Nothing runs on a timer when the screen is not visible, and nothing is shown while it works: the notes are local.
 */
@Composable
internal fun MemoSyncEffects(roomId: String, timelineItems: ImmutableList<TimelineItem>, localVersion: Long, editing: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val bindings = remember(context) { context.bindings<MemoSyncBindings>() }
    val sessionStore = remember(bindings) { bindings.memoSyncSessionStore() }
    val runner = remember(roomId) { MemoSyncHub.runner(context, sessionStore, roomId) }
    val room = remember(roomId) { MemoSyncHub.room(context, sessionStore, roomId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    // A retry that is due bumps this, so the push effect runs again (only while visible).
    var retryTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycle, runner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> visible = true
                Lifecycle.Event.ON_STOP -> {
                    visible = false
                    if (runner.hasPendingAttachments()) MemoSyncHub.scheduleUpload(bindings.memoSyncWorkManagerScheduler())
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Leaving the memo screen (another room, back): the same as going to the background.
            if (runner.hasPendingAttachments()) MemoSyncHub.scheduleUpload(bindings.memoSyncWorkManagerScheduler())
        }
    }

    // The answers of an encrypted room only reach the waiting call through the timeline (decrypted by the SDK).
    // 진단앱수신: the SDK timeline leaves custom event types out, so this alone never saw an answer; kept for a room
    // whose timeline would show them.
    LaunchedEffect(room, timelineItems) {
        memoAnswersOf(timelineItems).forEach(room::deliver)
    }

    // 진단앱수신: the answers as the SDK receives them (decrypted), through a read-only widget driver of the room.
    val readerReady = remember(roomId) { CompletableDeferred<Boolean>() }
    val activeRooms = remember(bindings) { bindings.memoSyncActiveRooms() }
    LaunchedEffect(room, roomId) {
        val joined = sessionStore.getLatestSession()?.let { activeRooms.getActiveRoomMatching(UserId(it.userId), RoomId(roomId)) }
        val driver = joined?.getEventReaderDriver(memoReaderWidgetSettings(), MEMO_RESPONSE_TYPES)?.getOrNull()
        if (driver == null) {
            val why = if (joined == null) "방 없음" else "드라이버 없음"
            Timber.i("June memo sync: %s", "응답 읽기 시작 못 함 ($why)")
            // 앱28 진단: the settings line says the answers of this open are only read by the /messages poll.
            MemoReaderStatus.set("시작 못 함($why)")
            readerReady.complete(false)
            return@LaunchedEffect
        }
        MemoReaderStatus.set("시작 중")
        try {
            runMemoAnswerReader(
                driver,
                { answer ->
                    MemoReaderStatus.answered()
                    room.deliver(answer)
                },
                readerReady,
            ) { line ->
                Timber.i("June memo sync: %s", line)
                if (line.startsWith("응답 읽기 준비")) MemoReaderStatus.set(if (line.contains("준비 됨")) "준비됨" else "읽기 허가 없음")
            }
        } finally {
            MemoReaderStatus.set("멈춤(화면 닫힘)")
        }
    }

    // Shown: pull once (and push what waits). Each time the screen comes back, once more — never on a timer.
    // 추가요청 2: while the editor is open the pull still runs (the draft is never overwritten, MemoEditTest), but
    // nothing goes up: the server would review a half written note again and again.
    LaunchedEffect(runner, visible, editing) {
        if (!visible) return@LaunchedEffect
        // The first request waits (a short while) for the answer reader, so its answer is not missed.
        withTimeoutOrNull(MEMO_READER_WAIT_MS) { readerReady.await() }
        runner.syncNow(push = !editing)
    }

    // 앱29 ②: a failed pull is tried again after 15 s, then 60 s — only while the screen is shown (hidden: the wait is
    // cancelled, the next open pulls anyway). Pulls going well ask for nothing, so nothing waits on a timer then.
    val retry by runner.pullRetry.collectAsState()
    LaunchedEffect(runner, visible, retry) {
        memoRetryPullWhenDue(visible, retry, sleep = { delay(it) }) { seq -> runner.retryPull(seq) }
    }

    // A local change while visible: wait for the input to stop, then one push. A new change restarts the wait.
    // 추가요청 2: the auto save of the editor writes locally only; the push waits for the editing to end (editing
    // turning false runs this effect again, so what piled up goes up in one round).
    LaunchedEffect(runner, visible, editing, localVersion, retryTick) {
        if (!memoPushAllowed(visible, editing, runner.hasPendingUploads())) return@LaunchedEffect
        delay(MemoSyncRunner.PUSH_DELAY_MS)
        runner.push()
        // Still something left (server off): one more try after its back-off, cancelled as soon as the screen hides.
        val wait = runner.nextRetryInMs() ?: return@LaunchedEffect
        delay(wait.coerceAtLeast(MemoSyncRunner.PUSH_DELAY_MS))
        retryTick++
    }
}

/** The settings of the in-app answer reader: started by its first message, the URL is never loaded. */
private fun memoReaderWidgetSettings() = MatrixWidgetSettings(
    id = MEMO_READER_WIDGET_ID,
    initAfterContentLoad = true,
    rawUrl = MEMO_READER_WIDGET_URL,
)
