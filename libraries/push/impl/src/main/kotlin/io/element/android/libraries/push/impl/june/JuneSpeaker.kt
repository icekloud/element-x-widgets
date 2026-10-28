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
import io.element.android.services.toolbox.api.systemclock.SystemClock
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

    /** 앱34: what the last [requestFocus] answered, for the diagnosis shown in the settings. */
    fun lastFocusOutcome(): JuneFocusOutcome = JuneFocusOutcome.Unknown

    /** 앱34: what the phone is doing right now (app, screen, music), or null when it cannot be read. */
    fun phoneState(): JunePhoneState? = null

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

    /** 앱34: the same with the diagnosis of the reading (focus, app and screen state, times). Never the message text. */
    fun record(result: JuneSpeakResult, diag: JuneSpeakDiag?) = record(result)
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultJuneSpeaker(
    private val engine: JuneSpeechEngine,
    private val keepAlive: JuneSpeakKeepAlive,
    private val statusStore: JuneSpeakStatusStore,
    private val systemClock: SystemClock,
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
            val items = candidates.mapNotNull { event ->
                juneSpeakableLine(event)?.let {
                    // 앱34: when its push arrived in the app, so the settings can say how long it took until the line was read
                    JuneSpeakItem(event.eventId, it, JunePushArrivals.take(event.eventId.value))
                }
            }
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

    private fun record(result: JuneSpeakResult, diag: JuneSpeakDiag? = null) {
        runCatchingExceptions { statusStore.record(result, diag) }.onFailure { Timber.tag(TAG).e(it, "Cannot save the reading status") }
    }

    /** The next line to read, or null when the queue is empty or nothing can be read anymore (then the queue is emptied). */
    private fun nextReadable(diag: JuneSpeakDiag? = null): JuneSpeakItem? {
        val block = engine.currentBlock()
        if (block != null) {
            dropAll(block.toResult(), diag)
            return null
        }
        return synchronized(lock) {
            queue.poll().also { if (it == null) busy.value = false }
        }
    }

    /** Empties the queue, [result] is recorded if lines were waiting. */
    private fun dropAll(result: JuneSpeakResult, diag: JuneSpeakDiag? = null) {
        val dropped = synchronized(lock) {
            val size = queue.size
            queue.clear()
            busy.value = false
            size
        }
        if (dropped > 0) {
            Timber.tag(TAG).d("Dropping $dropped lines: $result")
            record(result, diag)
        }
    }

    /** 앱34: what asking for the audio focus gave, kept for the diagnosis shown in the settings. */
    private data class FocusTry(val granted: Boolean, val attempts: Int)

    /**
     * Asks for the audio focus, a few times if refused (it may be refused for a moment, for instance while another sound ends).
     *
     * 앱34: a refusal no longer stops the reading. From Android 15 on, an app in the background only gets the focus while a foreground
     * service runs, so the lines would be lost exactly when they are needed (screen off, app closed). The line is read anyway: the music
     * of the other app is then not lowered and both are heard together, which the settings say ([JuneSpeakResult.ReadNoFocus]).
     */
    private suspend fun acquireFocus(): FocusTry {
        repeat(JUNE_SPEAK_FOCUS_ATTEMPTS) { attempt ->
            if (runCatchingExceptions { engine.requestFocus() }.getOrDefault(false)) return FocusTry(granted = true, attempts = attempt + 1)
            if (attempt < JUNE_SPEAK_FOCUS_ATTEMPTS - 1) delay(JUNE_SPEAK_FOCUS_RETRY_DELAY)
        }
        Timber.tag(TAG).w("Audio focus refused, reading anyway")
        return FocusTry(granted = false, attempts = JUNE_SPEAK_FOCUS_ATTEMPTS)
    }

    private fun now(): Long = runCatchingExceptions { systemClock.epochMillis() }.getOrDefault(0L)

    private suspend fun readQueue() {
        // Whatever happens below (failure, cancellation), the audio focus is released so the music never stays lowered or paused
        try {
            if (synchronized(lock) { queue.isEmpty() }) return
            val openStart = now()
            val failure = runCatchingExceptions { engine.open() }.getOrDefault(JuneSpeakResult.EngineNotReady)
            val openMillis = now() - openStart
            if (failure != null) {
                dropAll(failure, JuneSpeakDiag(focus = focusOutcome(), engineOpenMillis = openMillis))
                return
            }
            val focus = acquireFocus()
            val diag = JuneSpeakDiag(
                focus = if (focus.granted) JuneFocusOutcome.Granted else focusOutcome(),
                focusAttempts = focus.attempts,
                phone = runCatchingExceptions { engine.phoneState() }.getOrNull(),
                engineOpenMillis = openMillis,
            )
            // Only worth waiting when the focus was granted: it is the time the system takes to lower the music of the other app
            if (focus.granted) delay(JUNE_SPEAK_DUCK_DELAY)
            var item = nextReadable(diag)
            while (item != null) {
                val text = item.text
                val spoken = withTimeoutOrNull(JUNE_SPEAK_LINE_TIMEOUT) { runCatchingExceptions { engine.speak(text) }.getOrDefault(false) }
                Timber.tag(TAG).d("Read ${item.eventId}: ${spoken ?: "timeout"}, focus: ${focus.granted}")
                val lineDiag = diag.copy(pushToSpeakMillis = item.arrivalMillis?.let { (now() - it).coerceAtLeast(0L) })
                val result = when {
                    spoken != true -> JuneSpeakResult.Failed
                    focus.granted -> JuneSpeakResult.Read
                    else -> JuneSpeakResult.ReadNoFocus
                }
                record(result, lineDiag)
                item = nextReadable(diag)
            }
        } finally {
            runCatchingExceptions { engine.close() }
        }
    }

    private fun focusOutcome(): JuneFocusOutcome = runCatchingExceptions { engine.lastFocusOutcome() }.getOrDefault(JuneFocusOutcome.Unknown)
}
