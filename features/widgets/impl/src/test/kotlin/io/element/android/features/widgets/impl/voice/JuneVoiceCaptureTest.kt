/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val MIN_DURATION = 700.milliseconds

class JuneVoiceCaptureTest {
    @Test
    fun `one tap starts the recording`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate()
        val feedback = FakeJuneVoiceFeedback()
        val capture = createCapture(recorder = recorder, feedback = feedback)

        capture.toggle()

        assertThat(capture.state.value).isInstanceOf(JuneVoiceCaptureState.Recording::class.java)
        assertThat(recorder.isRecording).isTrue()
        assertThat(feedback.started).isEqualTo(1)
    }

    @Test
    fun `tapping again stops the recording and sends it at once`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 3.seconds)
        val sender = FakeJuneVoiceSender()
        val feedback = FakeJuneVoiceFeedback()
        val pendingStore = FakeJuneVoicePendingStore()
        val capture = createCapture(recorder = recorder, sender = sender, feedback = feedback, pendingStore = pendingStore)

        capture.toggle()
        capture.toggle()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.Sent))
        assertThat(sender.sent.map { it.duration }).containsExactly(3.seconds)
        assertThat(recorder.isRecording).isFalse()
        assertThat(feedback.stopped).isEqualTo(1)
        assertThat(feedback.sent).isEqualTo(1)
        assertThat(pendingStore.saved).isEmpty()
    }

    @Test
    fun `the recording is sent with the file, mime type and waveform of the recorder`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 2.seconds)
        val sender = FakeJuneVoiceSender()
        val capture = createCapture(recorder = recorder, sender = sender)

        capture.toggle()
        capture.toggle()

        val recording = sender.sent.single()
        assertThat(recording.file.path).isEqualTo(A_FILE.path)
        assertThat(recording.mimeType).isEqualTo("audio/ogg")
        assertThat(recording.waveform).isEqualTo(A_WAVEFORM)
    }

    @Test
    fun `a recording shorter than the minimum is discarded without sending anything`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 500.milliseconds)
        val sender = FakeJuneVoiceSender()
        val pendingStore = FakeJuneVoicePendingStore()
        val capture = createCapture(recorder = recorder, sender = sender, pendingStore = pendingStore)

        capture.toggle()
        capture.toggle()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.TooShort))
        assertThat(sender.sent).isEmpty()
        assertThat(recorder.discarded).isEqualTo(1)
        assertThat(pendingStore.saved).isEmpty()
    }

    @Test
    fun `a recording of exactly the minimum length is sent`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = MIN_DURATION)
        val sender = FakeJuneVoiceSender()
        val capture = createCapture(recorder = recorder, sender = sender)

        capture.toggle()
        capture.toggle()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.Sent))
        assertThat(sender.sent).hasSize(1)
    }

    @Test
    fun `the length is read before stopping, so a recorder losing it still sends`() = runTest {
        // The recorder reports no length once stopped, as it does when it is stopped twice
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 4.seconds, forgetsDurationOnStop = true)
        val sender = FakeJuneVoiceSender()
        val capture = createCapture(recorder = recorder, sender = sender)

        capture.toggle()
        capture.toggle()

        assertThat(sender.sent.single().duration).isEqualTo(4.seconds)
    }

    @Test
    fun `tapping twice while sending sends only once`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 3.seconds)
        val sender = FakeJuneVoiceSender(blocked = true)
        val capture = createCapture(recorder = recorder, sender = sender)
        capture.toggle()

        val sending = async { capture.toggle() }
        sender.awaitStarted()
        capture.toggle()
        capture.toggle()
        sender.unblock()
        sending.await()

        assertThat(sender.sent).hasSize(1)
        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.Sent))
    }

    @Test
    fun `cancelling deletes the recording and sends nothing`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 3.seconds)
        val sender = FakeJuneVoiceSender()
        val pendingStore = FakeJuneVoicePendingStore()
        val capture = createCapture(recorder = recorder, sender = sender, pendingStore = pendingStore)

        capture.toggle()
        capture.cancel()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.Cancelled))
        assertThat(sender.sent).isEmpty()
        assertThat(recorder.discarded).isEqualTo(1)
        assertThat(pendingStore.saved).isEmpty()
    }

    @Test
    fun `cancelling while sending is ignored`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 3.seconds)
        val sender = FakeJuneVoiceSender(blocked = true)
        val capture = createCapture(recorder = recorder, sender = sender)
        capture.toggle()

        val sending = async { capture.toggle() }
        sender.awaitStarted()
        capture.cancel()
        sender.unblock()
        sending.await()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.Sent))
        assertThat(recorder.discarded).isEqualTo(0)
    }

    @Test
    fun `cancelling while nothing is recorded does nothing`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate()
        val capture = createCapture(recorder = recorder)

        capture.cancel()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Idle)
        assertThat(recorder.discarded).isEqualTo(0)
    }

    @Test
    fun `a failed send keeps the recording and reports the failure`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 3.seconds)
        val sender = FakeJuneVoiceSender(result = Result.failure(JuneVoiceSendException("offline")))
        val pendingStore = FakeJuneVoicePendingStore()
        val feedback = FakeJuneVoiceFeedback()
        val capture = createCapture(recorder = recorder, sender = sender, feedback = feedback, pendingStore = pendingStore)

        capture.toggle()
        capture.toggle()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.SendFailed))
        assertThat(pendingStore.load()?.duration).isEqualTo(3.seconds)
        assertThat(recorder.discarded).isEqualTo(0)
        assertThat(feedback.failed).isEqualTo(1)
    }

    @Test
    fun `a kept recording can be sent again`() = runTest {
        val sender = FakeJuneVoiceSender()
        val pendingStore = FakeJuneVoicePendingStore().apply { save(A_RECORDING) }
        val capture = createCapture(sender = sender, pendingStore = pendingStore)

        capture.retryPending()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.Sent))
        assertThat(sender.sent).containsExactly(A_RECORDING)
        assertThat(pendingStore.load()).isNull()
    }

    @Test
    fun `sending again a recording that fails keeps it`() = runTest {
        val sender = FakeJuneVoiceSender(result = Result.failure(JuneVoiceSendException("offline")))
        val pendingStore = FakeJuneVoicePendingStore().apply { save(A_RECORDING) }
        val capture = createCapture(sender = sender, pendingStore = pendingStore)

        capture.retryPending()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.SendFailed))
        assertThat(pendingStore.load()).isEqualTo(A_RECORDING)
    }

    @Test
    fun `sending again with nothing kept sends nothing`() = runTest {
        val sender = FakeJuneVoiceSender()
        val capture = createCapture(sender = sender, pendingStore = FakeJuneVoicePendingStore())

        capture.retryPending()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.NothingRecorded))
        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `discarding a kept recording forgets it`() = runTest {
        val pendingStore = FakeJuneVoicePendingStore().apply { save(A_RECORDING) }
        val capture = createCapture(pendingStore = pendingStore)

        capture.discardPending()

        assertThat(pendingStore.load()).isNull()
        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.Cancelled))
    }

    @Test
    fun `the maximum length stops the recording, keeps it and sends nothing`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 5.seconds)
        val sender = FakeJuneVoiceSender()
        val pendingStore = FakeJuneVoicePendingStore()
        val capture = createCapture(recorder = recorder, sender = sender, pendingStore = pendingStore)

        capture.toggle()
        capture.stopAtMaxLength()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.MaxLengthReached))
        assertThat(sender.sent).isEmpty()
        assertThat(pendingStore.load()?.duration).isEqualTo(5.seconds)
        assertThat(recorder.isRecording).isFalse()
    }

    @Test
    fun `the service going away keeps the recording`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 5.seconds)
        val sender = FakeJuneVoiceSender()
        val pendingStore = FakeJuneVoicePendingStore()
        val capture = createCapture(recorder = recorder, sender = sender, pendingStore = pendingStore)

        capture.toggle()
        capture.keepAndStop()

        assertThat(sender.sent).isEmpty()
        assertThat(pendingStore.load()?.duration).isEqualTo(5.seconds)
    }

    @Test
    fun `without the microphone permission nothing is recorded`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(startFailure = JuneVoiceStartFailure.MissingPermission)
        val feedback = FakeJuneVoiceFeedback()
        val capture = createCapture(recorder = recorder, feedback = feedback)

        capture.toggle()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.MissingPermission))
        assertThat(recorder.isRecording).isFalse()
        assertThat(feedback.started).isEqualTo(0)
        assertThat(feedback.failed).isEqualTo(1)
    }

    @Test
    fun `during a call nothing is recorded`() = runTest {
        val capture = createCapture(recorder = FakeJuneVoiceRecorderGate(startFailure = JuneVoiceStartFailure.InCall))

        capture.toggle()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.InCall))
    }

    @Test
    fun `when the microphone is busy nothing is recorded`() = runTest {
        val capture = createCapture(recorder = FakeJuneVoiceRecorderGate(startFailure = JuneVoiceStartFailure.MicrophoneBusy))

        capture.toggle()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.MicrophoneBusy))
    }

    @Test
    fun `a recorder giving nothing back reports it and sends nothing`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 3.seconds, returnsNothingOnStop = true)
        val sender = FakeJuneVoiceSender()
        val capture = createCapture(recorder = recorder, sender = sender)

        capture.toggle()
        capture.toggle()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.NothingRecorded))
        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `a failed capture can be followed by a new one`() = runTest {
        val recorder = FakeJuneVoiceRecorderGate(recordedDuration = 3.seconds, startFailure = JuneVoiceStartFailure.MicrophoneBusy)
        val sender = FakeJuneVoiceSender()
        val capture = createCapture(recorder = recorder, sender = sender)
        capture.toggle()
        recorder.startFailure = null

        capture.toggle()
        capture.toggle()

        assertThat(capture.state.value).isEqualTo(JuneVoiceCaptureState.Done(JuneVoiceOutcome.Sent))
        assertThat(sender.sent).hasSize(1)
    }

    private fun createCapture(
        recorder: JuneVoiceRecorderGate = FakeJuneVoiceRecorderGate(),
        sender: JuneVoiceSender = FakeJuneVoiceSender(),
        feedback: JuneVoiceFeedback = FakeJuneVoiceFeedback(),
        pendingStore: JuneVoicePendingStore = FakeJuneVoicePendingStore(),
    ) = JuneVoiceCapture(
        recorder = recorder,
        sender = sender,
        feedback = feedback,
        pendingStore = pendingStore,
        minDuration = MIN_DURATION,
        clock = { A_TIMESTAMP },
    )
}

private const val A_TIMESTAMP = 1_000L
private val A_FILE = File("voice.ogg")
private val A_WAVEFORM = listOf(0.1f, 0.5f, 0.9f)
private val A_RECORDING = JuneVoiceRecording(
    file = A_FILE,
    mimeType = "audio/ogg",
    waveform = A_WAVEFORM,
    duration = 2.seconds,
)
