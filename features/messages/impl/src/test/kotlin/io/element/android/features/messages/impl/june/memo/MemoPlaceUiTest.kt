/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.june.memo

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.EmptyCoroutineContext

/** 앱21 ②: the place box left of the 편집 box; 추가요청 4: the tag line opens the tag sheet. Made up places. */
class MemoPlaceUiTest : RobolectricTest() {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MemoDb.Schema.create(it) }
    private val backend = MemoLocalBackend({ MemoDb(driver) }, dispatcher = EmptyCoroutineContext)

    /** A note whose stored header has [place] (as a `sync_pull` of 서버 15차 leaves it), or no header at all. */
    private fun note(text: String, place: JSONObject?, tags: List<String> = emptyList()): String = runBlocking {
        val id = backend.create(text)
        if (tags.isNotEmpty()) backend.call(MemoOp.editTags(MemoTarget(id), tags), "t")
        if (place != null) {
            val db = MemoDb(driver)
            db.putNote(db.note(id)!!.copy(headerJson = JSONObject().put(MEMO_FIELD_PLACE, place).toString()))
        }
        id
    }

    private fun AndroidComposeUiTest<ComponentActivity>.open(id: String): MemoController {
        val store = MemoStore(backend)
        runBlocking { store.loadNote(id) }
        var controller: MemoController? = null
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val c = remember { MemoController(store, scope, mutableStateOf(MemoLocation().openNote(id))) }
                controller = c
                MemoNoteScreen(c, id)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.NOTE_EDIT).fetchSemanticsNodes().isNotEmpty() }
        return controller!!
    }

    private fun AndroidComposeUiTest<ComponentActivity>.bounds(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private val food = JSONObject()
        .put("이름", "가짜 꼬치집")
        .put("종류", "음식점")
        .put("소개", "꼬치튀김 전문점")
        .put("주소", "가짜시 가짜구 1-2-3")
        .put("지도", "https://www.google.com/maps/search/?api=1&query=test")
        .put("출처", "사진GPS")

    @Test
    fun `without a place the edit box is alone, where and as big as before`() = runAndroidComposeUiTest<ComponentActivity> {
        open(note("장소 없는 메모", null))
        assertTrue(onAllNodesWithTag(MemoTags.NOTE_PLACE).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(MemoTags.NOTE_EDIT).assertExists()
    }

    @Test
    fun `a place is drawn left of the edit box on its line, and the edit box keeps its place and size`() = runAndroidComposeUiTest<ComponentActivity> {
        val plain = note("장소 없는 메모", null)
        val withPlace = note("오사카 저녁", food)
        val store = MemoStore(backend)
        runBlocking {
            store.loadNote(plain)
            store.loadNote(withPlace)
        }
        val shown = mutableStateOf(plain)
        setContent {
            ElementTheme {
                key(shown.value) {
                    val scope = rememberCoroutineScope()
                    val c = remember { MemoController(store, scope, mutableStateOf(MemoLocation().openNote(shown.value))) }
                    MemoNoteScreen(c, shown.value)
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.NOTE_EDIT).fetchSemanticsNodes().isNotEmpty() }
        val alone = bounds(MemoTags.NOTE_EDIT)
        // the same screen, the note with a place
        shown.value = withPlace
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.NOTE_PLACE).fetchSemanticsNodes().isNotEmpty() }
        val place = bounds(MemoTags.NOTE_PLACE)
        val box = bounds(MemoTags.NOTE_EDIT)
        assertEquals(alone.width, box.width, 1f)
        assertEquals(alone.height, box.height, 1f)
        assertEquals(alone.right, box.right, 1f)
        assertEquals(alone.bottom, box.bottom, 1f)
        assertTrue("place $place box $box", place.right <= box.left)
        assertEquals(box.center.y, place.center.y, 1f)
        assertEquals(box.height, place.height, 1f)
        // 앱23 추가요청 4 (소개는 칸에서 뺀다): the first line is the name only; 앱24 brings this test to it (결정기록 5).
        onNodeWithText("🍴 가짜 꼬치집", useUnmergedTree = true).assertExists()
        assertTrue(onAllNodesWithText("꼬치튀김 전문점", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        onNodeWithText("가짜시 가짜구 1-2-3", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a press on the place opens its map link in Google Maps, as it came`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = open(note("오사카 저녁", food))
        onNodeWithTag(MemoTags.NOTE_PLACE).performClick()
        waitForIdle()
        // Robolectric records the started activity (no app has to be there); the fall back is in MemoPlaceTest.
        var started: Intent? = null
        runOnUiThread { activity?.let { started = shadowOf(it).nextStartedActivity } }
        val intent = started!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MEMO_MAPS_PACKAGE, intent.`package`)
        assertEquals(food.getString("지도"), intent.dataString)
        assertEquals(null, controller.notice)
    }

    @Test
    fun `the tag line opens the tag sheet, a note without tags offers to add one`() = runAndroidComposeUiTest<ComponentActivity> {
        val id = note("태그 없는 메모", null)
        val controller = open(id)
        onNodeWithText(MEMO_TAG_ADD, useUnmergedTree = true).assertExists()
        onNodeWithTag(MemoTags.NOTE_TAGS).performClick()
        waitForIdle()
        val sheet = controller.sheet as MemoSheet.EditTags
        assertEquals(emptyList<String>(), sheet.tags)
        // Saving from the sheet writes the tags of this note (the same op as the list screen).
        sheet.onSave(listOf("교토", "빵집"))
        controller.sheet = null
        waitUntil(timeoutMillis = 5_000) { runBlocking { MemoDb(driver).note(id)!!.tags } == listOf("교토", "빵집") }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("#교토", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the tag line of a note with tags opens the sheet with them`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = open(note("교토 메모", null, tags = listOf("교토", "빵집")))
        onNodeWithText("#교토", useUnmergedTree = true).assertExists()
        onNodeWithTag(MemoTags.NOTE_TAGS).performClick()
        waitForIdle()
        assertEquals(listOf("교토", "빵집"), (controller.sheet as MemoSheet.EditTags).tags)
    }
}
