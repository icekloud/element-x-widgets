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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "JuneSpeaker"

/** Longest time spent reading one line aloud. */
internal val JUNE_SPEAK_LINE_TIMEOUT = 60.seconds

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

    /** Prepares the speech (engine in Korean, audio focus). Returns false if nothing can be read. */
    suspend fun open(): Boolean

    /** Reads [text] aloud and suspends until it is done. Returns false on failure. */
    suspend fun speak(text: String): Boolean

    /** Releases what [open] took. */
    fun close()
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultJuneSpeaker(
    private val engine: JuneSpeechEngine,
    private val keepAlive: JuneSpeakKeepAlive,
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
            val items = events.mapNotNull { event -> juneSpeakableLine(event)?.let { JuneSpeakItem(event.eventId, it) } }
            if (items.isEmpty()) return
            val block = engine.currentBlock()
            if (block != null) {
                Timber.tag(TAG).d("Not reading ${items.map { it.eventId }}: $block")
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
                                dropAll("failure")
                            }
                        }
                    }
                }
            }
            wakeUp.trySend(Unit)
            keepAlive.start()
        }.onFailure { Timber.tag(TAG).e(it, "Failed to queue the lines to read") }
    }

    override suspend fun awaitIdle(timeout: Duration) {
        if (withTimeoutOrNull(timeout) { busy.first { !it } } == null) {
            Timber.tag(TAG).w("Still reading after $timeout")
        }
    }

    /** The next line to read, or null when the queue is empty or nothing can be read anymore (then the queue is emptied). */
    private fun nextReadable(): JuneSpeakItem? {
        val block = engine.currentBlock()
        if (block != null) {
            dropAll(block.name)
            return null
        }
        return synchronized(lock) {
            queue.poll().also { if (it == null) busy.value = false }
        }
    }

    private fun dropAll(reason: String) = synchronized(lock) {
        if (!queue.isEmpty()) Timber.tag(TAG).d("Dropping ${queue.size} lines: $reason")
        queue.clear()
        busy.value = false
    }

    private suspend fun readQueue() {
        if (synchronized(lock) { queue.isEmpty() }) return
        val opened = runCatchingExceptions { engine.open() }.getOrDefault(false)
        if (!opened) {
            dropAll("speech not available")
            runCatchingExceptions { engine.close() }
            return
        }
        try {
            var item = nextReadable()
            while (item != null) {
                val text = item.text
                val spoken = withTimeoutOrNull(JUNE_SPEAK_LINE_TIMEOUT) { runCatchingExceptions { engine.speak(text) }.getOrDefault(false) }
                Timber.tag(TAG).d("Read ${item.eventId}: ${spoken ?: "timeout"}")
                item = nextReadable()
            }
        } finally {
            runCatchingExceptions { engine.close() }
        }
    }
}
