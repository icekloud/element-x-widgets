/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemUnknownContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContent
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import org.json.JSONObject

private val URL_ONLY = Regex("^\\s*https?://\\S+\\s*$")

/** The record tab view of the room timeline (newest first, like the timeline). */
internal data class MemoTimeline(
    val entries: List<MemoEntry>,
    /** Thumbnails of the photo inputs, by entry key. */
    val thumbnails: Map<String, MediaRequestData>,
    /** Answer events found in the timeline (the memo screen hands them to the backend). */
    val answers: List<MemoResponse>,
    /** The timeline events of the entries, by entry key (a photo tap opens the media viewer of the room). */
    val events: Map<String, TimelineItem.Event> = emptyMap(),
)

private fun TimelineItem.events(): List<TimelineItem.Event> = when (this) {
    is TimelineItem.Event -> listOf(this)
    is TimelineItem.GroupedEvents -> events
    else -> emptyList()
}

/** Reduces the timeline items to the record tab entries, oldest first. Memo answer and op events are hidden. */
internal fun memoTimeline(items: List<TimelineItem>): MemoTimeline {
    val entries = mutableListOf<MemoEntry>()
    val thumbs = mutableMapOf<String, MediaRequestData>()
    val answers = mutableListOf<MemoResponse>()
    val events = mutableMapOf<String, TimelineItem.Event>()
    for (event in items.asReversed().flatMap { it.events() }) {
        val key = event.id.value
        val content = event.content
        if (content is TimelineItemUnknownContent) {
            // app.june.memo.* answers and our own app.june.memo_op requests
            val json = runCatchingExceptions { event.timelineItemDebugInfoProvider().originalJson }.getOrNull()
            parseMemoResponseEvent(json)?.let { answers += it }
        } else {
            memoEntryContent(content)?.let { (kind, text) ->
                if (content is TimelineItemImageContent) thumbs[key] = content.thumbnailMediaRequestData
                events[key] = event
                entries += MemoEntry(
                    key = key,
                    eventId = event.eventId?.value,
                    isMine = event.isMine,
                    timeMillis = event.sentTimeMillis,
                    kind = kind,
                    text = text,
                    replyToEventId = event.inReplyTo?.eventId()?.value,
                    noteId = if (event.isMine) null else memoNoteIdOf(event),
                )
            }
        }
    }
    return MemoTimeline(entries, thumbs, answers, events)
}

/** Kind and text of a record tab entry, null for the contents the record tab does not show. */
private fun memoEntryContent(content: TimelineItemEventContent): Pair<MemoEntryKind, String>? = when (content) {
    is TimelineItemImageContent -> MemoEntryKind.Photo to content.caption.orEmpty()
    is TimelineItemVoiceContent -> MemoEntryKind.Voice to (content.caption ?: "음성")
    is TimelineItemFileContent -> MemoEntryKind.File to content.filename
    is TimelineItemTextBasedContent -> (if (URL_ONLY.matches(content.body)) MemoEntryKind.Link else MemoEntryKind.Text) to content.body
    else -> null
}

/** `content["app.june.memo"].id` of a bot answer (이벤트규약-변경 1), when the server adds it. */
private fun memoNoteIdOf(event: TimelineItem.Event): String? {
    val json = runCatchingExceptions { event.timelineItemDebugInfoProvider().originalJson }.getOrNull() ?: return null
    return runCatchingExceptions {
        JSONObject(json).optJSONObject("content")?.optJSONObject("app.june.memo")?.optString("id")?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

private fun io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails.eventId() =
    when (this) {
        is io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails.Ready -> eventId
        is io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails.Loading -> eventId
        is io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails.Error -> eventId
    }
