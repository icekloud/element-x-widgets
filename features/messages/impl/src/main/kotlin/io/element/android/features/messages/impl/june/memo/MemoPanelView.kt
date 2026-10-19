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
import io.element.android.libraries.designsystem.theme.components.DropdownMenu
import io.element.android.libraries.designsystem.theme.components.DropdownMenuItem
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 앱9 A·D: the proposal area of the memo bot room, full screen over the room (back closes it), with the instruction
 * input at the bottom ([directives] null = no server, the fake data mode: the input is off). 앱18 ②: the waiting cards and
 * "내 지시 → 메모봇 답" are one list like a chat (oldest on top, [memoPanelTimeline]) that opens at its newest row, the
 * bottom; 앱18 ① a line over the input takes the ended instructions off the list ("지우기").
 * 앱22: the switch over the input sends the same words to the memo bot as a chat instead ([chat], 규약-대화 v1); the
 * bubbles are rows of the same list and the change requests of the bot are cards over it.
 */
@Composable
internal fun MemoPanel(
    controller: MemoController,
    directives: MemoDirectives?,
    onSync: suspend () -> Unit,
    onClose: () -> Unit,
    chat: MemoChat? = null,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MemoPanelContent(controller, directives, onSync, onClose, chat)
    }
}

@Composable
internal fun MemoPanelContent(
    controller: MemoController,
    directives: MemoDirectives?,
    onSync: suspend () -> Unit,
    onClose: () -> Unit,
    chat: MemoChat? = null,
) {
    val index by controller.store.index.collectAsState()
    val groups = remember(index) { memoPanelGroups(index) }
    val none = remember { MutableStateFlow(emptyList<MemoDirective>()) }
    val sentList by (directives?.items ?: none).collectAsState()
    val noBubbles = remember { MutableStateFlow(emptyList<MemoBubble>()) }
    val bubbles by (chat?.items ?: noBubbles).collectAsState()
    val noMode = remember { MutableStateFlow(MemoInputMode.Directive) }
    val mode by (chat?.mode ?: noMode).collectAsState()
    val noRequests = remember { MutableStateFlow(persistentListOf<MemoChangeRequest>()) }
    val requests by (chat?.requests ?: noRequests).collectAsState()
    val noOff = remember { MutableStateFlow<String?>(null) }
    val chatOff by (chat?.off ?: noOff).collectAsState()
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val synced by rememberUpdatedState(onSync)
    val entries = remember(groups, sentList, bubbles) { memoPanelTimeline(groups, sentList, bubbles) }
    // The empty line stays on top while there is no card; the rows of the list are it and the entries.
    val rows = entries.size + if (groups.isEmpty()) 1 else 0
    val listState = rememberLazyListState()
    var scrollCause by remember { mutableStateOf(MemoPanelScroll.Open) }
    var askClear by remember { mutableStateOf(false) }
    var askReset by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    MemoPanelFollow(listState, rows, entries.joinToString("|") { it.key + "=" + it.look }, scrollCause) { scrollCause = MemoPanelScroll.Change }

    suspend fun pollOnce() {
        if (directives != null && directives.poll() > 0) synced()
        if (chat != null && chat.poll() > 0) synced()
    }

    // Opened: what the phone keeps, then the open instructions are asked about (규약-지시 흐름 3).
    LaunchedEffect(directives) {
        directives ?: return@LaunchedEffect
        directives.load()
        if (directives.poll() > 0) synced()
    }
    // 앱22: the chat of this room, and in 대화 mode the change requests of the memo bot (규약-대화 §6).
    LaunchedEffect(chat) {
        chat ?: return@LaunchedEffect
        chat.load()
        if (chat.mode.value == MemoInputMode.Chat) {
            if (chat.poll() > 0) synced()
            chat.loadRequests()
        }
    }
    // After a send: from 5 s, every 5 s in the first minute, then every 15 s, at most 3 minutes; only while open.
    // 추가요청 3: a send cut short ("보내는중" after the area closed) is asked about too.
    val openKey = sentList.filter { it.isOpen || it.status == MemoDirectiveStatus.SENDING }.joinToString { it.id + it.status }
    LaunchedEffect(directives, openKey) {
        directives ?: return@LaunchedEffect
        while (true) {
            val wait = directives.nextPollInMs() ?: break
            delay(wait)
            if (directives.poll() > 0) synced()
        }
    }
    // 앱22: the chat waits longer between look-ups (the memo bot takes 20~30 s or more, 결정기록 3).
    val talkKey = bubbles.filter { it.isOpen || it.status == MemoDirectiveStatus.SENDING }.joinToString { it.seq.toString() + it.localId + it.status }
    LaunchedEffect(chat, talkKey) {
        chat ?: return@LaunchedEffect
        while (true) {
            val wait = chat.nextPollInMs() ?: break
            delay(wait)
            if (chat.poll() > 0) synced()
        }
    }
    // 추가요청 3: "처리 중… (N초)" counts up once a second while something is on its way.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(openKey, talkKey) {
        while (openKey.isNotEmpty() || talkKey.isNotEmpty()) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    fun send() {
        val sentence = text.trim()
        if (sentence.isEmpty() || sending) return
        sending = true
        scrollCause = MemoPanelScroll.Send
        scope.launch {
            try {
                val ended = if (mode == MemoInputMode.Chat && chat != null) {
                    chat.send(sentence)?.status
                } else {
                    directives?.send(sentence)?.status
                }
                // Kept when it could not be sent: the same words go again with the same id.
                if (ended != null && ended != MemoDirectiveStatus.NOT_SENT) text = ""
            } finally {
                sending = false
            }
        }
    }

    fun selectMode(value: MemoInputMode) {
        val talk = chat ?: return
        if (value == mode) return
        text = text.take(value.maxChars)
        scope.launch {
            talk.setMode(value)
            if (value == MemoInputMode.Chat) {
                talk.loadRequests()
                if (talk.poll() > 0) synced()
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
                        if (mode == MemoInputMode.Chat) chat?.loadRequests()
                    }
                }.padding(8.dp),
                color = ElementTheme.colors.textActionAccent,
                style = ElementTheme.typography.fontBodySmMedium,
            )
            if (chat != null && mode == MemoInputMode.Chat) {
                Box {
                    Text(
                        "⋯",
                        modifier = Modifier.clickable { menuOpen = true }.padding(8.dp).testTag(MemoTags.PANEL_MENU),
                        style = ElementTheme.typography.fontHeadingSmMedium,
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("대화 초기화") },
                            onClick = {
                                menuOpen = false
                                askReset = true
                            },
                            modifier = Modifier.testTag(MemoTags.PANEL_RESET),
                        )
                    }
                }
            }
        }
        if (mode == MemoInputMode.Chat && requests.isNotEmpty()) MemoRequestCards(requests)
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
                    is MemoPanelEntry.Talk -> MemoChatRow(
                        bubble = e.bubble,
                        nowMs = nowMs,
                        onResend = { scope.launch { chat?.resend(e.bubble.localId) } },
                        onYes = {
                            scrollCause = MemoPanelScroll.Send
                            scope.launch { chat?.send(MEMO_CHAT_YES) }
                        },
                    )
                }
            }
        }
        if (directives != null && (sentList.isNotEmpty() || bubbles.isNotEmpty())) {
            MemoDirectiveHead(
                text = memoPanelHeadText(sentList.size, bubbles.size),
                canClear = memoDirectivesClearable(sentList).isNotEmpty() || bubbles.isNotEmpty(),
                onClear = { askClear = true },
            )
        }
        controller.error?.let { (reason, _) ->
            Text(
                reason,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = ElementTheme.colors.textCriticalPrimary,
                style = ElementTheme.typography.fontBodySmRegular,
            )
        }
        chatOff?.let { off ->
            Text(
                off,
                modifier = Modifier.padding(horizontal = 12.dp).testTag(MemoTags.PANEL_CHAT_OFF),
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
            if (chat != null) MemoInputModeRow(mode, text) { selectMode(it) }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it.take(mode.maxChars) },
                    placeholder = mode.placeholder,
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
            content = memoPanelClearText(sentList, bubbles),
            submitText = "지우기",
            cancelText = "취소",
            destructiveSubmit = true,
            onSubmitClick = {
                askClear = false
                scope.launch {
                    directives.clear()
                    chat?.clear()
                }
            },
            onDismiss = { askClear = false },
        )
    }
    if (askReset && chat != null) {
        ConfirmationDialog(
            content = MEMO_CHAT_RESET_ASK,
            submitText = "초기화",
            cancelText = "취소",
            onSubmitClick = {
                askReset = false
                scope.launch { chat.reset() }
            },
            onDismiss = { askReset = false },
        )
    }
}

