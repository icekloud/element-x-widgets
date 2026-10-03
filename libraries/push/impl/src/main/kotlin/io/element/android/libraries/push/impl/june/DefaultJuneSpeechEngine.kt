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
 * Element June: [JuneSpeechEngine] using the Android text to speech engine in Korean.
 *
 * The line is played like the voice of a navigation app (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, speech): on the media volume, so it is
 * heard even when the phone is silent or on vibrate, over the music of other apps which keeps playing lowered by the system
 * (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) and gets back to its volume when the focus is released in [close].
 */
@ContributesBinding(AppScope::class)
class DefaultJuneSpeechEngine(
    @ApplicationContext private val context: Context,
) : JuneSpeechEngine {
    private val audioManager: AudioManager? = context.getSystemService<AudioManager>()
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    /** Set when another app (a call for instance) takes the focus while reading: the reading stops. */
    @Volatile private var focusLost = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Timber.tag(TAG).d("Audio focus change: $change")
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            focusLost = true
            tts?.let { engine -> runCatchingExceptions { engine.stop() } }
        }
    }

    /** The volume stream of [speechAttributes]: the media one on phones. */
    private fun volumeStream(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        speechAttributes.volumeControlStream
    } else {
        AudioManager.STREAM_MUSIC
    }

    override fun currentBlock(): JuneSpeakBlock? {
        val audioManager = audioManager ?: return JuneSpeakBlock.NoBluetooth
        return juneSpeakBlock(
            enabled = JuneSettings.btSpeakEnabled(context),
            hasBluetoothOutput = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { juneIsBluetoothOutput(it.type) },
            isInCall = audioManager.mode != AudioManager.MODE_NORMAL,
            mediaVolume = audioManager.getStreamVolume(volumeStream()),
        )
    }

    override fun requestFocus(): Boolean {
        synchronized(lock) {
            return requestFocusLocked()
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocusLocked(): Boolean {
        if (hasFocus) return true
        val audioManager = audioManager ?: return false
        val result = runCatchingExceptions {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(speechAttributes)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) focusLost = false
        Timber.tag(TAG).d("Audio focus request: $result, music playing: ${audioManager.isMusicActive}")
        return hasFocus
    }

    override suspend fun open(): JuneSpeakResult? {
        if (tts != null) return null
        val engine = createEngine() ?: return JuneSpeakResult.EngineNotReady
        tts = engine
        val language = runCatchingExceptions { engine.setLanguage(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            Timber.tag(TAG).w("No Korean voice in the text to speech engine ($language)")
            return JuneSpeakResult.NoKoreanVoice
        }
        engine.setAudioAttributes(speechAttributes)
        return null
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
        if (focusLost) {
            Timber.tag(TAG).w("Audio focus lost, not reading")
            return false
        }
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
        // Release the focus first, so the music of other apps gets back to its volume whatever happens next
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
    private fun abandonFocus() {
        synchronized(lock) {
            val audioManager = audioManager
            if (audioManager != null) {
                runCatchingExceptions {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                    } else {
                        audioManager.abandonAudioFocus(focusListener)
                    }
                }
            }
            focusRequest = null
            hasFocus = false
            focusLost = false
        }
    }
}
