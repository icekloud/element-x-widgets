/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.june.memo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱19 ②: the tag sheet shows the tags as chips; the small ✕ takes one away in one press; "#" after a space makes a chip. */
class MemoTagEditorUiTest : RobolectricTest() {
    private fun AndroidComposeUiTest<ComponentActivity>.editor(tags: List<String>, known: List<String> = emptyList(), onSave: (List<String>) -> Unit = {}) {
        setContent {
            ElementTheme {
                MemoTagEditor(
                    title = "태그 편집",
                    tags = persistentListOf(*tags.toTypedArray()),
                    known = persistentListOf(*known.toTypedArray()),
                    onSave = onSave,
                    onCancel = {},
                )
            }
        }
        waitForIdle()
    }

    private fun AndroidComposeUiTest<ComponentActivity>.chips(): Int = onAllNodesWithTag(MemoTags.TAG_CHIP, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `the tags there are open as chips, each with its remove button`() = runAndroidComposeUiTest<ComponentActivity> {
        editor(listOf("일상", "지도"))
        assertEquals(2, chips())
        onNodeWithText("#일상", useUnmergedTree = true).assertExists()
        onNodeWithText("#지도", useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("일상 태그 제거").assertExists()
        onNodeWithContentDescription("지도 태그 제거").assertExists()
        // The input is empty: the tags are not text any more.
        onNodeWithText("#일상 #지도").assertDoesNotExist()
    }

    @Test
    fun `one press on the small x removes the tag at once, the chip itself does nothing`() = runAndroidComposeUiTest<ComponentActivity> {
        var saved: List<String>? = null
        editor(listOf("일상", "지도", "카페")) { saved = it }
        // 앱23 (결정기록 9): the chip's own text starts under the ✕ box (22dp, over the top left corner), so the
        // middle of the text node can land inside it. Pressed where only the chip is, nothing happens.
        onNodeWithText("#지도", useUnmergedTree = true).performTouchInput { click(centerRight) }
        waitForIdle()
        assertEquals(3, chips())
        onNodeWithContentDescription("지도 태그 제거").performClick()
        waitForIdle()
        assertEquals(2, chips())
        onNodeWithText("#지도", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(onAllNodesWithContentDescription("지도 태그 제거").fetchSemanticsNodes().isEmpty())
        onNodeWithText("저장").performClick()
        assertEquals(listOf("일상", "카페"), saved)
    }

    @Test
    fun `a hash after a space turns the typed tag into a chip, what is left is saved too`() = runAndroidComposeUiTest<ComponentActivity> {
        var saved: List<String>? = null
        editor(listOf("일상")) { saved = it }
        onNodeWithTag(MemoTags.TAG_INPUT).performTextInput("#지도 #")
        waitForIdle()
        assertEquals(2, chips())
        onNodeWithContentDescription("지도 태그 제거").assertExists()
        // A tag already a chip stays one chip.
        onNodeWithTag(MemoTags.TAG_INPUT).performTextInput("일상 #")
        waitForIdle()
        assertEquals(2, chips())
        // Left in the input when 저장 is pressed: a tag as before (no # needed).
        onNodeWithTag(MemoTags.TAG_INPUT).performTextInput("카페")
        onNodeWithText("저장").performClick()
        assertEquals(listOf("일상", "지도", "카페"), saved)
    }

    @Test
    fun `a known tag picked below becomes a chip and leaves the pick row`() = runAndroidComposeUiTest<ComponentActivity> {
        editor(listOf("일상"), known = listOf("일상", "여행"))
        // A tag already there is not offered.
        assertTrue(onAllNodesWithContentDescription("여행 태그 제거").fetchSemanticsNodes().isEmpty())
        onNodeWithText("#여행").performClick()
        waitForIdle()
        assertEquals(2, chips())
        onNodeWithContentDescription("여행 태그 제거").assertExists()
    }
}
