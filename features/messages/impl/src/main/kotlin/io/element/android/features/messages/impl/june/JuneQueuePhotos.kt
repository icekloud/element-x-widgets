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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContentWithAttachment
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val OP_TIMEOUT_MILLIS = 15_000L
private val CHIP_SIZE = 56.dp

/** The gateway's name rule for a picture added to a queued message: `june-to-<event id without $>.<n>.<ext>`. */
private val ATTACH_ID = Regex("[A-Za-z0-9_-]{8,128}")

/**
 * Element June: the file name of a picture picked while the queued message [targetEventId] is edited from the queue panel.
 * The gateway then adds the picture to that queued message instead of queueing it on its own. [index] is 1..999.
 * Null when the id cannot be carried in the name (the picture is then sent the usual way).
 */
internal fun juneAttachFileName(targetEventId: String, index: Int, extension: String): String? {
    val id = targetEventId.removePrefix("$")
    if (!ATTACH_ID.matches(id) || index !in 1..999) return null
    val ext = extension.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(5).ifEmpty { "jpg" }
    return "june-to-$id.$index.$ext"
}

/** A queue row for a message with pictures: "📷 N장 · <first line>", or "📷 사진 N장" without text. */
internal fun junePhotoLine(count: Int, text: String?): String {
    val first = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val n = count.coerceAtLeast(1)
    return if (first.isEmpty()) "📷 사진 ${n}장" else "📷 ${n}장 · $first"
}

/** The text of a queued message's main event: the body of a text, or the caption of a picture. */
internal fun juneQueueText(event: TimelineItem.Event?): String? = when (val content = event?.content) {
    is TimelineItemTextBasedContent -> content.body
    is TimelineItemEventContentWithAttachment -> content.caption
    else -> null
}

/** The event the composer is editing (its text or its caption), or null. */
internal fun juneEditingEventId(mode: MessageComposerMode): String? = when (mode) {
    is MessageComposerMode.Edit -> mode.eventOrTransactionId.eventId?.value
    is MessageComposerMode.EditCaption -> mode.eventOrTransactionId.eventId?.value
    else -> null
}

/** How a queued message is edited from the panel: its text, or the caption of its picture. Null = not editable here. */
internal enum class JuneEditKind { Text, Caption }

internal fun juneEditKind(item: JuneQueueItem, event: TimelineItem.Event?): JuneEditKind? = when {
    !item.editable || event == null -> null
    event.content is TimelineItemTextBasedContent -> JuneEditKind.Text
    event.content is TimelineItemImageContent -> JuneEditKind.Caption
    else -> null
}

private data class JunePendingOp(
    val itemId: String,
    val op: String,
    val photo: String?,
    val sentAt: Long,
    val onDone: ((ok: Boolean) -> Unit)?,
)

/** Queue controls sent to the gateway, greyed out until it reports the result; failures are told with a toast. */
internal class JuneQueueOps(private val roomId: String, private val scope: CoroutineScope, private val context: Context) {
    private val pending = mutableStateMapOf<String, JunePendingOp>()

    fun isBusy(itemId: String, photo: String? = null): Boolean =
        pending.values.any { it.itemId == itemId && (photo == null || it.photo == photo) }

    /** [onDone] runs once the gateway answered (ok or not), the request could not be sent, or it timed out (not ok). */
    fun send(op: String, itemId: String, photo: String? = null, onDone: ((ok: Boolean) -> Unit)? = null) {
        scope.launch {
            val rid = JuneQueueStore.client?.sendOp(roomId, op, itemId, photo)
            if (rid == null) {
                Toast.makeText(context, "요청을 보내지 못했습니다", Toast.LENGTH_SHORT).show()
                onDone?.invoke(false)
            } else {
                pending[rid] = JunePendingOp(itemId, op, photo, System.currentTimeMillis(), onDone)
                for (wait in longArrayOf(800L, 2_000L)) {
                    delay(wait)
                    JuneQueueStore.refresh(roomId)
                }
            }
        }
    }

