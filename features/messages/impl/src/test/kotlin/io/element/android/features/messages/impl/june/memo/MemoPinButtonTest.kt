/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱16 B: when the open note shows its pin button, what it says and the banner after a press. Made up notes. */
class MemoPinButtonTest : RobolectricTest() {
    @Test
    fun `every note that can be read gets the button, filled when pinned`() {
        val off = memoPinButton(pinned = false, trashed = false, editing = false, selecting = false)
        assertEquals(MemoPinButton(pinned = false, enabled = true), off)
        assertEquals("메모 고정", off?.description)
        val on = memoPinButton(pinned = true, trashed = false, editing = false, selecting = false)
        assertEquals(MemoPinButton(pinned = true, enabled = true), on)
        assertEquals("고정 해제", on?.description)
    }

    @Test
    fun `no button while editing, a button that does nothing in the selection mode`() {
        assertNull(memoPinButton(pinned = false, trashed = false, editing = true, selecting = false))
        assertNull(memoPinButton(pinned = true, trashed = false, editing = true, selecting = false))
        assertEquals(MemoPinButton(pinned = false, enabled = false), memoPinButton(pinned = false, trashed = false, editing = false, selecting = true))
    }

    @Test
    fun `a note in the trash can only lose a pin it still has`() {
        assertNull(memoPinButton(pinned = false, trashed = true, editing = false, selecting = false))
        assertEquals(MemoPinButton(pinned = true, enabled = true), memoPinButton(pinned = true, trashed = true, editing = false, selecting = false))
    }

    @Test
    fun `the banner says what happened`() {
        assertEquals("고정했습니다", memoPinUndoText(pinnedNow = true))
        assertEquals("고정을 해제했습니다", memoPinUndoText(pinnedNow = false))
    }

    @Test
    fun `a note is in the trash by the phone mark or by its folder`() {
        fun note(header: JSONObject) = MemoNote("n1", header, "본문", emptyList(), truncated = false)
        assertFalse(note(JSONObject().put("폴더", MEMO_INBOX).put("삭제", MEMO_DELETED_NONE)).trashed)
        assertTrue(note(JSONObject().put("폴더", MEMO_INBOX).put("삭제", MEMO_DELETED_TRASH)).trashed)
        assertTrue(note(JSONObject().put("폴더", MEMO_TRASH)).trashed)
        assertFalse(note(JSONObject()).trashed)
    }
}
