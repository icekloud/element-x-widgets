/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.june.memo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱17 D (관리자: "슬라이드로 탭 간 이동하는 기능은 폐기"): only the tab buttons change the tab. Uses nothing new of 앱17. */
class MemoTabSwipeGoneUiTest : RobolectricTest() {
    @Test
    fun `swiping sideways on the record tab no longer goes to the folder tab`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ElementTheme { MemoScreen(roomId = "!memo:test", useFakeData = true, timelineItems = persistentListOf()) }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.RECORD).fetchSemanticsNodes().isNotEmpty() }
        onRoot().performTouchInput { swipeLeft(startX = centerX + width * 0.3f, endX = centerX - width * 0.3f) }
        waitForIdle()
        // 앱11 추가요청 2 went to the folder tab here; now the record tab stays.
        assertTrue(onAllNodesWithTag(MemoTags.FOLDER_ROOT).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag(MemoTags.RECORD).fetchSemanticsNodes().isNotEmpty())
        // The tab buttons still switch.
        onNodeWithTag(MemoTags.TAB_FOLDERS).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.FOLDER_ROOT).fetchSemanticsNodes().isNotEmpty() }
    }
}
