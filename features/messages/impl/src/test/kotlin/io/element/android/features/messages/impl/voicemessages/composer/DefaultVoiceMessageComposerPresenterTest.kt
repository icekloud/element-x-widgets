/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.voicemessages.composer

import android.Manifest
import androidx.lifecycle.Lifecycle
import app.cash.turbine.TurbineTestContext
import com.google.common.truth.Truth.assertThat
import im.vector.app.features.analytics.plan.Composer
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.features.messages.impl.messagecomposer.aReplyMode
import io.element.android.features.messages.test.FakeMessageComposerContext
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioFocusRequester
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaplayer.test.FakeAudioFocus
import io.element.android.libraries.mediaplayer.test.FakeMediaPlayer
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.impl.DefaultMediaSender
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.libraries.permissions.api.aPermissionsState
import io.element.android.libraries.permissions.test.FakePermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenterFactory
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessagePlayerEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.voiceplayer.api.VoiceMessageException
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.test.FakeVoiceRecorder
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.any
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.test
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DefaultVoiceMessageComposerPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    // Element June: order of the recorder and sending calls, to check that a recording is stopped before it is sent
    private val callOrder = mutableListOf<String>()
    private val startRecordResult = lambdaRecorder<Unit> { }
    private val stopRecordResult = lambdaRecorder<Boolean, Unit> { cancelled -> callOrder += "stop(cancelled=$cancelled)" }
    private val deleteRecordingResult = lambdaRecorder<Unit> { callOrder += "delete" }
    private val voiceRecorder = FakeVoiceRecorder(
        recordingDuration = RECORDING_DURATION,
        startRecordResult = startRecordResult,
        stopRecordResult = stopRecordResult,
        deleteRecordingResult = deleteRecordingResult,
    )
    private val analyticsService = FakeAnalyticsService()
    private val sendVoiceMessageResult =
        lambdaRecorder<File, AudioInfo, List<Float>, EventId?, Result<FakeMediaUploadHandler>> { _, _, _, _ ->
            callOrder += "send"
            Result.success(FakeMediaUploadHandler())
        }
    private val joinedRoom = FakeJoinedRoom(
        liveTimeline = FakeTimeline().apply {
            sendVoiceMessageLambda = sendVoiceMessageResult
        },
    )
    private val mediaPreProcessor = FakeMediaPreProcessor().apply { givenAudioResult() }
    private val mediaSender = DefaultMediaSender(
        preProcessor = mediaPreProcessor,
        room = joinedRoom,
        timelineMode = Timeline.Mode.Live,
        mediaOptimizationConfigProvider = { MediaOptimizationConfig(compressImages = true, videoCompressionPreset = VideoCompressionPreset.STANDARD) },
    )
    private val requestAudioFocusResult = lambdaRecorder<AudioFocusRequester, () -> Unit, Unit> { _, _ -> }
    private val releaseAudioFocusResult = lambdaRecorder<Unit> { }
    private val audioFocus: AudioFocus = FakeAudioFocus(
        requestAudioFocusResult = requestAudioFocusResult,
        releaseAudioFocusResult = releaseAudioFocusResult,
    )
    private val messageComposerContext = FakeMessageComposerContext()

    companion object {
        private val RECORDING_DURATION = 1.seconds
        private val PLAYER_DURATION = 10.seconds
        private val FIRST_LEVEL_DURATION = 500.milliseconds
        private val RECORDING_STATE = VoiceMessageState.Recording(RECORDING_DURATION, listOf(0.1f, 0.2f).toImmutableList())
        private val FIRST_RECORDING_STATE = VoiceMessageState.Recording(FIRST_LEVEL_DURATION, listOf(0.1f).toImmutableList())
    }

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            startRecordResult.assertions().isNeverCalled()

            testPauseAndDestroy(initialState)
        }
    }

    @Test
    fun `present - recording state`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))

            assertThat(awaitItem().voiceMessageState).isEqualTo(FIRST_RECORDING_STATE)
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(RECORDING_STATE)
            startRecordResult.assertions().isCalledOnce()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - recording state - number of levels is limited`() = runTest {
        val numberOfLevels = 200
        val levels = List(numberOfLevels) { it / numberOfLevels.toFloat() }
        val voiceRecorder = FakeVoiceRecorder(
            levels = levels,
            recordingDuration = RECORDING_DURATION,
            startRecordResult = { },
            stopRecordResult = { },
            deleteRecordingResult = { },
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = voiceRecorder,
        )
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))

            // Skip until we reach the final state, which should have the last 128 levels
            skipItems(numberOfLevels - 1)
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isInstanceOf(VoiceMessageState.Recording::class.java)
            val recordingState = finalState.voiceMessageState as VoiceMessageState.Recording
            // The number of levels should be limited to 128 items
            assertThat(recordingState.levels.size).isEqualTo(128)
            assertThat(recordingState.levels).isEqualTo(levels.takeLast(128))
            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - recording keeps screen on`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().apply {
                eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
                assertThat(keepScreenOn).isFalse()
            }

            awaitItem().apply {
                assertThat(keepScreenOn).isTrue()
                eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            }

            val finalState = awaitItem().apply {
                assertThat(keepScreenOn).isFalse()
            }

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - recording requests audio focus and releases on stop`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val recordingState = awaitItem()
            requestAudioFocusResult.assertions().isCalledOnce()
            releaseAudioFocusResult.assertions().isNeverCalled()

            recordingState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem()
            releaseAudioFocusResult.assertions().isCalledOnce()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - cancelling recording releases audio focus`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Cancel))
            awaitItem()
            requestAudioFocusResult.assertions().isCalledOnce()
            releaseAudioFocusResult.assertions().isCalledOnce()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - audio focus loss during recording finishes gracefully`() = runTest {
        var onFocusLost: (() -> Unit)? = null
        val testAudioFocus = FakeAudioFocus(
            requestAudioFocusResult = { _, callback -> onFocusLost = callback },
            releaseAudioFocusResult = { },
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(audioFocus = testAudioFocus)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem()

            // simulate focus loss (phone call, etc)
            onFocusLost?.invoke()
            advanceUntilIdle()

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(aPreviewState())
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - abort recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Cancel))
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(true))
            deleteRecordingResult.assertions().isCalledOnce()
            // Element June: the X of the recording never sends anything
            sendVoiceMessageResult.assertions().isNeverCalled()
            assertThat(analyticsService.capturedEvents).isEmpty()
            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send now stops the recording then sends it without the preview step`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            assertThat(awaitItem().voiceMessageState).isEqualTo(FIRST_RECORDING_STATE)
            awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(RECORDING_STATE)
                it.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
            }

            val seen = mutableListOf<VoiceMessageState>()
            val finalState = awaitUntil(seen) { it.voiceMessageState == VoiceMessageState.Idle }
            // The recording is never offered as a preview to play, delete or send: only the sending indicator may show
            assertThat(seen.filterIsInstance<VoiceMessageState.Preview>().all { it.isSending }).isTrue()
            assertThat(callOrder).containsExactly("stop(cancelled=false)", "send", "delete").inOrder()
            sendVoiceMessageResult.assertions().isCalledOnce()
                .with(any(), any(), value(voiceRecorder.waveform), value(null))
            startRecordResult.assertions().isCalledOnce()
            releaseAudioFocusResult.assertions().isCalledOnce()
            assertThat(analyticsService.capturedEvents).containsExactly(aVoiceMessageComposerEvent(isReply = false))
            assertThat(finalState.showSendFailureDialog).isFalse()

            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send now keeps the reply target although the composer leaves the reply mode`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            messageComposerContext.composerMode = aReplyMode()
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            skipItems(1)
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
            // The composer view closes the reply mode right after the event, as for the send button of the preview
            messageComposerContext.composerMode = MessageComposerMode.Normal

            awaitUntil { it.voiceMessageState == VoiceMessageState.Idle }
            sendVoiceMessageResult.assertions().isCalledOnce()
                .with(any(), any(), any(), value(AN_EVENT_ID))
            assertThat(analyticsService.capturedEvents).containsExactly(aVoiceMessageComposerEvent(isReply = true))

            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send now on a too short recording discards it without sending`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().also {
                // 0.5 second, under the 0.7 second limit
                assertThat(it.voiceMessageState).isEqualTo(FIRST_RECORDING_STATE)
                it.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
            }

            val seen = mutableListOf<VoiceMessageState>()
            val finalState = awaitUntil(seen) { it.voiceMessageState == VoiceMessageState.Idle }
            assertThat(seen.filterIsInstance<VoiceMessageState.Preview>()).isEmpty()
            assertThat(finalState.showSendFailureDialog).isFalse()
            sendVoiceMessageResult.assertions().isNeverCalled()
            stopRecordResult.assertions().isCalledOnce().with(value(true))
            deleteRecordingResult.assertions().isCalledOnce()
            releaseAudioFocusResult.assertions().isCalledOnce()
            assertThat(analyticsService.capturedEvents).isEmpty()

            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send now limit is 700 ms`() {
        assertThat(VoiceMessageRecorderEvent.Send.isLongEnough(699.milliseconds)).isFalse()
        assertThat(VoiceMessageRecorderEvent.Send.isLongEnough(700.milliseconds)).isTrue()
        assertThat(VoiceMessageRecorderEvent.Send.isLongEnough(0.milliseconds)).isFalse()
        assertThat(VoiceMessageRecorderEvent.Send.isLongEnough(30.seconds)).isTrue()
    }

    @Test
    fun `present - send now on a recording of exactly 700 ms sends it`() = runTest {
        val voiceRecorder = FakeVoiceRecorder(
            recordingDuration = 700.milliseconds,
            levels = listOf(0.3f),
            startRecordResult = { },
            stopRecordResult = { },
            deleteRecordingResult = { },
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(voiceRecorder = voiceRecorder)
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
            awaitUntil { it.voiceMessageState == VoiceMessageState.Idle }
            sendVoiceMessageResult.assertions().isCalledOnce()

            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send now pressed twice sends only once`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            skipItems(1)
            awaitItem().run {
                eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
                eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
            }
            awaitUntil { it.voiceMessageState == VoiceMessageState.Idle }
            advanceUntilIdle()

            sendVoiceMessageResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            assertThat(analyticsService.capturedEvents).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - the X pressed while a recording is being sent does not delete it`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            skipItems(1)
            awaitItem().run {
                eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
                eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Cancel))
            }
            awaitUntil { it.voiceMessageState == VoiceMessageState.Idle }
            advanceUntilIdle()

            assertThat(callOrder).containsExactly("stop(cancelled=false)", "send", "delete").inOrder()
            sendVoiceMessageResult.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send now when not recording does nothing`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
            advanceUntilIdle()

            assertThat(initialState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isNeverCalled()
            stopRecordResult.assertions().isNeverCalled()
            assertThat(analyticsService.capturedEvents).isEmpty()
            assertThat(analyticsService.trackedErrors).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - leaving the app while recording keeps the preview and sends nothing`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            skipItems(1)
            awaitItem().eventSink(VoiceMessageComposerEvent.LifecycleEvent(event = Lifecycle.Event.ON_PAUSE))

            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState())
            advanceUntilIdle()
            sendVoiceMessageResult.assertions().isNeverCalled()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isNeverCalled()
            assertThat(analyticsService.capturedEvents).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send now failure keeps the recording in the preview and the retry keeps the reply target`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            // Let sending fail due to media preprocessing error
            mediaPreProcessor.givenResult(Result.failure(Exception()))
            messageComposerContext.composerMode = aReplyMode()
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            skipItems(1)
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
            messageComposerContext.composerMode = MessageComposerMode.Normal

            val failedState = awaitUntil {
                it.showSendFailureDialog && (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == false
            }
            assertThat(failedState.voiceMessageState).isEqualTo(aPreviewState())
            sendVoiceMessageResult.assertions().isNeverCalled()
            deleteRecordingResult.assertions().isNeverCalled()

            failedState.eventSink(VoiceMessageComposerEvent.DismissSendFailureDialog)
            val previewState = awaitUntil { !it.showSendFailureDialog }.also {
                assertThat(it.voiceMessageState).isEqualTo(aPreviewState())
            }

            // Send again from the preview, the composer is not in reply mode anymore
            mediaPreProcessor.givenAudioResult()
            previewState.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            awaitUntil { it.voiceMessageState == VoiceMessageState.Idle }
            sendVoiceMessageResult.assertions().isCalledOnce()
                .with(any(), any(), any(), value(AN_EVENT_ID))
            deleteRecordingResult.assertions().isCalledOnce()

            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a failed voice message deleted from the preview does not give its reply target to the next one`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            mediaPreProcessor.givenResult(Result.failure(Exception()))
            messageComposerContext.composerMode = aReplyMode()
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            skipItems(1)
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
            messageComposerContext.composerMode = MessageComposerMode.Normal
            val failedState = awaitUntil {
                it.showSendFailureDialog && (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == false
            }
            failedState.eventSink(VoiceMessageComposerEvent.DismissSendFailureDialog)
            awaitUntil { !it.showSendFailureDialog }.eventSink(VoiceMessageComposerEvent.DeleteVoiceMessage)
            val idleState = awaitUntil { it.voiceMessageState == VoiceMessageState.Idle }

            // A new recording in normal mode is not a reply
            mediaPreProcessor.givenAudioResult()
            idleState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitUntil { it.voiceMessageState == RECORDING_STATE }
                .eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Send))
            awaitUntil { it.voiceMessageState == VoiceMessageState.Idle }
            sendVoiceMessageResult.assertions().isCalledOnce()
                .with(any(), any(), any(), value(null))

            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - finish recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(aPreviewState())
            assertThat((finalState.voiceMessageState as VoiceMessageState.Preview).duration).isEqualTo(RECORDING_DURATION)
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isNeverCalled()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - play recording before it is ready`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            val finalState = awaitItem().apply {
                this.eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            }

            // Nothing should happen
            assertThat(finalState.voiceMessageState).isEqualTo(FIRST_RECORDING_STATE)
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isNeverCalled()
            deleteRecordingResult.assertions().isNeverCalled()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - play recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            val finalState = awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(aPlayingState())
                assertThat((it.voiceMessageState as VoiceMessageState.Preview).duration).isEqualTo(PLAYER_DURATION)
            }
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isNeverCalled()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - pause recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Pause))
            val finalState = awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(aPausedState())
            }
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isNeverCalled()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - seek recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Seek(0.5f)))
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(
                    aPreviewState(playbackProgress = 0.5f, time = 0.seconds, showCursor = true)
                )
            }
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(
                    aPreviewState(playbackProgress = 0.5f, time = 0.seconds, showCursor = true, duration = PLAYER_DURATION)
                )
            }
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(
                    aPreviewState(playbackProgress = 0.5f, time = 5.seconds, showCursor = true, duration = PLAYER_DURATION)
                )
                eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Seek(0.2f)))
            }
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(
                    aPreviewState(playbackProgress = 0.2f, time = 5.seconds, showCursor = true, duration = PLAYER_DURATION)
                )
            }
            val finalState = awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(
                    aPreviewState(playbackProgress = 0.2f, time = 2.seconds, showCursor = true, duration = PLAYER_DURATION)
                )
            }

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - delete recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.DeleteVoiceMessage)

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isCalledOnce()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - delete while playing`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            awaitItem().eventSink(VoiceMessageComposerEvent.DeleteVoiceMessage)
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPausedState())
            }

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isCalledOnce()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send recording`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isCalledOnce()
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isCalledOnce()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - sending is tracked`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            // Send a normal voice message
            messageComposerContext.composerMode = MessageComposerMode.Normal
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            skipItems(1) // Sending state
            advanceUntilIdle()
            // Now reply with a voice message
            messageComposerContext.composerMode = aReplyMode()
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            val finalState = awaitItem() // Sending state

            assertThat(analyticsService.capturedEvents).containsExactly(
                aVoiceMessageComposerEvent(isReply = false),
                aVoiceMessageComposerEvent(isReply = true)
            )

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send voice message passes reply event ID only when in reply mode`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            // First send in Normal mode (default composerMode).
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())
            val idleAfterFirstSend = awaitItem()
            assertThat(idleAfterFirstSend.voiceMessageState).isEqualTo(VoiceMessageState.Idle)

            // Switching to reply mode does not trigger recomposition, so reuse the prior eventSink.
            messageComposerContext.composerMode = aReplyMode()
            idleAfterFirstSend.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)

            sendVoiceMessageResult.assertions().isCalledExactly(2)
                .withSequence(
                    listOf(any(), any(), any(), value(null)),
                    listOf(any(), any(), any(), value(AN_EVENT_ID)),
                )

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send while playing`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.PlayerEvent(VoiceMessagePlayerEvent.Play))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPlayingState().toSendingState())
            skipItems(1) // Duplicate sending state

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isCalledOnce()
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isCalledOnce()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send recording before previous completed, waits`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().run {
                eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
                eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            }
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isCalledOnce()
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isCalledOnce()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send failures aren't tracked`() = runTest {
        // Let sending fail due to media preprocessing error
        mediaPreProcessor.givenResult(Result.failure(Exception()))
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState())
                eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            }

            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(aPreviewState(isSending = true))
            sendVoiceMessageResult.assertions().isNeverCalled()
            assertThat(analyticsService.trackedErrors).isEmpty()
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isNeverCalled()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send failures can be retried`() = runTest {
        // Let sending fail due to media preprocessing error
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            mediaPreProcessor.givenResult(Result.failure(Exception()))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            val previewState = awaitItem()

            previewState.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())

            ensureAllEventsConsumed()
            assertThat(previewState.voiceMessageState).isEqualTo(aPreviewState())
            sendVoiceMessageResult.assertions().isNeverCalled()

            mediaPreProcessor.givenAudioResult()
            previewState.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            val finalState = awaitItem()
            assertThat(finalState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isCalledOnce()
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))
            deleteRecordingResult.assertions().isCalledOnce()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send failures are displayed as an error dialog`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            // Let sending fail due to media preprocessing error
            mediaPreProcessor.givenResult(Result.failure(Exception()))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            awaitItem().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)

            assertThat(awaitItem().voiceMessageState).isEqualTo(aPreviewState().toSendingState())

            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState().toSendingState())
                assertThat(showSendFailureDialog).isTrue()
            }

            awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState())
                assertThat(showSendFailureDialog).isTrue()
                eventSink(VoiceMessageComposerEvent.DismissSendFailureDialog)
            }

            val finalState = awaitItem().apply {
                assertThat(voiceMessageState).isEqualTo(aPreviewState())
                assertThat(showSendFailureDialog).isFalse()
            }

            sendVoiceMessageResult.assertions().isNeverCalled()
            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - send error - missing recording is tracked`() = runTest {
        val presenter = createDefaultVoiceMessageComposerPresenter()
        presenter.test {
            val initialState = awaitItem()
            // Send the message before recording anything
            initialState.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)

            assertThat(initialState.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            sendVoiceMessageResult.assertions().isNeverCalled()
            assertThat(analyticsService.trackedErrors).hasSize(1)
            startRecordResult.assertions().isNeverCalled()

            testPauseAndDestroy(initialState)
        }
    }

    @Test
    fun `present - record error - security exceptions are tracked`() = runTest {
        val exception = SecurityException("")
        val startRecordResult = lambdaRecorder<Unit> { throw exception }
        val voiceRecorder = FakeVoiceRecorder(
            recordingDuration = RECORDING_DURATION,
            startRecordResult = startRecordResult,
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            voiceRecorder = voiceRecorder,
        )
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))

            sendVoiceMessageResult.assertions().isNeverCalled()
            assertThat(analyticsService.trackedErrors).containsExactly(
                VoiceMessageException.PermissionMissing(message = "Expected permission to record but none", cause = exception)
            )
            startRecordResult.assertions().isCalledOnce()

            testPauseAndDestroy(initialState)
        }
    }

    @Test
    fun `present - permission accepted first time`() = runTest {
        val permissionsPresenter = createFakePermissionsPresenter(
            recordPermissionGranted = false,
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            permissionsPresenter = permissionsPresenter,
        )
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            assertThat(awaitItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)

            initialState.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            startRecordResult.assertions().isNeverCalled()
            stopRecordResult.assertions().isCalledOnce().with(value(false))

            permissionsPresenter.setPermissionGranted()

            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            advanceUntilIdle()

            val finalState = expectMostRecentItem()
            assertThat(finalState.voiceMessageState).isEqualTo(RECORDING_STATE)
            startRecordResult.assertions().isCalledOnce()
            stopRecordResult.assertions().isCalledOnce().with(value(false))

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - permission denied previously`() = runTest {
        val permissionsPresenter = createFakePermissionsPresenter(
            recordPermissionGranted = false,
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            permissionsPresenter = permissionsPresenter,
        )
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))

            // See the dialog and accept it
            awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
                assertThat(it.showPermissionRationaleDialog).isTrue()
                it.eventSink(VoiceMessageComposerEvent.AcceptPermissionRationale)
            }
            skipItems(1)

            // Dialog is hidden, user accepts permissions
            assertThat(awaitItem().showPermissionRationaleDialog).isFalse()

            // Permission is granted, recording starts automatically
            permissionsPresenter.setPermissionGranted()
            advanceUntilIdle()

            val finalState = expectMostRecentItem()
            assertThat(finalState.voiceMessageState).isEqualTo(RECORDING_STATE)
            startRecordResult.assertions().isCalledOnce()

            testPauseAndDestroy(finalState)
        }
    }

    @Test
    fun `present - permission rationale dismissed`() = runTest {
        val permissionsPresenter = createFakePermissionsPresenter(
            recordPermissionGranted = false,
        )
        val presenter = createDefaultVoiceMessageComposerPresenter(
            permissionsPresenter = permissionsPresenter,
        )
        presenter.test {
            awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))

            // See the dialog and accept it
            awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
                assertThat(it.showPermissionRationaleDialog).isTrue()
                it.eventSink(VoiceMessageComposerEvent.DismissPermissionsRationale)
            }
            skipItems(1)

            // Dialog is hidden, user tries to record again
            awaitItem().also {
                assertThat(it.showPermissionRationaleDialog).isFalse()
                it.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
            }
            skipItems(1)

            // Dialog is shown once again
            val finalState = awaitItem().also {
                assertThat(it.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
                assertThat(it.showPermissionRationaleDialog).isTrue()
            }
            startRecordResult.assertions().isNeverCalled()

            cancelAndIgnoreRemainingEvents()
            testPauseAndDestroy(finalState)
        }
    }

    /**
     * Element June: wait for the first state matching [predicate], keeping the voice states seen on the way in [seen].
     */
    private suspend fun TurbineTestContext<VoiceMessageComposerState>.awaitUntil(
        seen: MutableList<VoiceMessageState> = mutableListOf(),
        predicate: (VoiceMessageComposerState) -> Boolean,
    ): VoiceMessageComposerState {
        while (true) {
            val item = awaitItem()
            seen += item.voiceMessageState
            if (predicate(item)) return item
        }
    }

    private suspend fun TurbineTestContext<VoiceMessageComposerState>.testPauseAndDestroy(
        mostRecentState: VoiceMessageComposerState,
    ) {
        mostRecentState.eventSink(
            VoiceMessageComposerEvent.LifecycleEvent(event = Lifecycle.Event.ON_PAUSE)
        )

        val onPauseState = when (val state = mostRecentState.voiceMessageState) {
            VoiceMessageState.Idle -> mostRecentState
            is VoiceMessageState.Recording -> {
                // If recorder was active, it stops
                awaitItem().apply {
                    assertThat(voiceMessageState).isEqualTo(aPreviewState())
                }
            }
            is VoiceMessageState.Preview -> when (state.isPlaying) {
                // If the preview was playing, it pauses
                true -> awaitItem().apply {
                    assertThat(voiceMessageState).isEqualTo(aPausedState())
                }
                false -> mostRecentState
            }
        }

        onPauseState.eventSink(
            VoiceMessageComposerEvent.LifecycleEvent(event = Lifecycle.Event.ON_DESTROY)
        )

        when (val state = onPauseState.voiceMessageState) {
            VoiceMessageState.Idle ->
                ensureAllEventsConsumed()
            is VoiceMessageState.Recording ->
                assertThat(awaitItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            is VoiceMessageState.Preview -> when (state.isSending) {
                true -> ensureAllEventsConsumed()
                false -> assertThat(awaitItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            }
        }
    }

    private fun TestScope.createDefaultVoiceMessageComposerPresenter(
        permissionsPresenter: PermissionsPresenter = createFakePermissionsPresenter(),
        voiceRecorder: VoiceRecorder = this@DefaultVoiceMessageComposerPresenterTest.voiceRecorder,
        audioFocus: AudioFocus = this@DefaultVoiceMessageComposerPresenterTest.audioFocus,
    ): DefaultVoiceMessageComposerPresenter {
        return DefaultVoiceMessageComposerPresenter(
            sessionCoroutineScope = backgroundScope,
            timelineMode = Timeline.Mode.Live,
            voiceRecorder = voiceRecorder,
            analyticsService = analyticsService,
            audioFocus = audioFocus,
            mediaSenderFactory = { mediaSender },
            player = VoiceMessageComposerPlayer(FakeMediaPlayer(), this),
            messageComposerContext = messageComposerContext,
            permissionsPresenterFactory = FakePermissionsPresenterFactory(permissionsPresenter),
        )
    }

    private fun createFakePermissionsPresenter(
        recordPermissionGranted: Boolean = true,
        recordPermissionShowDialog: Boolean = false,
    ): FakePermissionsPresenter {
        val initialPermissionState = aPermissionsState(
            showDialog = recordPermissionShowDialog,
            permission = Manifest.permission.RECORD_AUDIO,
            permissionGranted = recordPermissionGranted,
        )
        return FakePermissionsPresenter(
            initialState = initialPermissionState
        )
    }

    private fun aPreviewState(
        isPlaying: Boolean = false,
        playbackProgress: Float = 0f,
        isSending: Boolean = false,
        time: Duration = RECORDING_DURATION,
        duration: Duration = RECORDING_DURATION,
        showCursor: Boolean = false,
        waveform: List<Float> = voiceRecorder.waveform,
    ) = VoiceMessageState.Preview(
        isPlaying = isPlaying,
        playbackProgress = playbackProgress,
        isSending = isSending,
        time = time,
        duration = duration,
        showCursor = showCursor,
        waveform = waveform.toImmutableList(),
    )

    private fun aPlayingState() =
        aPreviewState(
            isPlaying = true,
            playbackProgress = 0.1f,
            showCursor = true,
            time = RECORDING_DURATION,
            duration = PLAYER_DURATION,
        )

    private fun aPausedState() =
        aPlayingState()
            .copy(isPlaying = false)

    private fun VoiceMessageState.Preview.toSendingState() =
        copy(
            isPlaying = false,
            isSending = true,
            showCursor = false,
            time = RECORDING_DURATION,
        )
}

private fun aVoiceMessageComposerEvent(
    isReply: Boolean = false
) = Composer(
    inThread = false,
    isEditing = false,
    isReply = isReply,
    messageType = Composer.MessageType.VoiceMessage,
    startsThread = null
)
