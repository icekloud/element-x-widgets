/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.june.memo

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
                Box {
                    MemoNoteScreen(controller, id)
                    // 앱14 D: the 앱12 title field (design system TextField with its label), drawn over the editor only to
                    // be measured; it takes no room from it.
                    TextField(value = "제목", onValueChange = {}, label = "제목", singleLine = true, modifier = Modifier.fillMaxWidth().testTag(OLD_FIELD))
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.EDIT_BODY).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithTag(MemoTags.NOTE_HEAD).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithTag(MemoTags.NOTE_DATE, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val title = onNodeWithTag(MemoTags.EDIT_TITLE).fetchSemanticsNode().size.height
        val tags = onNodeWithTag(MemoTags.EDIT_TAGS).fetchSemanticsNode().size.height
        val body = onNodeWithTag(MemoTags.EDIT_BODY).fetchSemanticsNode().size.height
        val oldField = onNodeWithTag(OLD_FIELD).fetchSemanticsNode().size.height
        // About half of the 앱12 field, measured on the same screen (no estimated constant).
        assertTrue("title $title px, 앱12 field $oldField px", title in (oldField * 0.25f).toInt()..(oldField * 0.6f).toInt())
        assertEquals("title and tags", title, tags)
        // The set height: 37 dp at font scale 1.
        val expected = memoEditFieldHeight(density.fontScale) * density.density
        assertTrue("title $title px, expected $expected px", kotlin.math.abs(title - expected) <= 1f)
        assertTrue("title $title px, body $body px", body > title * 3)
        MemoEditHub.drop(id)
    }

    private companion object {
        const val OLD_FIELD = "memo_test_old_field"
    }
}
