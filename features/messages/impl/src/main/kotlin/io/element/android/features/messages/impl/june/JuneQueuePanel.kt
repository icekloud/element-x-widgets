/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.designsystem.theme.components.DropdownMenu
import io.element.android.libraries.designsystem.theme.components.DropdownMenuItem
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val SAFETY_REFRESH_MILLIS = 20_000L

/**
 * Element June: the panels above the composer, top to bottom: running background processes, then
 * the bot's busy queue. When both show, the background list is capped lower so that the composer
 * area (at most half the screen) is not crowded; both lists scroll inside.
 */
@Composable
internal fun JuneComposerPanels(
    roomId: String,
    editingEventId: String?,
    onEdit: (TimelineItem.Event, JuneEditKind) -> Unit,
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

/** A queued message's row. With the server's picture list: "📷 N장 · <text>" / "📷 사진 N장"; older servers: as before. */
internal fun juneQueueLine(item: JuneQueueItem, event: TimelineItem.Event?): String =
    if (item.photos.isNotEmpty()) junePhotoLine(item.photos.size, juneQueueText(event)) else lineFor(item.kind, item.ids, event)

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
    onEdit: (TimelineItem.Event, JuneEditKind) -> Unit,
    onTick: (Long) -> Unit,
) {
    val context: Context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val held by JuneQueueStore.held.collectAsState()
    val heldHere = held[roomId].orEmpty()
    // Rows greyed out until the gateway reports the result of my request
    val ops = rememberJuneQueueOps(roomId)
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
    val header = juneQueueHeader(items.size, steering.size) ?: return
    // "밀어서 스티어링 감도" setting (observable: a change in the settings applies from the next push)
    val swipeFraction = JuneSettings.swipeSteerFraction(context)

    fun send(op: String, item: JuneQueueItem) {
        menuFor = null
        ops.send(op, item.id)
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
            // Pushing a row from right to left steers it, like "대신 스티어링으로 보내기" in its menu
            items.forEach { item ->
                key(item.id) {
                    val event = heldHere[item.id]
                    val busy = ops.isBusy(item.id)
                    val editing = item.id == editingEventId
                    JuneSwipeSteerRow(
                        canSwipe = juneCanSwipeSteer(item, editing = editing, busy = busy),
                        fraction = swipeFraction,
                        onSteer = { onDone ->
                            menuFor = null
                            ops.send("steer", item.id, onDone = onDone)
                        },
                        contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
                        modifier = Modifier.alpha(if (busy) 0.4f else 1f),
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
                                append(juneQueueLine(item, event))
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
                                // A picture message edits the caption of its main picture (the gateway reads the text from there)
                                val editKind = juneEditKind(item, event)
                                DropdownMenuItem(
                                    text = { Text("메시지 편집") },
                                    enabled = editKind != null,
                                    leadingIcon = { Icon(imageVector = CompoundIcons.Edit(), contentDescription = null) },
                                    onClick = {
                                        menuFor = null
                                        if (event != null && editKind != null) {
                                            JuneQueueStore.markEditing(roomId, item.id)
                                            onEdit(event, editKind)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("대신 스티어링으로 보내기") },
                                    enabled = juneCanSteer(item),
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
}
