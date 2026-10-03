/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.getSystemService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val TAG = "JuneSpeechEngine"

/**
 * Element June: [JuneSpeechEngine] using the Android text to speech engine in Korean, played on the media stream so it is heard
 * even when the phone is silent or on vibrate. Other audio is lowered (ducked) while reading.
 */
@ContributesBinding(AppScope::class)
class DefaultJuneSpeechEngine(
    @ApplicationContext private val context: Context,
) : JuneSpeechEngine {
    private val audioManager: AudioManager? = context.getSystemService<AudioManager>()
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var tts: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { Timber.tag(TAG).d("Audio focus change: $it") }

    override fun currentBlock(): JuneSpeakBlock? {
        val audioManager = audioManager ?: return JuneSpeakBlock.NoBluetooth
        return juneSpeakBlock(
            enabled = JuneSettings.btSpeakEnabled(context),
            hasBluetoothOutput = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { juneIsBluetoothOutput(it.type) },
            isInCall = audioManager.mode != AudioManager.MODE_NORMAL,
            mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
        )
    }

    override suspend fun open(): Boolean {
        val engine = createEngine() ?: return false
        tts = engine
        val language = runCatchingExceptions { engine.setLanguage(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            Timber.tag(TAG).w("No Korean voice in the text to speech engine ($language)")
            return false
        }
        engine.setAudioAttributes(speechAttributes)
        if (!requestFocus()) {
            Timber.tag(TAG).w("Audio focus refused")
            return false
        }
        return true
    }

    private suspend fun createEngine(): TextToSpeech? {
        val ready = CompletableDeferred<Boolean>()
        val engine = runCatchingExceptions {
            TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        }.getOrElse {
            Timber.tag(TAG).e(it, "Cannot create the text to speech engine")
            return null
        }
        val ok = withTimeoutOrNull(10.seconds) { ready.await() } == true
        if (!ok) {
            Timber.tag(TAG).w("Text to speech engine not ready")
            runCatchingExceptions { engine.shutdown() }
            return null
        }
        return engine
    }

    override suspend fun speak(text: String): Boolean {
        val engine = tts ?: return false
        val lineId = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                done.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                done.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                done.complete(false)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                done.complete(false)
            }
        })
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, lineId) != TextToSpeech.SUCCESS) {
            Timber.tag(TAG).w("Text to speech refused the line")
            return false
        }
        return try {
            done.await()
        } finally {
            // On timeout (cancellation), stop reading the line
            if (!done.isCompleted) runCatchingExceptions { engine.stop() }
        }
    }

    override fun close() {
        abandonFocus()
        tts?.let { engine ->
            runCatchingExceptions {
                engine.stop()
                engine.shutdown()
            }
        }
        tts = null
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        val audioManager = audioManager ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        val audioManager = audioManager ?: return
        runCatchingExceptions {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                audioManager.abandonAudioFocus(focusListener)
            }
        }
        focusRequest = null
    }
}
