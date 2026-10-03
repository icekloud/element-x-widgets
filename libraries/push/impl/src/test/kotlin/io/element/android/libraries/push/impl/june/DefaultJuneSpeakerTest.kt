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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultJuneSpeakerTest {
    private fun aMessage(id: Int, body: String = "🔊 줄 $id\n\n본문 $id") = aNotifiableMessageEvent(eventId = EventId("\$event$id"), body = body)

    // The speaker reads on backgroundScope: advanceUntilIdle() stops when only background work is left, runCurrent() runs it.
    private fun TestScope.createSpeaker(
        engine: JuneSpeechEngine = FakeJuneSpeechEngine(),
        keepAlive: JuneSpeakKeepAlive = FakeJuneSpeakKeepAlive(),
    ) = DefaultJuneSpeaker(engine = engine, keepAlive = keepAlive, coroutineScope = backgroundScope)

    @Test
    fun `the speaker line of a message is read and the process is kept alive`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val keepAlive = FakeJuneSpeakKeepAlive()
        val speaker = createSpeaker(engine, keepAlive)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        runCurrent()
        assertThat(engine.spoken).containsExactly("줄 1")
        assertThat(keepAlive.startCount).isEqualTo(1)
        assertThat(engine.openCount).isEqualTo(1)
        assertThat(engine.closeCount).isEqualTo(1)
        speaker.awaitIdle(1.seconds)
    }

    @Test
    fun `messages without a speaker line, or my own, are not read and nothing is started`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val keepAlive = FakeJuneSpeakKeepAlive()
        val speaker = createSpeaker(engine, keepAlive)
        speaker.onNotifiableEvents(
            listOf(
                aMessage(1, body = "📍 그냥 메시지"),
                aNotifiableMessageEvent(eventId = EventId("\$event2"), body = "🔊 내 메시지", senderId = A_SESSION_ID),
            )
        )
        runCurrent()
        assertThat(engine.spoken).isEmpty()
        assertThat(engine.openCount).isEqualTo(0)
        assertThat(keepAlive.startCount).isEqualTo(0)
    }

    @Test
    fun `nothing is read when blocked, for instance without bluetooth`() = runTest {
        val engine = FakeJuneSpeechEngine(block = JuneSpeakBlock.NoBluetooth)
        val keepAlive = FakeJuneSpeakKeepAlive()
        val speaker = createSpeaker(engine, keepAlive)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        runCurrent()
        assertThat(engine.spoken).isEmpty()
        assertThat(keepAlive.startCount).isEqualTo(0)
    }

    @Test
    fun `the same event is read only once`() = runTest {
        val engine = FakeJuneSpeechEngine()
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        runCurrent()
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        speaker.onNotifiableEvents(listOf(aMessage(1), aMessage(1)))
        runCurrent()
        assertThat(engine.spoken).containsExactly("줄 1")
    }

    @Test
    fun `lines are read in order and at most 3 wait, the oldest waiting ones are dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeJuneSpeechEngine(gate = gate)
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        runCurrent()
        // Line 1 is being read, 5 more arrive: only the 3 newest are kept
        speaker.onNotifiableEvents((2..6).map { aMessage(it) })
        gate.complete(Unit)
        runCurrent()
        assertThat(engine.spoken).containsExactly("줄 1", "줄 4", "줄 5", "줄 6").inOrder()
        assertThat(engine.openCount).isEqualTo(1)
        assertThat(engine.closeCount).isEqualTo(1)
    }

    @Test
    fun `waiting lines are dropped when the earphones are disconnected meanwhile`() = runTest {
        lateinit var engine: FakeJuneSpeechEngine
        // The earphones are disconnected while the first line is read
        engine = FakeJuneSpeechEngine(onSpeak = { engine.block = JuneSpeakBlock.NoBluetooth })
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents((1..3).map { aMessage(it) })
        runCurrent()
        assertThat(engine.spoken).containsExactly("줄 1")
        assertThat(engine.closeCount).isEqualTo(1)
        speaker.awaitIdle(1.seconds)
    }

    @Test
    fun `nothing is read and the queue is emptied when the speech cannot be prepared`() = runTest {
        val engine = FakeJuneSpeechEngine(canOpen = false)
        val speaker = createSpeaker(engine)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        runCurrent()
        assertThat(engine.spoken).isEmpty()
        assertThat(engine.closeCount).isEqualTo(1)
        // Not stuck: waiting returns at once
        speaker.awaitIdle(1.seconds)
        // A later message is tried again
        engine.canOpen = true
        speaker.onNotifiableEvents(listOf(aMessage(2)))
        runCurrent()
        assertThat(engine.spoken).containsExactly("줄 2")
    }

    @Test
    fun `an engine failure never reaches the caller`() = runTest {
        val engine = object : JuneSpeechEngine {
            override fun currentBlock(): JuneSpeakBlock? = error("boom")
            override suspend fun open(): Boolean = true
            override suspend fun speak(text: String): Boolean = true
            override fun close() = Unit
        }
        val keepAlive = FakeJuneSpeakKeepAlive()
        val speaker = createSpeaker(engine, keepAlive)
        speaker.onNotifiableEvents(listOf(aMessage(1)))
        runCurrent()
        assertThat(keepAlive.startCount).isEqualTo(0)
    }
}
