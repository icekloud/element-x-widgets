/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Element June: how a line is cut into chunks for the built-in voice. Same rules as the reference implementation of the model
 * (paragraphs, then sentences, then commas, then spaces), with a shorter maximum length for Korean.
 */
class JuneVoiceChunkTest {
    @Test
    fun `a short line is one chunk`() {
        assertThat(juneVoiceChunks("좋은 아침입니다.")).containsExactly("좋은 아침입니다.")
    }

    @Test
    fun `a blank line gives no chunk`() {
        assertThat(juneVoiceChunks("")).isEmpty()
        assertThat(juneVoiceChunks("   ")).isEmpty()
    }

    @Test
    fun `sentences are kept together while they fit`() {
        val text = "좋은 아침입니다. 오늘 오후 세 시에 회의가 있습니다. 비가 올 확률은 이십 퍼센트입니다."
        assertThat(juneVoiceChunks(text, maxLength = 40)).containsExactly(
            "좋은 아침입니다. 오늘 오후 세 시에 회의가 있습니다.",
            "비가 올 확률은 이십 퍼센트입니다.",
        ).inOrder()
    }

    @Test
    fun `a line longer than the maximum is cut at the sentence ends`() {
        val text = "첫 번째 문장입니다. 두 번째 문장입니다. 세 번째 문장입니다."
        val chunks = juneVoiceChunks(text, maxLength = 20)
        assertThat(chunks).containsExactly("첫 번째 문장입니다.", "두 번째 문장입니다.", "세 번째 문장입니다.").inOrder()
    }

    @Test
    fun `a sentence longer than the maximum is cut at the commas`() {
        val text = "가나다라마바사아자차카타파하, 가나다라마바사아자차카타파하, 가나다라마바사아자차카타파하."
        val chunks = juneVoiceChunks(text, maxLength = 20)
        assertThat(chunks).hasSize(3)
        chunks.forEach { assertThat(it.length).isAtMost(20) }
        assertThat(chunks.joinToString("").replace(",", "").replace(" ", ""))
            .isEqualTo(text.replace(",", "").replace(" ", ""))
    }

    @Test
    fun `a part longer than the maximum is cut at the spaces`() {
        val text = "하나 둘 셋 넷 다섯 여섯 일곱 여덟 아홉 열 열하나 열둘 열셋 열넷"
        val chunks = juneVoiceChunks(text, maxLength = 15)
        assertThat(chunks.size).isAtLeast(2)
        chunks.forEach { assertThat(it.length).isAtMost(15) }
        assertThat(chunks.joinToString(" ")).isEqualTo(text)
    }

    @Test
    fun `a single word longer than the maximum is kept whole`() {
        val word = "가".repeat(30)
        assertThat(juneVoiceChunks(word, maxLength = 15)).containsExactly(word)
    }

    @Test
    fun `question and exclamation marks end a sentence too`() {
        val text = "준비됐나요? 네, 준비됐습니다! 그럼 시작합니다."
        val chunks = juneVoiceChunks(text, maxLength = 14)
        assertThat(chunks).containsExactly("준비됐나요?", "네, 준비됐습니다!", "그럼 시작합니다.").inOrder()
    }

    @Test
    fun `a mix of Korean and English is cut at the sentence ends`() {
        val text = "The build passed. 빌드가 통과했습니다. Please check the APK."
        val chunks = juneVoiceChunks(text, maxLength = 25)
        assertThat(chunks).containsExactly("The build passed.", "빌드가 통과했습니다.", "Please check the APK.").inOrder()
    }

    @Test
    fun `a speaker line of the usual length stays in one chunk`() {
        val text = "메모봇이 새 제안 두 건을 만들었습니다. 확인하시고 수락하거나 거절해 주세요."
        assertThat(juneVoiceChunks(text)).hasSize(1)
    }

    @Test
    fun `the longest line that can be read is cut in a few chunks`() {
        val text = "가나다라마바사아자차카타파하. ".repeat(10).trim()
        val chunks = juneVoiceChunks(text)
        assertThat(chunks.size).isAtLeast(2)
        chunks.forEach { assertThat(it.length).isAtMost(JUNE_VOICE_MAX_CHUNK) }
    }
}
