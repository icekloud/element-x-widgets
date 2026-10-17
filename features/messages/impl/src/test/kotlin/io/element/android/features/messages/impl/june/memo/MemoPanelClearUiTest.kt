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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.testtags.TestTags
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱18 ①: "지우기" on the line of the instructions asks once, then takes the ended ones off; the running ones stay. */
class MemoPanelClearUiTest : RobolectricTest() {
    /** The memo bot room: the open instruction stays 처리중 (no answer yet). */
    private class Room : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            return when (op.op) {
                "지시조회" -> MemoResponse.Ok(
                    "r",
                    "지시조회",
                    null,
                    null,
                    JSONObject().put("결과", JSONArray().put(JSONObject().put("id", "run").put("상태", "처리중").put("메시지", ""))),
                )
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    private fun db(vararg items: MemoDirective): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver).also { it.setState("directives", JSONArray(items.map { d -> d.toJson() }).toString()) }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.panel(directives: MemoDirectives?) {
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val controller = remember { MemoController(MemoStore(FakeMemoBackend()), scope, mutableStateOf(MemoLocation())) }
                MemoPanelContent(controller, directives, onSync = {}, onClose = {})
            }
        }
        waitForIdle()
    }

    @Test
    fun `clear asks once, takes the ended instructions off and keeps the running one`() = runAndroidComposeUiTest<ComponentActivity> {
        val now = System.currentTimeMillis()
        val db = db(
            MemoDirective("done", "끝난 지시", MemoDirectiveStatus.DONE, "했어요", now - 60_000),
            MemoDirective("ask", "물어본 지시", MemoDirectiveStatus.QUESTION, "어느 노트?", now - 50_000),
            // Sent 4 min ago: still open, but no automatic look-up is due any more (opening the area asks once).
            MemoDirective("run", "진행 중인 지시", MemoDirectiveStatus.WORKING, "", now - 240_000),
        )
        val room = Room()
        val d = MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined)
        panel(d)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("끝난 지시"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("메모봇에 한 지시 3건").assertIsDisplayed()
        // Cancel: nothing goes.
        onNodeWithTag(MemoTags.PANEL_CLEAR).performClick()
        onNodeWithText("지시 2건을 목록에서 지울까요? 메모봇이 이미 한 일은 되돌려지지 않아요. 진행 중 1건은 남아요.").assertIsDisplayed()
        onNodeWithText("취소").performClick()
        waitForIdle()
        assertEquals(3, d.items.value.size)
        // 지우기: the two ended ones go, the running one stays; the button goes (nothing more to clear).
        onNodeWithTag(MemoTags.PANEL_CLEAR).performClick()
        onNode(hasTestTag(TestTags.dialogPositive.value)).performClick()
        waitUntil(timeoutMillis = 5_000) { d.items.value.size == 1 }
        assertEquals(listOf("run"), d.items.value.map { it.id })
        waitForIdle()
        assertTrue(onAllNodes(hasText("끝난 지시"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        onNodeWithText("메모봇에 한 지시 1건").assertIsDisplayed()
        assertTrue(onAllNodes(hasTestTag(MemoTags.PANEL_CLEAR)).fetchSemanticsNodes().isEmpty())
        // Nothing of the clear went to the server (only the look-ups of the open one).
        assertTrue(room.ops.all { it.op == "지시조회" })
        // The phone list is the same after a restart.
        assertEquals(listOf("run"), runBlocking { MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined).load() }.map { it.id })
    }

    @Test
    fun `no clear button while every instruction is still running`() = runAndroidComposeUiTest<ComponentActivity> {
        val db = db(MemoDirective("run", "진행 중인 지시", MemoDirectiveStatus.WORKING, "", System.currentTimeMillis() - 240_000))
        panel(MemoDirectives(db, Room(), dispatcher = Dispatchers.Unconfined))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("진행 중인 지시"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("메모봇에 한 지시 1건").assertIsDisplayed()
        assertTrue(onAllNodes(hasTestTag(MemoTags.PANEL_CLEAR)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the empty area keeps its line and has no instruction line`() = runAndroidComposeUiTest<ComponentActivity> {
        panel(MemoDirectives(db(), Room(), dispatcher = Dispatchers.Unconfined))
        onNodeWithTag(MemoTags.PANEL_EMPTY).assertIsDisplayed()
        assertTrue(onAllNodes(hasText("메모봇에 한 지시", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasTestTag(MemoTags.PANEL_CLEAR)).fetchSemanticsNodes().isEmpty())
    }
}
