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
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.june.JuneSamsungVoiceChoice
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.coroutines.coroutineContext

private const val TAG = "SamsungVoice"

/** How often the played position is looked at while waiting for the end of a line, and how long it may stay still before giving up. */
private const val POLL_DELAY_MILLIS = 50L
private const val STALLED_POLLS = 40

/**
 * 앱30: reads a line aloud with a Korean voice of the Samsung engine shipped inside the app, instead of the text to speech engine
 * of the phone. 앱32: which of the four voices is used is the one chosen in the settings.
 *
 * The line is written the way it is read in Korean ([juneKoreanSpeechText]) and cut into chunks ([juneVoiceChunks]); each chunk is
 * read by the engine, which gives back 16 bit mono samples, and is played as soon as it is made. The audio is played exactly like
 * the phone engine does it (navigation guidance usage, speech content), so the audio focus and the ducking of the other apps stay
 * the same.
 *
 * The library holds **one** engine for the whole process, so there is only one of this class ([SingleIn]) and only one line is
 * read at a time. The audio state, the audio focus and the reason not to read now stay the business of the phone engine.
 */
@Inject
@SingleIn(AppScope::class)
class SamsungJuneSpeechEngine(
    @ApplicationContext private val context: Context,
) : JuneSpeechEngine, JuneVoiceStatsSource, JuneVoiceLoadReport, JuneVoiceSpeakReport {
    private val native: SamsungTtsNative = JnaSamsungTtsNative()
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Only one line at a time: the engine of the library is a global one. */
    private val lock = Mutex()

    @Volatile private var sampleRate = 0

    /** 앱32: the voice the engine is open with, so another chosen voice is noticed and the engine opened again for it. */
    @Volatile private var openedVoice: SamsungVoice? = null

    /** 앱32: the number of the voice being read with, for the notes and the trace shown in the settings. */
    @Volatile private var openedChoice: JuneSamsungVoiceChoice = JuneSamsungVoiceChoice.Voice1

    /** Set when [close] was asked for while a line was being read: the engine is then closed once that line is done. */
    @Volatile private var closeWhenDone = false

    @Volatile override var lastLineStats: JuneVoiceLineStats? = null
        private set

    @Volatile override var lastLoadFailure: String? = null
        private set

    @Volatile override var lastLoadNote: String? = null
        private set

    @Volatile override var lastSpeakFailure: String? = null
        private set

    @Volatile override var lastSpeakTrace: String? = null
        private set

    /** The phone engine answers this: both voices play on the same output, with the same settings. */
    override fun currentBlock(): JuneSpeakBlock? = null

    override fun requestFocus(): Boolean = true

    override suspend fun open(): JuneSpeakResult? = withContext(dispatcher) {
        lock.withLock { prepare() }
    }

    /**
     * Gets the engine ready if it is not, and says whether it can read. Only called while [lock] is held.
     *
     * 앱32: the voice is the one chosen in the settings. The library holds one engine for the whole process and the voice is read
     * when it is opened, so choosing another one closes it and opens it again (the next line is then read with the new voice).
     */
    private fun prepare(): JuneSpeakResult? {
        val choice = JuneSettings.samsungVoice(context)
        val voice = samsungVoiceOf(choice)
        if (samsungVoiceNeedsReopen(openedVoice, voice)) {
            Timber.tag(TAG).d("Another Samsung voice was chosen: the engine is opened again")
            closeEngine()
        }
        if (sampleRate > 0) return null
        closeWhenDone = false
        val root = File(context.filesDir, SAMSUNG_VOICE_ASSETS)
        val ready = samsungVoicePrepare(
            native = native,
            root = root,
            voice = voice,
            assets = SamsungAndroidVoiceAssets(context.assets),
            linker = SamsungOsVoiceLinker(),
        )
        return when (ready) {
            is SamsungVoiceReady.Ready -> {
                sampleRate = ready.sampleRate
                openedVoice = voice
                openedChoice = choice
                lastLoadFailure = null
                lastLoadNote = samsungVoiceOpenNote(ready.copiedFiles)
                Timber.tag(TAG).d("The Samsung voice %s is ready at %d Hz", choice.shortLabel, ready.sampleRate)
                null
            }
            is SamsungVoiceReady.Failed -> {
                sampleRate = 0
                openedVoice = null
                openedChoice = choice
                // The number of the voice is kept in the reason: the settings then say which one could not be opened
                lastLoadFailure = samsungVoiceFailure(choice, ready.reason)
                lastLoadNote = null
                Timber.tag(TAG).w("The Samsung voice %s is not ready: %s", choice.shortLabel, ready.reason)
                JuneSpeakResult.EngineNotReady
            }
        }
    }

    override suspend fun speak(text: String): Boolean {
        val spoken = juneKoreanSpeechText(text)
        val chunks = juneVoiceChunks(spoken)
        if (chunks.isEmpty()) return false
        return withContext(dispatcher) {
            lock.withLock {
                // The engine may have been closed in between (the preview button uses the same one): it is opened again
                if (prepare() != null) {
                    false
                } else {
                    try {
                        play(chunks, text.length)
                    } finally {
                        if (closeWhenDone) closeEngine()
                    }
                }
            }
        }
    }

    private suspend fun play(chunks: List<String>, characters: Int): Boolean {
        val rate = sampleRate
        val start = System.currentTimeMillis()
        var audioMillis = 0L
        var writtenFrames = 0
        var track: AudioTrack? = null
        lastLineStats = null
        // 앱31: what the reading did, so the settings of the phone can say where it stopped. 앱32: with the number of the voice
        val voice = openedVoice ?: samsungVoiceOf(openedChoice)
        val trace = SamsungSpeakTrace(
            voice = samsungVoiceTraceName(openedChoice, voice),
            sampleRate = rate,
            chunks = chunks.size,
        )
        try {
            val read = samsungReadChunks(native, chunks, trace) { samples ->
                audioMillis += 1000L * samples.size / rate
                val current = track ?: createTrack(rate)?.also {
                    track = it
                    // The first chunk is played while the next one is still being made
                    it.play()
                } ?: return@samsungReadChunks false
                if (writtenFrames > 0) writtenFrames += writeSilence(current, rate)
                writtenFrames += write(current, samples)
                true
            }
            if (!read) return failed(trace)
            if (writtenFrames == 0) {
                trace.failure = "오디오 트랙 쓰기 0"
                return failed(trace)
            }
            track?.let { waitUntilPlayed(it, writtenFrames) }
            lastSpeakFailure = null
            lastSpeakTrace = trace.line()
            lastLineStats = JuneVoiceLineStats(characters, trace.generationMillis, audioMillis)
            Timber.tag(TAG).d("Read %d characters in %d chunks: %s", characters, chunks.size, trace.line())
            return true
        } finally {
            track?.let { stop(it) }
            if (lastLineStats == null) {
                lastLineStats = JuneVoiceLineStats(characters, System.currentTimeMillis() - start, audioMillis)
            }
        }
    }

    /** Keeps why the line gave no sound, so [JuneVoiceFallbackEngine] can show it, and answers false. */
    private fun failed(trace: SamsungSpeakTrace): Boolean {
        lastSpeakFailure = trace.failure
        lastSpeakTrace = trace.line()
        Timber.tag(TAG).w("The Samsung voice gave no sound: %s · %s", trace.failure, trace.line())
        return false
    }

    private fun createTrack(sampleRate: Int): AudioTrack? = runCatchingExceptions {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimumSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(speechAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimumSize.coerceAtLeast(sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.getOrElse { error ->
        Timber.tag(TAG).e(error, "Cannot play the Samsung voice")
        null
    }

    /** The short silence left between two chunks, like the built-in voice does. Returns the number of frames written. */
    private suspend fun writeSilence(track: AudioTrack, sampleRate: Int): Int =
        write(track, ShortArray((sampleRate * JUNE_VOICE_CHUNK_SILENCE_SECONDS).toInt()))

    /** Writes every sample (the track blocks while it plays) and returns how many frames went in. */
    private suspend fun write(track: AudioTrack, samples: ShortArray): Int {
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
     * Waits until the [frames] that were written have been played: the line is only done once it has been heard, otherwise the
     * reader would start the next line over the end of this one.
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
        if (lock.tryLock()) {
            try {
                closeEngine()
            } finally {
                lock.unlock()
            }
            return
        }
        // A line is being read: it is stopped and the engine is closed as soon as that line is done
        closeWhenDone = true
        runCatchingExceptions { native.stop() }.onFailure { Timber.tag(TAG).e(it, "Cannot stop the Samsung voice") }
    }

    private fun closeEngine() {
        closeWhenDone = false
        openedVoice = null
        if (sampleRate == 0) return
        sampleRate = 0
        runCatchingExceptions { native.close() }.onFailure { Timber.tag(TAG).e(it, "Cannot close the Samsung voice") }
    }
}
