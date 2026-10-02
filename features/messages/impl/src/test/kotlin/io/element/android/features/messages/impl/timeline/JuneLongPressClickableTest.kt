/*
 * Copyright (c) 2026 Element June.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline

import androidx.activity.ComponentActivity
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class JuneLongPressClickableTest : RobolectricTest() {
    private var clicks = 0
    private var longClicks = 0
    private var haptics = 0

    @Test
    fun `a tap runs onClick only`() = runAndroidComposeUiTest<ComponentActivity> {
        setButton()
        onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(100)
            up()
        }
        waitForIdle()
        assertThat(clicks).isEqualTo(1)
        assertThat(longClicks).isEqualTo(0)
        assertThat(haptics).isEqualTo(0)
    }

    @Test
    fun `holding 450 ms is still a tap`() = runAndroidComposeUiTest<ComponentActivity> {
        setButton()
        onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(450)
            up()
        }
        waitForIdle()
        assertThat(clicks).isEqualTo(1)
        assertThat(longClicks).isEqualTo(0)
        assertThat(haptics).isEqualTo(0)
    }

    @Test
    fun `releasing at exactly 500 ms is a long press, not a tap`() = runAndroidComposeUiTest<ComponentActivity> {
        setButton()
        onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(500)
            up()
        }
        waitForIdle()
        assertThat(clicks).isEqualTo(0)
        assertThat(longClicks).isEqualTo(1)
        assertThat(haptics).isEqualTo(1)
    }

    @Test
    fun `holding 800 ms runs the long press once and no click`() = runAndroidComposeUiTest<ComponentActivity> {
        setButton()
        onNodeWithTag(TAG).performTouchInput { longClick(durationMillis = 800) }
        waitForIdle()
        assertThat(clicks).isEqualTo(0)
        assertThat(longClicks).isEqualTo(1)
        assertThat(haptics).isEqualTo(1)
    }

    @Test
    fun `accessibility exposes the click and the labelled long click`() = runAndroidComposeUiTest<ComponentActivity> {
        setButton()
        onNodeWithTag(TAG).assert(
            SemanticsMatcher("long click label") { it.config[SemanticsActions.OnLongClick].label == LABEL }
        )
        onNodeWithTag(TAG).performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithTag(TAG).performSemanticsAction(SemanticsActions.OnLongClick)
        waitForIdle()
        assertThat(clicks).isEqualTo(1)
        assertThat(longClicks).isEqualTo(1)
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setButton() {
        setContent {
            val handler = remember {
                JuneLongPressHandler(
                    onClick = { clicks++ },
                    onLongClick = { longClicks++ },
                    onLongPressReached = { haptics++ },
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .juneLongPressClickable(
                        handler = handler,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onAccessibilityClick = { clicks++ },
                        onAccessibilityLongClick = { longClicks++ },
                        longClickLabel = LABEL,
                    )
                    .testTag(TAG)
            )
        }
    }

    private companion object {
        const val TAG = "button"
        const val LABEL = "길게 누르면 맨 아래로 이동"
    }
}
