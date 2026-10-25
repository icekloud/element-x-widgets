/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.june.JuneVoiceEngineChoice
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Element June: the engine that reads with the built-in voice when it is chosen, and falls back to the phone engine line by line when
 * the built-in one cannot read. The setting is never changed by a failure.
 */
class JuneVoiceFallbackEngineTest {
    private fun createEngine(
        builtIn: FakeJuneSpeechEngine = FakeJuneSpeechEngine(),
        phone: FakeJuneSpeechEngine = FakeJuneSpeechEngine(),
        chosen: JuneVoiceEngineChoice = JuneVoiceEngineChoice.BuiltIn,
        notes: MutableList<String> = mutableListOf(),
    ) = JuneVoiceFallbackEngine(
        builtIn = builtIn,
        phone = phone,
        chosenEngine = { chosen },
        onNote = { notes += it },
    )

    @Test
    fun `the phone engine reads everything when it is the chosen one`() = runTest {
        val builtIn = FakeJuneSpeechEngine()
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(builtIn = builtIn, phone = phone, chosen = JuneVoiceEngineChoice.Phone)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        engine.close()
        assertThat(phone.spoken).containsExactly("안녕하세요")
        assertThat(builtIn.spoken).isEmpty()
        assertThat(builtIn.openCount).isEqualTo(0)
    }

    @Test
    fun `the built-in engine reads everything when it works`() = runTest {
        val builtIn = FakeJuneSpeechEngine()
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(builtIn = builtIn, phone = phone)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        engine.close()
        assertThat(builtIn.spoken).containsExactly("안녕하세요")
        assertThat(phone.spoken).isEmpty()
    }

    @Test
    fun `a line the built-in engine cannot read is read by the phone engine`() = runTest {
        val builtIn = FakeJuneSpeechEngine(speakResult = false)
        val phone = FakeJuneSpeechEngine()
        val notes = mutableListOf<String>()
        val engine = createEngine(builtIn = builtIn, phone = phone, notes = notes)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        assertThat(builtIn.spoken).containsExactly("안녕하세요")
        assertThat(phone.spoken).containsExactly("안녕하세요")
        assertThat(notes).isNotEmpty()
    }

    @Test
    fun `a line that makes the built-in engine throw is read by the phone engine`() = runTest {
        val builtIn = FakeJuneSpeechEngine(speakFailure = IllegalStateException("inference failed"))
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(builtIn = builtIn, phone = phone)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        assertThat(phone.spoken).containsExactly("안녕하세요")
    }

    @Test
    fun `the phone engine takes over when the built-in model cannot be opened`() = runTest {
        val builtIn = FakeJuneSpeechEngine(openResult = JuneSpeakResult.EngineNotReady)
        val phone = FakeJuneSpeechEngine()
        val notes = mutableListOf<String>()
        val engine = createEngine(builtIn = builtIn, phone = phone, notes = notes)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        assertThat(builtIn.spoken).isEmpty()
        assertThat(phone.spoken).containsExactly("안녕하세요")
        assertThat(notes).isNotEmpty()
    }

    @Test
    fun `the note says why the built-in model could not be opened`() = runTest {
        val builtIn = FakeJuneSpeechEngine(openResult = JuneSpeakResult.EngineNotReady).apply {
            lastLoadFailure = "세션 생성 vocoder.onnx · IOException: fstat failed"
        }
        val notes = mutableListOf<String>()
        val engine = createEngine(builtIn = builtIn, notes = notes)
        assertThat(engine.open()).isNull()
        assertThat(notes).containsExactly("폰 기본 대체 · 내장 준비 실패: 세션 생성 vocoder.onnx · IOException: fstat failed")
    }

    @Test
    fun `the note gives the error when opening the built-in model throws`() = runTest {
        val builtIn = object : JuneSpeechEngine by FakeJuneSpeechEngine() {
            override suspend fun open(): JuneSpeakResult? = throw IllegalStateException("broken at /data/app/x")
        }
        val notes = mutableListOf<String>()
        val engine = JuneVoiceFallbackEngine(builtIn, FakeJuneSpeechEngine(), { JuneVoiceEngineChoice.BuiltIn }, { notes += it })
        assertThat(engine.open()).isNull()
        assertThat(notes).containsExactly("폰 기본 대체 · 내장 준비 실패: IllegalStateException: broken at <경로>")
    }

