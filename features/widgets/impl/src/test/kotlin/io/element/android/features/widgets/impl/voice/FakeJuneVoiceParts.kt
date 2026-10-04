/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import kotlinx.coroutines.CompletableDeferred
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FakeJuneVoiceRecorderGate(
    private val recordedDuration: Duration = 3.seconds,
    private val returnsNothingOnStop: Boolean = false,
    private val forgetsDurationOnStop: Boolean = false,
    var startFailure: JuneVoiceStartFailure? = null,
) : JuneVoiceRecorderGate {
    var isRecording = false
        private set
    var discarded = 0
        private set

    override suspend fun start(): JuneVoiceStartFailure? {
        startFailure?.let { return it }
        isRecording = true
        return null
    }

    override fun elapsed(): Duration = if (isRecording) recordedDuration else 0.milliseconds

    override suspend fun stop(): JuneVoiceRecording? {
        isRecording = false
        if (returnsNothingOnStop) return null
        return JuneVoiceRecording(
            file = File("voice.ogg"),
            mimeType = "audio/ogg",
            waveform = listOf(0.1f, 0.5f, 0.9f),
            duration = if (forgetsDurationOnStop) 0.milliseconds else recordedDuration,
        )
    }

    override suspend fun discard() {
        discarded++
    }
}

class FakeJuneVoiceSender(
    private val result: Result<Unit> = Result.success(Unit),
    blocked: Boolean = false,
) : JuneVoiceSender {
    private val sentRecordings = mutableListOf<JuneVoiceRecording>()
    private val started = CompletableDeferred<Unit>()
    private val gate = if (blocked) CompletableDeferred() else CompletableDeferred(Unit)

    val sent: List<JuneVoiceRecording> get() = sentRecordings

    override suspend fun send(recording: JuneVoiceRecording): Result<Unit> {
        started.complete(Unit)
        gate.await()
        sentRecordings.add(recording)
        return result
    }

    /** Suspends until a send is in progress, so a test can tap the icon again while it is sending. */
    suspend fun awaitStarted() = started.await()

    fun unblock() {
        gate.complete(Unit)
    }
}

class FakeJuneVoiceFeedback : JuneVoiceFeedback {
    var started = 0
        private set
    var stopped = 0
        private set
    var sent = 0
        private set
    var failed = 0
        private set
    var released = 0
        private set

    override fun onRecordingStarted() {
        started++
    }

    override fun onRecordingStopped() {
        stopped++
    }

    override fun onSent() {
        sent++
    }

    override fun onFailed() {
        failed++
    }

    override fun release() {
        released++
    }
}

class FakeJuneVoicePendingStore : JuneVoicePendingStore {
    private val savedRecordings = mutableListOf<JuneVoiceRecording>()
    private var pending: JuneVoiceRecording? = null

    val saved: List<JuneVoiceRecording> get() = savedRecordings

    override fun save(recording: JuneVoiceRecording) {
        savedRecordings.add(recording)
        pending = recording
    }

    override fun load(): JuneVoiceRecording? = pending

    override fun clear() {
        pending = null
    }
}