/**
 * 앱22: the switch in front of the input, "지시 | 대화" (관리자 결정; 지시 is the one of before and stays the default). In
 * 대화 the line under it says what the mode does and how long a word may be.
 */
@Composable
private fun MemoInputModeRow(mode: MemoInputMode, text: String, onSelectMode: (MemoInputMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemoInputModeTab(MemoInputMode.Directive, mode, MemoTags.PANEL_MODE_DIRECTIVE, onSelectMode)
            MemoInputModeTab(MemoInputMode.Chat, mode, MemoTags.PANEL_MODE_CHAT, onSelectMode)
            if (mode == MemoInputMode.Chat) {
                Text(
                    memoInputCounter(text, mode),
                    modifier = Modifier.weight(1f).padding(start = 8.dp).testTag(MemoTags.PANEL_COUNT),
                    color = ElementTheme.colors.textSecondary,
                    style = ElementTheme.typography.fontBodyXsRegular,
                )
            }
        }
        if (mode.hint.isNotEmpty()) {
            Text(
                mode.hint,
                modifier = Modifier.padding(top = 2.dp).testTag(MemoTags.PANEL_HINT),
                color = ElementTheme.colors.textSecondary,
                style = ElementTheme.typography.fontBodyXsRegular,
            )
        }
    }
}

