/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.coroutines.launch

/**
 * 앱32 요구 ②: the mark of the second line of the card of [id], read from the review log the controller keeps. Null
 * when nothing is to be shown (no log yet, no review ever asked for, or a finished one already seen).
 *
 * The time it is worked out with is the time of the drawing: a review that has been waiting too long turns into
 * 확인 필요 the next time the screen draws (no timer anywhere — 앱 원칙, 결정기록 D9).
 */
@Composable
internal fun memoReviewMarkFor(controller: MemoController, id: String): MemoReviewPhase? {
    val keeper = controller.reviewLog ?: return null
    val entries by keeper.entries.collectAsState()
    if (entries.isEmpty()) return null
    return memoReviewMarkOf(entries, id, System.currentTimeMillis(), keeper.boot)
}

/**
 * 앱32 요구 ③ (추가요청 2 ④: "검토 현황 상단에 고정구조, 펼침 구조로"): the 검토 현황 area at the top of the proposal
 * screen. It is **not** part of the card list below, so scrolling the proposals never takes it away.
 *
 * Closed it is one line ("검토 현황 · 진행 1 · 완료 2") with an arrow and 새로고침; pressed, it opens the rows: one per
 * note, and one folded line per press of several notes ("3개 중 1개 완료") that opens on its own press. Opening and
 * closing happen only by a press (never on its own), and nothing is remembered when the screen is left (결정기록 D6).
 * With no review ever asked for the whole area is hidden.
 */
