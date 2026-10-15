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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 앱9 A·D: the proposal area of the memo bot room, full screen over the room (back closes it). The waiting cards on
 * top, "내 지시 → 메모봇 답" below them, and the instruction input at the bottom ([directives] null = no server, the fake
 * data mode: the input is off).
 */
@Composable
internal fun MemoPanel(
    controller: MemoController,
    directives: MemoDirectives?,
    onSynced: suspend () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MemoPanelContent(controller, directives, onSynced, onClose)
    }
}

@Composable
internal fun MemoPanelContent(
    controller: MemoController,
    directives: MemoDirectives?,
    onSynced: suspend () -> Unit,
    onClose: () -> Unit,
) {
    val index by controller.store.index.collectAsState()
    val groups = remember(index) { memoPanelGroups(index) }
    val none = remember { MutableStateFlow(emptyList<MemoDirective>()) }
    val sentList by (directives?.items ?: none).collectAsState()
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    suspend fun pollOnce() {
        if (directives == null) return
        if (directives.poll() > 0) onSynced()
    }

    // Opened: what the phone keeps, then the open instructions are asked about (규약-지시 흐름 3).
    LaunchedEffect(directives) {
        directives ?: return@LaunchedEffect
        directives.load()
        pollOnce()
    }
    // After a send: from 5 s, every 5 s in the first minute, then every 15 s, at most 3 minutes; only while open.
    val openKey = sentList.filter { it.isOpen }.joinToString { it.id + it.status }
    LaunchedEffect(directives, openKey) {
        directives ?: return@LaunchedEffect
        while (true) {
            val wait = directives.nextPollInMs() ?: break
            delay(wait)
            pollOnce()
        }
    }

    fun send() {
        val d = directives ?: return
        val sentence = text.trim()
        if (sentence.isEmpty() || sending) return
        sending = true
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
                    scope.launch {
                        onSynced()
                        pollOnce()
                    }
                }.padding(8.dp),
                color = ElementTheme.colors.textActionAccent,
                style = ElementTheme.typography.fontBodySmMedium,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
            items(groups, key = { it.key }) { g -> MemoProposalCard(controller, g) }
            if (sentList.isNotEmpty()) {
                item(key = "dir-head") {
                    Text(
                        "메모봇에 한 지시",
                        modifier = Modifier.padding(top = 12.dp),
                        style = ElementTheme.typography.fontBodySmMedium,
                        color = ElementTheme.colors.textSecondary,
                    )
                }
                items(sentList, key = { "d-" + it.id }) { d ->
                    MemoDirectiveRow(d, onResend = { scope.launch { directives?.resend(d.id) } })
                }
            }
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
}

/** "내 지시 → 메모봇 답": the sentence on the right, the answer (or the state) on the left. */
@Composable
private fun MemoDirectiveRow(d: MemoDirective, onResend: () -> Unit) {
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
                "🤖 " + memoDirectiveAnswerText(d),
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
