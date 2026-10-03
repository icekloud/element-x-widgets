/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.push.impl.notifications.fixtures.aNotifiableMessageEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultJuneSpeakerTest {
    private fun aMessage(id: Int, body: String = "🔊 줄 $id\n\n본문 $id") = aNotifiableMessageEvent(eventId = EventId("\$event$id"), body = body)

    private val cronMessage = """
        Cronjob Response: 동선 알림 발송(5분)
        (job_id: e9d0db134e2d)
        -------------

        🔊 긴스이로 걸어가는 중이에요.

        📍 롬 시어터 남쪽
        To stop or manage this job, send me a new message (e.g. "stop reminder 동선 알림 발송(5분)").
    """.trimIndent()

    // The speaker reads on backgroundScope: advanceUntilIdle() stops when only background work is left, runCurrent() runs it.
    private fun TestScope.createSpeaker(
        engine: JuneSpeechEngine = FakeJuneSpeechEngine(),
        keepAlive: JuneSpeakKeepAlive = FakeJuneSpeakKeepAlive(),
        statusStore: JuneSpeakStatusStore = FakeJuneSpeakStatusStore(),
        coroutineScope: CoroutineScope = backgroundScope,
    ) = DefaultJuneSpeaker(engine = engine, keepAlive = keepAlive, statusStore = statusStore, coroutineScope = coroutineScope)

    /** Runs the reading, including the short waits (focus retries, ducking), but less than the line timeout. */
    private fun TestScope.settle() {
        advanceTimeBy(5.seconds)
        runCurrent()
    }

    @Test
    fun `the speaker line of a message is read and the process is kept alive`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val keepAlive = FakeJuneSpeakKeepAlive()
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, keepAlive, status)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(engine.spoken).containsExactly("줄 1")
        assertThat(keepAlive.startCount).isEqualTo(1)
        assertThat(engine.openCount).isEqualTo(1)
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(status.results).containsExactly(JuneSpeakResult.Read)
        speaker.awaitIdle(1.seconds)
    }

    @Test
    fun `the speaker line of a scheduled message with the server header is read`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents(listOf(aMessage(1, body = cronMessage)))
        settle()
        assertThat(engine.spoken).containsExactly("긴스이로 걸어가는 중이에요.")
    }

    @Test
    fun `the line is read while the focus is held, the focus is released afterwards`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents((1..2).map { aMessage(it) })
        settle()
        assertThat(engine.spoken).containsExactly("줄 1", "줄 2").inOrder()
        assertThat(engine.focusWhileSpeaking).containsExactly(true, true)
        // Asked once for the whole queue, so the music is not raised and lowered again between two lines
        assertThat(engine.focusRequestCount).isEqualTo(1)
        assertThat(engine.hasFocus).isFalse()
    }

    @Test
    fun `the focus is asked for as soon as a line is queued, while the push is being handled`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        // Before the reading loop runs
        assertThat(engine.focusRequestCount).isEqualTo(1)
        assertThat(engine.hasFocus).isTrue()
        settle()
        assertThat(engine.hasFocus).isFalse()
    }

    @Test
    fun `the first syllable is read only after a short wait, once the music is lowered`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        runCurrent()
        assertThat(engine.spoken).isEmpty()
        advanceTimeBy(JUNE_SPEAK_DUCK_DELAY - 1.milliseconds)
        runCurrent()
        assertThat(engine.spoken).isEmpty()
        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertThat(engine.spoken).containsExactly("줄 1")
        assertThat(JUNE_SPEAK_DUCK_DELAY.inWholeMilliseconds).isAtMost(1000L)
    }

    @Test
    fun `a refused focus is asked for again shortly`() = runTest {
        // Refused when queued and at the first try of the reading loop, granted at the second one
        val engine = FakeJuneSpeechEngine(focusResults = listOf(false, false, true))
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, statusStore = status)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(engine.focusRequestCount).isEqualTo(3)
        assertThat(engine.spoken).containsExactly("줄 1")
        assertThat(status.results).containsExactly(JuneSpeakResult.Read)
        assertThat(engine.hasFocus).isFalse()
    }

    @Test
    fun `nothing is read when the focus is always refused, and the reason is recorded`() = runTest {
        val engine = FakeJuneSpeechEngine(focusResults = listOf(false))
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, statusStore = status)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(engine.spoken).isEmpty()
        assertThat(engine.focusRequestCount).isEqualTo(1 + JUNE_SPEAK_FOCUS_ATTEMPTS)
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(status.results).containsExactly(JuneSpeakResult.FocusRefused)
        // Not stuck
        speaker.awaitIdle(1.seconds)
    }

    @Test
    fun `messages without a speaker line, or my own, are not read and nothing is started`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val keepAlive = FakeJuneSpeakKeepAlive()
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, keepAlive, status)
        speaker.onNotifiableEvents(
            listOf(
                aMessage(1, body = "📍 그냥 메시지"),
                aNotifiableMessageEvent(eventId = EventId("\$event2"), body = "🔊 내 메시지", senderId = A_SESSION_ID),
            )
        )
        settle()
        assertThat(engine.spoken).isEmpty()
        assertThat(engine.openCount).isEqualTo(0)
        assertThat(engine.focusRequestCount).isEqualTo(0)
        assertThat(keepAlive.startCount).isEqualTo(0)
        // A message without 🔊 at all does not change the last status
        assertThat(status.results).isEmpty()
    }

    @Test
    fun `a speaker emoji that does not start one of the first lines is recorded as no speaker line`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, statusStore = status)
        speaker.onNotifiableEvents(listOf(aMessage(1, body = "메시지 중간에 🔊 이모지")))
        settle()
        assertThat(engine.spoken).isEmpty()
        assertThat(status.results).containsExactly(JuneSpeakResult.NoSpeakerLine)
    }

    @Test
    fun `nothing is read when blocked, for instance without bluetooth, and the reason is recorded`() = runTest {
        val engine = FakeJuneSpeechEngine(block = JuneSpeakBlock.NoBluetooth)
        val keepAlive = FakeJuneSpeakKeepAlive()
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, keepAlive, status)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(engine.spoken).isEmpty()
        assertThat(engine.focusRequestCount).isEqualTo(0)
        assertThat(keepAlive.startCount).isEqualTo(0)
        assertThat(status.results).containsExactly(JuneSpeakResult.NoBluetooth)
    }

    @Test
    fun `each block reason is recorded with its own result`() = runTest {
        mapOf(
            JuneSpeakBlock.Disabled to JuneSpeakResult.Disabled,
            JuneSpeakBlock.NoBluetooth to JuneSpeakResult.NoBluetooth,
            JuneSpeakBlock.InCall to JuneSpeakResult.InCall,
            JuneSpeakBlock.MediaMuted to JuneSpeakResult.MediaMuted,
        ).forEach { (block, result) ->
            assertThat(block.toResult()).isEqualTo(result)
        }
    }

    @Test
    fun `the same event is read only once`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        speaker.onNotifiableEvents(listOf(aMessage(1), aMessage(1)))
        settle()
        assertThat(engine.spoken).containsExactly("줄 1")
    }

    @Test
    fun `lines are read in order and at most 3 wait, the oldest waiting ones are dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeJuneSpeechEngine(gate = gate)
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        // Line 1 is being read, 5 more arrive: only the 3 newest are kept
        speaker.onNotifiableEvents((2..6).map { aMessage(it) })
        gate.complete(Unit)
        settle()
        assertThat(engine.spoken).containsExactly("줄 1", "줄 4", "줄 5", "줄 6").inOrder()
        assertThat(engine.openCount).isEqualTo(1)
        // The lines that arrived while reading wake the loop once more: it finds nothing left and releases again, which does no harm
        assertThat(engine.closeCount).isAtLeast(1)
        assertThat(engine.hasFocus).isFalse()
    }

    @Test
    fun `waiting lines are dropped when the earphones are disconnected meanwhile`() = runTest {
        lateinit var engine: FakeJuneSpeechEngine
        // The earphones are disconnected while the first line is read
        engine = FakeJuneSpeechEngine(onSpeak = { engine.block = JuneSpeakBlock.NoBluetooth })
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, statusStore = status)
        speaker.onNotifiableEvents((1..3).map { aMessage(it) })
        settle()
        assertThat(engine.spoken).containsExactly("줄 1")
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(engine.hasFocus).isFalse()
        assertThat(status.results).containsExactly(JuneSpeakResult.Read, JuneSpeakResult.NoBluetooth).inOrder()
        speaker.awaitIdle(1.seconds)
    }

    @Test
    fun `nothing is read and the focus is released when the speech cannot be prepared`() = runTest {
        val engine = FakeJuneSpeechEngine(openResult = JuneSpeakResult.NoKoreanVoice)
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, statusStore = status)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(engine.spoken).isEmpty()
        assertThat(engine.closeCount).isEqualTo(1)
        // The focus taken when the line was queued is given back: the music is not left lowered
        assertThat(engine.hasFocus).isFalse()
        assertThat(status.results).containsExactly(JuneSpeakResult.NoKoreanVoice)
        // Not stuck: waiting returns at once
        speaker.awaitIdle(1.seconds)
        // A later message is tried again
        engine.openResult = null
        speaker.onNotifiableEvents(listOf(aMessage(2)))
        settle()
        assertThat(engine.spoken).containsExactly("줄 2")
    }

    @Test
    fun `the focus is released when reading fails`() = runTest {
        val engine = FakeJuneSpeechEngine(speakFailure = IllegalStateException("boom"))
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, statusStore = status)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(engine.spoken).containsExactly("줄 1")
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(engine.hasFocus).isFalse()
        assertThat(status.results).containsExactly(JuneSpeakResult.Failed)
    }

    @Test
    fun `the focus is released when a line takes too long`() = runTest {
        val engine = FakeJuneSpeechEngine(gate = CompletableDeferred())
        val status = FakeJuneSpeakStatusStore()
        val speaker = createSpeaker(engine, statusStore = status)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(engine.hasFocus).isTrue()
        advanceTimeBy(JUNE_SPEAK_LINE_TIMEOUT)
        runCurrent()
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(engine.hasFocus).isFalse()
        assertThat(status.results).containsExactly(JuneSpeakResult.Failed)
    }

    @Test
    fun `the focus is released when the reading is cancelled`() = runTest {
        val engine = FakeJuneSpeechEngine(gate = CompletableDeferred())
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val speaker = createSpeaker(engine, coroutineScope = scope)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(engine.spoken).containsExactly("줄 1")
        assertThat(engine.hasFocus).isTrue()
        scope.cancel()
        runCurrent()
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(engine.hasFocus).isFalse()
    }

    @Test
    fun `an engine failure never reaches the caller`() = runTest {
        val engine = object : JuneSpeechEngine {
            override fun currentBlock(): JuneSpeakBlock? = error("boom")
            override fun requestFocus(): Boolean = true
            override suspend fun open(): JuneSpeakResult? = null
            override suspend fun speak(text: String): Boolean = true
            override fun close() = Unit
        }
        val keepAlive = FakeJuneSpeakKeepAlive()
        val speaker = createSpeaker(engine, keepAlive)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(keepAlive.startCount).isEqualTo(0)
    }

    @Test
    fun `a failing status store never stops the reading`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val speaker = createSpeaker(engine, statusStore = { error("boom") })
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        settle()
        assertThat(engine.spoken).containsExactly("줄 1")
        assertThat(engine.hasFocus).isFalse()
    }
}