    fun onResults(results: List<JuneQueueResult>) {
        val nowMs = System.currentTimeMillis()
        pending.entries.toList().forEach { (rid, info) ->
            val res = results.firstOrNull { it.rid == rid }
            if (res != null) {
                pending.remove(rid)
                if (!res.ok) Toast.makeText(context, juneQueueErrorText(res.op, res.err), Toast.LENGTH_SHORT).show()
                info.onDone?.invoke(res.ok)
            } else if (nowMs - info.sentAt > OP_TIMEOUT_MILLIS) {
                pending.remove(rid)
                info.onDone?.invoke(false)
            }
        }
    }

    val size: Int get() = pending.size
}

@Composable
internal fun rememberJuneQueueOps(roomId: String): JuneQueueOps {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ops = remember(roomId) { JuneQueueOps(roomId, scope, context) }
    // Report the results of my requests (only failures need words; success is visible by itself).
    val results = JuneQueueStore.all.collectAsState().value[roomId]?.results.orEmpty()
    LaunchedEffect(results, ops.size) { ops.onResults(results) }
    return ops
}

internal fun juneQueueErrorText(op: String, err: String?): String = when (err) {
    "not_queued" -> "이미 실행되어 처리하지 않았습니다"
    "not_text" -> "사진 외 파일·영상 메시지는 스티어링할 수 없습니다"
    "media_missing" -> "사진 파일을 찾지 못해 대기열에 그대로 둡니다"
    "starting" -> "봇이 막 시작하는 중이라 대기열에 그대로 둡니다"
    "idle" -> "지금 실행 중인 작업이 없어 대기열에 그대로 둡니다"
    "steer_failed" -> "스티어링하지 못해 대기열에 그대로 둡니다"
    "empty" -> "글 없이 사진 한 장만 남아 뺄 수 없습니다. 메시지 취소를 쓰세요"
    "no_photo" -> "이미 빠진 사진입니다"
    else -> when (op) {
        "steer" -> "스티어링하지 못했습니다"
        "detach_photo" -> "사진을 빼지 못했습니다"
        else -> "취소하지 못했습니다"
    }
}

/**
 * Element June: while a queued message with pictures is edited from the queue panel, its pictures sit right above the
 * composer as small chips; the x takes one out of the queued message (the picture shows in the timeline again).
 * [showAddTile] adds a + tile (caption editing has no + button in the composer) that calls [onAddPhoto].
 * It is part of the composer area, so it keeps its room above the keyboard (see JuneComposerStack).
 */
@Composable
internal fun JuneEditPhotosRow(
    roomId: String,
    editingEventId: String?,
    showAddTile: Boolean,
    onAddPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ops = rememberJuneQueueOps(roomId)
    val queues by JuneQueueStore.all.collectAsState()
    val held by JuneQueueStore.held.collectAsState()
    val item = editingEventId?.let { id -> queues[roomId]?.liveItems(System.currentTimeMillis())?.firstOrNull { it.id == id } } ?: return
    // Only edits started from the queue panel: those are the ones whose picked pictures join the queued message
    if (!JuneQueueStore.isPanelEdit(roomId, item.id)) return
    if (item.photos.isEmpty() && !showAddTile) return
    val heldHere = held[roomId].orEmpty()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item.photos.forEach { photo ->
            val busy = ops.isBusy(item.id, photo.name)
            val content = heldHere[photo.id]?.content as? TimelineItemImageContent
            Box(modifier = Modifier.size(CHIP_SIZE).alpha(if (busy) 0.4f else 1f)) {
                if (content != null) {
                    AsyncImage(
                        model = content.thumbnailMediaRequestData,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(CHIP_SIZE)
                            .clip(shape)
                            .border(1.dp, ElementTheme.colors.borderDisabled, shape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(CHIP_SIZE)
                            .clip(shape)
                            .background(ElementTheme.colors.bgSubtleSecondary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(imageVector = CompoundIcons.Image(), contentDescription = null, tint = ElementTheme.colors.iconSecondary)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(enabled = !busy) { ops.send("detach_photo", item.id, photo.name) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CompoundIcons.Close(),
                        contentDescription = "대기 메시지에서 사진 빼기",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (showAddTile) {
            Box(
                modifier = Modifier
                    .size(CHIP_SIZE)
                    .clip(shape)
                    .border(1.dp, ElementTheme.colors.borderDisabled, shape)
                    .clickable(onClick = onAddPhoto),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = CompoundIcons.Plus(), contentDescription = "대기 메시지에 사진 추가", tint = ElementTheme.colors.iconSecondary)
            }
        }
    }
}
