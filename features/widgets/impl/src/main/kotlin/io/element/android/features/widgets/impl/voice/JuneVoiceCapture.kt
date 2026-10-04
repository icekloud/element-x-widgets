/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.time.Duration

/**
 * Element June: a recording that is finished and can be sent, or kept for a later retry.
 */
data class JuneVoiceRecording(
    val file: File,
    val mimeType: String,
    val waveform: List<Float>,
    val duration: Duration,
)

/** Why [JuneVoiceRecorderGate.start] did not start a recording. */
enum class JuneVoiceStartFailure {
    MissingPermission,
    InCall,
    MicrophoneBusy,
    Error,
}

/** Records audio, the only part of [JuneVoiceCapture] that touches the microphone. */
interface JuneVoiceRecorderGate {
    /** Starts recording; returns null on success, or why it could not start. */
    suspend fun start(): JuneVoiceStartFailure?

    /** The time elapsed since the recording started, read before stopping it. */
    fun elapsed(): Duration

    /** Stops the recording and returns it, or null when nothing was recorded. */
    suspend fun stop(): JuneVoiceRecording?

    /** Deletes the current recording and its file. */
    suspend fun discard()
}

/** Sends a finished recording to the room of the shortcut. */
interface JuneVoiceSender {
    suspend fun send(recording: JuneVoiceRecording): Result<Unit>
}

/** Short sound and vibration, so the shortcut can be used without looking at the screen. */
interface JuneVoiceFeedback {
    fun onRecordingStarted()
    fun onRecordingStopped()
    fun onSent()
    fun onFailed()
    fun release()
}

/** Keeps a recording that could not be sent, so it survives the service being stopped. */
interface JuneVoicePendingStore {
    fun save(recording: JuneVoiceRecording)
    fun load(): JuneVoiceRecording?
    fun clear()
}

/** How a capture ended, shown to the user as a short notification. */
enum class JuneVoiceOutcome {
    Sent,
    TooShort,
    Cancelled,
    MaxLengthReached,
    SendFailed,
    NothingRecorded,
    MissingPermission,
    InCall,
    MicrophoneBusy,
    RecorderError,
}

/** State of the voice shortcut, drawn as the ongoing notification by the service. */
sealed interface JuneVoiceCaptureState {
    data object Idle : JuneVoiceCaptureState
    data class Recording(val startedAtMillis: Long) : JuneVoiceCaptureState
    data object Sending : JuneVoiceCaptureState
    data object Cancelling : JuneVoiceCaptureState
    data class Done(val outcome: JuneVoiceOutcome) : JuneVoiceCaptureState
}

/**
 * Element June: the voice shortcut logic, without any Android dependency so it can be unit tested.
 *
 * One tap starts the recording, the next one stops it and sends it to the commander room at once.
 * A recording shorter than [minDuration] is treated as a mistaken tap and silently discarded; a recording that
 * could not be sent is kept in [pendingStore] and can be sent again or deleted from the notification.
 */
