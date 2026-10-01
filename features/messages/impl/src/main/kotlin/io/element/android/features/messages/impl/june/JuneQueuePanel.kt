/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContentWithAttachment
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.designsystem.theme.components.DropdownMenu
import io.element.android.libraries.designsystem.theme.components.DropdownMenuItem
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val SAFETY_REFRESH_MILLIS = 20_000L
private const val OP_TIMEOUT_MILLIS = 15_000L

/**
 * Element June: the panels above the composer, top to bottom: running background processes, then
 * the bot's busy queue. When both show, the background list is capped lower so that the composer
 * area (at most half the screen) is not crowded; both lists scroll inside.
 */
@Composable
internal fun JuneComposerPanels(
    roomId: String,
    editingEventId: String?,
    onEdit: (TimelineItem.Event) -> Unit,
) {
    val queues by JuneQueueStore.all.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val queued = queues[roomId]?.liveItems(now).orEmpty()
    val steering = juneSteeringRows(queued, queues[roomId]?.liveSteering(now).orEmpty())
    JuneBackgroundPanel(roomId = roomId, maxListHeight = if (queued.isEmpty() && steering.isEmpty()) 240.dp else 120.dp)
    JuneQueuePanel(
        roomId = roomId,
        items = queued,
        steering = steering,
        editingEventId = editingEventId,
        onEdit = onEdit,
        onTick = { now = it },
    )
}

/** Steered messages the model has not read yet, minus any the server still lists as queued. */
internal fun juneSteeringRows(items: List<JuneQueueItem>, steering: List<JuneHeldItem>): List<JuneHeldItem> {
    val queued = items.mapTo(HashSet()) { it.id }
    return steering.filter { it.id !in queued }
}