@Composable
internal fun MemoReviewStatusArea(controller: MemoController, onOpenNote: (String) -> Unit) {
    val keeper = controller.reviewLog ?: return
    val entries by keeper.entries.collectAsState()
    if (entries.isEmpty()) return
    val nowMs = System.currentTimeMillis()
    val boot = keeper.boot
    var open by remember { mutableStateOf(false) }
    var openGroups by remember { mutableStateOf(setOf<String>()) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val titles = memoReviewTitles(controller)
    // 요구 ⑥·결정기록 D9: opening the proposal screen asks once about the reviews still running — the same kind of
    // moment as the pull the screen already does when it opens. Never a timer.
    // 앱33 추가요청 1 ③: coming back to the screen is such a moment too ([visits] goes up on every later ON_START),
    // so a review whose 5 minute follow-up is over is asked about once more without pressing 새로고침.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visits by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) visits++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(keeper, visits) {
        memoReviewRefresh(keeper, controller.reviewer, if (visits > 1) MemoCheckCause.BACK else MemoCheckCause.OPEN)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgSubtleSecondary)
            .testTag(MemoTags.REVIEW_STATUS)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(MemoTags.REVIEW_STATUS_HEAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                memoReviewHeadText(entries, nowMs, boot),
                modifier = Modifier.weight(1f),
                style = ElementTheme.typography.fontBodySmMedium,
            )
            Text(
                if (refreshing) "확인 중…" else "새로고침",
                modifier = Modifier
                    .clickable(enabled = !refreshing) {
                        refreshing = true
                        scope.launch {
                            try {
                                memoReviewRefresh(keeper, controller.reviewer)
                            } finally {
                                refreshing = false
                            }
                        }
                    }
                    .padding(horizontal = 8.dp)
                    .testTag(MemoTags.REVIEW_STATUS_REFRESH),
                color = ElementTheme.colors.textActionAccent,
                style = ElementTheme.typography.fontBodySmMedium,
            )
            Text(if (open) "▾" else "▸", style = ElementTheme.typography.fontBodySmMedium)
        }
        if (open) {
            val rows = remember(entries, openGroups) { memoReviewRows(entries, nowMs, boot) }
            // 추가요청 2 ④: the open area never takes more than this much of the screen; its own rows scroll inside it.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight * MEMO_REVIEW_PANEL_FRACTION)
                        .testTag(MemoTags.REVIEW_STATUS_LIST),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rows.forEach { row ->
                        when (row) {
                            is MemoReviewRow.Note -> item(key = "n-${row.entry.id}") {
                                MemoReviewStatusRow(row.entry, titles[row.entry.id], nowMs, boot) {
                                    keeper.seen(row.entry.id)
                                    onOpenNote(row.entry.id)
                                }
                            }
                            is MemoReviewRow.Group -> {
                                item(key = "g-${row.batch}") {
                                    MemoReviewStatusGroupRow(row, nowMs, open = row.batch in openGroups) {
                                        openGroups = if (row.batch in openGroups) openGroups - row.batch else openGroups + row.batch
                                    }
                                }
                                if (row.batch in openGroups) {
                                    items(row.entries, key = { "n-${it.id}" }) { e ->
                                        MemoReviewStatusRow(e, titles[e.id], nowMs, boot, inGroup = true) {
                                            keeper.seen(e.id)
                                            onOpenNote(e.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 추가요청 2 ④: the open 검토 현황 area takes at most this much of the screen it is drawn in. */
internal const val MEMO_REVIEW_PANEL_FRACTION = 0.4f

/** The titles of the notes the screen has loaded, by id (a note not loaded has none: the row says 제목 없음). */
@Composable
private fun memoReviewTitles(controller: MemoController): Map<String, String> {
    val lists by controller.store.lists.collectAsState()
    return remember(lists) {
        lists.values.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.title.isNotBlank() && !it.untitled }
            .associate { it.id to it.title }
    }
}

/**
 * 앱32 요구 ③·④: one note of the 검토 현황 list — its title, how long ago it was asked for, where it stands, and the
 * **two separate lines** of what came of it: 검토 결과 (제안 N건 / 제안 없음) and 지시 결과 (what the server did with the
 * directive, or that it did not say). A press opens the note (and counts as having seen it, 결정기록 D5·D15).
 */
@Composable
private fun MemoReviewStatusRow(
    entry: MemoReviewEntry,
    title: String?,
    nowMs: Long,
    boot: String,
    inGroup: Boolean = false,
    onOpen: () -> Unit,
) {
    val phase = memoReviewPhase(entry, nowMs, boot)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = if (inGroup) 12.dp else 0.dp, top = 4.dp, bottom = 4.dp)
            .testTag(MemoTags.REVIEW_STATUS_ROW),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title?.takeIf { it.isNotBlank() } ?: "제목 없음",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ElementTheme.typography.fontBodySmMedium,
            )
            Text(
                memoReviewAgoText(nowMs - entry.askedMs),
                color = ElementTheme.colors.textSecondary,
                style = ElementTheme.typography.fontBodyXsRegular,
            )
        }
        Text(
            "${memoReviewPhaseMark(phase)} ${memoReviewStateText(entry, nowMs, boot)}",
            color = if (phase == MemoReviewPhase.Attention) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary,
            style = ElementTheme.typography.fontBodyXsRegular,
        )
        // 요구 ④: the review result and the directive result are never one sentence.
        val result = memoReviewResultText(entry)
        if (result.isNotEmpty()) {
            Text(result, color = ElementTheme.colors.textSecondary, style = ElementTheme.typography.fontBodyXsRegular)
            Text(
                memoReviewDirectiveResultText(entry),
                color = ElementTheme.colors.textSecondary,
                style = ElementTheme.typography.fontBodyXsRegular,
            )
        }
    }
}

/** 요구 ③·⑦: the folded line of one press of several notes; a press on it opens its notes under it. */
@Composable
private fun MemoReviewStatusGroupRow(group: MemoReviewRow.Group, nowMs: Long, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp)
            .testTag(MemoTags.REVIEW_STATUS_GROUP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            memoReviewGroupText(group),
            modifier = Modifier.weight(1f),
            style = ElementTheme.typography.fontBodySmMedium,
        )
        Text(
            memoReviewAgoText(nowMs - group.entries.maxOf { it.askedMs }),
            color = ElementTheme.colors.textSecondary,
            style = ElementTheme.typography.fontBodyXsRegular,
        )
        Text(if (open) " ▾" else " ▸", style = ElementTheme.typography.fontBodySmMedium)
    }
}

/** The small picture the 검토 현황 rows put in front of their state words (the cards use icons, 결정기록 D4). */
internal fun memoReviewPhaseMark(phase: MemoReviewPhase): String = when (phase) {
    MemoReviewPhase.Asked -> "↗"
    MemoReviewPhase.Queued -> "⏱"
    MemoReviewPhase.Working -> "↻"
    MemoReviewPhase.Done -> "✓"
    MemoReviewPhase.Attention -> "!"
}
