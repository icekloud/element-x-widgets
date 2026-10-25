/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Test tag of the sync button. */
internal const val MEMO_SYNC_BUTTON_TAG = "memo_sync_button"

/** 앱29 ②: test tag of the 받기 필요 word beside it. */
internal const val MEMO_PULL_NEEDED_TAG = "memo_pull_needed"

/**
 * The sync button of the memo bot room top bar (앱6 항목 2), in place of the "중단" (/stop) icon of the other rooms.
 * A press syncs at once: `sync_pull`, the waiting attachments, then `sync_push` ([MemoSyncRunner.syncByButton]).
 * A spinner shows while it runs, a short line tells how it ended. It never runs twice at the same time.
 */
@Composable
internal fun MemoSyncButton(roomId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val runner = remember(context, roomId) {
        MemoSyncHub.runner(context, context.bindings<MemoSyncBindings>().memoSyncSessionStore(), roomId)
    }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    // 앱29 ②: both pull retries failed: a short word beside the button until a pull goes well (a press pulls again).
    val pullNeeded by runner.pullNeeded.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (pullNeeded && !running) {
            Text(
                text = MEMO_PULL_NEEDED_TEXT,
                modifier = Modifier.padding(end = 4.dp).testTag(MEMO_PULL_NEEDED_TAG),
                style = ElementTheme.typography.fontBodyXsMedium,
                color = ElementTheme.colors.textCriticalPrimary,
            )
        }
        MemoSyncButtonIcon(runner, running, onRunning = { running = it }, scope = scope)
    }
}

@Composable
private fun MemoSyncButtonIcon(runner: MemoSyncRunner, running: Boolean, onRunning: (Boolean) -> Unit, scope: CoroutineScope) {
    val context = LocalContext.current.applicationContext
    Box(Modifier.size(24.dp).testTag(MEMO_SYNC_BUTTON_TAG), contentAlignment = Alignment.Center) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(1.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                modifier = Modifier.clickable {
                    onRunning(true)
                    scope.launch {
                        // 앱8: the spinner always stops and a line is always shown, whatever ended the press.
                        val text = try {
                            memoSyncReportText(withContext(Dispatchers.IO) { runner.syncByButtonReport() })
                        } catch (e: CancellationException) {
                            // The screen went away: nothing to show. A time out from below still gets its line.
                            if (!isActive) throw e
                            Timber.w("June memo: sync button timed out (%s)", e.javaClass.simpleName)
                            memoSyncButtonText(MemoButtonSync.TimedOut)
                        } catch (e: Exception) {
                            Timber.w("June memo: sync button failed (%s)", e.javaClass.simpleName)
                            memoSyncButtonText(MemoButtonSync.Failed)
                        } finally {
                            onRunning(false)
                        }
                        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                    }
                },
                imageVector = CompoundIcons.Restart(),
                contentDescription = "동기화",
            )
        }
    }
}

/**
 * The room id the top bar shows the sync button for: the memo bot room with its memo screen (not a thread, not the
 * fake data mode, which has no server). Null in every other room, which keeps the "중단" icon.
 */
internal fun memoSyncRoomIdOf(context: Context, roomId: String, inThread: Boolean): String? =
    roomId.takeIf { !inThread && JuneSettings.isMemoRoom(context, it) && !JuneSettings.memoFakeData(context) }

/** Test tag of the list icon of the memo bot room (앱9 A: opens the proposal area). */
internal const val MEMO_PANEL_BUTTON_TAG = "memo_panel_button"

/**
 * The room id the list icon opens the proposal area for (앱9 A): the memo bot room with its memo screen, not a
 * thread. The fake data mode is included: its memo screen and cards are shown too (the instruction input is off).
 */
internal fun memoPanelRoomIdOf(context: Context, roomId: String, inThread: Boolean): String? =
    roomId.takeIf { !inThread && JuneSettings.isMemoRoom(context, it) }

/** Test tag of the selection mode icon of the memo bot room (앱11 C), left of the list icon. */
internal const val MEMO_SELECT_BUTTON_TAG = "memo_select_button"

/** 앱11 C: the selection mode icon of the memo bot room (on: filled check). A second press ends the mode. */
@Composable
internal fun MemoSelectButton(roomId: String, modifier: Modifier = Modifier) {
    val rooms by MemoSelectHub.rooms.collectAsState()
    val on = roomId in rooms
    Icon(
        modifier = modifier.testTag(MEMO_SELECT_BUTTON_TAG).clickable { MemoSelectHub.toggle(roomId) },
        imageVector = if (on) CompoundIcons.CheckCircleSolid() else CompoundIcons.CheckCircle(),
        contentDescription = if (on) "선택 모드 끝내기" else "여러 개 선택",
    )
}

/** The list icon of the memo bot room: opens the proposal area, a badge with the waiting cards (none at 0). */
@Composable
internal fun MemoPanelButton(roomId: String, modifier: Modifier = Modifier) {
    val counts by MemoPanelHub.waiting.collectAsState()
    val count = counts[roomId] ?: 0
    Box(modifier.testTag(MEMO_PANEL_BUTTON_TAG).clickable { MemoPanelHub.open(roomId) }) {
        Icon(
            imageVector = CompoundIcons.ListBulleted(),
            contentDescription = if (count > 0) "제안 ${count}건" else "제안",
        )
        if (count > 0) {
            MemoBadge(
                if (count > 99) "99+" else count.toString(),
                Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).testTag(MemoTags.PANEL_BADGE),
            )
        }
    }
}
