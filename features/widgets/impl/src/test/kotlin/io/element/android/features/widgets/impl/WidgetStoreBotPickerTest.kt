/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import androidx.test.platform.app.InstrumentationRegistry
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 앱15 B: the picker settings and the badge marks kept by [WidgetStore]. */
class WidgetStoreBotPickerTest : RobolectricTest() {
    private fun store() = WidgetStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun `a phone with only the old settings starts with 3 fixed and 2 flexible places`() {
        val store = store()
        store.saveBotPickerRoomIds("@me:test", listOf("!a:test", "!b:test"))
        store.saveBotPickerRadius(200f)
        assertEquals(BotPickerSlotCounts(fixed = 3, flexible = 2), store.getBotPickerSlotCounts())
    }

    @Test
    fun `the counts are saved and read back within their limits`() {
        val store = store()
        store.saveBotPickerSlotCounts(BotPickerSlotCounts(fixed = 4, flexible = 0))
        assertEquals(BotPickerSlotCounts(fixed = 4, flexible = 0), store.getBotPickerSlotCounts())
        store.saveBotPickerSlotCounts(BotPickerSlotCounts(fixed = 99, flexible = -1))
        assertEquals(BotPickerSlotCounts(fixed = MAX_BOT_PICKER_FIXED, flexible = 0), store.getBotPickerSlotCounts())
    }

    @Test
    fun `the fixed places are on top for a phone without the setting, and the choice is kept`() {
        val store = store()
        store.saveBotPickerSlotCounts(BotPickerSlotCounts(fixed = 3, flexible = 2))
        assertEquals(BotPickerFixedSide.Top, store.getBotPickerFixedSide())
        store.saveBotPickerFixedSide(BotPickerFixedSide.Bottom)
        assertEquals(BotPickerFixedSide.Bottom, store.getBotPickerFixedSide())
        store.saveBotPickerFixedSide(BotPickerFixedSide.Top)
        assertEquals(BotPickerFixedSide.Top, store.getBotPickerFixedSide())
    }

    @Test
    fun `the badge marks are kept per session`() {
        val store = store()
        val marks = mapOf("!a:test" to BotBadgeMark(2, 1_760_000_000_000L))
        store.saveBotBadgeMarks("@me:test", marks)
        assertEquals(marks, store.getBotBadgeMarks("@me:test"))
        assertEquals(emptyMap<String, BotBadgeMark>(), store.getBotBadgeMarks("@other:test"))
    }
}
