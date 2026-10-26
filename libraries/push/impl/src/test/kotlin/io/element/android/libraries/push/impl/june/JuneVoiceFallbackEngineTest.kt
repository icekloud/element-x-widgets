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
 * Element June: the engine that reads with the Samsung voice of the app when it is chosen, and falls back to the phone engine line by
 * line when that voice cannot read. The setting is never changed by a failure.
 */
class JuneVoiceFallbackEngineTest {
    private fun createEngine(
        phone: FakeJuneSpeechEngine = FakeJuneSpeechEngine(),
        chosen: JuneVoiceEngineChoice = JuneVoiceEngineChoice.Samsung,
        notes: MutableList<String> = mutableListOf(),
        samsung: FakeJuneSpeechEngine? = FakeJuneSpeechEngine(),
    ) = JuneVoiceFallbackEngine(
        phone = phone,
        chosenEngine = { chosen },
        onNote = { notes += it },
        samsung = samsung,
    )

    @Test
    fun `the phone engine reads everything when it is the chosen one`() = runTest {
        val samsung = FakeJuneSpeechEngine()
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(phone = phone, chosen = JuneVoiceEngineChoice.Phone, samsung = samsung)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        engine.close()
        assertThat(phone.spoken).containsExactly("안녕하세요")
        assertThat(samsung.spoken).isEmpty()
        assertThat(samsung.openCount).isEqualTo(0)
    }

    @Test
    fun `the Samsung engine reads everything when it is the chosen one`() = runTest {
        val samsung = FakeJuneSpeechEngine()
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(phone = phone, samsung = samsung)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        engine.close()
        assertThat(samsung.spoken).containsExactly("안녕하세요")
        assertThat(phone.spoken).isEmpty()
    }

    @Test
    fun `an install that had chosen the removed Supertonic voice reads with the Samsung voice`() = runTest {
        val samsung = FakeJuneSpeechEngine()
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(phone = phone, chosen = JuneVoiceEngineChoice.parse("builtin"), samsung = samsung)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        assertThat(samsung.spoken).containsExactly("안녕하세요")
        assertThat(phone.spoken).isEmpty()
    }

    @Test
    fun `a line the Samsung engine cannot read is read by the phone engine`() = runTest {
        val samsung = FakeJuneSpeechEngine(speakResult = false)
        val phone = FakeJuneSpeechEngine()
        val notes = mutableListOf<String>()
        val engine = createEngine(phone = phone, notes = notes, samsung = samsung)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        assertThat(samsung.spoken).containsExactly("안녕하세요")
        assertThat(phone.spoken).containsExactly("안녕하세요")
        assertThat(notes).isNotEmpty()
    }

    @Test
    fun `a line that makes the Samsung engine throw is read by the phone engine`() = runTest {
        val samsung = FakeJuneSpeechEngine(speakFailure = IllegalStateException("synthesis failed"))
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(phone = phone, samsung = samsung)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        assertThat(phone.spoken).containsExactly("안녕하세요")
    }

    @Test
    fun `the phone engine takes over when the Samsung voice cannot be opened`() = runTest {
        val samsung = FakeJuneSpeechEngine(openResult = JuneSpeakResult.EngineNotReady)
        val phone = FakeJuneSpeechEngine()
        val notes = mutableListOf<String>()
        val engine = createEngine(phone = phone, notes = notes, samsung = samsung)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        assertThat(samsung.spoken).isEmpty()
        assertThat(phone.spoken).containsExactly("안녕하세요")
        assertThat(notes).isNotEmpty()
    }

    @Test
    fun `the note says why the Samsung voice could not be opened`() = runTest {
        val samsung = FakeJuneSpeechEngine(openResult = JuneSpeakResult.EngineNotReady).apply {
            lastLoadFailure = "라이브러리 적재 실패 · UnsatisfiedLinkError"
        }
        val notes = mutableListOf<String>()
        val engine = createEngine(notes = notes, samsung = samsung)
        assertThat(engine.open()).isNull()
        assertThat(notes).containsExactly("폰 기본 대체 · 삼성 준비 실패: 라이브러리 적재 실패 · UnsatisfiedLinkError")
    }

    @Test
    fun `the note gives the error when opening the Samsung voice throws`() = runTest {
        val samsung = object : JuneSpeechEngine by FakeJuneSpeechEngine() {
            override suspend fun open(): JuneSpeakResult? = throw IllegalStateException("broken at /data/app/x")
        }
        val notes = mutableListOf<String>()
        val engine = JuneVoiceFallbackEngine(FakeJuneSpeechEngine(), { JuneVoiceEngineChoice.Samsung }, { notes += it }, samsung)
        assertThat(engine.open()).isNull()
        assertThat(notes).containsExactly("폰 기본 대체 · 삼성 준비 실패: IllegalStateException: broken at <경로>")
    }

