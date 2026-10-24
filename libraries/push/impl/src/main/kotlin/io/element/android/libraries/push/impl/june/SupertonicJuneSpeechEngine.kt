/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.zacsweers.metro.Inject
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.coroutineContext

private const val TAG = "JuneVoiceEngine"

/** How often the played position is looked at while waiting for the end of a line, and how long it may stay still before giving up. */
private const val POLL_DELAY_MILLIS = 50L
private const val STALLED_POLLS = 40

/**
 * Element June: reads a line aloud with the Korean voice built into the app (Supertonic 3, voice F1), instead of the text to speech
 * engine of the phone.
 *
 * The line is written the way it is read in Korean ([juneKoreanSpeechText]), cut into chunks ([juneVoiceChunks]), and each chunk is
 * played as soon as it is made, so the first words are heard while the next chunk is still being worked on. The audio is played
 * exactly like the phone engine does it (navigation guidance usage, speech content), so the audio focus and the ducking of the other
 * apps stay the same.
 *
 * The audio state, the audio focus and the reason not to read now stay the business of the phone engine: this one only makes sound.
 */
@Inject
class SupertonicJuneSpeechEngine(
    @ApplicationContext private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : JuneSpeechEngine, JuneVoiceStatsSource {
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var model: JuneVoiceModel? = null

    @Volatile override var lastLineStats: JuneVoiceLineStats? = null
        private set

    /** The phone engine answers this: both voices play on the same output, with the same settings. */
    override fun currentBlock(): JuneSpeakBlock? = null

    override fun requestFocus(): Boolean = true

    override suspend fun open(): JuneSpeakResult? {
        if (model != null) return null
        val loaded = withContext(dispatcher) { JuneVoiceModel.load(context) } ?: return JuneSpeakResult.EngineNotReady
        model = loaded
        return null
    }

    override suspend fun speak(text: String): Boolean {
        val model = model ?: return false
        val spoken = juneKoreanSpeechText(text)
        val chunks = juneVoiceChunks(spoken)
        if (chunks.isEmpty()) return false
        return withContext(dispatcher) { play(model, chunks, text.length) }
    }

    private suspend fun play(model: JuneVoiceModel, chunks: List<String>, characters: Int): Boolean {
        val start = System.currentTimeMillis()
        var generationMillis = 0L
        var audioMillis = 0L
        var writtenFrames = 0
        var track: AudioTrack? = null
        try {
            chunks.forEach { chunk ->
                coroutineContext.ensureActive()
                val chunkStart = System.currentTimeMillis()
                val audio = model.synthesize(chunk)
                generationMillis += System.currentTimeMillis() - chunkStart
                audioMillis += audio.durationMillis
                val current = track ?: (createTrack(audio.sampleRate) ?: return false).also {
                    track = it
                    // The first chunk is played while the next one is still being made
                    it.play()
                }
                if (writtenFrames > 0) writtenFrames += writeSilence(current, audio.sampleRate)
                writtenFrames += write(current, audio.samples)
            }
            track?.let { waitUntilPlayed(it, writtenFrames) }
            lastLineStats = JuneVoiceLineStats(characters, generationMillis, audioMillis)
            Timber.tag(TAG).d("Read %d characters in %d chunks: %d ms made, %d ms played", characters, chunks.size, generationMillis, audioMillis)
            return true
        } catch (error: OutOfMemoryError) {
            Timber.tag(TAG).e(error, "Not enough memory for the built-in voice")
            return false
        } finally {
            track?.let { stop(it) }
            if (lastLineStats == null) {
                lastLineStats = JuneVoiceLineStats(characters, System.currentTimeMillis() - start, audioMillis)
            }
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack? = runCatchingExceptions {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimumSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
            .setAudioAttributes(speechAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimumSize.coerceAtLeast(sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.getOrElse { error ->
        Timber.tag(TAG).e(error, "Cannot play the built-in voice")
        null
    }

    /** The short silence left between two chunks, like the reference implementation. Returns the number of frames written. */
    private suspend fun writeSilence(track: AudioTrack, sampleRate: Int): Int =
        write(track, FloatArray((sampleRate * JUNE_VOICE_CHUNK_SILENCE_SECONDS).toInt()))

    /** Writes every sample (the track blocks while it plays) and returns how many frames went in. */
    private suspend fun write(track: AudioTrack, samples: FloatArray): Int {
        var offset = 0
        while (offset < samples.size) {
            coroutineContext.ensureActive()
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                Timber.tag(TAG).w("The audio track refused the samples (%d)", written)
                break
            }
            offset += written
        }
        return offset
    }

    /**
     * Waits until the [frames] that were written have been played: the line is only done once it has been heard, otherwise the reader
     * would start the next line over the end of this one.
     */
    private suspend fun waitUntilPlayed(track: AudioTrack, frames: Int) {
        var lastPosition = -1
        var stalledPolls = 0
        while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            coroutineContext.ensureActive()
            val position = track.playbackHeadPosition
            if (position >= frames) return
            // The head stops moving when the output goes away (the headset is switched off while reading)
            stalledPolls = if (position == lastPosition) stalledPolls + 1 else 0
            if (stalledPolls > STALLED_POLLS) {
                Timber.tag(TAG).w("The audio stopped moving at %d of %d frames", position, frames)
                return
            }
            lastPosition = position
            delay(POLL_DELAY_MILLIS)
        }
    }

    private fun stop(track: AudioTrack) {
        runCatchingExceptions {
            if (track.state == AudioTrack.STATE_INITIALIZED) track.stop()
            track.release()
        }.onFailure { Timber.tag(TAG).e(it, "Cannot stop the audio track") }
    }

    override fun close() {
        model?.close()
        model = null
    }
}
