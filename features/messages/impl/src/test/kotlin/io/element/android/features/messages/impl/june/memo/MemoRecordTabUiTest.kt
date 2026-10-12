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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** v2 (A3): the record tab reads the local notes, so what the composer saves shows at once, without the server. */
class MemoRecordTabUiTest : RobolectricTest() {
    @Test
    fun `record tab shows the local notes at once and never a waiting line`() = runAndroidComposeUiTest<ComponentActivity> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        val backend = MemoLocalBackend({ MemoDb(driver) }, dispatcher = EmptyCoroutineContext)
        runBlocking { backend.create("교토 빵집 메모") }
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val controller = MemoController(MemoStore(backend), scope, mutableStateOf(MemoLocation()))
                val version by MemoLocalChanges.version.collectAsState()
                MemoRecordTab(controller, version)
            }
        }
        waitUntil(5_000) { onAllNodesWithTag(MemoTags.RECORD_ROW).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("교토 빵집 메모", substring = true).assertIsDisplayed()
        onNodeWithText("📥 받은편지함").assertIsDisplayed()
        // A second note typed below (airplane mode: no server at all) shows too, through the local change signal.
        runBlocking { backend.create("환전 10만엔") }
        waitUntil(5_000) { onAllNodesWithTag(MemoTags.RECORD_ROW).fetchSemanticsNodes().size == 2 }
        onNodeWithText("환전 10만엔", substring = true).assertIsDisplayed()
        // No line waits for a bot answer anywhere (the old "⏳ 메모봇 처리 중…").
        onAllNodesWithText("처리 중", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
}
