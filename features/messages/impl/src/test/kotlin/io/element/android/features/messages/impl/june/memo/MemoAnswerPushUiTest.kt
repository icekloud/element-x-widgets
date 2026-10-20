/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.june.memo

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

/** 앱25 요구 ④: a card whose immediate push did not reach the server says so in its line (no new screen). Made up data. */
class MemoAnswerPushUiTest : RobolectricTest() {
    @Test
    fun `a card whose answer did not reach the server says it will try again`() = runAndroidComposeUiTest<ComponentActivity> {
        MemoAnswerFailures.clear(listOf("ui-fail", "ui-sending"))
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val controller = remember { MemoController(MemoStore(FakeMemoBackend()), scope, mutableStateOf(MemoLocation())) }
                androidx.compose.foundation.layout.Column {
                    MemoProposalCard(controller, MemoProposal("ui-sending", "분류", "가짜 태그 하나", MEMO_PROPOSAL_SENDING))
                }
            }
        }
        onNodeWithTag(MemoTags.PROPOSAL_SENDING).assertIsDisplayed()
        onNodeWithText("처리 중 · 메모봇에 전하는 중").assertIsDisplayed()
        MemoAnswerFailures.failed(listOf("ui-sending"))
        waitForIdle()
        onNodeWithText("처리 중 · 서버에 닿지 못함 · 다시 시도해요").assertIsDisplayed()
        MemoAnswerFailures.clear(listOf("ui-sending"))
        waitForIdle()
        onNodeWithText("처리 중 · 메모봇에 전하는 중").assertIsDisplayed()
    }
}
