/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import io.element.android.libraries.core.coroutine.parallelMap
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.ForwardEventException
import io.element.android.libraries.matrix.impl.roomlist.roomOrNull
import io.element.android.libraries.matrix.impl.timeline.runWithTimelineListenerRegistered
import kotlinx.coroutines.withTimeout
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.contentWithoutRelationFromMessage
import kotlin.time.Duration.Companion.milliseconds

/**
 * Helper to forward event contents from a room to a set of other rooms.
 * @param roomListService the [RoomListService] to fetch room instances to forward the event to
 * @param toEventContent turns the forwarded message into the event content to send (replaced in tests)
 */
class RoomContentForwarder(
    private val roomListService: RoomListService,
    private val toEventContent: (MessageContent) -> RoomMessageEventContentWithoutRelation = ::contentWithoutRelationFromMessage,
) {
    /**
     * Forwards the event with the given [eventId] from the [fromTimeline] to the given [toRoomIds].
     * @param fromTimeline the room to forward the event from
     * @param eventId the id of the event to forward
     * @param toRoomIds the ids of the rooms to forward the event to
     * @param timeoutMs the maximum time in milliseconds to wait for the event to be sent to a room
     * @param header Element June: the "[전달: …]" line put in front of the forwarded message (see [juneForwardContent])
     */
    suspend fun forward(
        fromTimeline: Timeline,
        eventId: EventId,
        toRoomIds: List<RoomId>,
        timeoutMs: Long = 5000L,
        header: String? = null,
    ) {
        val messageLikeContent = (fromTimeline.getEventTimelineItemByEventId(eventId.value).content as? TimelineItemContent.MsgLike)?.content
            ?: throw ForwardEventException(toRoomIds)

        val content = (messageLikeContent.kind as? MsgLikeKind.Message)?.content
            ?: throw ForwardEventException(toRoomIds)

        // Element June: one event with the header inside, or (types without room for it) the header just before
        val contents = when (header) {
            null -> listOf(content)
            else -> juneForwardContent(content, header)?.let { listOf(it) } ?: listOf(juneForwardHeaderContent(header), content)
        }

        val targetRooms = toRoomIds.toSet().mapNotNull { roomId -> roomListService.roomOrNull(roomId.value) }
        val failedForwardingTo = mutableSetOf<RoomId>()
        targetRooms.parallelMap { room ->
            room.use { targetRoom ->
                runCatchingExceptions {
                    // Sending a message requires a registered timeline listener
                    val timeline = targetRoom.timeline()
                    timeline.runWithTimelineListenerRegistered {
                        withTimeout(timeoutMs.milliseconds) {
                            // Element June: one after the other in the room's send queue, the header first
                            contents.forEach { timeline.send(toEventContent(it)) }
                        }
                    }
                }
            }.onFailure {
                failedForwardingTo.add(RoomId(room.id()))
            }
        }

        if (failedForwardingTo.isNotEmpty()) {
            throw ForwardEventException(failedForwardingTo.toList())
        }
    }
}
