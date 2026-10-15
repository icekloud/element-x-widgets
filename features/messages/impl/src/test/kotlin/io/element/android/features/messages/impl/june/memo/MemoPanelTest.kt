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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱9: the proposal area (text, grouping, one answer per id) and the instructions (규약-지시 v1). All data is made up. */
class MemoPanelTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    /** The memo bot room: answers sync_pull / sync_push (with [proposalStatus]), 지시 and 지시조회. */
    private class Room(
        var online: Boolean = true,
        var proposals: List<JSONObject> = emptyList(),
        var proposalStatus: (JSONObject) -> JSONObject = { a -> JSONObject().put("id", a.getString("id")).put("상태", a.getString("응답")) },
        var orderAnswer: (String) -> MemoResponse = { id -> MemoResponse.Ok("r", "지시", id, null, JSONObject().put("id", id).put("상태", "접수")) },
        var pollRows: JSONArray = JSONArray(),
        var pull: JSONObject = JSONObject().put("노트", JSONArray()).put("기준", 0),
    ) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            if (!online) throw MemoSendException("off")
            return when (op.op) {
                "sync_pull" -> MemoResponse.Sync("r", pull)
                "sync_push" -> {
                    val answers = JSONArray()
                    (op.args["제안응답"] as? JSONArray)?.let { a -> for (i in 0 until a.length()) answers.put(proposalStatus(a.getJSONObject(i))) }
                    MemoResponse.SyncAck("r", JSONObject().put("결과", JSONArray()).put("제안결과", answers).put("서버순번", 0))
                }
                "지시" -> orderAnswer(op.args["id"] as String)
                "지시조회" -> MemoResponse.Ok("r", "지시조회", null, null, JSONObject().put("결과", pollRows))
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = if (online) proposals else null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null

        fun answersSent(): List<JSONObject> = ops.filter { it.op == "sync_push" }.flatMap { op ->
            val a = op.args["제안응답"] as? JSONArray ?: JSONArray()
            (0 until a.length()).map { a.getJSONObject(it) }
        }
    }

    private fun runner(db: MemoDb, room: Room) = MemoSyncRunner(db, room, clock = { now }, readFile = { null })

    private fun photoProposal(id: String, note: String, extra: String = "## 사진 설명\n책상 위 사진") = JSONObject()
        .put("id", id)
        .put("종류", "첨부분석")
        .put("요약", "제안 · 사진 설명을 본문에 더합니다")
        .put("상태", "대기")
        .put("노트", note)
        .put("제안내용", JSONObject().put("본문추가", extra))

    // ---------------------------------------------------------------- B: text

    @Test
    fun `the card text has no 제안 in front`() {
        assertEquals("새 태그 #포켓몬카드 를 답니다", memoProposalText("제안 · 새 태그 #포켓몬카드 를 답니다"))
        assertEquals("새 태그 #카드", memoProposalText("🤖 제안: 새 태그 #카드"))
        assertEquals("새 폴더: 영상", memoProposalText("새 폴더 제안: 영상"))
        assertEquals("10/5 빵집", memoProposalText("10/5 빵집"))
        // A word "제안" inside the sentence stays.
        assertEquals("친구 제안 정리", memoProposalText("친구 제안 정리"))
        assertEquals("📅 10/5 빵집", memoProposalLine(MemoProposal("p", "일정추가", "제안 · 10/5 빵집", "대기")))
        assertEquals("새 태그 #카드", memoProposalLine(MemoProposal("p", "분류", "제안 · 새 태그 #카드", "대기")))
        assertEquals("분류", memoProposalLine(MemoProposal("p", "분류", "", "대기")))
    }

    // ---------------------------------------------------------------- C: grouping

    @Test
    fun `same note, kind and text are one card, and accepting it sends every id once, applied once`() {
        val a = MemoProposal("p1", "첨부분석", "제안 · 사진 설명", "대기", noteId = "n1")
        val b = MemoProposal("p2", "첨부분석", "사진 설명", "대기", noteId = "n1")
        val c = MemoProposal("p3", "첨부분석", "사진 설명", "대기", noteId = "n2")
        val d = MemoProposal("p4", "분류", "사진 설명", "대기", noteId = "n1")
        val groups = memoPanelGroups(MemoIndex(emptyList(), listOf(a, b, c, d)))
        assertEquals(listOf(listOf("p1", "p2"), listOf("p3"), listOf("p4")), groups.map { it.ids })
        assertEquals(3, memoPanelWaitingCount(MemoIndex(emptyList(), listOf(a, b, c, d))))

        val accept = memoGroupOps(groups[0], MemoAnswer.Accept)
        assertEquals(listOf("accept" to "p1", "dismiss" to "p2"), accept.map { it.op to it.args["id"] })
        assertEquals(listOf("p1", "p2"), memoGroupOps(groups[0], MemoAnswer.Dismiss).map { it.args["id"] })
        assertEquals(listOf("defer" to "p1", "defer" to "p2"), memoGroupOps(groups[0], MemoAnswer.Defer).map { it.op to it.args["id"] })
        assertEquals(listOf("defer" to "p1", "dismiss" to "p2"), memoGroupOps(groups[0], MemoAnswer.Defer, date = "2026-10-20").map { it.op to it.args["id"] })
        // A card being sent gets no answer at all.
        val sending = MemoProposalGroup(listOf(a.copy(status = MEMO_PROPOSAL_SENDING)))
        assertTrue(memoGroupOps(sending, MemoAnswer.Accept).isEmpty())
    }

    @Test
    fun `a grouped accept leaves the body as it is and sends one answer per id`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("책상 사진")
        val room = Room(proposals = listOf(photoProposal("p1", note), photoProposal("p2", note), photoProposal("p3", note)))
        val r = runner(db, room)
        r.syncNow()
        val before = db.note(note)!!
        val group = memoPanelGroups(local.fetchIndex()).single()
        assertEquals(listOf("p1", "p2", "p3"), group.ids)
        memoGroupOps(group, MemoAnswer.Accept).forEach { local.call(it, "rid") }
        // 앱10 B (관리자: "메모 내용은 건드리지 말 것"): an old 첨부분석 card changes nothing in the note.
        assertEquals(before.contentHash, db.note(note)!!.contentHash)
        assertEquals(before.body, db.note(note)!!.body)
        assertEquals(before.revs, db.note(note)!!.revs)
        now += 10_000
        r.push()
        val sent = room.answersSent()
        assertEquals(listOf("p1" to "수락", "p2" to "거절", "p3" to "거절"), sent.map { it.getString("id") to it.getString("응답") })
        assertTrue(local.fetchIndex().proposals.isEmpty())
    }

    // ---------------------------------------------------------------- C: one answer per id

    @Test
    fun `a second press on the same proposal applies nothing and queues nothing`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("책상 사진")
        val room = Room(online = true, proposals = listOf(photoProposal("p1", note)))
        val r = runner(db, room)
        r.syncNow()
        val body = db.note(note)!!.body
        repeat(3) { assertTrue(local.call(MemoOp.accept("p1"), "rid$it") is MemoResponse.Ok) }
        local.call(MemoOp.dismiss("p1"), "rid-d")
        // 앱10 B: the body never gets the photo text (it went in once before).
        assertEquals(body, db.note(note)!!.body)
        assertEquals(0, Regex("## 사진 설명").findAll(db.note(note)!!.body).count())
        assertEquals(1, db.outbox().count { it.kind == MEMO_OUTBOX_PROPOSAL })
        assertEquals("수락", JSONObject(db.outbox().single { it.kind == MEMO_OUTBOX_PROPOSAL }.payload).getString("응답"))
        // Until the server has it the card stays, locked ("처리중"): not counted on the badge.
        val index = local.fetchIndex()
        assertEquals(MEMO_PROPOSAL_SENDING, index.proposals.single().status)
        assertEquals(0, memoPanelWaitingCount(index))
    }

    @Test
    fun `server off - the answer waits once, reopening sends it once, the card goes when the server has it`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("메모")
        val room = Room(proposals = listOf(photoProposal("p1", note)))
        val r = runner(db, room)
        r.syncNow()
        local.call(MemoOp.accept("p1"), "rid")
        room.online = false
        r.syncNow()
        r.syncNow()
        assertEquals(1, db.outbox().count { it.kind == MEMO_OUTBOX_PROPOSAL })
        room.online = true
        now += MemoSync.BACKOFF_MS.last() + 1
        // Reopened: the index still lists it as waiting (written before the answer), the card stays locked.
        r.syncNow()
        assertEquals(1, room.answersSent().size)
        assertTrue(local.fetchIndex().proposals.isEmpty())
        // A late index that still lists p1 as waiting does not bring the card back.
        r.syncNow()
        assertTrue(local.fetchIndex().proposals.isEmpty())
        assertEquals(1, room.answersSent().size)
    }

    @Test
    fun `이미처리 clears the card, an unknown value is dropped without sending again`() = runTest {
        val db = db()
        val local = backend(db)
        val note = local.create("메모")
        val room = Room(proposals = listOf(photoProposal("p1", note), photoProposal("p2", note, "다른 글")))
        room.proposalStatus = { a ->
            if (a.getString("id") == "p1") {
                JSONObject().put("id", "p1").put("상태", "이미처리").put("원상태", "수락")
            } else {
                JSONObject().put("id", "p2").put("상태", "새로운값")
            }
        }
        val r = runner(db, room)
        r.syncNow()
        local.call(MemoOp.accept("p1"), "a")
        local.call(MemoOp.accept("p2"), "b")
        now += 10_000
        r.push()
        assertTrue(db.outbox().none { it.kind == MEMO_OUTBOX_PROPOSAL })
        assertNull(db.suggestion("p1"))
        assertTrue("p1" in memoAnsweredProposals(db))
        // Unknown value: nothing sent again, the local answer stands.
        assertEquals(MEMO_SUGGESTION_ACCEPTED, db.suggestion("p2")!!.status)
        r.push()
        assertEquals(2, room.answersSent().size)
        assertEquals(MemoProposalResult.Deferred, memoProposalResultOf(JSONObject().put("상태", "이미처리").put("원상태", "보류")))
        assertEquals(MemoProposalResult.NotApplied, memoProposalResultOf(JSONObject().put("상태", "오류")))
    }

    @Test
    fun `a server number on a note is not shown and does not make the note go up again`() = runTest {
        // 추가요청 3: the number (#N) is for the next round; this app must stay safe when the server already sends it.
        val db = db()
        val change = JSONObject()
            .put("id", "20261015-090000-test")
            .put("폴더", MEMO_INBOX)
            .put("제목", "서버 노트")
            .put("본문", "본문\n")
            .put("수정", "2026-10-15T09:00:00+09:00")
            .put("기기", "서버")
            .put("순번", 7)
            .put("번호", 12)
            .put("머리말", JSONObject().put("번호", 12))
        val room = Room(pull = JSONObject().put("노트", JSONArray().put(change)).put("기준", 7))
        val r = runner(db, room)
        r.syncNow()
        val n = db.note("20261015-090000-test")!!
        assertEquals("서버 노트", n.title)
        assertFalse(n.dirty)
        r.syncNow()
        assertTrue(db.outbox().none { it.kind == MEMO_OUTBOX_NOTE })
        assertTrue(room.ops.none { op -> op.op == "sync_push" && (op.args["변경"] as? JSONArray)?.length() ?: 0 > 0 })
    }

    // ---------------------------------------------------------------- D: instructions

    private fun directives(db: MemoDb, room: Room) =
        MemoDirectives(db, room, clock = { now }, dispatcher = Dispatchers.Unconfined, newId = { _, _ -> "dev-${room.ops.size}" })

    @Test
    fun `an instruction is sent with its id, received, and asked about until it is done`() = runTest {
        val db = db()
        val room = Room()
        val d = directives(db, room)
        val sent = d.send("  책상, NAS 태그 불필요하니까 태그 삭제  ")!!
        assertEquals(MemoDirectiveStatus.RECEIVED, sent.status)
        val op = room.ops.single { it.op == "지시" }
        assertEquals("책상, NAS 태그 불필요하니까 태그 삭제", op.args["문장"])
        assertEquals(sent.id, op.args["id"])
        assertTrue(d.hasOpen())
        assertEquals(5_000L, d.nextPollInMs())

        room.pollRows = JSONArray().put(JSONObject().put("id", sent.id).put("상태", "처리중").put("메시지", ""))
        assertEquals(0, d.poll())
        assertEquals(MemoDirectiveStatus.WORKING, d.items.value.single().status)
        room.pollRows = JSONArray().put(
            JSONObject()
                .put("id", sent.id)
                .put("상태", "완료")
                .put("메시지", "태그 책상, NAS를 지웠어요 (노트 4건)")
                .put("요약", JSONObject().put("영향노트", 4))
        )
        assertEquals(1, d.poll())
        assertEquals("태그 책상, NAS를 지웠어요 (노트 4건)", memoDirectiveAnswerText(d.items.value.single()))
        assertFalse(d.hasOpen())
        // Nothing open: no look-up is sent.
        val polls = room.ops.count { it.op == "지시조회" }
        assertEquals(0, d.poll())
        assertEquals(polls, room.ops.count { it.op == "지시조회" })
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(sent.id), room.ops.first { it.op == "지시조회" }.args["ids"] as List<String>)
    }

    @Test
    fun `offline - the instruction is kept as not sent and goes again with the same id`() = runTest {
        val db = db()
        val room = Room(online = false)
        val d = directives(db, room)
        val first = d.send("여행 폴더 이름을 여행2026으로")!!
        assertEquals(MemoDirectiveStatus.NOT_SENT, first.status)
        assertEquals("보내지 못했어요", memoDirectiveAnswerText(first))
        // The same words again: the same id (the server handles an id once).
        val again = d.send("여행 폴더 이름을 여행2026으로")!!
        assertEquals(first.id, again.id)
        room.online = true
        val resent = d.resend(first.id)!!
        assertEquals(MemoDirectiveStatus.RECEIVED, resent.status)
        val ids = room.ops.filter { it.op == "지시" }.map { it.args["id"] }.toSet()
        assertEquals(setOf(first.id), ids)
        assertEquals(1, d.items.value.size)
    }

    @Test
    fun `after a restart the open instructions are asked about again, a send cut short can be sent again`() = runTest {
        val db = db()
        val room = Room()
        val d = directives(db, room)
        val sent = d.send("태그 카드 다시 제안 받아줘")!!
        // A new process: the ids come from the database.
        val later = directives(db, room)
        assertEquals(listOf(sent.id), later.load().map { it.id })
        room.pollRows = JSONArray().put(JSONObject().put("id", sent.id).put("상태", "질문").put("메시지", "어느 노트인지 알려 주세요"))
        assertEquals(0, later.poll())
        assertEquals(MemoDirectiveStatus.QUESTION, later.items.value.single().status)
        // A state cut short in the middle of a send becomes "못보냄".
        db.setState("directives", JSONArray().put(MemoDirective("x1", "문장", MemoDirectiveStatus.SENDING).toJson()).toString())
        assertEquals(MemoDirectiveStatus.NOT_SENT, directives(db, room).load().single().status)
    }

    @Test
    fun `a refused instruction shows the reason, long text is cut, only the last 20 are kept`() = runTest {
        val db = db()
        val room = Room(orderAnswer = { MemoResponse.Error("r", "지시", "지시 문장이 비었다") })
        val d = directives(db, room)
        val refused = d.send("x")!!
        assertEquals(MemoDirectiveStatus.REFUSED, refused.status)
        assertEquals("지시 문장이 비었다", memoDirectiveAnswerText(refused))
        room.orderAnswer = { id -> MemoResponse.Ok("r", "지시", id, null, JSONObject().put("id", id).put("상태", "접수")) }
        d.send("가".repeat(700))
        assertEquals(MEMO_DIRECTIVE_MAX_CHARS, (room.ops.last { it.op == "지시" }.args["문장"] as String).length)
        repeat(25) { d.send("지시 $it") }
        assertEquals(MEMO_DIRECTIVE_KEEP, d.items.value.size)
        assertEquals("지시 24", d.items.value.last().text)
        assertNull(d.send("   "))
    }

    @Test
    fun `look-ups run from 5 s to 3 min, and the id fits the server rule`() {
        assertEquals(5_000L, memoDirectivePollDelayMs(t0, t0))
        assertEquals(2_000L, memoDirectivePollDelayMs(t0, t0 + 3_000))
        assertEquals(5_000L, memoDirectivePollDelayMs(t0, t0 + 30_000))
        assertEquals(15_000L, memoDirectivePollDelayMs(t0, t0 + 90_000))
        assertNull(memoDirectivePollDelayMs(t0, t0 + 181_000))
        val id = memoNewDirectiveId("ABC234", t0) { 0 }
        assertTrue(id, Regex("^[A-Za-z0-9._-]{1,64}$").matches(id))
        assertTrue(id.startsWith("ABC234-"))
        // The messages of the phone never say "제안".
        listOf(
            MemoDirectiveStatus.SENDING,
            MemoDirectiveStatus.NOT_SENT,
            MemoDirectiveStatus.RECEIVED,
            MemoDirectiveStatus.WORKING,
            MemoDirectiveStatus.DONE,
            MemoDirectiveStatus.QUESTION,
            MemoDirectiveStatus.FAILED,
            MemoDirectiveStatus.REFUSED,
        ).forEach { s -> assertFalse(memoDirectiveAnswerText(MemoDirective("i", "t", s)).contains("제안")) }
    }
}
