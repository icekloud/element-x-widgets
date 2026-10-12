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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.architecture.bindings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay

/**
 * The A4 sync of the memo screen (설계 §k-4), only while the screen is visible:
 * - shown (opened, back from the background): one `sync_pull` round, then what waits goes up
 * - a local change: `sync_push` once the input stopped for [MemoSyncRunner.PUSH_DELAY_MS]
 * - a failed send is tried again after its back-off, still only while visible
 * - hidden (ON_STOP) with something left to send: one one-time job that runs when the network is there
 *
 * Nothing runs on a timer when the screen is not visible, and nothing is shown while it works: the notes are local.
 */
@Composable
internal fun MemoSyncEffects(roomId: String, timelineItems: ImmutableList<TimelineItem>, localVersion: Long) {
    val context = LocalContext.current.applicationContext
    val bindings = remember(context) { context.bindings<MemoSyncBindings>() }
    val sessionStore = remember(bindings) { bindings.memoSyncSessionStore() }
    val runner = remember(roomId) { MemoSyncHub.runner(context, sessionStore, roomId) }
    val room = remember(roomId) { MemoSyncHub.room(context, sessionStore, roomId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    // A retry that is due bumps this, so the push effect runs again (only while visible).
    var retryTick by remember { mutableStateOf(0) }

    DisposableEffect(lifecycle, runner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> visible = true
                Lifecycle.Event.ON_STOP -> {
                    visible = false
                    if (runner.hasPendingUploads()) MemoSyncHub.scheduleUpload(bindings.memoSyncWorkManagerScheduler())
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Leaving the memo screen (another room, back): the same as going to the background.
            if (runner.hasPendingUploads()) MemoSyncHub.scheduleUpload(bindings.memoSyncWorkManagerScheduler())
        }
    }

    // The answers of an encrypted room only reach the waiting call through the timeline (decrypted by the SDK).
    LaunchedEffect(room, timelineItems) {
        memoAnswersOf(timelineItems).forEach(room::deliver)
    }

    // Shown: pull once (and push what waits). Each time the screen comes back, once more — never on a timer.
    LaunchedEffect(runner, visible) {
        if (visible) runner.syncNow()
    }

    // A local change while visible: wait for the input to stop, then one push. A new change restarts the wait.
    LaunchedEffect(runner, visible, localVersion, retryTick) {
        if (!visible || !runner.hasPendingUploads()) return@LaunchedEffect
        delay(MemoSyncRunner.PUSH_DELAY_MS)
        runner.push()
        // Still something left (server off): one more try after its back-off, cancelled as soon as the screen hides.
        val wait = runner.nextRetryInMs() ?: return@LaunchedEffect
        delay(wait.coerceAtLeast(MemoSyncRunner.PUSH_DELAY_MS))
        retryTick++
    }
}
