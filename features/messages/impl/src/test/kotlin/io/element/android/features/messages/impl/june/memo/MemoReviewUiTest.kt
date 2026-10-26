/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.june.memo

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.compound.theme.ElementTheme
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** 앱17: the review from a card (A), the selection bar (B) and the open note (C). D is in MemoTabSwipeGoneUiTest. */
class MemoReviewUiTest : RobolectricTest() {
    /** The memo bot room as a fake: takes every review at once, the status says "제안 2건". */
    private class Room : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            val ids = memoArgStrings(op.args["ids"])
            val content = when (op.op) {
                MEMO_OP_REVIEW -> JSONObject().put("op", op.op).put("접수", ids.size).put("건너뜀", JSONArray())
                else -> JSONObject().put("op", op.op).put(
                    "상태",
                    JSONArray(ids.map { JSONObject().put("id", it).put("상태", "완료").put("결과", "제안 2건").put("시각", "2026-10-17T10:00:00+09:00") }),
                )
            }
            return MemoResponse.Ok("r", op.op, null, null, content)
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null

        fun reviewed(): List<List<String>> = ops.filter { it.op == MEMO_OP_REVIEW }.map { memoArgStrings(it.args["ids"]) }
    }

    private fun backend(): MemoLocalBackend {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoLocalBackend({ MemoDb(driver) }, dispatcher = EmptyCoroutineContext)
    }

    /** The record tab of [backend] with a controller that sends its reviews to [room]. */
    private fun AndroidComposeUiTest<ComponentActivity>.recordTab(backend: MemoLocalBackend, room: Room): MemoController {
        var controller: MemoController? = null
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val c = remember {
                    MemoController(MemoStore(backend), scope, mutableStateOf(MemoLocation())).also {
                        it.reviewer = MemoReviewer(room, dispatcher = Dispatchers.Unconfined)
                    }
                }
                controller = c
                val version by MemoLocalChanges.version.collectAsState()
                MemoRecordTab(c, version)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.RECORD_ROW).fetchSemanticsNodes().isNotEmpty() }
        return controller!!
    }

    @Test
    fun `a card pushed far to the left opens the directive sheet and stays, short or to the right it does not`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking { backend.create("교토 빵집 메모") }
        val room = Room()
        val controller = recordTab(backend, room)
        // 20 % of the width: back to its place, nothing sent.
        onNodeWithTag(MemoReviewTags.SWIPE).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.2f) }
        waitForIdle()
        assertTrue(room.reviewed().isEmpty())
        assertEquals(null, controller.sheet)
        // To the right: nothing.
        onNodeWithTag(MemoReviewTags.SWIPE).performTouchInput { swipeRight(startX = left + 1f, endX = right - 1f) }
        waitForIdle()
        assertTrue(room.reviewed().isEmpty())
        // Up: the list scrolls, nothing sent.
        onNodeWithTag(MemoReviewTags.SWIPE).performTouchInput { swipeUp() }
        waitForIdle()
        assertTrue(room.reviewed().isEmpty())
        assertEquals(null, controller.sheet)
        // 앱31 추가요청 1: 70 % opens the directive sheet of this one note; nothing is sent yet and the card is still
        // there, back in its place.
        onNodeWithTag(MemoReviewTags.SWIPE).performTouchInput { swipeLeft(startX = right - 1f, endX = right - 1f - width * 0.7f) }
        waitUntil(timeoutMillis = 5_000) { controller.sheet != null }
        assertEquals(MemoSheet.ReviewDirective(id), controller.sheet)
        assertTrue(room.reviewed().isEmpty())
        waitForIdle()
        assertEquals(1, onAllNodesWithTag(MemoTags.RECORD_ROW).fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithTag(MemoReviewTags.SWIPE_HINT).fetchSemanticsNodes().isEmpty())
        // 취소: nothing was sent, the card is back, the note is untouched (a push can be repeated at once).
        controller.sheet = null
        waitForIdle()
        assertTrue(room.ops.isEmpty())
        // 검토 with an empty field: the review of before, then the look-up tells the result.
        controller.reviewNote(id, "")
        waitUntil(timeoutMillis = 5_000) { room.reviewed().isNotEmpty() }
        assertEquals(listOf(listOf(id)), room.reviewed())
        assertFalse(room.ops.first { it.op == MEMO_OP_REVIEW }.args.containsKey(MEMO_ARG_DIRECTIVE))
        waitUntil(timeoutMillis = 10_000) { controller.notice?.text == "검토 완료: 제안 2건" }
        // A second push right away: the same note opens nothing within 3 s and says why; nothing is sent again.
        onNodeWithTag(MemoReviewTags.SWIPE).performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
        waitUntil(timeoutMillis = 5_000) { controller.notice?.text == MEMO_REVIEW_RECENT_NOTICE }
        assertEquals(null, controller.sheet)
        assertEquals(1, room.reviewed().size)
    }

    @Test
    fun `the card offers the review to the screen reader, and nothing can be pushed in the selection mode`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking { backend.create("접근성 메모") }
        val room = Room()
        val controller = recordTab(backend, room)
        val actions = onNodeWithTag(MemoTags.RECORD_ROW).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val review = actions.single { it.label == MEMO_REVIEW_ACTION }
        runOnIdle { review.action() }
        waitUntil(timeoutMillis = 5_000) { room.reviewed() == listOf(listOf(id)) }
        controller.select = controller.select.start()
        waitForIdle()
        val inSelection = onNodeWithTag(MemoTags.RECORD_ROW).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertTrue(inSelection.none { it.label == MEMO_REVIEW_ACTION })
        onNodeWithTag(MemoReviewTags.SWIPE).performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
        waitForIdle()
        assertEquals(1, room.reviewed().size)
    }

    @Test
    fun `app31 - the selection bar opens the directive sheet, 검토 sends the selected notes and ends the selection`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val room = Room()
            var controller: MemoController? = null
            setContent {
                ElementTheme {
                    val scope = rememberCoroutineScope()
                    val c = remember {
                        MemoController(MemoStore(FakeMemoBackend()), scope, mutableStateOf(MemoLocation())).also {
                            it.reviewer = MemoReviewer(room, dispatcher = Dispatchers.Unconfined)
                        }
                    }
                    controller = c
                    MemoSelectBar(c, listOf("a", "b", "c").toImmutableList())
                }
            }
            val c = controller!!
            c.select = c.select.start()
            waitForIdle()
            // Nothing selected: the button is there but does nothing.
            onNodeWithTag(MemoReviewTags.BULK).assertIsNotEnabled()
            c.select = c.select.toggle("a").toggle("c")
            waitForIdle()
            // 앱31 ①: the press opens the sheet with the two selected notes; nothing is sent and the selection stays.
            onNodeWithTag(MemoReviewTags.BULK).performClick()
            waitForIdle()
            assertEquals(MemoSheet.ReviewDirectiveMany(listOf("a", "c")), c.sheet)
            assertTrue(room.reviewed().isEmpty())
            assertTrue(c.select.active)
            // 검토 with the written directive (what the sheet hands over): both notes, one request, the selection ends.
            c.sheet = null
            c.bulkReview(listOf("a", "c"), " 사진 속 간판을 읽어줘 ")
            waitUntil(timeoutMillis = 5_000) { room.reviewed().isNotEmpty() }
            assertEquals(listOf(listOf("a", "c")), room.reviewed())
            assertEquals("사진 속 간판을 읽어줘", room.ops.first { it.op == MEMO_OP_REVIEW }.args[MEMO_ARG_DIRECTIVE])
            assertFalse(c.select.active)
        }

    @Test
    fun `app31 - 취소 of the bulk sheet sends nothing and keeps the selection`() = runAndroidComposeUiTest<ComponentActivity> {
        val room = Room()
        var controller: MemoController? = null
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val c = remember {
                    MemoController(MemoStore(FakeMemoBackend()), scope, mutableStateOf(MemoLocation())).also {
                        it.reviewer = MemoReviewer(room, dispatcher = Dispatchers.Unconfined)
                    }
                }
                controller = c
                MemoSelectBar(c, listOf("a", "b").toImmutableList())
                MemoSheetHostForTest(c)
            }
        }
        val c = controller!!
        c.select = c.select.start().toggle("a").toggle("b")
        waitForIdle()
        onNodeWithTag(MemoReviewTags.BULK).performClick()
        waitForIdle()
        // The sheet says the directive goes to both notes.
        onNodeWithTag(MemoReviewTags.DIRECTIVE_TARGET).assertTextEquals("선택한 2개 노트에 함께 적용됩니다")
        onNodeWithTag(MemoReviewTags.DIRECTIVE_INPUT).performTextInput("간판을 읽어줘")
        onNodeWithTag(MemoReviewTags.DIRECTIVE_CANCEL).performClick()
        waitForIdle()
        assertEquals(null, c.sheet)
        assertTrue(room.ops.isEmpty())
        assertTrue(c.select.active)
        assertEquals(listOf("a", "b"), c.select.ids)
    }

    @Test
    fun `app31 - 검토 with an empty field is the review of before - no 지시 key`() = runAndroidComposeUiTest<ComponentActivity> {
        val room = Room()
        var controller: MemoController? = null
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val c = remember {
                    MemoController(MemoStore(FakeMemoBackend()), scope, mutableStateOf(MemoLocation())).also {
                        it.reviewer = MemoReviewer(room, dispatcher = Dispatchers.Unconfined)
                    }
                }
                controller = c
                MemoSelectBar(c, listOf("a", "b").toImmutableList())
                MemoSheetHostForTest(c)
            }
        }
        val c = controller!!
        c.select = c.select.start().toggle("a").toggle("b")
        waitForIdle()
        onNodeWithTag(MemoReviewTags.BULK).performClick()
        waitForIdle()
        onNodeWithTag(MemoReviewTags.DIRECTIVE_SEND).performClick()
        waitUntil(timeoutMillis = 5_000) { room.reviewed().isNotEmpty() }
        assertEquals(listOf(listOf("a", "b")), room.reviewed())
        assertFalse(room.ops.first { it.op == MEMO_OP_REVIEW }.args.containsKey(MEMO_ARG_DIRECTIVE))
        assertFalse(c.select.active)
    }

    /** 앱31: the sheet host of MemoScreen, for the tests that press 취소 / 검토 in the sheet itself. */
    @Composable
    private fun MemoSheetHostForTest(controller: MemoController) {
        val sheet = controller.sheet
        if (sheet is MemoSheet.ReviewDirectiveMany) {
            MemoReviewDirectiveSheet(
                onReview = { text ->
                    controller.sheet = null
                    controller.bulkReview(sheet.ids, text)
                },
                onCancel = { controller.sheet = null },
                count = sheet.ids.size,
            )
        }
    }

    @Test
    fun `without a server the selection bar has no review button`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val c = remember { MemoController(MemoStore(FakeMemoBackend()), scope, mutableStateOf(MemoLocation())) }
                c.select = c.select.start()
                MemoSelectBar(c, persistentListOf())
            }
        }
        waitForIdle()
        assertTrue(onAllNodesWithTag(MemoReviewTags.BULK).fetchSemanticsNodes().isEmpty())
    }

    /** The open note of [id] with a reviewer, as MemoScreen has it. */
    private fun AndroidComposeUiTest<ComponentActivity>.open(backend: MemoLocalBackend, id: String, room: Room): MemoController {
        val store = MemoStore(backend)
        runBlocking { store.loadNote(id) }
        var controller: MemoController? = null
        val nav = mutableStateOf(MemoLocation().openNote(id))
        setContent {
            ElementTheme {
                val scope = rememberCoroutineScope()
                val c = remember { MemoController(store, scope, nav).also { it.reviewer = MemoReviewer(room, dispatcher = Dispatchers.Unconfined) } }
                controller = c
                MemoNoteScreen(c, id)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoReviewTags.BUTTON).fetchSemanticsNodes().isNotEmpty() }
        return controller!!
    }

    @Test
    fun `the open note has the review button left of the pin button, both whole, and a press reviews the note`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking { backend.create("가슴 운동 세트별 중량·횟수 기록 아주 긴 제목이 들어가도 버튼이 잘리지 않아야 한다") }
        val room = Room()
        val controller = open(backend, id, room)
        val review = onNodeWithTag(MemoReviewTags.BUTTON).fetchSemanticsNode()
        val pin = onNodeWithTag(MemoA11Tags.PIN_TOGGLE).fetchSemanticsNode()
        val read = onNodeWithTag(MemoTags.NOTE_READ).fetchSemanticsNode().boundsInRoot
        val min = MEMO_PIN_TOGGLE_DP * density.density - 1f
        val whole = listOf(review.size, pin.size).all { it.width >= min && it.height >= min }
        assertTrue("review ${review.size} pin ${pin.size}", whole)
        // Left of the pin button, on the same line, and neither is cut at the right edge.
        assertTrue("review ${review.boundsInRoot} pin ${pin.boundsInRoot}", review.boundsInRoot.right <= pin.boundsInRoot.left + 1f)
        assertEquals(review.boundsInRoot.center.y, pin.boundsInRoot.center.y, 1f)
        assertTrue("pin ${pin.boundsInRoot} in $read", pin.boundsInRoot.right <= read.right + 1f)
        assertEquals(listOf(MEMO_REVIEW_ACTION), review.config.getOrNull(SemanticsProperties.ContentDescription))
        // 앱21 ①: the press opens the directive sheet first, nothing is sent yet.
        onNodeWithTag(MemoReviewTags.BUTTON).performClick()
        waitForIdle()
        assertEquals(MemoSheet.ReviewDirective(id), controller.sheet)
        assertTrue(room.reviewed().isEmpty())
        // 검토 with the field left empty (what the sheet hands over): the review of before, without 지시.
        controller.sheet = null
        controller.reviewNote(id, "")
        waitUntil(timeoutMillis = 5_000) { room.reviewed().isNotEmpty() }
        assertEquals(listOf(listOf(id)), room.reviewed())
        assertFalse(room.ops.first { it.op == MEMO_OP_REVIEW }.args.containsKey(MEMO_ARG_DIRECTIVE))
        waitUntil(timeoutMillis = 10_000) { controller.notice != null }
        // A fast second press: no sheet, not sent again.
        onNodeWithTag(MemoReviewTags.BUTTON).performClick()
        waitForIdle()
        assertEquals(null, controller.sheet)
        assertEquals(1, room.reviewed().size)
    }

    @Test
    fun `a directive typed in the sheet goes with the review of the open note`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking { backend.create("오사카 여행 사진") }
        val room = Room()
        val controller = open(backend, id, room)
        onNodeWithTag(MemoReviewTags.BUTTON).performClick()
        waitForIdle()
        assertEquals(MemoSheet.ReviewDirective(id), controller.sheet)
        controller.sheet = null
        controller.reviewNote(id, "  간판 글자를 읽어줘 ")
        waitUntil(timeoutMillis = 5_000) { room.reviewed().isNotEmpty() }
        assertEquals("간판 글자를 읽어줘", room.ops.first { it.op == MEMO_OP_REVIEW }.args[MEMO_ARG_DIRECTIVE])
    }

    @Test
    fun `no review button while editing or in the trash, a dimmed one in the selection mode`() = runAndroidComposeUiTest<ComponentActivity> {
        val backend = backend()
        val id = runBlocking { backend.create("편집할 메모") }
        val room = Room()
        val controller = open(backend, id, room)
        controller.select = controller.select.start()
        waitForIdle()
        onNodeWithTag(MemoReviewTags.BUTTON).assertIsNotEnabled()
        controller.select = controller.select.end()
        controller.go { it.startEditing() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoTags.EDIT_BODY).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithTag(MemoReviewTags.BUTTON).fetchSemanticsNodes().isEmpty())
        controller.go { it.stopEditing() }
        MemoEditHub.drop(id)
        runBlocking { backend.call(MemoOp.trash(MemoTarget(id)), "t") }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(MemoReviewTags.BUTTON).fetchSemanticsNodes().isEmpty() }
        assertTrue(room.reviewed().isEmpty())
    }
}
