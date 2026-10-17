/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 앱9 A·D: the proposal area of the memo bot room, full screen over the room (back closes it), with the instruction
 * input at the bottom ([directives] null = no server, the fake data mode: the input is off). 앱18 ②: the waiting cards and
 * "내 지시 → 메모봇 답" are one list like a chat (oldest on top, [memoPanelTimeline]) that opens at its newest row, the
 * bottom; 앱18 ① a line over the input takes the ended instructions off the list ("지우기").
 */
@Composable
internal fun MemoPanel(
    controller: MemoController,
    directives: MemoDirectives?,
    onSync: suspend () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MemoPanelContent(controller, directives, onSync, onClose)
    }
}

@Composable
internal fun MemoPanelContent(
    controller: MemoController,
    directives: MemoDirectives?,
    onSync: suspend () -> Unit,
    onClose: () -> Unit,
) {
    val index by controller.store.index.collectAsState()
    val groups = remember(index) { memoPanelGroups(index) }
    val none = remember { MutableStateFlow(emptyList<MemoDirective>()) }
    val sentList by (directives?.items ?: none).collectAsState()
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val synced by rememberUpdatedState(onSync)
    val entries = remember(groups, sentList) { memoPanelTimeline(groups, sentList) }
    // The empty line stays on top while there is no card; the rows of the list are it and the entries.
    val rows = entries.size + if (groups.isEmpty()) 1 else 0
    val listState = rememberLazyListState()
    var scrollCause by remember { mutableStateOf(MemoPanelScroll.Open) }
    var askClear by remember { mutableStateOf(false) }
    MemoPanelFollow(listState, rows, entries.joinToString("|") { it.key + "=" + it.look }, scrollCause) { scrollCause = MemoPanelScroll.Change }

    suspend fun pollOnce() {
        if (directives == null) return
        if (directives.poll() > 0) synced()
    }

    // Opened: what the phone keeps, then the open instructions are asked about (규약-지시 흐름 3).
    LaunchedEffect(directives) {
        directives ?: return@LaunchedEffect
        directives.load()
        pollOnce()
    }
    // After a send: from 5 s, every 5 s in the first minute, then every 15 s, at most 3 minutes; only while open.
    // 추가요청 3: a send cut short ("보내는중" after the area closed) is asked about too.
    val openKey = sentList.filter { it.isOpen || it.status == MemoDirectiveStatus.SENDING }.joinToString { it.id + it.status }
    LaunchedEffect(directives, openKey) {
        directives ?: return@LaunchedEffect
        while (true) {
            val wait = directives.nextPollInMs() ?: break
            delay(wait)
            pollOnce()
        }
    }
    // 추가요청 3: "처리 중… (N초)" counts up once a second while something is on its way.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(openKey) {
        while (openKey.isNotEmpty()) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    fun send() {
        val d = directives ?: return
        val sentence = text.trim()
        if (sentence.isEmpty() || sending) return
        sending = true
        scrollCause = MemoPanelScroll.Send
        scope.launch {
            try {
                val ended = d.send(sentence)
                // Kept when it could not be sent: the same words go again with the same id.
                if (ended != null && ended.status != MemoDirectiveStatus.NOT_SENT) text = ""
            } finally {
                sending = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ElementTheme.colors.bgCanvasDefault)
            .systemBarsPadding()
            .imePadding()
            .testTag(MemoTags.PANEL),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", modifier = Modifier.clickable(onClick = onClose).padding(8.dp), style = ElementTheme.typography.fontHeadingSmMedium)
            Text("제안", modifier = Modifier.weight(1f), style = ElementTheme.typography.fontHeadingSmMedium)
            Text(
                "새로고침",
                modifier = Modifier.clickable {
                    scrollCause = MemoPanelScroll.Refresh
                    scope.launch {
                        synced()
                        pollOnce()
                    }
                }.padding(8.dp),
                color = ElementTheme.colors.textActionAccent,
                style = ElementTheme.typography.fontBodySmMedium,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(MemoTags.PANEL_LIST),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "대기 중인 제안이 없어요",
                        modifier = Modifier.padding(vertical = 16.dp).testTag(MemoTags.PANEL_EMPTY),
                        color = ElementTheme.colors.textSecondary,
                    )
                }
            }
            items(entries, key = { it.key }) { e ->
                when (e) {
                    is MemoPanelEntry.Card -> MemoProposalCard(controller, e.group)
                    is MemoPanelEntry.Order -> MemoDirectiveRow(e.directive, nowMs, onResend = { scope.launch { directives?.resend(e.directive.id) } })
                }
            }
        }
        if (directives != null && sentList.isNotEmpty()) {
            MemoDirectiveHead(count = sentList.size, canClear = memoDirectivesClearable(sentList).isNotEmpty(), onClear = { askClear = true })
        }
        controller.error?.let { (reason, _) ->
            Text(
                reason,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = ElementTheme.colors.textCriticalPrimary,
                style = ElementTheme.typography.fontBodySmRegular,
            )
        }
        if (directives == null) {
            Text(
                "가짜 데이터 모드에서는 지시를 보낼 수 없어요",
                modifier = Modifier.padding(12.dp),
                color = ElementTheme.colors.textSecondary,
                style = ElementTheme.typography.fontBodySmRegular,
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it.take(MEMO_DIRECTIVE_MAX_CHARS) },
                    placeholder = "메모봇에 지시 (예: 책상, NAS 태그 삭제)",
                    maxLines = 4,
                    modifier = Modifier.weight(1f).testTag(MemoTags.PANEL_INPUT),
                )
                Text(
                    if (sending) "…" else "보내기",
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ElementTheme.colors.bgActionPrimaryRest)
                        .clickable(enabled = !sending && text.isNotBlank()) { send() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(MemoTags.PANEL_SEND),
                    color = ElementTheme.colors.textOnSolidPrimary,
                    style = ElementTheme.typography.fontBodySmMedium,
                )
            }
        }
    }
    if (askClear && directives != null) {
        ConfirmationDialog(
            content = memoDirectivesClearText(sentList),
            submitText = "지우기",
            cancelText = "취소",
            destructiveSubmit = true,
            onSubmitClick = {
                askClear = false
                scope.launch { directives.clear() }
            },
            onDismiss = { askClear = false },
        )
    }
}