@Composable
private fun MemoInputModeTab(value: MemoInputMode, mode: MemoInputMode, tag: String, onSelectMode: (MemoInputMode) -> Unit) {
    val chosen = value == mode
    Text(
        value.label,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (chosen) ElementTheme.colors.bgActionPrimaryRest else ElementTheme.colors.bgSubtleSecondary)
            .clickable { onSelectMode(value) }
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .testTag(tag),
        color = if (chosen) ElementTheme.colors.textOnSolidPrimary else ElementTheme.colors.textSecondary,
        style = ElementTheme.typography.fontBodySmMedium,
    )
}

/** 앱22 (규약-대화 §6): what the memo bot asked to have changed. The phone only shows it (no button changes a state). */
@Composable
private fun MemoRequestCards(requests: ImmutableList<MemoChangeRequest>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(MemoTags.PANEL_REQUESTS)) {
        Text(
            memoRequestHeadText(requests),
            color = ElementTheme.colors.textSecondary,
            style = ElementTheme.typography.fontBodySmMedium,
        )
        requests.take(MEMO_REQUEST_CARDS).forEach { r ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ElementTheme.colors.bgSubtleSecondary)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag(MemoTags.PANEL_REQUEST_CARD),
            ) {
                Text(memoRequestCardLine(r), style = ElementTheme.typography.fontBodySmMedium)
                Text(
                    memoRequestCardDetail(r),
                    color = ElementTheme.colors.textSecondary,
                    style = ElementTheme.typography.fontBodyXsRegular,
                )
            }
        }
        if (requests.size > MEMO_REQUEST_CARDS) {
            Text(
                "외 ${requests.size - MEMO_REQUEST_CARDS}건 더",
                modifier = Modifier.padding(top = 2.dp),
                color = ElementTheme.colors.textSecondary,
                style = ElementTheme.typography.fontBodyXsRegular,
            )
        }
    }
}

/** How many request cards the area draws at once. */
internal const val MEMO_REQUEST_CARDS = 3

