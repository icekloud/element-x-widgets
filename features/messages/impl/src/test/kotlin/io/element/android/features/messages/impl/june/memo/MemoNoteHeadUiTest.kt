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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** 앱15 E·F·G·H: the open note head, the sync mark, the 편집 box and the 📌 that takes the pin off. Made up data. */
class MemoNoteHeadUiTest : RobolectricTest() {
    private fun backend(): MemoLocalBackend {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoLocalBackend({ MemoDb(driver) }, dispatcher = EmptyCoroutineContext)
    }

    @Test
    fun `the open note has the edit box at the bottom right, no edit in the head, and the waiting mark`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val store = MemoStore(backend)
        val id = runBlocking { backend.create("가슴 운동 세트별 중량·횟수 기록\n본문").also { store.loadNote(it) } }
        val nav = mutableStateOf(MemoLocation().openNote(id))
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                MemoNoteScreen(MemoController(store, scope, nav), id)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.NOTE_EDIT).fetchSemanticsNodes().isNotEmpty() }
        // G: the box is drawn, the head has no 편집 any more, the body keeps room for the box.
        onNodeWithTag(MemoTags.NOTE_EDIT).assertIsDisplayed()
        onNodeWithTag(MemoTags.NOTE_EDIT_ROOM, useUnmergedTree = true).assertExists()
        assertEquals(1, onAllNodesWithText("편집", useUnmergedTree = true).fetchSemanticsNodes().size)
        val box = onNodeWithTag(MemoTags.NOTE_EDIT).fetchSemanticsNode().boundsInRoot
        val read = onNodeWithTag(MemoTags.NOTE_READ).fetchSemanticsNode().boundsInRoot
        assertTrue("box $box in $read", box.right <= read.right && box.bottom <= read.bottom && box.left > read.center.x)
        assertTrue("box height ${box.height}", box.height >= MEMO_EDIT_BOX_HEIGHT_DP * density.density - 1f)
        // E: the title ends before the dates.
        val head = onNodeWithTag(MemoTags.NOTE_HEAD).fetchSemanticsNode().boundsInRoot
        val dates = onNodeWithTag(MemoTags.NOTE_DATE, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("head $head dates $dates", head.right <= dates.left + 1f)
        // F: a new phone note waits for the server; the mark has no text, only its description.
        val mark = onNodeWithTag(memoSyncMarkTag(MemoSyncMark.Waiting), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf("동기화 대기"), mark.config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(mark.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
        assertTrue(mark.boundsInRoot.right <= dates.right)
        // A press on the box starts the editor, and the box goes away while editing.
        onNodeWithTag(MemoTags.NOTE_EDIT).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.EDIT_BODY).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(nav.value.editing)
        assertTrue(onAllNodesWithTag(MemoTags.NOTE_EDIT).fetchSemanticsNodes().isEmpty())
        MemoEditHub.drop(id)
    }

    @Test
    // The name has no emoji: Robolectric could not decode it ("Error decoding percent encoded characters").
    fun `a pinned note loses its pin from the tray pin mark and gets it back with undo`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking {
            backend.create("점심으로 돈까스 먹는 중").also { backend.call(MemoOp.pin(MemoTarget(it)), "p") }
        }
        var controller: MemoController? = null
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val c = MemoController(MemoStore(backend), scope, mutableStateOf(MemoLocation()))
                controller = c
                val version by MemoLocalChanges.version.collectAsState()
                MemoRecordTab(c, version)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoA11Tags.PIN_CELL).fetchSemanticsNodes().size == 1 }
        // H: one line over the tray width.
        val strip = onNodeWithTag(MemoA11Tags.PIN_STRIP).fetchSemanticsNode().boundsInRoot
        val row = onNodeWithTag(MemoA11Tags.PIN_CELL).fetchSemanticsNode().boundsInRoot
        assertTrue("row $row strip $strip", row.width > strip.width * 0.85f)
        // The tray 📌 and the card 📌.
        assertEquals(2, onAllNodesWithTag(MemoA11Tags.PIN_OFF).fetchSemanticsNodes().size)
        onAllNodesWithTag(MemoA11Tags.PIN_OFF)[0].performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoA11Tags.PIN_CELL).fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoA11Tags.PIN_OFF).fetchSemanticsNodes().isEmpty() }
        val undo = controller?.undo
        assertNotNull(undo)
        undo!!.onUndo()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoA11Tags.PIN_CELL).fetchSemanticsNodes().size == 1 }
        assertTrue(runBlocking { (backend.call(MemoOp.get(id), "g") as MemoResponse.Note).note.pinned })
    }
}
