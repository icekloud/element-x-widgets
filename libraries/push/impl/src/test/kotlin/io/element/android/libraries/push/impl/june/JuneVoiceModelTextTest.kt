/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.Normalizer

class JuneVoiceModelTextTest {
    @Test
    fun `the text is wrapped in the language tags`() {
        assertThat(juneVoiceModelText("안녕하세요.")).startsWith("<ko>")
        assertThat(juneVoiceModelText("안녕하세요.")).endsWith("</ko>")
    }

    /**
     * The model counts the letters Korean syllables are made of, not the syllables: without this the line is read far too fast.
     * "안" is one syllable but three letters, so the text given to the model has to be longer than what was written.
     */
    @Test
    fun `korean syllables are split into the letters they are made of`() {
        val text = juneVoiceModelText("안녕")
        val inside = text.removePrefix("<ko>").removeSuffix("</ko>")
        // 안녕 = ㅇㅏㄴ + ㄴㅕㅇ = 6 letters, plus the full stop that is added
        assertThat(inside.length).isEqualTo(7)
        assertThat(inside.codePointCount(0, inside.length)).isEqualTo(7)
    }

    @Test
    fun `a sentence that ends with nothing gets a full stop`() {
        assertThat(juneVoiceModelText("회의 있음")).endsWith(".</ko>")
        assertThat(juneVoiceModelText("회의 있음?")).endsWith("?</ko>")
        assertThat(juneVoiceModelText("회의 있음.")).endsWith(".</ko>")
    }

    @Test
    fun `symbols the model cannot read are replaced or dropped`() {
        // The heart is dropped without leaving a space, so "d" and "e" end up together, as in the reference implementation
        val inside = juneVoiceModelText("a_b/c#d♥e").removePrefix("<ko>").removeSuffix("</ko>")
        assertThat(inside).isEqualTo("a b c de.")
    }

    @Test
    fun `an at sign is read as a word`() {
        assertThat(juneVoiceModelText("a@b")).isEqualTo("<ko>a at b.</ko>")
    }

    @Test
    fun `repeated spaces and spaces before punctuation are tidied`() {
        // The text is compared after the same NFKD normalisation, since the syllables are split into letters
        val expected = Normalizer.normalize("<ko>회의 있음.</ko>", Normalizer.Form.NFKD)
        assertThat(juneVoiceModelText("회의   있음 .")).isEqualTo(expected)
    }

    @Test
    fun `an empty chunk gives nothing`() {
        assertThat(juneVoiceModelText("   ")).isEmpty()
    }
}
