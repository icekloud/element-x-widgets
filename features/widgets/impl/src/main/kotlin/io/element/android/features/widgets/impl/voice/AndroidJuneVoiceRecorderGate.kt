/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.libraries.voicerecorder.impl.DefaultVoiceRecorder
import io.element.android.libraries.voicerecorder.impl.audio.AndroidAudioReader
import io.element.android.libraries.voicerecorder.impl.audio.AudioConfig
import io.element.android.libraries.voicerecorder.impl.audio.DBovAudioLevelCalculator
import io.element.android.libraries.voicerecorder.impl.audio.DefaultEncoder
import io.element.android.libraries.voicerecorder.impl.audio.SampleRate
import io.element.android.libraries.voicerecorder.impl.file.VoiceFileConfig
import io.element.android.libraries.voicerecorder.impl.file.VoiceFileManager
import io.element.android.opusencoder.OggOpusEncoder
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Element June: records with the very same recorder as the message composer (opus/ogg, same bit rate and waveform),
 * built by hand here because [DefaultVoiceRecorder] is normally created in the room scope, which a service does not have.
 */
class AndroidJuneVoiceRecorderGate(
    private val context: Context,
    coroutineScope: CoroutineScope,
) : JuneVoiceRecorderGate {
    private val audioConfig = AudioConfig(
        format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SampleRate.HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build(),
        // 24 kbps, as in VoiceRecorderBindingContainer
        bitRate = 24_000,
        sampleRate = SampleRate,
        source = MediaRecorder.AudioSource.MIC,
    )
    private val fileConfig = VoiceFileConfig(
        cacheSubdir = "june_voice_shortcut",
        fileExt = "ogg",
        mimeType = MimeTypes.Ogg,
    )
    private val recorder = DefaultVoiceRecorder(
        dispatchers = CoroutineDispatchers.Default,
        timeSource = TimeSource.Monotonic,
        audioReaderFactory = AndroidAudioReader.Factory,
        encoder = DefaultEncoder({ OggOpusEncoder.create() }, audioConfig),
        fileManager = JuneVoiceFileManager(context, fileConfig),
        config = audioConfig,
        fileConfig = fileConfig,
        audioLevelCalculator = DBovAudioLevelCalculator(),
        sessionCoroutineScope = coroutineScope,
    )

    // The permission is checked right here, on every start
    @SuppressLint("MissingPermission")
    override suspend fun start(): JuneVoiceStartFailure? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return JuneVoiceStartFailure.MissingPermission
        }
        val audioManager = context.getSystemService<AudioManager>()
        if (audioManager != null && audioManager.mode != AudioManager.MODE_NORMAL) {
            Timber.tag(TAG).i("Not recording, the audio mode is ${audioManager.mode}")
            return JuneVoiceStartFailure.InCall
        }
        return try {
            recorder.startRecord()
            null
        } catch (securityException: SecurityException) {
            Timber.tag(TAG).e(securityException, "Not allowed to record")
            JuneVoiceStartFailure.MissingPermission
        } catch (illegalState: IllegalStateException) {
            // AudioRecord cannot be built, usually because another app holds the microphone
            Timber.tag(TAG).e(illegalState, "Cannot record")
            JuneVoiceStartFailure.MicrophoneBusy
        } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
            Timber.tag(TAG).e(error, "Cannot record")
            JuneVoiceStartFailure.Error
        }
    }

    override fun elapsed(): Duration {
        return (recorder.state.value as? VoiceRecorderState.Recording)?.elapsedTime ?: 0.milliseconds
    }

    override suspend fun stop(): JuneVoiceRecording? {
        recorder.stopRecord()
        val finished = recorder.state.value as? VoiceRecorderState.Finished ?: return null
        return JuneVoiceRecording(
            file = finished.file,
            mimeType = finished.mimeType,
            waveform = finished.waveform,
            duration = finished.duration,
        )
    }

    override suspend fun discard() {
        recorder.deleteRecording()
    }

    private companion object {
        const val TAG = "JuneVoice"
    }
}

/**
 * Element June: the composer stores recordings under a directory named after the room; the shortcut always records
 * for the same room, so one directory is enough.
 */
private class JuneVoiceFileManager(
    private val context: Context,
    private val config: VoiceFileConfig,
) : VoiceFileManager {
    override fun createFile(): File {
        val directory = File(context.cacheDir, config.cacheSubdir).apply { mkdirs() }
        return File(directory, "${UUID.randomUUID()}.${config.fileExt}")
    }

    override fun deleteFile(file: File) {
        file.delete()
    }
}
