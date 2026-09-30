/*
 * Copyright (c) 2026 Element June.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import android.widget.Toast
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.features.messages.impl.timeline.components.juneBatchPosition
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStateContent
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemRoomBeginningModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * Element June: the ▲/▼ buttons step through the user's own messages. Each step puts the top edge of the
 * message right under the top bar (and the banners overlaid on the timeline).
 *
 * - [onPrevious]: the closest own message above that line; older history is loaded when none is loaded yet.
 * - [onNext]: the closest own message below that line; when there is none, the given fallback (jump to bottom).
 */
@Stable
internal class JuneOwnMessageJump(
    private val hasPreviousState: () -> Boolean,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
) {
    val hasPrevious: Boolean get() = hasPreviousState()
}

private const val MAX_OLDER_PAGES = 20

@Composable
internal fun rememberJuneOwnMessageJump(
    lazyListState: LazyListState,
    timelineItems: List<TimelineItem>,
    topInset: Dp,
    onLoadOlder: () -> Unit,
    onNoNextMessage: () -> Unit,
): JuneOwnMessageJump {
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestItems by rememberUpdatedState(timelineItems)
    val topPx by rememberUpdatedState(with(density) { topInset.roundToPx() })
    val slackPx = remember(density) { with(density) { 8.dp.roundToPx() } }
    val latestLoadOlder by rememberUpdatedState(onLoadOlder)
    val latestNoNext by rememberUpdatedState(onNoNextMessage)

    val ownIndices by remember { derivedStateOf { latestItems.juneOwnMessageIndices() } }
    val mayHaveOlder by remember {
        derivedStateOf { latestItems.isNotEmpty() && latestItems.none { (it as? TimelineItem.Virtual)?.model is TimelineItemRoomBeginningModel } }
    }
    val hasPrevious by remember {
        derivedStateOf { mayHaveOlder || lazyListState.layoutInfo.juneOwnAbove(ownIndices, topPx, slackPx).any() }
    }
    // Presses run one after the other, so that pressing twice quickly moves two messages
    val mutex = remember { Mutex() }

    return remember(lazyListState) {
        JuneOwnMessageJump(
            hasPreviousState = { hasPrevious },
            onPrevious = {
                scope.launch {
                    mutex.withLock {
                        var pages = 0
                        while (true) {
                            for (index in lazyListState.layoutInfo.juneOwnAbove(ownIndices, topPx, slackPx).toList()) {
                                if (lazyListState.juneAlignTop(index, topPx)) return@withLock
                            }
                            if (!mayHaveOlder || pages >= MAX_OLDER_PAGES) break
                            pages++
                            // Wait for real events: the loading indicator alone also changes the list
                            val before = latestItems.juneEventCount()
                            latestLoadOlder()
                            withTimeoutOrNull(4.seconds) { snapshotFlow { latestItems.juneEventCount() }.first { it != before } } ?: break
                        }
                        Toast.makeText(context, "이전 내 메시지가 없습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onNext = {
                scope.launch {
                    mutex.withLock {
                        for (index in lazyListState.layoutInfo.juneOwnBelow(ownIndices, topPx, slackPx).toList()) {
                            if (lazyListState.juneAlignTop(index, topPx)) return@withLock
                        }
                        latestNoNext()
                    }
                }
            },
        )
    }
}

private fun List<TimelineItem>.juneEventCount(): Int = count { it !is TimelineItem.Virtual }

/**
 * Indices (0 = newest, bottom of the reversed list) of the user's own messages. A batch of pictures counts as one
 * message: only its first picture, the top of the block, is a target.
 */
internal fun List<TimelineItem>.juneOwnMessageIndices(): IntArray {
    val result = ArrayList<Int>()
    forEachIndexed { index, item ->
        if (item is TimelineItem.Event &&
            item.isMine &&
            item.content !is TimelineItemStateContent &&
            item.content !is TimelineItemRedactedContent &&
            item.juneBatchPosition()?.let { it.first > 1 } != true
        ) {
            result.add(index)
        }
    }
    return result.toIntArray()
}

/**
 * Distance in px from the top of the list to the top edge of a visible item. In a reversed list the offsets are
 * measured upwards from the bottom content padding, and [LazyListLayoutInfo.viewportEndOffset] is the top edge.
 */
private fun LazyListLayoutInfo.juneTopOf(index: Int): Int? =
    visibleItemsInfo.firstOrNull { it.index == index }?.let { viewportEndOffset - (it.offset + it.size) }

/** Own messages whose top edge is above the line, closest first. */
private fun LazyListLayoutInfo.juneOwnAbove(indices: IntArray, topPx: Int, slackPx: Int): Sequence<Int> {
    val lastVisible = visibleItemsInfo.lastOrNull()?.index ?: return emptySequence()
    return indices.asSequence().filter { index ->
        index > lastVisible || juneTopOf(index)?.let { it < topPx - slackPx } == true
    }
}

/** Own messages whose top edge is below the line, closest first. */
private fun LazyListLayoutInfo.juneOwnBelow(indices: IntArray, topPx: Int, slackPx: Int): Sequence<Int> {
    val firstVisible = visibleItemsInfo.firstOrNull()?.index ?: return emptySequence()
    return indices.reversed().asSequence().filter { index ->
        index < firstVisible || juneTopOf(index)?.let { it > topPx + slackPx } == true
    }
}

/**
 * Scrolls so that the top edge of item [index] sits [topPx] below the top of the list.
 * Returns false when the list could not move at all (already at an end), so the caller can try the next message.
 */
private suspend fun LazyListState.juneAlignTop(index: Int, topPx: Int): Boolean {
    // Wait for the first layout
    scroll { }
    var jumped = false
    if (layoutInfo.juneTopOf(index) == null) {
        // Not on screen: jump to it first so its size is known (scrollToItem remeasures synchronously)
        scrollToItem(index)
        jumped = true
    }
    val top = layoutInfo.juneTopOf(index) ?: return jumped
    // In a reversed list a positive delta scrolls towards older items, which moves the content down
    val consumed = animateScrollBy((topPx - top).toFloat())
    return jumped || abs(consumed) >= 1f
}