class JuneVoiceCapture(
    private val recorder: JuneVoiceRecorderGate,
    private val sender: JuneVoiceSender,
    private val feedback: JuneVoiceFeedback,
    private val pendingStore: JuneVoicePendingStore,
    private val minDuration: Duration,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<JuneVoiceCaptureState>(JuneVoiceCaptureState.Idle)
    val state: StateFlow<JuneVoiceCaptureState> = mutableState

    /** The icon was tapped: start a recording, or stop and send the one in progress. */
    suspend fun toggle() {
        when (mutableState.value) {
            is JuneVoiceCaptureState.Recording -> sendNow()
            // A second tap while the recording is being sent or discarded must not send anything twice
            JuneVoiceCaptureState.Sending, JuneVoiceCaptureState.Cancelling -> Unit
            else -> start()
        }
    }

    suspend fun start() {
        mutex.withLock {
            if (mutableState.value is JuneVoiceCaptureState.Recording) return
            if (mutableState.value == JuneVoiceCaptureState.Sending || mutableState.value == JuneVoiceCaptureState.Cancelling) return
            mutableState.value = JuneVoiceCaptureState.Sending
        }
        val failure = recorder.start()
        if (failure != null) {
            feedback.onFailed()
            finish(failure.toOutcome())
            return
        }
        feedback.onRecordingStarted()
        mutableState.value = JuneVoiceCaptureState.Recording(clock())
    }

    /** Stop the recording and send it right away. */
    suspend fun sendNow() {
        if (!startFinishing(JuneVoiceCaptureState.Sending)) return
        val recording = stopRecording() ?: return
        deliver(recording)
    }

    /** Stop the recording and throw it away. */
    suspend fun cancel() {
        if (!startFinishing(JuneVoiceCaptureState.Cancelling)) return
        recorder.stop()
        recorder.discard()
        feedback.onRecordingStopped()
        finish(JuneVoiceOutcome.Cancelled)
    }

    /** The recording reached the maximum length: stop it and keep it, without sending anything. */
    suspend fun stopAtMaxLength() {
        if (!startFinishing(JuneVoiceCaptureState.Sending)) return
        val recording = stopRecording() ?: return
        pendingStore.save(recording)
        feedback.onFailed()
        finish(JuneVoiceOutcome.MaxLengthReached)
    }

    /** Send again the recording kept after a failure or a maximum length stop. */
    suspend fun retryPending() {
        mutex.withLock {
            if (mutableState.value is JuneVoiceCaptureState.Recording) return
            if (mutableState.value == JuneVoiceCaptureState.Sending || mutableState.value == JuneVoiceCaptureState.Cancelling) return
            mutableState.value = JuneVoiceCaptureState.Sending
        }
        val recording = pendingStore.load()
        if (recording == null) {
            finish(JuneVoiceOutcome.NothingRecorded)
            return
        }
        deliver(recording)
    }

    /** Delete the recording kept after a failure. */
    suspend fun discardPending() {
        mutex.withLock {
            if (mutableState.value is JuneVoiceCaptureState.Recording) return
            if (mutableState.value == JuneVoiceCaptureState.Sending || mutableState.value == JuneVoiceCaptureState.Cancelling) return
        }
        pendingStore.load()?.file?.delete()
        pendingStore.clear()
        finish(JuneVoiceOutcome.Cancelled)
    }

    /** Called when the service goes away while a recording is in progress: keep the recording, send nothing. */
    suspend fun keepAndStop() {
        if (!startFinishing(JuneVoiceCaptureState.Sending)) return
        val recording = stopRecording() ?: return
        pendingStore.save(recording)
        finish(JuneVoiceOutcome.SendFailed)
    }

    /** Moves out of the recording state, returns false when there is nothing to finish. */
    private suspend fun startFinishing(target: JuneVoiceCaptureState): Boolean {
        return mutex.withLock {
            if (mutableState.value !is JuneVoiceCaptureState.Recording) {
                false
            } else {
                mutableState.value = target
                true
            }
        }
    }

    /**
     * Stops the recorder and returns the recording when it is worth sending, or null when the capture is already
     * finished (nothing recorded, or too short and silently discarded).
     */
    private suspend fun stopRecording(): JuneVoiceRecording? {
        // Read the elapsed time before stopping: the length of the finished recording can be lost when the
        // recorder is stopped twice (screen off while the upload starts).
        val elapsed = recorder.elapsed()
        feedback.onRecordingStopped()
        val recording = recorder.stop()
        if (recording == null) {
            finish(JuneVoiceOutcome.NothingRecorded)
            return null
        }
        val duration = maxOf(elapsed, recording.duration)
        if (duration < minDuration) {
            recorder.discard()
            finish(JuneVoiceOutcome.TooShort)
            return null
        }
        return recording.copy(duration = duration)
    }

    private suspend fun deliver(recording: JuneVoiceRecording) {
        val result = sender.send(recording)
        if (result.isSuccess) {
            pendingStore.clear()
            feedback.onSent()
            finish(JuneVoiceOutcome.Sent)
        } else {
            pendingStore.save(recording)
            feedback.onFailed()
            finish(JuneVoiceOutcome.SendFailed)
        }
    }

    private fun finish(outcome: JuneVoiceOutcome) {
        mutableState.value = JuneVoiceCaptureState.Done(outcome)
    }
}

private fun JuneVoiceStartFailure.toOutcome(): JuneVoiceOutcome = when (this) {
    JuneVoiceStartFailure.MissingPermission -> JuneVoiceOutcome.MissingPermission
    JuneVoiceStartFailure.InCall -> JuneVoiceOutcome.InCall
    JuneVoiceStartFailure.MicrophoneBusy -> JuneVoiceOutcome.MicrophoneBusy
    JuneVoiceStartFailure.Error -> JuneVoiceOutcome.RecorderError
}
