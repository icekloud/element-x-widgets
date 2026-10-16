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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Assert.assertFalse
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

    // The name has no emoji: Robolectric could not decode it ("Error decoding percent encoded characters").
    @Test
    fun `a pinned note loses its pin from the tray pin mark and gets it back with undo`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking {
            backend.create("점심으로 돈까스 먹는 중").also { backend.call(MemoOp.pin(MemoTarget(it)), "p") }
        }
        var controller: MemoController? = null
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                // 앱16 C: remembered, as MemoScreen does; made again on every recomposition it lost its undo (CI 38856026885).
                val c = remember { MemoController(MemoStore(backend), scope, mutableStateOf(MemoLocation())) }
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

    /** 앱16 B: the open note of [id] with a remembered controller, as MemoScreen has it. */
    private fun AndroidComposeUiTest<ComponentActivity>.open(
        backend: MemoLocalBackend,
        id: String,
        location: MemoLocation = MemoLocation().openNote(id),
    ): MemoController {
        val store = MemoStore(backend)
        runBlocking { store.loadNote(id) }
        var controller: MemoController? = null
        val nav = mutableStateOf(location)
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val c = remember { MemoController(store, scope, nav) }
                controller = c
                MemoNoteScreen(c, id)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.NOTE_READ).fetchSemanticsNodes().isNotEmpty() || nav.value.editing }
        return controller!!
    }

    /** What the 📌 button of the open note says to the screen reader (null when there is no button). */
    private fun AndroidComposeUiTest<ComponentActivity>.pinButtonSays(): List<String>? {
        val node = onAllNodesWithTag(MemoA11Tags.PIN_TOGGLE).fetchSemanticsNodes().firstOrNull() ?: return null
        return node.config.getOrNull(SemanticsProperties.ContentDescription)
    }

    private fun pinnedNow(backend: MemoLocalBackend, id: String): Boolean =
        runBlocking { (backend.call(MemoOp.get(id), "g") as MemoResponse.Note).note.pinned }

    @Test
    fun `a note that is not pinned shows the pin button, a press pins it and undo takes it back`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking { backend.create("점심으로 돈까스 먹는 중") }
        val controller = open(backend, id)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoA11Tags.PIN_TOGGLE).fetchSemanticsNodes().size == 1 }
        val button = onNodeWithTag(MemoA11Tags.PIN_TOGGLE).fetchSemanticsNode()
        assertEquals(listOf("메모 고정"), button.config.getOrNull(SemanticsProperties.ContentDescription))
        // At least 48dp to press.
        assertTrue("size ${button.size}", button.size.width >= 48 * density.density - 1f && button.size.height >= 48 * density.density - 1f)
        // The old 앱15 mark of a pinned note is not there on a note that is not pinned.
        assertTrue(onAllNodesWithTag(MemoA11Tags.PIN_OFF).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(MemoA11Tags.PIN_TOGGLE).performClick()
        waitUntil(timeoutMillis = 5_000) { pinnedNow(backend, id) }
        waitUntil(timeoutMillis = 5_000) { controller.undo != null }
        assertEquals("고정했습니다", controller.undo?.text)
        waitUntil(timeoutMillis = 5_000) { pinButtonSays() == listOf("고정 해제") }
        controller.undo!!.onUndo()
        waitUntil(timeoutMillis = 5_000) { !pinnedNow(backend, id) }
    }

    @Test
    fun `on a pinned note the pin button takes the pin off with undo`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking { backend.create("고정된 메모").also { backend.call(MemoOp.pin(MemoTarget(it)), "p") } }
        val controller = open(backend, id)
        waitUntil(timeoutMillis = 5_000) { pinButtonSays() == listOf("고정 해제") }
        onNodeWithTag(MemoA11Tags.PIN_TOGGLE).performClick()
        waitUntil(timeoutMillis = 5_000) { !pinnedNow(backend, id) }
        waitUntil(timeoutMillis = 5_000) { controller.undo != null }
        assertEquals("고정을 해제했습니다", controller.undo?.text)
        controller.undo!!.onUndo()
        waitUntil(timeoutMillis = 5_000) { pinnedNow(backend, id) }
    }

    @Test
    fun `no pin button while editing, and a dimmed one that does nothing in the selection mode`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking { backend.create("편집할 메모") }
        val controller = open(backend, id, MemoLocation().openNote(id).startEditing())
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.EDIT_BODY).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithTag(MemoA11Tags.PIN_TOGGLE).fetchSemanticsNodes().isEmpty())
        controller.go { it.stopEditing() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoA11Tags.PIN_TOGGLE).fetchSemanticsNodes().size == 1 }
        controller.select = controller.select.start()
        waitForIdle()
        onNodeWithTag(MemoA11Tags.PIN_TOGGLE).assertIsNotEnabled()
        assertFalse(pinnedNow(backend, id))
        MemoEditHub.drop(id)
    }

    @Test
    fun `a note in the trash shows no pin button`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking { backend.create("버린 메모").also { backend.call(MemoOp.trash(MemoTarget(it)), "t") } }
        open(backend, id)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.NOTE_READ).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertTrue(onAllNodesWithTag(MemoA11Tags.PIN_TOGGLE).fetchSemanticsNodes().isEmpty())
    }
}