/**
 * 앱18 ①: the line over the input, "메모봇에 한 지시 N건" and "지우기" on its right ([canClear]: there is something to clear).
 * 추가요청 1: it only tidies the screen, every instruction goes. 앱22: the bubbles of the chat go with them.
 */
@Composable
private fun MemoDirectiveHead(text: String, canClear: Boolean, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
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

/**
 * 앱22: one bubble of the chat — the words of the user on the right, the memo bot and the server on the left. A 질문 of
 * the server has a "네" button under it (규약-대화 §5), and a body change shows what would be written (§4).
 */
@Composable
private fun MemoChatRow(bubble: MemoBubble, nowMs: Long, onResend: () -> Unit, onYes: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(MemoTags.PANEL_BUBBLE)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (bubble.isUser) Alignment.CenterEnd else Alignment.CenterStart) {
            val critical = !bubble.isUser && (bubble.status == MemoDirectiveStatus.FAILED || bubble.status == MemoDirectiveStatus.REFUSED)
            Text(
                MemoChatSide.mark(bubble.side) + bubble.text,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (bubble.isUser) ElementTheme.colors.bgActionPrimaryRest else ElementTheme.colors.bgSubtleSecondary)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = when {
                    bubble.isUser -> ElementTheme.colors.textOnSolidPrimary
                    critical -> ElementTheme.colors.textCriticalPrimary
                    else -> ElementTheme.colors.textPrimary
                },
                style = ElementTheme.typography.fontBodySmRegular,
            )
        }
        bubble.compare?.let { MemoCompareBox(it) }
        val line = memoBubbleStatusText(bubble, nowMs)
        if (line.isNotEmpty() || bubble.canResend || bubble.asksYes) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (line.isNotEmpty()) {
                    Text(
                        line,
                        modifier = Modifier.widthIn(max = 300.dp).testTag(MemoTags.PANEL_BUBBLE_STATE),
                        color = if (bubble.status == MemoDirectiveStatus.NOT_SENT) {
                            ElementTheme.colors.textCriticalPrimary
                        } else {
                            ElementTheme.colors.textSecondary
                        },
                        style = ElementTheme.typography.fontBodyXsRegular,
                    )
                }
                if (bubble.canResend) {
                    Text(
                        "다시 보내기",
                        modifier = Modifier.clickable(onClick = onResend).padding(8.dp).testTag(MemoTags.PANEL_RESEND),
                        color = ElementTheme.colors.textActionAccent,
                        style = ElementTheme.typography.fontBodySmMedium,
                    )
                }
                if (bubble.asksYes) {
                    Text(
                        "네",
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElementTheme.colors.bgActionPrimaryRest)
                            .clickable(onClick = onYes)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .testTag(MemoTags.PANEL_YES),
                        color = ElementTheme.colors.textOnSolidPrimary,
                        style = ElementTheme.typography.fontBodySmMedium,
                    )
                }
            }
        }
    }
}

/** 규약-대화 §4: the body before and after, with the line that says what a "네" does. */
@Composable
private fun MemoCompareBox(compare: MemoCompare) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ElementTheme.colors.bgSubtleSecondary)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(MemoTags.PANEL_COMPARE),
    ) {
        Text(memoCompareSummary(compare), style = ElementTheme.typography.fontBodySmMedium, color = ElementTheme.colors.textSecondary)
        Text("전", modifier = Modifier.padding(top = 4.dp), color = ElementTheme.colors.textSecondary, style = ElementTheme.typography.fontBodyXsRegular)
        Text(compare.before, style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textPrimary)
        Text("후", modifier = Modifier.padding(top = 4.dp), color = ElementTheme.colors.textSecondary, style = ElementTheme.typography.fontBodyXsRegular)
        Text(compare.after, style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textPrimary)
        Text(
            MEMO_CHAT_COMPARE_NOTICE,
            modifier = Modifier.padding(top = 4.dp),
            color = ElementTheme.colors.textSecondary,
            style = ElementTheme.typography.fontBodyXsRegular,
        )
    }
}
