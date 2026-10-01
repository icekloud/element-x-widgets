/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.june

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.components.ExpandableBottomSheetLayout
import io.element.android.libraries.designsystem.components.rememberExpandableBottomSheetLayoutState
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

private const val TAG_TOP = "june_top"
private const val TAG_COMPOSER = "june_composer"
private const val TAG_STACK = "june_stack"

class JuneComposerStackTest : RobolectricTest() {
    @Test
    fun `top area gets what the composer leaves`() {
        assertThat(juneTopAreaMaxHeight(availableHeight = 240, topNaturalHeight = 300, bottomMinHeight = 60, peekHeight = 48)).isEqualTo(180)
    }

    @Test
    fun `top area keeps its own height when everything fits`() {
        assertThat(juneTopAreaMaxHeight(availableHeight = 400, topNaturalHeight = 100, bottomMinHeight = 60, peekHeight = 48)).isEqualTo(100)
    }

    @Test
    fun `top area keeps a peek row when the composer alone fills the sheet`() {
        assertThat(juneTopAreaMaxHeight(availableHeight = 240, topNaturalHeight = 300, bottomMinHeight = 260, peekHeight = 48)).isEqualTo(48)
        assertThat(juneTopAreaMaxHeight(availableHeight = 240, topNaturalHeight = 20, bottomMinHeight = 260, peekHeight = 48)).isEqualTo(20)
    }

    @Test
    fun `top area is not limited when the height is not bounded`() {
        assertThat(juneTopAreaMaxHeight(availableHeight = Constraints.Infinity, topNaturalHeight = 300, bottomMinHeight = 60, peekHeight = 48))
            .isEqualTo(300)
    }

    @Test
    fun `nothing above the composer gives no top area`() {
        assertThat(juneTopAreaMaxHeight(availableHeight = 240, topNaturalHeight = 0, bottomMinHeight = 60, peekHeight = 48)).isEqualTo(0)
    }

    // Problem B reproduced in the real composer sheet: panels + pending pictures (300dp) and the input (60dp) in a sheet capped at 240dp
    // (half of the height left above the keyboard). The old Column measured the panels first and left the input no height at all.
    @Test
    fun `old column layout leaves the composer no room in a capped sheet`() = runAndroidComposeUiTest {
        setInComposerSheet(maxSheetHeight = 240.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(300.dp).testTag(TAG_TOP))
                Box(Modifier.fillMaxWidth().height(60.dp).testTag(TAG_COMPOSER))
            }
        }
        onNodeWithTag(TAG_COMPOSER).assertHeightIsEqualTo(0.dp)
    }

    @Test
    fun `stack keeps the whole composer visible at the bottom of a capped sheet`() = runAndroidComposeUiTest {
        setInComposerSheet(maxSheetHeight = 240.dp) {
            JuneComposerStack(
                modifier = Modifier.fillMaxWidth(),
                top = { Box(Modifier.fillMaxWidth().height(300.dp).testTag(TAG_TOP)) },
                bottom = { Box(Modifier.fillMaxWidth().height(60.dp).testTag(TAG_COMPOSER)) },
            )
        }
        onNodeWithTag(TAG_COMPOSER)
            .assertHeightIsEqualTo(60.dp)
            .assertTopPositionInRootIsEqualTo(SCREEN_HEIGHT - 60.dp)
        // The panels start at the top of the sheet and scroll inside the 180dp left to them.
        onNodeWithTag(TAG_TOP).assertTopPositionInRootIsEqualTo(SCREEN_HEIGHT - 240.dp)
    }

    @Test
    fun `stack shows everything when it fits, like the column it replaces`() = runAndroidComposeUiTest {
        setInComposerSheet(maxSheetHeight = 300.dp) {
            JuneComposerStack(
                modifier = Modifier.fillMaxWidth(),
                top = { Box(Modifier.fillMaxWidth().height(100.dp).testTag(TAG_TOP)) },
                bottom = { Box(Modifier.fillMaxWidth().height(60.dp).testTag(TAG_COMPOSER)) },
            )
        }
        onNodeWithTag(TAG_TOP)
            .assertHeightIsEqualTo(100.dp)
            .assertTopPositionInRootIsEqualTo(SCREEN_HEIGHT - 160.dp)
        onNodeWithTag(TAG_COMPOSER)
            .assertHeightIsEqualTo(60.dp)
            .assertTopPositionInRootIsEqualTo(SCREEN_HEIGHT - 60.dp)
    }

    @Test
    fun `stack reports the whole content as its minimum height`() = runAndroidComposeUiTest {
        setContent {
            Column(modifier = Modifier.height(IntrinsicSize.Min)) {
                JuneComposerStack(
                    modifier = Modifier.fillMaxWidth().testTag(TAG_STACK),
                    top = { Box(Modifier.fillMaxWidth().height(100.dp)) },
                    bottom = { Box(Modifier.fillMaxWidth().height(60.dp)) },
                )
            }
        }
        onNodeWithTag(TAG_STACK).assertHeightIsEqualTo(160.dp)
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setInComposerSheet(
        maxSheetHeight: Dp,
        sheetContent: @Composable () -> Unit,
    ) {
        setContent {
            Box(Modifier.fillMaxWidth().height(SCREEN_HEIGHT)) {
                ExpandableBottomSheetLayout(
                    sheetDragHandle = {},
                    bottomSheetContent = { sheetContent() },
                    state = rememberExpandableBottomSheetLayoutState(),
                    maxBottomSheetContentHeight = maxSheetHeight,
                    isSwipeGestureEnabled = false,
                    content = { Box(Modifier.fillMaxSize()) },
                )
            }
        }
    }

    private companion object {
        // Below Robolectric's default screen height (470dp), so the box is not shrunk by the window.
        val SCREEN_HEIGHT = 360.dp
    }
}
