/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.forward.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.timeline.TimelineProvider
import io.element.android.libraries.matrix.api.timeline.getActiveTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

@AssistedInject
class ForwardMessagesPresenter(
    @Assisted eventId: String,
    @Assisted private val timelineProvider: TimelineProvider,
    @Assisted private val sourceName: String?,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
) : Presenter<ForwardMessagesState> {
    private val eventId: EventId = EventId(eventId)

    @AssistedFactory
    fun interface Factory {
        fun create(eventId: String, timelineProvider: TimelineProvider, sourceName: String?): ForwardMessagesPresenter
    }

    private val forwardingActionState: MutableState<AsyncAction<List<RoomId>>> = mutableStateOf(AsyncAction.Uninitialized)

    // Element June: rooms picked in the room selector, waiting for the optional context comment
    private val pendingRoomIds: MutableState<List<RoomId>?> = mutableStateOf(null)

    fun onRoomSelected(roomIds: List<RoomId>) {
        pendingRoomIds.value = roomIds
    }

    @Composable
    override fun present(): ForwardMessagesState {
        fun handleEvent(event: ForwardMessagesEvent) {
            when (event) {
                ForwardMessagesEvent.ClearError -> forwardingActionState.value = AsyncAction.Uninitialized
                is ForwardMessagesEvent.ConfirmForward -> {
                    val roomIds = pendingRoomIds.value.orEmpty()
                    pendingRoomIds.value = null
                    if (roomIds.isNotEmpty()) sessionCoroutineScope.forwardEvent(eventId, roomIds, event.comment)
                }
                ForwardMessagesEvent.CancelForward -> {
                    // Cancelling the comment dialog stops the forward and closes the screen
                    pendingRoomIds.value = null
                    forwardingActionState.value = AsyncAction.Success(emptyList())
                }
            }
        }

        return ForwardMessagesState(
            forwardAction = forwardingActionState.value,
            pendingRoomIds = pendingRoomIds.value,
            sourceName = sourceName,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.forwardEvent(
        eventId: EventId,
        roomIds: List<RoomId>,
        comment: String,
    ) = launch {
        suspend {
            // Element June: the header goes inside the forwarded message (one event per room) so the receiving bot
            // knows where it comes from. Types without room for it get the header just before (see forwardEventWithHeader).
            val header = juneForwardHeader(sourceName, comment)
            timelineProvider.getActiveTimeline().forwardEventWithHeader(eventId, roomIds, header)
                .onFailure {
                    Timber.e(it, "Error while forwarding event")
                }
                .getOrThrow()
            roomIds
        }.runCatchingUpdatingState(forwardingActionState)
    }
}

/**
 * Element June: "[전달: <room>, 맥락:<comment>]", or "[전달: <room>]" without a comment. Always one line: the receiving bot
 * reads the header as the first line of the message, so line breaks in the comment or the room name become spaces.
 */
internal fun juneForwardHeader(sourceName: String?, comment: String): String {
    val source = sourceName?.let(::juneOneLine)?.takeIf { it.isNotEmpty() } ?: "알 수 없는 방"
    val text = juneOneLine(comment)
    return if (text.isEmpty()) "[전달: $source]" else "[전달: $source, 맥락:$text]"
}

private val JUNE_LINE_BREAKS = Regex("\\s*[\\r\\n\\u0085\\u2028\\u2029]+\\s*")

private fun juneOneLine(text: String): String = text.trim().replace(JUNE_LINE_BREAKS, " ")
