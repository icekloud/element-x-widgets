/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱15 E·G: the size of the open note title and the room of the 편집 box. */
class MemoNoteHeadStyleTest {
    @Test
    fun `the open note title is three quarters of the old size`() {
        val small = memoNoteHeadTitleStyle(TextStyle(fontSize = 16.sp, lineHeight = 22.sp))
        assertEquals(12f, small.fontSize.value, 0.001f)
        assertEquals(16.5f, small.lineHeight.value, 0.001f)
        assertEquals(0.75f, MEMO_NOTE_HEAD_TITLE_SCALE, 0.001f)
        assertEquals(2, MEMO_HEAD_TITLE_LINES)
    }

    @Test
    fun `the edit box is a comfortable touch and the body keeps room for it`() {
        assertTrue(MEMO_EDIT_BOX_HEIGHT_DP >= 48)
        assertEquals(MEMO_EDIT_BOX_HEIGHT_DP + 2 * MEMO_EDIT_BOX_MARGIN_DP, MEMO_EDIT_BOX_ROOM_DP)
    }
}
