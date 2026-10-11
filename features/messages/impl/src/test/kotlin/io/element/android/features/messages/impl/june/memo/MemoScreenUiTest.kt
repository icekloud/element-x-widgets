/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.june.memo

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class MemoScreenUiTest : RobolectricTest() {
    @Test
    fun `tabs switch without adding a back step, the folder tab shows the pending proposal badge`() = runAndroidComposeUiTest<ComponentActivity> {
        var tab by mutableStateOf(MemoTab.Record)
        setContent { ElementTheme { MemoTabBar(selected = tab, pendingProposals = 2, onSelect = { tab = it }) } }
        onNodeWithText("기록").assertIsDisplayed()
        onNodeWithText("폴더").assertIsDisplayed()
        onNodeWithText("검색").assertIsDisplayed()
        // The clickable tab merges its children: the badge is a node of the unmerged tree only.
        onNodeWithTag(MemoTags.FOLDER_BADGE, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(MemoTags.TAB_SEARCH).performClick()
        assertThat(tab).isEqualTo(MemoTab.Search)
        onNodeWithTag(MemoTags.TAB_FOLDERS).performClick()
        assertThat(tab).isEqualTo(MemoTab.Folders)
        // Only the tab changed: nothing to go back to inside the folder tab root but the record tab.
        assertThat(MemoLocation(tab = tab).back()).isEqualTo(MemoBack.To(MemoLocation(tab = MemoTab.Record)))
    }

    @Test
    fun `no badge without pending proposals`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent { ElementTheme { MemoTabBar(selected = MemoTab.Record, pendingProposals = 0, onSelect = {}) } }
        onAllNodesWithTag(MemoTags.FOLDER_BADGE, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `proposal band card shows its buttons only while waiting`() = runAndroidComposeUiTest<ComponentActivity> {
        val sent = mutableListOf<String>()
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val controller = MemoController(MemoStore(FakeMemoBackend()), scope, mutableStateOf(MemoLocation()))
                androidx.compose.foundation.layout.Column {
                    MemoProposalCard(controller, MemoProposal("p1", "일정추가", "10/5 빵집", "대기", noteId = "n1"))
                    MemoProposalCard(controller, MemoProposal("p2", "폴더생성", "영상 5건", "대기", folder = "영상"))
                    MemoProposalCard(controller, MemoProposal("p3", "일정추가", "다시 보는 중", "재검토"))
                }
                sent += "drawn"
            }
        }
        onAllNodesWithTag(MemoTags.BAND).assertCountEquals(3)
        onNodeWithText("다른 날").assertIsDisplayed()
        onNodeWithText("만들기").assertIsDisplayed()
        onNodeWithText("기존 폴더로").assertIsDisplayed()
        onNodeWithText("플랜봇 재검토 중").assertIsDisplayed()
        onAllNodesWithTag(MemoTags.BAND).assertCountEquals(3)
        assertThat(sent).isNotEmpty()
    }

    @Test
    fun `tile has three rows, at most four tags plus the rest`() = runAndroidComposeUiTest<ComponentActivity> {
        val item = MemoItem(
            id = "a",
            kind = "텍스트",
            title = "환전 10만엔 더",
            preview = MemoPreview.Text("공항 말고 시내에서"),
            date = "2026-10-11T14:30:12+09:00",
            tags = listOf("환전", "할일", "교토", "이번주", "여행준비", "돈"),
            folder = "여행",
            pinned = true,
        )
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                MemoTile(MemoController(MemoStore(FakeMemoBackend()), scope, mutableStateOf(MemoLocation())), item)
            }
        }
        onNodeWithTag(MemoTags.TILE_PREVIEW, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("공항 말고 시내에서", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(MemoTags.TILE_TITLE, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("10-11 · 📝 텍스트", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("#이번주", useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithTag(MemoTags.TILE_TAGS, useUnmergedTree = true).assertCountEquals(1)
        onNodeWithText("+2", useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithText("#여행준비").assertCountEquals(0)
        onNodeWithText("📌", useUnmergedTree = true).assertIsDisplayed()
        assertThat(memoVisibleTags(item.tags, 4)).isEqualTo(listOf("환전", "할일", "교토", "이번주") to 2)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithText(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true)
}
