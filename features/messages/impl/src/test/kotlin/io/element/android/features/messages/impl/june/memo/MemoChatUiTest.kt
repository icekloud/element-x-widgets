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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

/**
 * 앱22: the switch over the input of the proposal area, the bubbles in the same list, the "네" of a body change and the
 * cards of the change requests. All data is made up (no address, no id of a real room).
 */
class MemoChatUiTest : RobolectricTest() {
    /** The memo bot room: 대화 is taken, 대화조회 answers [bubbles], 요청조회 answers [requests]. */
    private class Room(var bubbles: JSONArray = JSONArray(), var requests: JSONArray = JSONArray()) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()
        var seq = 0

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            return when (op.op) {
                MEMO_OP_CHAT -> MemoResponse.Ok("r", op.op, null, null, JSONObject().put("순번", ++seq).put("상태", "접수"))
                MEMO_OP_CHAT_POLL -> MemoResponse.Ok("r", op.op, null, null, JSONObject().put("말풍선", bubbles).put("더있음", false))
                MEMO_OP_REQUEST_POLL -> MemoResponse.Ok("r", op.op, null, null, JSONObject().put("요청", requests))
                "지시조회" -> MemoResponse.Ok("r", op.op, null, null, JSONObject().put("결과", JSONArray()))
                "지시" -> MemoResponse.Ok("r", op.op, null, null, JSONObject().put("id", op.args["id"]).put("상태", "접수"))
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun bubble(seq: Int, side: String, text: String, status: String = "완료") =
        JSONObject().put("순번", seq).put("쪽", side).put("글", text).put("상태", status)

    private fun AndroidComposeUiTest<ComponentActivity>.panel(directives: MemoDirectives, chat: MemoChat?) {
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val controller = remember { MemoController(MemoStore(FakeMemoBackend()), scope, mutableStateOf(MemoLocation())) }
                MemoPanelContent(controller, directives, onSync = {}, onClose = {}, chat = chat)
            }
        }
        waitForIdle()
    }

    private fun shown(tag: String, test: AndroidComposeUiTest<ComponentActivity>): Boolean =
        test.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the input opens in 지시, looks like before, and the switch turns it into 대화`() = runAndroidComposeUiTest<ComponentActivity> {
        val db = db()
        val room = Room()
        val chat = MemoChat(db, room, dispatcher = Dispatchers.Unconfined, newId = { _, _ -> "m1" })
        panel(MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined), chat)
        // 지시: the words and the look of 앱21 (no counter, no line under the switch, no menu).
        onNodeWithText("메모봇에 지시 (예: 책상, NAS 태그 삭제)").assertIsDisplayed()
        assertTrue(onAllNodes(hasTestTag(MemoTags.PANEL_COUNT)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasTestTag(MemoTags.PANEL_HINT)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasTestTag(MemoTags.PANEL_MENU)).fetchSemanticsNodes().isEmpty())
        assertTrue(room.ops.none { it.op == MEMO_OP_CHAT_POLL || it.op == MEMO_OP_REQUEST_POLL })
        // 대화: the counter of 2000, the line that says what it does, and the menu of the chat.
        onNodeWithTag(MemoTags.PANEL_MODE_CHAT).performClick()
        waitUntil(timeoutMillis = 5_000) { chat.mode.value == MemoInputMode.Chat }
        waitForIdle()
        onNodeWithTag(MemoTags.PANEL_COUNT, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("0/2000", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("메모봇과 대화합니다. 노트를 고치거나 규칙을 바꾸는 것도 말로 부탁할 수 있어요", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(MemoTags.PANEL_MENU).assertIsDisplayed()
        // A word typed in 대화 goes to the chat op, not to the instruction op.
        onNodeWithTag(MemoTags.PANEL_INPUT).performTextInput("3번 노트 본문을 다듬어 줘")
        onNodeWithTag(MemoTags.PANEL_SEND).performClick()
        waitUntil(timeoutMillis = 5_000) { room.ops.any { it.op == MEMO_OP_CHAT } }
        assertEquals("3번 노트 본문을 다듬어 줘", room.ops.first { it.op == MEMO_OP_CHAT }.args["문장"])
        assertTrue(room.ops.none { it.op == "지시" })
        waitUntil(timeoutMillis = 5_000) { shown(MemoTags.PANEL_BUBBLE, this) }
        // The phone keeps the mode: a new chat object of the same database opens in 대화.
        val again = MemoChat(db, room, dispatcher = Dispatchers.Unconfined)
        assertEquals(MemoInputMode.Chat, runBlocking { again.apply { load() }.mode.value })
    }

    @Test
    fun `an answer of the memo bot is a bubble of the same list, with the state of my word`() = runAndroidComposeUiTest<ComponentActivity> {
        val db = db()
        val room = Room()
        val chat = MemoChat(db, room, dispatcher = Dispatchers.Unconfined, newId = { _, _ -> "m1" })
        runBlocking { chat.setMode(MemoInputMode.Chat) }
        room.bubbles = JSONArray(
            listOf(
                bubble(1, MemoChatSide.USER, "장보기 태그를 달아 줘"),
                bubble(2, MemoChatSide.BOT, "3번 노트에 달았어요."),
            ),
        )
        panel(MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined), chat)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("🤖 3번 노트에 달았어요."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("장보기 태그를 달아 줘", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("메모봇과의 대화 2줄").assertIsDisplayed()
        // 지우기 takes the bubbles off the screen without sending anything about them.
        onNodeWithTag(MemoTags.PANEL_CLEAR).performClick()
        onNodeWithText(memoPanelClearText(emptyList(), chat.items.value)).assertIsDisplayed()
        onNode(hasTestTag(TestTags.dialogPositive.value)).performClick()
        waitUntil(timeoutMillis = 5_000) { chat.items.value.isEmpty() }
        waitForIdle()
        assertTrue(onAllNodes(hasText("🤖 3번 노트에 달았어요."), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(room.ops.all { it.op in setOf(MEMO_OP_CHAT_POLL, MEMO_OP_REQUEST_POLL, "지시조회") })
        // The cleared numbers stay cleared: the same answer again draws nothing.
        waitUntil(timeoutMillis = 5_000) { runBlocking { chat.poll() } >= 0 }
        assertTrue(chat.items.value.isEmpty())
    }

    @Test
    fun `a body change shows what would be written and the 네 button sends the word`() = runAndroidComposeUiTest<ComponentActivity> {
        val db = db()
        val room = Room()
        val chat = MemoChat(db, room, dispatcher = Dispatchers.Unconfined, newId = { _, _ -> "m1" })
        runBlocking { chat.setMode(MemoInputMode.Chat) }
        room.bubbles = JSONArray(
            listOf(
                bubble(1, MemoChatSide.SERVER, "많이 바뀌어요. 이렇게 고칠까요?", "질문")
                    .put("비교", JSONObject().put("전", "짧은 본문").put("후", "고친 본문\n두 줄째").put("잘림", false)),
            ),
        )
        panel(MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined), chat)
        waitUntil(timeoutMillis = 5_000) { shown(MemoTags.PANEL_COMPARE, this) }
        onNodeWithText("짧은 본문", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("고친 본문\n두 줄째", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(MEMO_CHAT_COMPARE_NOTICE, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(MemoTags.PANEL_YES).performClick()
        waitUntil(timeoutMillis = 5_000) { room.ops.any { it.op == MEMO_OP_CHAT } }
        assertEquals("네", room.ops.first { it.op == MEMO_OP_CHAT }.args["문장"])
    }

    @Test
    fun `app24 - the work cards of a server bubble are one line each, five first and the rest behind one press`() = runAndroidComposeUiTest<ComponentActivity> {
        val db = db()
        val room = Room()
        val chat = MemoChat(db, room, dispatcher = Dispatchers.Unconfined)
        runBlocking { chat.setMode(MemoInputMode.Chat) }
        val works = JSONArray(
            (1..7).map { n ->
                JSONObject().put("종류", "노트작성").put("대상", "#$n").put("결과", "가짜 노트 $n 을 만들었어요").put("되돌리기", n != 7)
            },
        )
        room.bubbles = JSONArray(
            listOf(
                bubble(1, MemoChatSide.USER, "가짜 노트 일곱 개 만들어 줘"),
                bubble(2, MemoChatSide.SERVER, "새 노트 7건을 만들었어요.").put("작업결과", works)
                    .put("요약", JSONObject().put("노트작성", JSONArray(listOf(JSONObject().put("번호", 1))))),
                // 서버 20차 and before: no 작업결과, the bubble is only its words.
                bubble(3, MemoChatSide.SERVER, "태그를 달았어요."),
            ),
        )
        panel(MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined), chat)
        waitUntil(timeoutMillis = 5_000) { shown(MemoTags.PANEL_WORK_CARD, this) }
        fun cards() = onAllNodes(hasTestTag(MemoTags.PANEL_WORK_CARD), useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals(5, cards())
        onNodeWithText("노트작성 · #1 · 가짜 노트 1 을 만들었어요", useUnmergedTree = true).assertExists()
        assertTrue(onAllNodes(hasText("노트작성 · #6 · 가짜 노트 6 을 만들었어요"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // The 요약 object is not drawn as a line again under the cards.
        val summaryLine = hasText("노트작성", substring = true) and hasTestTag(MemoTags.PANEL_BUBBLE_STATE)
        assertTrue(onAllNodes(summaryLine, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        onNodeWithText("외 2건 더 보기", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(7, cards())
        onNodeWithText("노트작성 · #7 · 가짜 노트 7 을 만들었어요", useUnmergedTree = true).assertExists()
        // The cards that 되돌려줘 takes back carry the mark (6 of the 7).
        assertEquals(6, onAllNodes(hasContentDescription(MEMO_WORK_UNDO_LABEL), useUnmergedTree = true).fetchSemanticsNodes().size)
        onNodeWithText(MEMO_WORK_LESS, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(5, cards())
        // The bubble without cards is drawn as before.
        onNodeWithText("⚙ 태그를 달았어요.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the change requests are cards over the list, and nothing is drawn without one`() = runAndroidComposeUiTest<ComponentActivity> {
        val db = db()
        val room = Room()
        val chat = MemoChat(db, room, dispatcher = Dispatchers.Unconfined)
        runBlocking { chat.setMode(MemoInputMode.Chat) }
        panel(MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined), chat)
        waitForIdle()
        assertTrue(onAllNodes(hasTestTag(MemoTags.PANEL_REQUESTS)).fetchSemanticsNodes().isEmpty())
        room.requests = JSONArray(
            listOf(
                JSONObject().put("번호", 3).put("종류", "규칙").put("대상", "메모프로필/SOUL.md").put("요약", "답은 세 문장 이내").put("상태", "검토대기"),
            ),
        )
        runBlocking { chat.loadRequests() }
        waitUntil(timeoutMillis = 5_000) { shown(MemoTags.PANEL_REQUESTS, this) }
        onNodeWithText("메모봇 변경 요청 1건", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("3번 · 답은 세 문장 이내", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("규칙 · 메모프로필/SOUL.md · 검토대기", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `대화 초기화 asks first and then tells the server`() = runAndroidComposeUiTest<ComponentActivity> {
        val db = db()
        val room = Room()
        val chat = MemoChat(db, room, dispatcher = Dispatchers.Unconfined, newId = { _, _ -> "m1" })
        runBlocking { chat.setMode(MemoInputMode.Chat) }
        panel(MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined), chat)
        onNodeWithTag(MemoTags.PANEL_MENU).performClick()
        waitForIdle()
        onNodeWithText("대화 초기화").performClick()
        onNodeWithText(MEMO_CHAT_RESET_ASK).assertIsDisplayed()
        onNode(hasTestTag(TestTags.dialogPositive.value)).performClick()
        waitUntil(timeoutMillis = 5_000) { room.ops.any { it.op == MEMO_OP_CHAT } }
        assertEquals(true, room.ops.first { it.op == MEMO_OP_CHAT }.args["초기화"])
    }
}
