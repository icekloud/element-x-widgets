/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService
import io.element.android.features.widgets.impl.WidgetStore
import io.element.android.libraries.core.extensions.runCatchingExceptions
import timber.log.Timber

/**
 * Element June: the sound and vibration of the voice shortcut, for when it is used without looking at the screen.
 *
 * The audio focus is held for the whole recording as a transient "may duck" request, so music keeps playing at a
 * lower volume instead of stopping (the composer uses an exclusive request, which pauses the music player).
 */
class AndroidJuneVoiceFeedback(
    private val context: Context,
    private val store: WidgetStore,
) : JuneVoiceFeedback {
    private val audioManager = context.getSystemService<AudioManager>()
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null

    override fun onRecordingStarted() {
        requestFocus()
        vibrate(START_VIBRATION_MS)
        if (store.isVoiceShortcutToneEnabled()) {
            playTone()
        }
    }

    override fun onRecordingStopped() {
        vibrate(STOP_VIBRATION_MS)
    }

    override fun onSent() {
        vibrate(SENT_VIBRATION_MS)
    }

    override fun onFailed() {
        vibrate(FAILED_VIBRATION_MS)
    }

    override fun release() {
        releaseFocus()
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        val manager = audioManager ?: return
        runCatchingExceptions {
            val listener = AudioManager.OnAudioFocusChangeListener {
                Timber.tag(TAG).d("Audio focus changed: $it")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                manager.requestAudioFocus(request)
                focusRequest = request
            } else {
                manager.requestAudioFocus(listener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                focusListener = listener
            }
        }.onFailure { Timber.tag(TAG).w(it, "Cannot request the audio focus") }
    }

    @Suppress("DEPRECATION")
    private fun releaseFocus() {
        val manager = audioManager ?: return
        runCatchingExceptions {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                focusListener?.let { manager.abandonAudioFocus(it) }
            }
        }.onFailure { Timber.tag(TAG).w(it, "Cannot release the audio focus") }
        focusRequest = null
        focusListener = null
    }

    /** A short beep, released right away so it never holds the music down. */
    private fun playTone() {
        runCatchingExceptions {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
            tone.release()
        }.onFailure { Timber.tag(TAG).w(it, "Cannot play the start tone") }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(durationMs: Long) {
        runCatchingExceptions {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService<VibratorManager>()?.defaultVibrator
            } else {
                context.getSystemService<Vibrator>()
            } ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(durationMs)
            }
        }.onFailure { Timber.tag(TAG).w(it, "Cannot vibrate") }
    }

    private companion object {
        const val TAG = "JuneVoice"
        const val START_VIBRATION_MS = 45L
        const val STOP_VIBRATION_MS = 25L
        const val SENT_VIBRATION_MS = 20L
        const val FAILED_VIBRATION_MS = 200L
        const val TONE_VOLUME = 80
        const val TONE_MS = 120
    }
}
