/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.push.impl.notifications.model.NotifiableEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "JuneSpeaker"

/** Longest time spent reading one line aloud. */
internal val JUNE_SPEAK_LINE_TIMEOUT = 60.seconds

/** Number of audio focus requests before giving up, and the wait between two of them. */
internal const val JUNE_SPEAK_FOCUS_ATTEMPTS = 3
internal val JUNE_SPEAK_FOCUS_RETRY_DELAY = 300.milliseconds

/** Wait after getting the audio focus, so the music of other apps is already lowered when the first syllable is read. */
internal val JUNE_SPEAK_DUCK_DELAY = 500.milliseconds

/**
 * Element June: reads aloud the 🔊 line of incoming messages when a Bluetooth audio output is connected.
 */
interface JuneSpeaker {
    /** Queues the 🔊 line of [events] that can be read. Returns immediately, never throws. */
    fun onNotifiableEvents(events: List<NotifiableEvent>)

    /** Suspends until every queued line has been read, or [timeout] has elapsed. */
    suspend fun awaitIdle(timeout: Duration)
}

/**
 * The Android side of [JuneSpeaker]: settings and audio state, text to speech and audio focus.
 */
interface JuneSpeechEngine {
    /** The reason not to read aloud now, or null if it can be read. */
    fun currentBlock(): JuneSpeakBlock?

    /** Asks for the audio focus (other audio is lowered) if not held yet. Returns true if it is held. Released by [close]. */
    fun requestFocus(): Boolean

    /** Prepares the text to speech engine in Korean. Returns null when ready, else the reason why nothing can be read. */
    suspend fun open(): JuneSpeakResult?

    /** Reads [text] aloud and suspends until it is done. Returns false on failure. */
    suspend fun speak(text: String): Boolean

    /** Releases the audio focus and the engine. Can be called at any time, even if nothing was taken. */
    fun close()
}

/**
 * Element June: keeps the outcome of the last attempt to read a 🔊 line, to show it in the settings. Never the message text.
 */
fun interface JuneSpeakStatusStore {
    fun record(result: JuneSpeakResult)
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultJuneSpeaker(
    private val engine: JuneSpeechEngine,
    private val keepAlive: JuneSpeakKeepAlive,
    private val statusStore: JuneSpeakStatusStore,
    @AppCoroutineScope
    private val coroutineScope: CoroutineScope,
) : JuneSpeaker {
    private val lock = Any()
    private val queue = JuneSpeakQueue()
    private val busy = MutableStateFlow(false)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    override fun onNotifiableEvents(events: List<NotifiableEvent>) {
        runCatchingExceptions {
            val candidates = events.filterIsInstance<NotifiableMessageEvent>().filter { juneIsSpeakCandidate(it) }
            val items = candidates.mapNotNull { event -> juneSpeakableLine(event)?.let { JuneSpeakItem(event.eventId, it) } }
            if (items.isEmpty()) {
                // A 🔊 that is not at the start of one of the first lines: say why it was not read. Other messages leave the status as is.
                if (candidates.any { it.body.orEmpty().contains(JUNE_SPEAKER_EMOJI) }) {
                    record(JuneSpeakResult.NoSpeakerLine)
                }
                return
            }
            val block = engine.currentBlock()
            if (block != null) {
                Timber.tag(TAG).d("Not reading ${items.map { it.eventId }}: $block")
                record(block.toResult())
                return
            }
            synchronized(lock) {
                items.forEach { item ->
                    if (!queue.offer(item)) Timber.tag(TAG).d("Already read ${item.eventId}")
                }
                if (queue.isEmpty()) return
                busy.value = true
                if (loop?.isActive != true) {
                    loop = coroutineScope.launch {
                        while (true) {
                            wakeUp.receive()
                            runCatchingExceptions { readQueue() }.onFailure {
                                Timber.tag(TAG).e(it, "Failed to read the lines")
                                dropAll(JuneSpeakResult.Failed)
                            }
                        }
                    }
                }
            }
            // Ask for the audio focus right away, while the push is being handled: from Android 15 on, an app in the background only gets
            // it while it runs a foreground service (the one of the push handling when the screen is off). Released by the reading loop.
            runCatchingExceptions { engine.requestFocus() }
            wakeUp.trySend(Unit)
            keepAlive.start()
        }.onFailure { Timber.tag(TAG).e(it, "Failed to queue the lines to read") }
    }

    override suspend fun awaitIdle(timeout: Duration) {
        if (withTimeoutOrNull(timeout) { busy.first { !it } } == null) {
            Timber.tag(TAG).w("Still reading after $timeout")
        }
    }

    private fun record(result: JuneSpeakResult) {
        runCatchingExceptions { statusStore.record(result) }.onFailure { Timber.tag(TAG).e(it, "Cannot save the reading status") }
    }

    /** The next line to read, or null when the queue is empty or nothing can be read anymore (then the queue is emptied). */
    private fun nextReadable(): JuneSpeakItem? {
        val block = engine.currentBlock()
        if (block != null) {
            dropAll(block.toResult())
            return null
        }
        return synchronized(lock) {
            queue.poll().also { if (it == null) busy.value = false }
        }
    }

    /** Empties the queue, [result] is recorded if lines were waiting. */
    private fun dropAll(result: JuneSpeakResult) {
        val dropped = synchronized(lock) {
            val size = queue.size
            queue.clear()
            busy.value = false
            size
        }
        if (dropped > 0) {
            Timber.tag(TAG).d("Dropping $dropped lines: $result")
            record(result)
        }
    }

    /** Asks for the audio focus, a few times if refused (it may be refused for a moment, for instance while another sound ends). */
    private suspend fun acquireFocus(): Boolean {
        repeat(JUNE_SPEAK_FOCUS_ATTEMPTS) { attempt ->
            if (runCatchingExceptions { engine.requestFocus() }.getOrDefault(false)) return true
            if (attempt < JUNE_SPEAK_FOCUS_ATTEMPTS - 1) delay(JUNE_SPEAK_FOCUS_RETRY_DELAY)
        }
        Timber.tag(TAG).w("Audio focus refused")
        return false
    }

    private suspend fun readQueue() {
        // Whatever happens below (failure, cancellation), the audio focus is released so the music never stays lowered or paused
        try {
            if (synchronized(lock) { queue.isEmpty() }) return
            val failure = runCatchingExceptions { engine.open() }.getOrDefault(JuneSpeakResult.EngineNotReady)
            if (failure != null) {
                dropAll(failure)
                return
            }
            if (!acquireFocus()) {
                dropAll(JuneSpeakResult.FocusRefused)
                return
            }
            delay(JUNE_SPEAK_DUCK_DELAY)
            var item = nextReadable()
            while (item != null) {
                val text = item.text
                val spoken = withTimeoutOrNull(JUNE_SPEAK_LINE_TIMEOUT) { runCatchingExceptions { engine.speak(text) }.getOrDefault(false) }
                Timber.tag(TAG).d("Read ${item.eventId}: ${spoken ?: "timeout"}")
                record(if (spoken == true) JuneSpeakResult.Read else JuneSpeakResult.Failed)
                item = nextReadable()
            }
        } finally {
            runCatchingExceptions { engine.close() }
        }
    }
}