/**
 * 앱18 ①: the line over the input, "메모봇에 한 지시 N건" and "지우기" on its right ([canClear]: there is something to clear).
 * 추가요청 1: it only tidies the screen, every instruction goes.
 */
@Composable
private fun MemoDirectiveHead(count: Int, canClear: Boolean, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "메모봇에 한 지시 ${count}건",
            modifier = Modifier.weight(1f),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textSecondary,
        )
        if (canClear) {
            Text(
                "지우기",
                modifier = Modifier.clickable(onClick = onClear).padding(8.dp).testTag(MemoTags.PANEL_CLEAR),
                color = ElementTheme.colors.textActionAccent,
                style = ElementTheme.typography.fontBodySmMedium,
            )
        }
    }
}

/**
 * 앱18 ②: keeps the list at its newest row. [cause] Open, Refresh and Send always go down; a change of the rows ([shape])
 * only when the list was at the bottom ([memoPanelFollows]), and so does a smaller area (the keyboard opened). "At the
 * bottom" is read when the list is scrolled (by the user or here), not when rows come, so a new row does not make it
 * look as if the user had scrolled up. [done] tells the caller the cause has been handled.
 */
@Composable
private fun MemoPanelFollow(listState: LazyListState, rows: Int, shape: String, cause: MemoPanelScroll, done: () -> Unit) {
    var atBottom by remember { mutableStateOf(true) }
    val currentRows by rememberUpdatedState(rows)
    val handled by rememberUpdatedState(done)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { atBottom = !listState.canScrollForward }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize }.collect { if (atBottom) listState.toBottom(currentRows) }
    }
    LaunchedEffect(shape, cause) {
        if (memoPanelFollows(cause, atBottom)) {
            listState.toBottom(rows)
            atBottom = true
        }
        if (cause != MemoPanelScroll.Change) handled()
    }
}

/** Down to the end of the last row, also when that row is taller than the area (a few steps of one area height at most). */
private suspend fun LazyListState.toBottom(rows: Int) {
    if (rows <= 0) return
    scrollToItem(rows - 1)
    var steps = 0
    while (canScrollForward && steps++ < MEMO_PANEL_BOTTOM_STEPS) scrollBy(layoutInfo.viewportSize.height.coerceAtLeast(1).toFloat())
}

private const val MEMO_PANEL_BOTTOM_STEPS = 4

/** "내 지시 → 메모봇 답": the sentence on the right, the answer (or the state) on the left. */
@Composable
private fun MemoDirectiveRow(d: MemoDirective, nowMs: Long, onResend: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(MemoTags.PANEL_DIRECTIVE)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                d.text,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ElementTheme.colors.bgActionPrimaryRest)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = ElementTheme.colors.textOnSolidPrimary,
                style = ElementTheme.typography.fontBodySmRegular,
            )
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val critical = d.status == MemoDirectiveStatus.NOT_SENT || d.status == MemoDirectiveStatus.FAILED || d.status == MemoDirectiveStatus.REFUSED
            Text(
                "🤖 " + memoDirectiveAnswerText(d, nowMs),
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ElementTheme.colors.bgSubtleSecondary)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = if (critical) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textPrimary,
                style = ElementTheme.typography.fontBodySmRegular,
            )
            if (d.canResend) {
                Text(
                    "다시 보내기",
                    modifier = Modifier.clickable(onClick = onResend).padding(8.dp).testTag(MemoTags.PANEL_RESEND),
                    color = ElementTheme.colors.textActionAccent,
                    style = ElementTheme.typography.fontBodySmMedium,
                )
            }
        }
    }
}
