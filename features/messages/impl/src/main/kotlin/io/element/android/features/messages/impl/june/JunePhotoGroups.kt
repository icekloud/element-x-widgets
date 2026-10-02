/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import io.element.android.features.messages.impl.timeline.components.event.juneBatchId
import io.element.android.features.messages.impl.timeline.model.JunePhotoGroup
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemThreadInfo
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.abs

/** Two pictures further apart than this are drawn in separate bubbles, unless they were sent together. */
internal const val JUNE_PHOTO_GROUP_WINDOW_MS = 2 * 60 * 1000L

/**
 * Element June: draw runs of pictures from one sender as one message bubble. [items] is newest first, as shown by the
 * timeline. Only the drawing is shared: every picture stays its own event (reply, reactions, long press, delete and
 * opening it work per picture). The pictures of a run get [TimelineItem.Event.junePhotoGroup], and the newest one,
 * at the bottom of the bubble, also carries the read receipts of the whole run since only it shows them.
 * Returns [items] itself when nothing changes.
 */
internal fun juneGroupPhotos(items: ImmutableList<TimelineItem>): ImmutableList<TimelineItem> {
    var result: MutableList<TimelineItem>? = null
    fun replace(index: Int, item: TimelineItem) {
        val list = result ?: items.toMutableList().also { result = it }
        list[index] = item
    }

    var newest = 0
    while (newest < items.size) {
        var oldest = newest
        while (oldest + 1 < items.size && juneCanJoinPhotos(older = items[oldest + 1], newer = items[oldest])) {
            oldest++
        }
        if (oldest == newest) {
            val item = items[newest]
            if (item is TimelineItem.Event && item.junePhotoGroup != null) replace(newest, item.copy(junePhotoGroup = null))
        } else {
            val run = (newest..oldest).map { items[it] as TimelineItem.Event }
            val size = run.size
            // Only the newest picture shows read receipts: give it those of the whole run, newest first, once per user
            val receipts = run.flatMap { it.readReceiptState.receipts }.distinctBy { it.avatarData.id }.toImmutableList()
            run.forEachIndexed { offset, event ->
                val group = JunePhotoGroup(index = size - 1 - offset, size = size)
                val newReceipts = offset == 0 && receipts != event.readReceiptState.receipts
                if (newReceipts || event.junePhotoGroup != group) {
                    replace(
                        newest + offset,
                        event.copy(
                            junePhotoGroup = group,
                            readReceiptState = if (newReceipts) event.readReceiptState.copy(receipts = receipts) else event.readReceiptState,
                        ),
                    )
                }
            }
        }
        newest = oldest + 1
    }
    return result?.toImmutableList() ?: items
}

/**
 * Element June: true when the picture [newer] can be drawn in the same bubble right below the picture [older], the item
 * just before it. A picture with a caption ends its bubble (the text goes under it, once), and so does a picture with
 * reactions or replies in a thread, since those are drawn right below it. A reply starts a new bubble, its quote is
 * drawn at the top.
 */
internal fun juneCanJoinPhotos(older: TimelineItem, newer: TimelineItem): Boolean {
    if (older !is TimelineItem.Event || newer !is TimelineItem.Event) return false
    val olderImage = older.content as? TimelineItemImageContent ?: return false
    val newerImage = newer.content as? TimelineItemImageContent ?: return false
    if (older.senderId != newer.senderId) return false
    if (olderImage.showCaption || older.reactionsState.reactions.isNotEmpty()) return false
    if (newer.inReplyTo != null) return false
    if (older.threadInfo is TimelineItemThreadInfo.ThreadRoot) return false
    if (older.threadInfo.threadRootIdOrNull() != newer.threadInfo.threadRootIdOrNull()) return false
    // The red time of a picture that failed to send must stay visible
    if (older.failedToSend || newer.failedToSend) return false
    if (abs(newer.sentTimeMillis - older.sentTimeMillis) <= JUNE_PHOTO_GROUP_WINDOW_MS) return true
    // Pictures sent together by the app (june-<id>-<i>of<n>) stay together, however long the upload took
    return olderImage.juneBatchId()?.let { it == newerImage.juneBatchId() } == true
}

private fun TimelineItemThreadInfo?.threadRootIdOrNull() = (this as? TimelineItemThreadInfo.ThreadResponse)?.threadRootId
