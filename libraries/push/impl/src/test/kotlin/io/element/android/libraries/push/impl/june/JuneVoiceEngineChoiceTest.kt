/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.june.JuneVoiceEngineChoice
import org.junit.Test

/**
 * Element June: the voice chosen to read the 🔊 lines. Only the phone engine and the Samsung voice are left: the Supertonic voice
 * was taken out of the app, and an install that had chosen it keeps a voice of the app, the Samsung one.
 */
class JuneVoiceEngineChoiceTest {
    @Test
    fun `only the phone engine and the Samsung voice can be chosen`() {
        assertThat(JuneVoiceEngineChoice.entries).containsExactly(JuneVoiceEngineChoice.Phone, JuneVoiceEngineChoice.Samsung).inOrder()
    }

    @Test
    fun `the phone engine stays the default`() {
        assertThat(JuneVoiceEngineChoice.parse(null)).isEqualTo(JuneVoiceEngineChoice.Phone)
        assertThat(JuneVoiceEngineChoice.parse("")).isEqualTo(JuneVoiceEngineChoice.Phone)
        assertThat(JuneVoiceEngineChoice.parse("something else")).isEqualTo(JuneVoiceEngineChoice.Phone)
    }

    @Test
    fun `the saved values are read back`() {
        assertThat(JuneVoiceEngineChoice.parse("phone")).isEqualTo(JuneVoiceEngineChoice.Phone)
        assertThat(JuneVoiceEngineChoice.parse("samsung")).isEqualTo(JuneVoiceEngineChoice.Samsung)
        JuneVoiceEngineChoice.entries.forEach { choice ->
            assertThat(JuneVoiceEngineChoice.parse(choice.key)).isEqualTo(choice)
        }
    }

    @Test
    fun `an install that had chosen the removed Supertonic voice reads with the Samsung voice`() {
        // The key the settings saved for that voice
        assertThat(JuneVoiceEngineChoice.parse("builtin")).isEqualTo(JuneVoiceEngineChoice.Samsung)
    }

    @Test
    fun `pressing the setting goes from the phone engine to the Samsung voice and back`() {
        assertThat(JuneVoiceEngineChoice.Phone.next()).isEqualTo(JuneVoiceEngineChoice.Samsung)
        assertThat(JuneVoiceEngineChoice.Samsung.next()).isEqualTo(JuneVoiceEngineChoice.Phone)
    }

    @Test
    fun `nothing shown names the removed voice`() {
        JuneVoiceEngineChoice.entries.forEach { choice ->
            assertThat(choice.label).doesNotContain("내장")
            assertThat(choice.label).doesNotContain("슈퍼토닉")
            assertThat(choice.shortLabel).doesNotContain("내장")
        }
        assertThat(JuneVoiceEngineChoice.Samsung.label).isEqualTo("삼성 음성 (한국어)")
        assertThat(JuneVoiceEngineChoice.Samsung.shortLabel).isEqualTo("삼성")
    }
}
