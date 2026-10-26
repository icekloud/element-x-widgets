/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 앱31: reading a line with the Samsung engine, and what the settings of the phone are told when it gives no sound. Every way it
 * can fail has to show a different reason, otherwise a failure on the phone cannot be understood without its logs.
 */
class SamsungSpeakDiagnosisTest {
    private fun trace(chunks: Int = 1) = SamsungSpeakTrace(
        voice = SAMSUNG_VOICE_KO_L08.folderName,
        sampleRate = 24_000,
        chunks = chunks,
    )

    // (가) the engine refused the text

    @Test
    fun `an engine that refuses the text says the code it answered`() = runTest {
        val native = FakeSamsungTtsNative(inputTextCode = 7, audio = ShortArray(0))
        val trace = trace()
        val read = samsungReadChunks(native, listOf("안녕하세요"), trace) { true }
        assertThat(read).isFalse()
        assertThat(trace.failure).isEqualTo("입력 코드 7(중단됨)")
        assertThat(trace.line()).contains("입력 7(중단됨)")
    }

    @Test
    fun `the engine is not asked for the next chunk once it refused one`() = runTest {
        val native = FakeSamsungTtsNative(inputTextCode = 191, audio = ShortArray(0))
        val trace = trace(chunks = 2)
        samsungReadChunks(native, listOf("첫 줄", "둘째 줄"), trace) { true }
        assertThat(native.spoken).containsExactly("첫 줄")
        assertThat(trace.failure).isEqualTo("입력 코드 191(화자 바꾸기 실패)")
    }

    // (나) the callback said the line was stopped and gave nothing

    @Test
    fun `a callback that only stops the line says so`() = runTest {
        val native = FakeSamsungTtsNative().apply {
            answers += SamsungFakeRead(pieces = listOf(ShortArray(0) to SAMSUNG_AUDIO_STOPPED))
        }
        val trace = trace()
        val read = samsungReadChunks(native, listOf("안녕하세요"), trace) { true }
        assertThat(read).isFalse()
        assertThat(trace.failure).isEqualTo("콜백 중단(유형 8)")
        assertThat(trace.line()).contains("콜백 1회")
        assertThat(trace.line()).contains("샘플 0개")
    }

    // (다) the callback gave no sample at all

    @Test
    fun `a callback that never comes says no sample was made`() = runTest {
        val native = FakeSamsungTtsNative(audio = ShortArray(0))
        val trace = trace()
        val read = samsungReadChunks(native, listOf("안녕하세요"), trace) { true }
        assertThat(read).isFalse()
        assertThat(trace.failure).isEqualTo("샘플 0개")
        assertThat(trace.line()).contains("콜백 0회")
    }

    // (라) the audio track could not be made

    @Test
    fun `an audio track that cannot be made says so`() = runTest {
        val native = FakeSamsungTtsNative(audio = ShortArray(240))
        val trace = trace()
        val read = samsungReadChunks(native, listOf("안녕하세요"), trace) { false }
        assertThat(read).isFalse()
        assertThat(trace.failure).isEqualTo("오디오 트랙 실패")
        assertThat(trace.line()).contains("트랙 없음")
    }

    // (마) the phone ran out of memory

    @Test
    fun `running out of memory says so instead of throwing`() = runTest {
        val native = FakeSamsungTtsNative(audio = ShortArray(240))
        val trace = trace()
        val read = samsungReadChunks(native, listOf("안녕하세요"), trace) { throw OutOfMemoryError("samples") }
        assertThat(read).isFalse()
        assertThat(trace.failure).isEqualTo("메모리 부족")
    }

    // What the engine really does: a skipped sentence is not a failure

    @Test
    fun `audio that comes with the stop result is still played`() = runTest {
        val native = FakeSamsungTtsNative().apply {
            answers += SamsungFakeRead(
                pieces = listOf(
                    ShortArray(240) to 0,
                    ShortArray(0) to SAMSUNG_AUDIO_STOPPED,
                )
            )
        }
        val played = mutableListOf<Int>()
        val trace = trace()
        val read = samsungReadChunks(native, listOf("안녕하세요"), trace) { played += it.size; true }
        assertThat(read).isTrue()
        assertThat(trace.failure).isNull()
        assertThat(played).containsExactly(240)
    }