    @Test
    fun `the note keeps the old text when the Samsung engine gives no reason`() = runTest {
        val notes = mutableListOf<String>()
        val engine = createEngine(notes = notes, samsung = FakeJuneSpeechEngine(openResult = JuneSpeakResult.EngineNotReady))
        engine.open()
        assertThat(notes).containsExactly("폰 기본 대체(삼성 준비 실패)")
    }

    @Test
    fun `the note says how the Samsung voice was made ready`() = runTest {
        val samsung = FakeJuneSpeechEngine().apply { lastLoadNote = "음성 파일 4개 설치" }
        val notes = mutableListOf<String>()
        val engine = createEngine(notes = notes, samsung = samsung)
        assertThat(engine.open()).isNull()
        engine.speak("안녕하세요")
        assertThat(notes.first()).isEqualTo("삼성 준비: 음성 파일 4개 설치")
        assertThat(notes.last()).startsWith("삼성 · 5자")
        assertThat(notes.last()).endsWith(" · 음성 파일 4개 설치")
    }

    @Test
    fun `nothing can be read when both engines fail to open`() = runTest {
        val samsung = FakeJuneSpeechEngine(openResult = JuneSpeakResult.EngineNotReady)
        val phone = FakeJuneSpeechEngine(openResult = JuneSpeakResult.NoKoreanVoice)
        val engine = createEngine(phone = phone, samsung = samsung)
        assertThat(engine.open()).isEqualTo(JuneSpeakResult.NoKoreanVoice)
    }

    @Test
    fun `the Samsung engine is tried again on the next line after a failure`() = runTest {
        val samsung = FakeJuneSpeechEngine(speakResult = false)
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(phone = phone, samsung = samsung)
        engine.open()
        engine.speak("첫 줄")
        samsung.speakResult = true
        engine.speak("둘째 줄")
        assertThat(samsung.spoken).containsExactly("첫 줄", "둘째 줄").inOrder()
        assertThat(phone.spoken).containsExactly("첫 줄")
    }

    @Test
    fun `the block reason and the audio focus come from the phone engine`() = runTest {
        val samsung = FakeJuneSpeechEngine(block = null)
        val phone = FakeJuneSpeechEngine(block = JuneSpeakBlock.NoBluetooth)
        val engine = createEngine(phone = phone, samsung = samsung)
        assertThat(engine.currentBlock()).isEqualTo(JuneSpeakBlock.NoBluetooth)
        assertThat(engine.requestFocus()).isTrue()
        assertThat(phone.focusRequestCount).isEqualTo(1)
        assertThat(samsung.focusRequestCount).isEqualTo(0)
    }

    @Test
    fun `closing releases both engines`() = runTest {
        val samsung = FakeJuneSpeechEngine()
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(phone = phone, samsung = samsung)
        engine.open()
        engine.speak("안녕하세요")
        engine.close()
        assertThat(samsung.closeCount).isEqualTo(1)
        assertThat(phone.closeCount).isEqualTo(1)
    }

    @Test
    fun `the phone engine is only opened when it is needed`() = runTest {
        val samsung = FakeJuneSpeechEngine()
        val phone = FakeJuneSpeechEngine()
        val engine = createEngine(phone = phone, samsung = samsung)
        engine.open()
        engine.speak("안녕하세요")
        assertThat(phone.openCount).isEqualTo(0)
        samsung.speakResult = false
        engine.speak("둘째 줄")
        assertThat(phone.openCount).isEqualTo(1)
    }

    @Test
    fun `the note of a line read by the Samsung voice says which voice read it and the time it took`() = runTest {
        val notes = mutableListOf<String>()
        val engine = createEngine(notes = notes, samsung = FakeJuneSpeechEngine())
        engine.open()
        engine.speak("안녕하세요")
        assertThat(notes).hasSize(1)
        assertThat(notes.first()).startsWith("삼성 · 5자 · 생성 ")
    }

    @Test
    fun `the phone engine reads when the Samsung voice is chosen but not available`() = runTest {
        val phone = FakeJuneSpeechEngine()
        val notes = mutableListOf<String>()
        val engine = createEngine(phone = phone, notes = notes, samsung = null)
        assertThat(engine.open()).isNull()
        assertThat(engine.speak("안녕하세요")).isTrue()
        assertThat(phone.spoken).containsExactly("안녕하세요")
        assertThat(notes).containsExactly("폰 기본 대체(삼성 없음)")
    }
}
