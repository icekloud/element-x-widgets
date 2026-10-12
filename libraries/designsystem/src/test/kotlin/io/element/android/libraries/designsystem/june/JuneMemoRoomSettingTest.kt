/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.june

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Only the room picked as the memo bot room opens the memo screen: every other room keeps the chat (요구 A "다른 방 영향 0"). */
class JuneMemoRoomSettingTest : RobolectricTest() {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        JuneSettings.setMemoRoomId(context, null)
    }

    @After
    fun tearDown() {
        JuneSettings.setMemoRoomId(context, null)
    }

    @Test
    fun `no memo room picked, no room is the memo room`() {
        assertThat(JuneSettings.memoRoomId(context)).isNull()
        assertThat(JuneSettings.isMemoRoom(context, "!memo:server")).isFalse()
        assertThat(JuneSettings.isMemoRoom(context, "")).isFalse()
    }

    @Test
    fun `only the picked room is the memo room`() {
        JuneSettings.setMemoRoomId(context, "!memo:server")
        assertThat(JuneSettings.isMemoRoom(context, "!memo:server")).isTrue()
        assertThat(JuneSettings.isMemoRoom(context, "!other:server")).isFalse()
        assertThat(JuneSettings.isMemoRoom(context, "!memo:other")).isFalse()
    }

    @Test
    fun `a blank id or clearing the setting turns the memo room off`() {
        JuneSettings.setMemoRoomId(context, "!memo:server")
        JuneSettings.setMemoRoomId(context, " ")
        assertThat(JuneSettings.memoRoomId(context)).isNull()
        assertThat(JuneSettings.isMemoRoom(context, "!memo:server")).isFalse()
        JuneSettings.setMemoRoomId(context, "!memo:server")
        JuneSettings.setMemoRoomId(context, null)
        assertThat(JuneSettings.isMemoRoom(context, "!memo:server")).isFalse()
    }
}