/** Panel title: "대기 N" and/or "반영 대기 M"; null when there is nothing to show. */
internal fun juneQueueHeader(queued: Int, steering: Int): String? {
    val parts = buildList {
        if (queued > 0) add(if (queued >= 25) "대기 $queued · 곧 가득 참(최대 32)" else "대기 $queued")
        if (steering > 0) add("반영 대기 $steering")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun errorText(op: String, err: String?): String = when (err) {
    "not_queued" -> "이미 실행되어 처리하지 않았습니다"
    "not_text" -> "사진 외 파일·영상 메시지는 스티어링할 수 없습니다"
    "media_missing" -> "사진 파일을 찾지 못해 대기열에 그대로 둡니다"
    "starting" -> "봇이 막 시작하는 중이라 대기열에 그대로 둡니다"
    "idle" -> "지금 실행 중인 작업이 없어 대기열에 그대로 둡니다"
    "steer_failed" -> "스티어링하지 못해 대기열에 그대로 둡니다"
    else -> if (op == "steer") "스티어링하지 못했습니다" else "취소하지 못했습니다"
}

private fun lineFor(item: JuneQueueItem, event: TimelineItem.Event?): String = lineFor(item.kind, item.ids, event)

/** A steered message's row: "⏩ 반영 대기 · <first line>". The server sends no kind here; a batch is pictures. */
internal fun juneSteeringLine(item: JuneHeldItem, event: TimelineItem.Event?): String =
    "⏩ 반영 대기 · " + lineFor(if (item.ids.size > 1) "photo" else null, item.ids, event)

private fun lineFor(kind: String?, ids: List<String>, event: TimelineItem.Event?): String {
    val content = event?.content
    return when {
        content is TimelineItemTextBasedContent -> content.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        kind == "photo" || content is TimelineItemEventContentWithAttachment -> {
            val caption = (content as? TimelineItemEventContentWithAttachment)?.caption?.trim().orEmpty()
            val count = ids.size.coerceAtLeast(1)
            "📷 사진 ${count}장" + if (caption.isNotEmpty()) " + $caption" else ""
        }
        else -> "대기 중인 메시지"
    }
}

@Composable
private fun JuneQueuePanel(
    roomId: String,
    items: List<JuneQueueItem>,
    steering: List<JuneHeldItem>,
    editingEventId: String?,
    onEdit: (TimelineItem.Event) -> Unit,
    onTick: (Long) -> Unit,
) {
    val context: Context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val held by JuneQueueStore.held.collectAsState()
    val heldHere = held[roomId].orEmpty()
    // rid -> (item id, op, sent at): rows greyed out until the gateway reports the result
    val pending = remember(roomId) { mutableStateMapOf<String, Triple<String, String, Long>>() }
    var menuFor by remember(roomId) { mutableStateOf<String?>(null) }

    LaunchedEffect(roomId) {
        if (JuneQueueStore.client == null) {
            JuneQueueStore.client = JuneQueueClient(context.bindings<JuneBackgroundBindings>().juneSessionStore())
        }
        JuneQueueStore.clearEdits(roomId)
    }
    // Look again when the timeline changes (my message, 👀/✅/⏩ reactions, a bot reply). The gateway
    // queues a message a moment after it shows up, so look a few times right after a change.
    LaunchedEffect(roomId) {
        JuneQueueStore.pulse.map { it[roomId] }.distinctUntilChanged().collectLatest {
            for (wait in longArrayOf(0L, 1_500L, 3_000L)) {
                delay(wait)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) JuneQueueStore.refresh(roomId)
            }
        }
    }
    LaunchedEffect(roomId) {
        while (true) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) JuneQueueStore.refresh(roomId)
            onTick(System.currentTimeMillis())
            delay(SAFETY_REFRESH_MILLIS)
        }
    }
    // Report the results of my requests (only failures need words; success is visible by itself).
    val results = JuneQueueStore.all.collectAsState().value[roomId]?.results.orEmpty()
    LaunchedEffect(results, pending.size) {
        val nowMs = System.currentTimeMillis()
        pending.entries.toList().forEach { (rid, info) ->
            val res = results.firstOrNull { it.rid == rid }
            if (res != null) {
                pending.remove(rid)
                if (!res.ok) Toast.makeText(context, errorText(res.op, res.err), Toast.LENGTH_SHORT).show()
            } else if (nowMs - info.third > OP_TIMEOUT_MILLIS) {
                pending.remove(rid)
            }
        }
    }

    val header = juneQueueHeader(items.size, steering.size) ?: return

    fun send(op: String, item: JuneQueueItem) {
        menuFor = null
        scope.launch {
            val rid = JuneQueueStore.client?.sendOp(roomId, op, item.id)
            if (rid == null) {
                Toast.makeText(context, "요청을 보내지 못했습니다", Toast.LENGTH_SHORT).show()
            } else {
                pending[rid] = Triple(item.id, op, System.currentTimeMillis())
                for (wait in longArrayOf(800L, 2_000L)) {
                    delay(wait)
                    JuneQueueStore.refresh(roomId)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ElementTheme.colors.bgSubtleSecondary),
    ) {
        Text(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
            text = header,
            style = ElementTheme.typography.fontBodySmMedium,
            color = if (items.size >= 25) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary,
        )
        Column(
            modifier = Modifier
                .heightIn(max = 132.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Steered but not read by the model yet: hidden from the timeline, so shown here. No menu,
            // as it can no longer be edited, steered or cancelled.
            steering.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = juneSteeringLine(item, heldHere[item.id]),
                        style = ElementTheme.typography.fontBodyMdRegular,
                        color = ElementTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            items.forEach { item ->
                val event = heldHere[item.id]
                val busy = pending.values.any { it.first == item.id }
                val editing = item.id == editingEventId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (busy) 0.4f else 1f)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier.size(16.dp),
                        imageVector = CompoundIcons.Time(),
                        contentDescription = null,
                        tint = ElementTheme.colors.iconSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        modifier = Modifier.weight(1f),
                        text = buildString {
                            if (editing) append("[편집 중] ")
                            append(lineFor(item, event))
                            if (item.edited) append(" (편집됨)")
                        },
                        style = ElementTheme.typography.fontBodyMdRegular,
                        color = ElementTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box {
                        Icon(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(enabled = !busy) { menuFor = item.id }
                                .padding(10.dp),
                            imageVector = CompoundIcons.OverflowHorizontal(),
                            contentDescription = "대기 메시지 메뉴",
                            tint = ElementTheme.colors.iconSecondary,
                        )
                        DropdownMenu(
                            expanded = menuFor == item.id,
                            onDismissRequest = { menuFor = null },
                        ) {
                            val canEdit = item.editable && event?.content is TimelineItemTextBasedContent
                            DropdownMenuItem(
                                text = { Text("메시지 편집") },
                                enabled = canEdit,
                                leadingIcon = { Icon(imageVector = CompoundIcons.Edit(), contentDescription = null) },
                                onClick = {
                                    menuFor = null
                                    if (event != null) {
                                        JuneQueueStore.markEditing(roomId, item.id)
                                        onEdit(event)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("대신 스티어링으로 보내기") },
                                enabled = item.kind == "text" || item.kind == "photo",
                                leadingIcon = { Icon(imageVector = CompoundIcons.Forward(), contentDescription = null) },
                                onClick = { send("steer", item) },
                            )
                            DropdownMenuItem(
                                text = { Text("메시지 취소", color = ElementTheme.colors.textCriticalPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = CompoundIcons.Close(),
                                        contentDescription = null,
                                        tint = ElementTheme.colors.iconCriticalPrimary,
                                    )
                                },
                                onClick = { send("cancel", item) },
                            )
                        }
                    }
                }
            }
        }
    }
}
