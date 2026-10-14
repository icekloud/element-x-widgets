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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/** 앱6: what the record card draws for photos (항목 1·3) and its longer body text (항목 4·5). */
class MemoRecordCardUiTest : RobolectricTest() {
    @Test
    fun `a server photo is asked from the SDK with the json under file, a phone original is loaded as the file`() {
        val sealed = memoEncryptAttachment(byteArrayOf(1, 2, 3))
        // The server form: no mimetype.
        val bare = sealed.fileJson("mxc://hs/a", null).toString()
        val model = memoPhotoModel("mxc://hs/a", bare, null) as MediaRequestData
        val json = JSONObject(model.source!!.json!!)
        assertEquals("mxc://hs/a", json.getJSONObject("file").getString("url"))
        assertEquals("mxc://hs/a", model.source!!.safeUrl)
        assertTrue(model.kind is MediaRequestData.Kind.Thumbnail)
        // The phone original wins while it is there.
        assertEquals(File("/p/1.jpg"), memoPhotoModel("mxc://hs/a", bare, "/p/1.jpg"))
    }

    @Test
    fun `the record card shows four photos with the rest counted and the body on up to seven lines`() = runAndroidComposeUiTest<ComponentActivity> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        val backend = MemoLocalBackend({ MemoDb(driver) }, dispatcher = EmptyCoroutineContext)
        val long = (1..12).joinToString("\n") { "줄$it 교토 빵집" }
        runBlocking {
            backend.create("사진 여섯 장", (1..6).map { MemoLocalAttachment(id = "a$it", localPath = "/none/$it.jpg") })
            backend.create(long)
        }
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val controller = MemoController(MemoStore(backend), scope, mutableStateOf(MemoLocation()))
                val version by MemoLocalChanges.version.collectAsState()
                MemoRecordTab(controller, version)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.RECORD_ROW).fetchSemanticsNodes().size == 2 }
        // The row is clickable (it merges its children), so the inner tags are read from the unmerged tree.
        // 항목 3: four cells in one row, "+2" on the last one.
        onAllNodesWithTag(MemoTags.RECORD_PHOTO, useUnmergedTree = true).assertCountEquals(MEMO_CARD_PHOTOS)
        onNodeWithText("+2", useUnmergedTree = true).assertIsDisplayed()
        // 항목 1 ③: a photo that cannot be drawn (no file, no server copy yet) offers "다시" instead of a grey box.
        onAllNodesWithTag(MemoTags.PHOTO_RETRY, useUnmergedTree = true).assertCountEquals(MEMO_CARD_PHOTOS)
        // 항목 4·5: the body text of both cards, the long one cut to seven lines.
        onAllNodesWithTag(MemoTags.RECORD_TEXT, useUnmergedTree = true).assertCountEquals(2)
        onNodeWithText("사진 여섯 장", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("줄1 교토 빵집", substring = true, useUnmergedTree = true).assertIsDisplayed()
        // The text is laid out with at most seven lines and an ellipsis (what was 2 lines before); the card has no
        // fixed height, so it grows with the text.
        onAllNodesWithTag(MemoTags.RECORD_TEXT, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val layout = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val input = layout.single().layoutInput
            assertEquals(MEMO_CARD_LINES, input.maxLines)
            assertEquals(TextOverflow.Ellipsis, input.overflow)
        }
    }
}