    @Test
    fun `the note keeps the old text when the built-in engine gives no reason`() = runTest {
        val notes = mutableListOf<String>()
        val engine = createEngine(builtIn = FakeJuneSpeechEngine(openResult = JuneSpeakResult.EngineNotReady), notes = notes)
        engine.open()
        assertThat(notes).containsExactly("폰 기본 대체(내장 준비 실패)")
    }

    @Test
    fun `the note says when the model files had to be copied`() = runTest {
        val builtIn = FakeJuneSpeechEngine().apply { lastLoadNote = "복사 방식" }
        val notes = mutableListOf<String>()
        val engine = createEngine(builtIn = builtIn, notes = notes)
        assertThat(engine.open()).isNull()
        engine.speak("안녕하세요")
        assertThat(notes.first()).isEqualTo("내장 준비: 복사 방식")
        assertThat(notes.last()).startsWith("내장 · 5자")
        assertThat(notes.last()).endsWith(" · 복사 방식")
    }

    @Test
    fun `nothing can be read when both engines fail to open`() = runTest {
        val builtIn = FakeJuneSpeechEngine(openResult = JuneSpeakResult.EngineNotReady)
        val phone = FakeJuneSpeechEngine(openResult = JuneSpeakResult.NoKoreanVoice)
        val engine = createEngine(builtIn = builtIn, phone = phone)
        assertThat(engine.open()).isEqualTo(JuneSpeakResult.NoKoreanVoice)
    }

    @Test
    fun `the built-in engine is tried again on the next line after a failure`() = runTest {
        val builtIn = FakeJuneSpeechEngine(speakResult = false)
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(builtIn = builtIn, phone = phone)
        engine.open()
        engine.speak("첫 줄")
        builtIn.speakResult = true
        engine.speak("둘째 줄")
        assertThat(builtIn.spoken).containsExactly("첫 줄", "둘째 줄").inOrder()
        assertThat(phone.spoken).containsExactly("첫 줄")
    }

    @Test
    fun `the block reason and the audio focus come from the phone engine`() = runTest {
        val builtIn = FakeJuneSpeechEngine(block = null)
        val phone = FakeJuneSpeechEngine(block = JuneSpeakBlock.NoBluetooth)
        val engine = createEngine(builtIn = builtIn, phone = phone)
        assertThat(engine.currentBlock()).isEqualTo(JuneSpeakBlock.NoBluetooth)
        assertThat(engine.requestFocus()).isTrue()
        assertThat(phone.focusRequestCount).isEqualTo(1)
        assertThat(builtIn.focusRequestCount).isEqualTo(0)
    }

    @Test
    fun `closing releases both engines`() = runTest {
        val builtIn = FakeJuneSpeechEngine()
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(builtIn = builtIn, phone = phone)
        engine.open()
        engine.speak("안녕하세요")
        engine.close()
        assertThat(builtIn.closeCount).isEqualTo(1)
        assertThat(phone.closeCount).isEqualTo(1)
    }

    @Test
    fun `the phone engine is only opened when it is needed`() = runTest {
        val builtIn = FakeJuneSpeechEngine()
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(builtIn = builtIn, phone = phone)
        engine.open()
        engine.speak("안녕하세요")
        assertThat(phone.openCount).isEqualTo(0)
        builtIn.speakResult = false
        engine.speak("둘째 줄")
        assertThat(phone.openCount).isEqualTo(1)
    }

    @Test
    fun `the note of a line read by the built-in voice gives the time it took`() = runTest {
        val notes = mutableListOf<String>()
        val builtIn = FakeJuneSpeechEngine()
        val engine = createEngine(builtIn = builtIn, notes = notes)
        engine.open()
        engine.speak("안녕하세요")
        assertThat(notes).hasSize(1)
        assertThat(notes.first()).contains("내장")
    }
}
