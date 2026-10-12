/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent

private fun TimelineItem.events(): List<TimelineItem.Event> = when (this) {
    is TimelineItem.Event -> listOf(this)
    is TimelineItem.GroupedEvents -> events
    else -> emptyList()
}

/**
 * The photo events of the loaded room timeline, by mxc url: a note attachment already uploaded opens the media viewer
 * of the room. v2 (설계 §g): the record tab no longer reads the timeline, this is all that is left of it.
 */
internal fun memoPhotosByMxc(items: List<TimelineItem>): Map<String, TimelineItem.Event> {
    val photos = mutableMapOf<String, TimelineItem.Event>()
    for (event in items.flatMap { it.events() }) {
        val content = event.content
        if (content is TimelineItemImageContent) photos[content.mediaSource.safeUrl] = event
    }
    return photos
}
