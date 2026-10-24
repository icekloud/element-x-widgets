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

    /** The memo bot room: 지시 is taken (접수), 지시조회 answers [pollRows] after [beforeAnswer] (the user acts meanwhile). */
    private class Room(var pollRows: JSONArray = JSONArray()) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()
        var beforeAnswer: suspend () -> Unit = {}

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            return when (op.op) {
                "지시" -> MemoResponse.Ok("r", "지시", null, null, JSONObject().put("id", op.args["id"]).put("상태", "접수"))
                "지시조회" -> MemoResponse.Ok("r", "지시조회", null, null, JSONObject().put("결과", pollRows)).also { beforeAnswer() }
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
    fun `clear tidies the screen - every instruction goes, the running ones too`() {
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
        // 추가요청 1 ("그냥 화면상의 내용 정리하는 기능"): all of them, the running ones too.
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h"), memoDirectivesClearable(all).map { it.id })
        assertEquals("지시 8건을 화면에서 지울까요? 메모봇이 하는 일에는 영향이 없어요\n진행 중 3건도 화면에서만 지워져요", memoDirectivesClearText(all))
        assertEquals("지시 2건을 화면에서 지울까요? 메모봇이 하는 일에는 영향이 없어요", memoDirectivesClearText(all.take(2)))
        assertEquals(listOf("f", "g", "h"), memoDirectivesClearable(all.drop(5)).map { it.id })
        // Nothing on the list: nothing to clear (the button is hidden).
        assertTrue(memoDirectivesClearable(emptyList()).isEmpty())
    }

    @Test
    fun `clearing takes every instruction off the phone only, remembers the ids, and a later answer brings none back`() = runTest {
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
        assertEquals(3, d.clear())
        assertTrue(d.items.value.isEmpty())
        // The phone remembers the cleared ids in its state table (no new table), and a new process reads an empty list.
        assertEquals(setOf("old1", "old2", "run1"), JSONArray(db.state("directives_cleared")).let { a -> (0 until a.length()).map(a::getString).toSet() })
        assertTrue(MemoDirectives(db, room, dispatcher = Dispatchers.Unconfined).load().isEmpty())
        // Nothing was sent to the server by the clear, and nothing is asked about any more.
        assertTrue(room.ops.isEmpty())
        room.pollRows = JSONArray().put(JSONObject().put("id", "run1").put("상태", "완료").put("메시지", "태그를 지웠어요"))
        assertEquals(0, d.poll())
        assertTrue(room.asked().isEmpty())
        assertTrue(d.items.value.isEmpty())
        assertEquals(0, d.clear())
    }

    @Test
    fun `an answer that comes after a clear made during its look-up is ignored, a new instruction is shown`() = runTest {
        val db = db()
        val room = Room()
        keep(db, directive("run1", MemoDirectiveStatus.WORKING, t0))
        val d = MemoDirectives(db, room, clock = { now }, dispatcher = Dispatchers.Unconfined)
        d.load()
        // The user presses 지우기 while the look-up for run1 is on its way; its answer (완료) comes after.
        room.pollRows = JSONArray().put(JSONObject().put("id", "run1").put("상태", "완료").put("메시지", "끝"))
        room.beforeAnswer = { d.clear() }
        assertEquals(0, d.poll())
        assertEquals(listOf(listOf("run1")), room.asked())
        assertTrue(d.items.value.isEmpty())
        room.beforeAnswer = {}
        // A new instruction after the clear is on the list as usual.
        val next = d.send("새 지시")!!
        assertEquals(listOf(next.id), d.items.value.map { it.id })
        // A send still on its way when the list is cleared does not come back with its answer either.
        val db2 = db()
        val room2 = object : MemoSyncTransport by room {
            lateinit var dd: MemoDirectives

            override suspend fun call(op: MemoOp): MemoResponse {
                if (op.op == "지시") dd.clear()
                return room.call(op)
            }
        }
        val d2 = MemoDirectives(db2, room2, clock = { now }, dispatcher = Dispatchers.Unconfined).also { room2.dd = it }
        d2.send("보내는 사이에 지움")
        assertTrue(d2.items.value.isEmpty())
    }

    @Test
    fun `the phone remembers at most the newest cleared ids`() = runTest {
        val db = db()
        val d = MemoDirectives(db, Room(), clock = { now }, dispatcher = Dispatchers.Unconfined)
        repeat(6) { round ->
            keep(db, *(0 until MEMO_DIRECTIVE_KEEP).map { directive("r$round-$it", MemoDirectiveStatus.DONE, t0 + it) }.toTypedArray())
            d.load()
            assertEquals(MEMO_DIRECTIVE_KEEP, d.clear())
        }
        val ids = JSONArray(db.state("directives_cleared")).let { a -> (0 until a.length()).map(a::getString) }
        assertEquals(MEMO_DIRECTIVE_CLEARED_KEEP, ids.size)
        assertEquals("r5-${MEMO_DIRECTIVE_KEEP - 1}", ids.last())
        assertFalse("r0-0" in ids)
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
