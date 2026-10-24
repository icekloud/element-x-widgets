/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.forward.impl

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_ROOM_ID_3
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.LiveTimelineProvider
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ForwardMessagesPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createForwardMessagesPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.forwardAction.isUninitialized()).isTrue()
        }
    }

    @Test
    fun `present - forward successful`() = runTest {
        val forwardLambda = lambdaRecorder { _: EventId, _: List<RoomId>, _: String ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline().apply {
            this.forwardEventWithHeaderLambda = forwardLambda
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val presenter = createForwardMessagesPresenter(fakeRoom = room)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            val summary = aRoomSummary()
            // Element June: picking the rooms opens the comment dialog, the forward starts once it is confirmed
            presenter.onRoomSelected(listOf(summary.roomId))
            assertThat(awaitItem().pendingRoomIds).containsExactly(summary.roomId)
            initialState.eventSink(ForwardMessagesEvent.ConfirmForward(""))
            val forwardingState = awaitForwardStarted()
            assertThat(forwardingState.forwardAction.isLoading()).isTrue()
            val successfulForwardState = awaitItem()
            assertThat(successfulForwardState.forwardAction).isEqualTo(AsyncAction.Success(listOf(summary.roomId)))
            forwardLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - select a room and forward failed, then clear`() = runTest {
        val forwardLambda = lambdaRecorder { _: EventId, _: List<RoomId>, _: String ->
            Result.failure<Unit>(IllegalStateException("error"))
        }
        val timeline = FakeTimeline().apply {
            this.forwardEventWithHeaderLambda = forwardLambda
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val presenter = createForwardMessagesPresenter(fakeRoom = room)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            val summary = aRoomSummary()
            presenter.onRoomSelected(listOf(summary.roomId))
            skipItems(1)
            initialState.eventSink(ForwardMessagesEvent.ConfirmForward(""))
            assertThat(awaitForwardStarted().forwardAction.isLoading()).isTrue()
            val failedForwardState = awaitItem()
            assertThat(failedForwardState.forwardAction.isFailure()).isTrue()
            // Then clear error
            failedForwardState.eventSink(ForwardMessagesEvent.ClearError)
            assertThat(awaitItem().forwardAction.isUninitialized()).isTrue()
            forwardLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - June forward to three rooms - one forward call with the header, no separate header message`() = runTest {
        val forwardLambda = lambdaRecorder { _: EventId, _: List<RoomId>, _: String ->
            Result.success(Unit)
        }
        // sendMessageLambda keeps its default, which fails the test when a header message is sent on its own
        val timeline = FakeTimeline().apply {
            this.forwardEventWithHeaderLambda = forwardLambda
        }
        val presenter = createForwardMessagesPresenter(fakeRoom = FakeJoinedRoom(liveTimeline = timeline), sourceName = "원래 방")
        val rooms = listOf(A_ROOM_ID, A_ROOM_ID_2, A_ROOM_ID_3)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            presenter.onRoomSelected(rooms)
            skipItems(1)
            initialState.eventSink(ForwardMessagesEvent.ConfirmForward("  이것 좀 봐  "))
            assertThat(awaitForwardStarted().forwardAction.isLoading()).isTrue()
            assertThat(awaitItem().forwardAction).isEqualTo(AsyncAction.Success(rooms))
        }
        forwardLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID), value(rooms), value("[전달: 원래 방, 맥락:이것 좀 봐]"))
    }

    @Test
    fun `present - June forward without a comment - the header is the room alone`() = runTest {
        val forwardLambda = lambdaRecorder { _: EventId, _: List<RoomId>, _: String ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline().apply {
            this.forwardEventWithHeaderLambda = forwardLambda
        }
        val presenter = createForwardMessagesPresenter(fakeRoom = FakeJoinedRoom(liveTimeline = timeline), sourceName = "원래 방")
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            skipItems(1)
            initialState.eventSink(ForwardMessagesEvent.ConfirmForward(""))
            assertThat(awaitForwardStarted().forwardAction.isLoading()).isTrue()
            assertThat(awaitItem().forwardAction.isSuccess()).isTrue()
        }
        forwardLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID), value(listOf(A_ROOM_ID)), value("[전달: 원래 방]"))
    }

    @Test
    fun `June header - with and without a comment`() {
        assertThat(juneForwardHeader("원래 방", "")).isEqualTo("[전달: 원래 방]")
        assertThat(juneForwardHeader("원래 방", "   ")).isEqualTo("[전달: 원래 방]")
        assertThat(juneForwardHeader("원래 방", " 맥락 ")).isEqualTo("[전달: 원래 방, 맥락:맥락]")
    }

    @Test
    fun `June header - no room name`() {
        assertThat(juneForwardHeader(null, "")).isEqualTo("[전달: 알 수 없는 방]")
        assertThat(juneForwardHeader("  ", "a")).isEqualTo("[전달: 알 수 없는 방, 맥락:a]")
    }

    @Test
    fun `June header - always one line, brackets and long comments kept`() {
        val header = juneForwardHeader("방\n이름", "첫 줄\n\n둘째 줄\r\n셋째 ] [줄]")
        assertThat(header).isEqualTo("[전달: 방 이름, 맥락:첫 줄 둘째 줄 셋째 ] [줄]]")
        assertThat(header.lines()).hasSize(1)
        val long = "긴 맥락 ".repeat(500).trim()
        val longHeader = juneForwardHeader("방", long)
        assertThat(longHeader).isEqualTo("[전달: 방, 맥락:$long]")
        assertThat(longHeader.lines()).hasSize(1)
    }
}

/** Element June: the dialog closing and the forward starting may come as one state or two: waits for the forward. */
private suspend fun ReceiveTurbine<ForwardMessagesState>.awaitForwardStarted(): ForwardMessagesState {
    while (true) {
        val state = awaitItem()
        if (!state.forwardAction.isUninitialized()) return state
    }
}

fun TestScope.createForwardMessagesPresenter(
    eventId: EventId = AN_EVENT_ID,
    fakeRoom: FakeJoinedRoom = FakeJoinedRoom(),
    sourceName: String? = null,
) = ForwardMessagesPresenter(
    eventId = eventId.value,
    timelineProvider = LiveTimelineProvider(fakeRoom),
    sourceName = sourceName,
    sessionCoroutineScope = this,
)
