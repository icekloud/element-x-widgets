/*
 * Copyright (c) 2026 Element June contributors.
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** 앱13 A·B: the record card dates and the editor without its head, with small title and tag fields. Made up data. */
class MemoEditScreenUiTest : RobolectricTest() {
    private fun backend(): MemoLocalBackend {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoLocalBackend({ MemoDb(driver) }, dispatcher = EmptyCoroutineContext)
    }

    @Test
    fun `the record card draws its dates apart from the title`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        runBlocking { backend.create("가슴 운동 세트별 중량 · 횟수 기록") }
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val controller = MemoController(MemoStore(backend), scope, mutableStateOf(MemoLocation()))
                val version by MemoLocalChanges.version.collectAsState()
                MemoRecordTab(controller, version)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.RECORD_ROW).fetchSemanticsNodes().size == 1 }
        onNodeWithTag(MemoTags.RECORD_DATE, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(MemoTags.RECORD_HEAD, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the editor has no head line, and its title field is far smaller than the body`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val store = MemoStore(backend)
        val id = runBlocking {
            backend.create("본문 첫 줄").also { store.loadNote(it) }
        }
        MemoEditHub.drop(id)
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val controller = MemoController(store, scope, mutableStateOf(MemoLocation().openNote(id).startEditing()))
                MemoNoteScreen(controller, id)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.EDIT_BODY).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithTag(MemoTags.NOTE_HEAD).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag(MemoTags.NOTE_DATE, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val title = onNodeWithTag(MemoTags.EDIT_TITLE).fetchSemanticsNode().size.height
        val body = onNodeWithTag(MemoTags.EDIT_BODY).fetchSemanticsNode().size.height
        val oldField = MEMO_OLD_FIELD_DP * density.density
        assertTrue("title $title px, 앱12 field $oldField px", title < oldField * 0.6f)
        assertTrue("title $title px, body $body px", body > title * 3)
        MemoEditHub.drop(id)
    }
}
