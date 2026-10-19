/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.home.impl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.june.JuneMemoButtonSpot
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test

/** 앱19 추가요청 1: a tap opens the memo room; a long press and a drag move the button, and then the room does not open. */
class JuneMovableMemoButtonTest : RobolectricTest() {
    @Test
    fun `a short tap opens the memo room and does not move the button`() = runAndroidComposeUiTest {
        var clicks = 0
        val moves = mutableListOf<JuneMemoButtonSpot>()
        setSafeContent { JuneMovableMemoButton(spot = null, onMove = { moves += it }, onClick = { clicks++ }) }

        onNodeWithTag(JUNE_HOME_MEMO_BUTTON).performClick()
        waitForIdle()

        assertThat(clicks).isEqualTo(1)
        assertThat(moves).isEmpty()
    }

    @Test
    fun `a long press and a drag move the button and do not open the memo room`() = runAndroidComposeUiTest {
        var clicks = 0
        val moves = mutableListOf<JuneMemoButtonSpot>()
        setSafeContent { JuneMovableMemoButton(spot = null, onMove = { moves += it }, onClick = { clicks++ }) }

        onNodeWithTag(JUNE_HOME_MEMO_BUTTON).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            moveBy(Offset(-100f, -200f))
            moveBy(Offset(-100f, -200f))
            up()
        }
        waitForIdle()

        assertThat(clicks).isEqualTo(0)
        assertThat(moves).hasSize(1)
        // up and to the left of the default place (bottom centre)
        assertThat(moves.single().x).isLessThan(JuneMemoButtonSpot.DEFAULT.x)
        assertThat(moves.single().y).isLessThan(JuneMemoButtonSpot.DEFAULT.y)
    }

    @Test
    fun `a moved button still opens the memo room with a tap`() = runAndroidComposeUiTest {
        var clicks = 0
        setSafeContent { JuneMovableMemoButton(spot = JuneMemoButtonSpot(0.2f, 0.3f), onMove = {}, onClick = { clicks++ }) }

        onNodeWithTag(JUNE_HOME_MEMO_BUTTON).performClick()
        waitForIdle()

        assertThat(clicks).isEqualTo(1)
    }
}
