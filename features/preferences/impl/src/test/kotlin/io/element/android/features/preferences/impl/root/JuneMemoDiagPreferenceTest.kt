/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.root

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 앱28 추가요청 1: the memo diagnosis item shows what the memo sync wrote, or that nothing was written yet. */
class JuneMemoDiagPreferenceTest {
    @Test
    fun `no diagnosis yet says so`() {
        assertThat(juneMemoDiagSupportingText(null)).isEqualTo(JUNE_MEMO_DIAG_EMPTY)
        assertThat(juneMemoDiagSupportingText("  ")).isEqualTo(JUNE_MEMO_DIAG_EMPTY)
    }

    @Test
    fun `the lines of the memo sync are shown as they are`() {
        val text = "기준 11:24:25\n사진 올리기: 대기 0 · 실패 0"
        assertThat(juneMemoDiagSupportingText(text)).isEqualTo(text)
    }
}