    @Test
    fun `a chunk the engine skips does not lose the whole line`() = runTest {
        val native = FakeSamsungTtsNative().apply {
            answers += SamsungFakeRead(pieces = listOf(ShortArray(0) to SAMSUNG_AUDIO_STOPPED))
            answers += SamsungFakeRead(pieces = listOf(ShortArray(480) to 0))
        }
        val played = mutableListOf<Int>()
        val trace = trace(chunks = 2)
        val read = samsungReadChunks(native, listOf("첫 줄", "둘째 줄"), trace) { played += it.size; true }
        assertThat(read).isTrue()
        assertThat(played).containsExactly(480)
        assertThat(trace.samples).isEqualTo(480)
    }

    // The line shown in the settings

    @Test
    fun `the line says how far the reading got`() = runTest {
        val native = FakeSamsungTtsNative().apply {
            answers += SamsungFakeRead(pieces = listOf(ShortArray(120) to 0, ShortArray(360) to 2))
        }
        val trace = trace()
        samsungReadChunks(native, listOf("안녕하세요"), trace) { true }
        assertThat(trace.line()).isEqualTo(
            "열기 OK · ko_KR_l08 · 1/1청크 5자 · 입력 OK · 콜백 2회 · 샘플 480개 · 트랙 OK · 24000Hz"
        )
    }

    @Test
    fun `the line says which chunk was being read when it failed`() = runTest {
        val native = FakeSamsungTtsNative().apply {
            answers += SamsungFakeRead(pieces = listOf(ShortArray(240) to 0))
            answers += SamsungFakeRead(code = 7)
        }
        val trace = trace(chunks = 3)
        samsungReadChunks(native, listOf("하나", "둘", "셋"), trace) { true }
        assertThat(trace.line()).contains("2/3청크 1자")
        assertThat(trace.line()).contains("입력 7(중단됨)")
    }

    @Test
    fun `the time the engine took is counted`() = runTest {
        val native = FakeSamsungTtsNative()
        val trace = trace()
        samsungReadChunks(native, listOf("안녕하세요"), trace) { true }
        assertThat(trace.generationMillis).isAtLeast(0L)
        assertThat(trace.failure).isNull()
    }

    // The reason reaches the settings through the fallback engine

    @Test
    fun `the note of a failed line gives the reason and how far it got`() = runTest {
        val notes = mutableListOf<String>()
        val samsung = FakeJuneSpeechEngine(speakResult = false).apply {
            lastSpeakFailure = "샘플 0개"
            lastSpeakTrace = "열기 OK · ko_KR_l08 · 1/1청크 5자 · 입력 OK · 콜백 0회 · 샘플 0개 · 트랙 없음 · 24000Hz"
        }
        val engine = JuneVoiceFallbackEngine(
            builtIn = FakeJuneSpeechEngine(),
            phone = FakeJuneSpeechEngine(),
            chosenEngine = { io.element.android.libraries.designsystem.june.JuneVoiceEngineChoice.Samsung },
            onNote = { notes += it },
            samsung = samsung,
        )
        engine.open()
        engine.speak("안녕하세요")
        assertThat(notes).containsExactly(
            "폰 기본 대체(삼성 생성 실패: 샘플 0개) · 열기 OK · ko_KR_l08 · 1/1청크 5자 · 입력 OK · 콜백 0회 · 샘플 0개 · 트랙 없음 · 24000Hz"
        )
    }

    @Test
    fun `an engine that says nothing keeps the old note`() = runTest {
        val notes = mutableListOf<String>()
        val engine = JuneVoiceFallbackEngine(
            builtIn = FakeJuneSpeechEngine(),
            phone = FakeJuneSpeechEngine(),
            chosenEngine = { io.element.android.libraries.designsystem.june.JuneVoiceEngineChoice.Samsung },
            onNote = { notes += it },
            samsung = FakeJuneSpeechEngine(speakResult = false),
        )
        engine.open()
        engine.speak("안녕하세요")
        assertThat(notes).containsExactly("폰 기본 대체(생성 실패)")
    }
}
