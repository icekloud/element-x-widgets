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
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱18 ② (관리자: "제안탭 진입 시 채팅스크롤이 제일 하단부에 위치"): the proposal area opens at its newest row, the bottom; a
 * new row is followed only when the list is at the bottom. Only names that were there before 앱18 are used, so this test
 * also runs on the code before the change (where it fails: the area opened at the top). All data is made up.
 */
class MemoPanelBottomUiTest : RobolectricTest() {
    /** The memo bot room: every instruction is done at once (nothing stays open, no look-up runs). */
    private class Room : MemoSyncTransport {
        override suspend fun call(op: MemoOp): MemoResponse = when (op.op) {
            "지시" -> MemoResponse.Ok("r", "지시", null, null, JSONObject().put("id", op.args["id"]).put("상태", "완료").put("메시지", "했어요"))
            "지시조회" -> MemoResponse.Ok("r", "지시조회", null, null, JSONObject().put("결과", JSONArray()))
            else -> MemoResponse.Error("r", op.op, "모름")
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    private val t0 = 1_760_000_000_000L

    private fun text(i: Int) = "지시 %02d · 책상 정리 태그를 바꿔 줘".format(i)

    /** 20 instructions already done, the oldest first: taller than the area. */
    private fun directives(): MemoDirectives {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        val db = MemoDb(driver)
        val items = (0 until 20).map { MemoDirective("d$it", text(it), MemoDirectiveStatus.DONE, "했어요", t0 + it * 1_000L) }
        db.setState("directives", JSONArray(items.map { it.toJson() }).toString())
        var n = 0
        return MemoDirectives(db, Room(), dispatcher = Dispatchers.Unconfined, newId = { _, _ -> "new${n++}" })
    }

    private fun AndroidComposeUiTest<ComponentActivity>.panel(directives: MemoDirectives) {
        val store = MemoStore(FakeMemoBackend())
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val controller = remember { MemoController(store, scope, mutableStateOf(MemoLocation())) }
                MemoPanelContent(controller, directives, onSync = {}, onClose = {})
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(text(0)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() || shown(text(19)) }
        waitForIdle()
    }

    /** True when a node with [value] is composed and on the screen. */
    private fun AndroidComposeUiTest<ComponentActivity>.shown(value: String): Boolean {
        val nodes = onAllNodes(hasText(value), useUnmergedTree = true)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        return try {
            nodes.onFirst().assertIsDisplayed()
            true
        } catch (e: AssertionError) {
            false
        }
    }

    @Test
    fun `the area opens at its newest row, the oldest are above`() = runAndroidComposeUiTest<ComponentActivity> {
        panel(directives())
        assertTrue("the newest instruction is on the screen", shown(text(19)))
        assertFalse("the oldest instruction is not on the screen", shown(text(0)))
        assertFalse("the empty line on top is not on the screen", shown("대기 중인 제안이 없어요"))
        // Up: the old ones.
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        waitForIdle()
        assertTrue(shown("대기 중인 제안이 없어요"))
        assertFalse(shown(text(19)))
    }

    @Test
    fun `a new row is followed only at the bottom, a send of the user always goes down`() = runAndroidComposeUiTest<ComponentActivity> {
        val d = directives()
        panel(d)
        // Reading above: a row that comes on its own does not take the list away.
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        waitForIdle()
        runBlocking { d.send("위에서 읽는 중에 온 지시") }
        waitForIdle()
        assertTrue(shown("대기 중인 제안이 없어요"))
        assertFalse(shown("위에서 읽는 중에 온 지시"))
        // At the bottom (row 21: the empty line, 20 old ones, the one just sent): the next one is followed.
        onNode(hasScrollToIndexAction()).performScrollToIndex(21)
        waitForIdle()
        runBlocking { d.send("맨 아래에서 온 지시") }
        waitForIdle()
        assertTrue(shown("맨 아래에서 온 지시"))
        // Above again, and a send from the input: down to it.
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        waitForIdle()
        onNodeWithTag(MemoTags.PANEL_INPUT).performTextInput("입력창에서 보낸 지시")
        onNodeWithTag(MemoTags.PANEL_SEND).performClick()
        waitUntil(timeoutMillis = 5_000) { shown("입력창에서 보낸 지시") }
        assertFalse(shown("대기 중인 제안이 없어요"))
    }
}
