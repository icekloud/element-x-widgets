/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.june

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class JuneSwipeSteerRowTest : RobolectricTest() {
    private var steers = 0
    private var taps = 0
    private var answer: ((Boolean) -> Unit)? = null

    @Test
    fun `pushing the row all the way to the left steers it once`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow()
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
        waitForIdle()
        assertThat(steers).isEqualTo(1)
    }

    @Test
    fun `pushing past the threshold steers, short of it does not`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow()
        // 20 % of the width (minus the touch slop): goes back, nothing sent
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.2f) }
        waitForIdle()
        assertThat(steers).isEqualTo(0)
        // 70 %: sent
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.7f) }
        waitForIdle()
        assertThat(steers).isEqualTo(1)
    }

    @Test
    fun `pushing to the right does nothing`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow()
        onNodeWithTag(ROW).performTouchInput { swipeRight(startX = left + 1f, endX = right - 1f) }
        waitForIdle()
        assertThat(steers).isEqualTo(0)
    }

    @Test
    fun `a blocked row (edited, busy, not steerable) cannot be pushed`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow(canSwipe = false)
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
        waitForIdle()
        assertThat(steers).isEqualTo(0)
    }

    @Test
    fun `a vertical drag scrolls the panel and does not steer`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow()
        onNodeWithTag(ROW).performTouchInput { swipeUp() }
        waitForIdle()
        assertThat(steers).isEqualTo(0)
    }

    @Test
    fun `tapping the menu button in the row still works`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow()
        onNodeWithTag(MENU).performClick()
        waitForIdle()
        assertThat(taps).isEqualTo(1)
        assertThat(steers).isEqualTo(0)
    }

    @Test
    fun `a failed request lets the row be pushed again`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow()
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
        waitForIdle()
        // Waiting for the gateway: a second push does not send again
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
        waitForIdle()
        assertThat(steers).isEqualTo(1)
        runOnIdle { answer?.invoke(false) }
        waitForIdle()
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
        waitForIdle()
        assertThat(steers).isEqualTo(2)
    }

    @Test
    fun `accessibility services get a steer action, unless the row is blocked`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow()
        onNodeWithTag(ROW).assert(
            SemanticsMatcher("steer action") { node ->
                node.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.any { it.label == A11Y_LABEL }
            }
        )
        val action = onNodeWithTag(ROW).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == A11Y_LABEL }
        runOnIdle { action.action() }
        waitForIdle()
        assertThat(steers).isEqualTo(1)
    }

    @Test
    fun `a blocked row has no steer action`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow(canSwipe = false)
        onNodeWithTag(ROW).assert(
            SemanticsMatcher("no steer action") { node ->
                node.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.none { it.label == A11Y_LABEL }
            }
        )
    }

    @Test
    fun `a sensitive setting (20 percent) steers with a short push`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow(fraction = 0.2f)
        // 10 %: not enough
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.1f) }
        waitForIdle()
        assertThat(steers).isEqualTo(0)
        // 35 %: enough at 20 % (it would not be at the default 40 %)
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.35f) }
        waitForIdle()
        assertThat(steers).isEqualTo(1)
    }

    @Test
    fun `a hard setting (70 percent) needs a long push`() = runAndroidComposeUiTest<ComponentActivity> {
        setRow(fraction = 0.7f)
        // 55 %: enough at the default 40 %, not at 70 %
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.55f) }
        waitForIdle()
        assertThat(steers).isEqualTo(0)
        // 90 %: enough
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.9f) }
        waitForIdle()
        assertThat(steers).isEqualTo(1)
    }

    @Test
    fun `a changed setting applies from the next push, without a new row`() = runAndroidComposeUiTest<ComponentActivity> {
        val fraction = mutableFloatStateOf(0.7f)
        setRowLive(fraction = { fraction.floatValue })
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.35f) }
        waitForIdle()
        assertThat(steers).isEqualTo(0)
        fraction.floatValue = 0.2f
        waitForIdle()
        onNodeWithTag(ROW).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.35f) }
        waitForIdle()
        assertThat(steers).isEqualTo(1)
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setRow(canSwipe: Boolean = true, fraction: Float = 0.4f) =
        setRowLive(canSwipe = canSwipe, fraction = { fraction })

    private fun AndroidComposeUiTest<ComponentActivity>.setRowLive(canSwipe: Boolean = true, fraction: () -> Float) {
        setContent {
            Column(modifier = Modifier.width(360.dp).heightIn(max = 80.dp).verticalScroll(rememberScrollState())) {
                JuneSwipeSteerRow(
                    canSwipe = canSwipe,
                    fraction = fraction(),
                    onSteer = { onDone ->
                        steers++
                        answer = onDone
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.testTag(ROW),
                ) {
                    Box(Modifier.weight(1f).height(48.dp))
                    Box(
                        Modifier
                            .size(40.dp)
                            .clickable { taps++ }
                            .testTag(MENU)
                    )
                }
                // More rows below, so that the panel can scroll
                Box(Modifier.height(200.dp))
            }
        }
    }

    private companion object {
        const val ROW = "row"
        const val MENU = "menu"
        const val A11Y_LABEL = "스티어링으로 보내기(왼쪽으로 밀기)"
    }
}
