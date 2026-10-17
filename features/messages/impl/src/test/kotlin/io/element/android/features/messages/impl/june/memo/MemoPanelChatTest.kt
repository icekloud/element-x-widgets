/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱18 ① the instructions "지우기" takes off the list, ② the proposal area as a chat (order, following the newest row).
 * All data is made up; the server answers are fakes. Robolectric: org.json of the plain unit tests is a stub.
 */
class MemoPanelChatTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    /** The memo bot room: 지시 is taken (접수), 지시조회 answers [pollRows]. */
    private class Room(var pollRows: JSONArray = JSONArray()) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            return when (op.op) {
                "지시" -> MemoResponse.Ok("r", "지시", null, null, JSONObject().put("id", op.args["id"]).put("상태", "접수"))
                "지시조회" -> MemoResponse.Ok("r", "지시조회", null, null, JSONObject().put("결과", pollRows))
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null

        @Suppress("UNCHECKED_CAST")
        fun asked(): List<List<String>> = ops.filter { it.op == "지시조회" }.map { it.args["ids"] as List<String> }
    }

    private fun directive(id: String, status: String, sentMs: Long = 0) = MemoDirective(id, "문장 $id", status, sentMs = sentMs)

    private fun keep(db: MemoDb, vararg items: MemoDirective) = db.setState("directives", JSONArray(items.map { it.toJson() }).toString())

    // ---------------------------------------------------------------- ① 지우기

    @Test
    fun `the ended instructions can go, the running ones stay`() {
        val all = listOf(
            directive("a", MemoDirectiveStatus.DONE),
            directive("b", MemoDirectiveStatus.QUESTION),
            directive("c", MemoDirectiveStatus.FAILED),
            directive("d", MemoDirectiveStatus.REFUSED),
            directive("e", MemoDirectiveStatus.NOT_SENT),
            directive("f", MemoDirectiveStatus.SENDING),
            directive("g", MemoDirectiveStatus.RECEIVED),
            directive("h", MemoDirectiveStatus.WORKING),
        )
        assertEquals(listOf("a", "b", "c", "d", "e"), memoDirectivesClearable(all).map { it.id })
        assertEquals("지시 5건을 목록에서 지울까요? 메모봇이 이미 한 일은 되돌려지지 않아요. 진행 중 3건은 남아요.", memoDirectivesClearText(all))
        assertEquals("지시 2건을 목록에서 지울까요? 메모봇이 이미 한 일은 되돌려지지 않아요.", memoDirectivesClearText(all.take(2)))
        // Only running ones: nothing to clear (the button is hidden).
        assertTrue(memoDirectivesClearable(all.drop(5)).isEmpty())
    }

    @Test
    fun `clearing takes the ended ones off the phone only, and a later answer does not bring one back`() = runTest {
        val db = db()
        val room = Room()
        keep(
            db,
            directive("old1", MemoDirectiveStatus.DONE, t0),
            directive("old2", MemoDirectiveStatus.QUESTION, t0 + 1),
            directive("run1", MemoDirectiveStatus.RECEIVED, t0 + 2),
        )
        val d = MemoDirectives(db, room, clock = { now }, dispatcher = Dispatchers.Unconfined)
        d.load()
        assertEquals(2, d.clear())
        assertEquals(listOf("run1"), d.items.value.map { it.id })
        // The database keeps the same: a new process reads the running one only.
        assertEquals(listOf("run1"), MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined).load().map { it.id })
        // Nothing was sent to the server by the clear.
        assertTrue(room.ops.isEmpty())
        // The look-up asks only for the running one; an answer that also lists a cleared id does not bring it back.
        room.pollRows = JSONArray()
            .put(JSONObject().put("id", "old1").put("상태", "완료").put("메시지", "끝"))
            .put(JSONObject().put("id", "run1").put("상태", "완료").put("메시지", "태그를 지웠어요"))
        assertEquals(1, d.poll())
        assertEquals(listOf(listOf("run1")), room.asked())
        assertEquals(listOf("run1"), d.items.value.map { it.id })
        assertEquals(MemoDirectiveStatus.DONE, d.items.value.single().status)
        // Nothing left to clear but the one now ended; a second clear empties the list.
        assertEquals(1, d.clear())
        assertTrue(d.items.value.isEmpty())
        assertEquals(0, d.clear())
    }

    // ---------------------------------------------------------------- ② the chat order

    private fun card(id: String, seenMs: Long) = MemoProposalGroup.of(MemoProposal(id, "분류", "새 태그 #$id", "대기", noteId = "n-$id", seenMs = seenMs))

    @Test
    fun `cards and instructions are one list, oldest on top, newest at the bottom`() {
        val groups = listOf(card("p1", t0 + 5_000), card("p2", t0 + 1_000), card("p0", 0))
        val orders = listOf(directive("d1", MemoDirectiveStatus.DONE, t0 + 2_000), directive("d2", MemoDirectiveStatus.RECEIVED, t0 + 9_000))
        val keys = memoPanelTimeline(groups, orders).map { it.key }
        // A card without a time on top; then by time; the newest instruction last.
        assertEquals(listOf("g-p0", "g-p2", "d-d1", "g-p1", "d-d2"), keys)
        // The same time keeps the order: cards in the order of the index, then the instructions.
        val same = memoPanelTimeline(listOf(card("a", 0), card("b", 0)), listOf(directive("x", MemoDirectiveStatus.DONE, 0)))
        assertEquals(listOf("g-a", "g-b", "d-x"), same.map { it.key })
        assertTrue(memoPanelTimeline(emptyList(), emptyList()).isEmpty())
        // A grouped card takes its earliest known time.
        val grouped = MemoProposalGroup(
            listOf(
                MemoProposal("q1", "분류", "s", "대기", seenMs = 0),
                MemoProposal("q2", "분류", "s", "대기", seenMs = t0 + 7),
                MemoProposal("q3", "분류", "s", "대기", seenMs = t0 + 3),
            ),
        )
        assertEquals(t0 + 3, grouped.seenMs)
    }

    @Test
    fun `the area goes down when opened, refreshed or sent from, and on its own only when it is at the bottom`() {
        for (atBottom in listOf(true, false)) {
            assertTrue(memoPanelFollows(MemoPanelScroll.Open, atBottom))
            assertTrue(memoPanelFollows(MemoPanelScroll.Refresh, atBottom))
            assertTrue(memoPanelFollows(MemoPanelScroll.Send, atBottom))
        }
        assertTrue(memoPanelFollows(MemoPanelScroll.Change, atBottom = true))
        assertFalse(memoPanelFollows(MemoPanelScroll.Change, atBottom = false))
    }

    @Test
    fun `a waiting proposal of the phone carries the time the phone first got it`() = runTest {
        val db = db()
        val payload = JSONObject().put("id", "p1").put("종류", "분류").put("요약", "새 태그 #책상").put("상태", "대기").put("노트", "n1")
        db.putSuggestion(MemoSuggestionRow("p1", "n1", "분류", payload.toString(), MEMO_SUGGESTION_WAITING, t0 + 42))
        val local = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)
        val p = local.fetchIndex().proposals.single()
        assertEquals(t0 + 42, p.seenMs)
        assertEquals("새 태그 #책상", p.summary)
        // From the server index (no time there): 0, the card goes on top.
        assertEquals(0L, parseMemoProposal(payload)!!.seenMs)
    }
}
