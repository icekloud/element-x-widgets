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

/**
 * 앱22 (규약-대화 v1): the input of the proposal area switches between 지시 and 대화, the bubbles come into the same
 * list, and the change requests of the memo bot are shown. All ids, notes and words here are made up.
 * Robolectric: org.json of the plain unit tests is a stub.
 */
class MemoChatTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    /** The memo bot room: 대화 takes a word (접수), 대화조회 answers [rows] (in pages when [pages] has more than one). */
    private class Room(
        var pages: List<JSONObject> = listOf(JSONObject().put("말풍선", JSONArray()).put("더있음", false)),
        var online: Boolean = true,
        var off: Boolean = false,
    ) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()
        var requests: JSONArray = JSONArray()
        private var page = 0
        var seq = 0

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            if (!online) throw MemoSendException("가짜 네트워크 없음")
            if (off) return MemoResponse.Error("r", op.op, "기능 꺼짐: 메모봇 대화(JUNE_MEMO_CHAT)")
            return when (op.op) {
                MEMO_OP_CHAT -> MemoResponse.Ok("r", op.op, null, null, JSONObject().put("대화id", op.args["대화id"]).put("순번", ++seq).put("상태", "접수"))
                MEMO_OP_CHAT_POLL -> MemoResponse.Ok("r", op.op, null, null, pages[page.coerceAtMost(pages.size - 1)].also { page++ })
                MEMO_OP_REQUEST_POLL -> MemoResponse.Ok("r", op.op, null, null, JSONObject().put("요청", requests))
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    /** The names of a json object (its order is not the same in every org.json). */
    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    private fun chat(db: MemoDb, room: Room) =
        MemoChat(db, room, clock = { now }, dispatcher = Dispatchers.Unconfined, newId = { _, _ -> "msg-${room.ops.size}" })

    private fun bubble(seq: Int, side: String, text: String, status: String = "완료", time: String = "", requests: List<Int> = emptyList()) =
        JSONObject()
            .put("순번", seq)
            .put("쪽", side)
            .put("글", text)
            .put("상태", status)
            .put("시각", time)
            .apply { if (requests.isNotEmpty()) put("요청", JSONArray(requests)) }

    private fun page(bubbles: List<JSONObject>, more: Boolean = false, states: List<Pair<Int, String>> = emptyList()) = JSONObject()
        .put("말풍선", JSONArray(bubbles))
        .put("더있음", more)
        .put("상태목록", JSONArray(states.map { (seq, state) -> JSONObject().put("순번", seq).put("상태", state) }))

    // ---------------------------------------------------------------- the switch

    @Test
    fun `the mode is 지시 by default and is remembered across restarts`() = runTest {
        val db = db()
        val room = Room()
        val first = chat(db, room)
        first.load()
        assertEquals(MemoInputMode.Directive, first.mode.value)
        first.setMode(MemoInputMode.Chat)
        // A new process (the screen was closed) reads the same mode.
        val again = chat(db, room)
        again.load()
        assertEquals(MemoInputMode.Chat, again.mode.value)
        again.setMode(MemoInputMode.Directive)
        assertEquals(MemoInputMode.Directive, chat(db, room).apply { load() }.mode.value)
        // Anything else that may be in the state table is the mode of before.
        assertEquals(MemoInputMode.Directive, MemoInputMode.of(null))
        assertEquals(MemoInputMode.Directive, MemoInputMode.of("무언가"))
        assertEquals(MemoInputMode.Chat, MemoInputMode.of("Chat"))
    }

    @Test
    fun `each mode has its own limit, counter and words`() {
        assertEquals(MEMO_DIRECTIVE_MAX_CHARS, MemoInputMode.Directive.maxChars)
        assertEquals(500, MemoInputMode.Directive.maxChars)
        assertEquals(2000, MemoInputMode.Chat.maxChars)
        assertEquals("지시", MemoInputMode.Directive.label)
        assertEquals("대화", MemoInputMode.Chat.label)
        assertEquals("12/500", memoInputCounter("가".repeat(12), MemoInputMode.Directive))
        assertEquals("12/2000", memoInputCounter("가".repeat(12), MemoInputMode.Chat))
        // The instruction mode says what it said before, and has no line under the switch.
        assertEquals("메모봇에 지시 (예: 책상, NAS 태그 삭제)", MemoInputMode.Directive.placeholder)
        assertEquals("", MemoInputMode.Directive.hint)
        assertTrue(MemoInputMode.Chat.placeholder.startsWith("메모봇과 대화"))
        assertEquals("메모봇과 대화합니다. 노트를 고치거나 규칙을 바꾸는 것도 말로 부탁할 수 있어요", MemoInputMode.Chat.hint)
    }

    // ---------------------------------------------------------------- 대화 · 대화조회

    @Test
    fun `a word is sent with its message id, taken, and the answers come into the list`() = runTest {
        val db = db()
        val room = Room()
        val c = chat(db, room)
        c.load()
        val sent = c.send("  3번 노트에 장보기 태그를 달아 줘  ")!!
        val op = room.ops.single { it.op == MEMO_OP_CHAT }
        assertEquals("3번 노트에 장보기 태그를 달아 줘", op.args["문장"])
        assertEquals(sent.localId, op.args["메시지id"])
        assertTrue((op.args["대화id"] as String).isNotEmpty())
        assertNull(op.args["초기화"])
        assertEquals(MemoDirectiveStatus.RECEIVED, sent.status)
        assertEquals(1, sent.seq)
        assertTrue(c.hasOpen())
        assertEquals(5_000L, c.nextPollInMs())

        room.pages = listOf(
            page(
                listOf(
                    bubble(1, MemoChatSide.USER, "3번 노트에 장보기 태그를 달아 줘", "완료", "2026-10-19T09:00:00+09:00"),
                    bubble(2, MemoChatSide.BOT, "달았어요.", "완료", "2026-10-19T09:00:30+09:00"),
                    JSONObject(bubble(3, MemoChatSide.SERVER, "노트 1건에 태그를 달았어요.", "완료", "2026-10-19T09:00:31+09:00").toString())
                        .put("요약", JSONObject().put("태그추가", JSONArray(listOf("장보기"))).put("영향노트", 1)),
                ),
                states = listOf(1 to "완료"),
            ),
        )
        assertEquals(2, c.poll())
        val rows = c.items.value
        assertEquals(listOf(1, 2, 3), rows.map { it.seq })
        assertEquals(MemoDirectiveStatus.DONE, rows[0].status)
        // The word of the user keeps its message id (a resend would use it) and gets the number of the server.
        assertEquals(sent.localId, rows[0].localId)
        assertEquals("영향노트 1 · 태그추가 장보기", rows[2].summary)
        // The look-up asks from the last number the phone has.
        val polls = room.ops.filter { it.op == MEMO_OP_CHAT_POLL }
        assertEquals(0, polls.first().args["마지막순번"])
        assertFalse(c.hasOpen())
        assertNull(c.nextPollInMs())
        val newChat = chat(db, room)
        newChat.load()
        assertEquals(listOf(1, 2, 3), newChat.items.value.map { it.seq })
        room.pages = listOf(page(emptyList()))
        assertEquals(0, newChat.poll())
        assertEquals(3, room.ops.last { it.op == MEMO_OP_CHAT_POLL }.args["마지막순번"])
    }

    @Test
    fun `더있음 is followed at once, and the numbers are not doubled`() = runTest {
        val db = db()
        val room = Room(
            pages = listOf(
                page(listOf(bubble(1, MemoChatSide.USER, "첫 말"), bubble(2, MemoChatSide.BOT, "첫 답")), more = true),
                page(listOf(bubble(2, MemoChatSide.BOT, "첫 답"), bubble(3, MemoChatSide.SERVER, "태그를 달았어요.")), more = false),
                page(emptyList()),
            ),
        )
        val c = chat(db, room)
        c.load()
        c.poll()
        assertEquals(listOf(1, 2, 3), c.items.value.map { it.seq })
        assertEquals(listOf(0, 2), room.ops.filter { it.op == MEMO_OP_CHAT_POLL }.map { it.args["마지막순번"] })
    }

    @Test
    fun `offline - the word is kept as not sent and goes again with the same message id`() = runTest {
        val db = db()
        val room = Room(online = false)
        val c = chat(db, room)
        c.load()
        val first = c.send("폴더 이름을 바꿔 줘")!!
        assertEquals(MemoDirectiveStatus.NOT_SENT, first.status)
        assertEquals("보내지 못했어요", memoBubbleStatusText(first))
        assertTrue(first.canResend)
        // The same words again: the same message id (the server handles one id once).
        val again = c.send("폴더 이름을 바꿔 줘")!!
        assertEquals(first.localId, again.localId)
        room.online = true
        val resent = c.resend(first.localId)!!
        assertEquals(MemoDirectiveStatus.RECEIVED, resent.status)
        assertEquals(setOf(first.localId), room.ops.filter { it.op == MEMO_OP_CHAT }.map { it.args["메시지id"] }.toSet())
        assertEquals(1, c.items.value.size)
    }

    @Test
    fun `a word left on its way by a closed screen can be sent again after a restart`() = runTest {
        val db = db()
        val room = Room()
        db.setState(
            "chat_bubbles",
            JSONArray(listOf(MemoBubble(0, MemoChatSide.USER, "보내던 말", MemoDirectiveStatus.SENDING, sentMs = t0, localId = "m1").toJson())).toString(),
        )
        val c = chat(db, room)
        val kept = c.load().single()
        assertEquals(MemoDirectiveStatus.NOT_SENT, kept.status)
        assertTrue(kept.canResend)
    }

    @Test
    fun `대화 초기화 is sent without a sentence and does not touch the screen`() = runTest {
        val db = db()
        val room = Room()
        val c = chat(db, room)
        c.load()
        c.send("무언가 한 마디")
        val before = c.items.value.size
        assertTrue(c.reset())
        val op = room.ops.last { it.op == MEMO_OP_CHAT }
        assertEquals(true, op.args["초기화"])
        assertNull(op.args["문장"])
        assertEquals(before, c.items.value.size)
    }

    // ---------------------------------------------------------------- 지우기 and the cleared numbers

    @Test
    fun `clearing takes the bubbles off the phone only and a late answer does not bring them back`() = runTest {
        val db = db()
        val room = Room(pages = listOf(page(listOf(bubble(1, MemoChatSide.USER, "말"), bubble(2, MemoChatSide.BOT, "답")))))
        val c = chat(db, room)
        c.load()
        c.poll()
        assertEquals(2, c.clear())
        assertTrue(c.items.value.isEmpty())
        assertTrue(room.ops.none { it.op !in setOf(MEMO_OP_CHAT_POLL, MEMO_OP_REQUEST_POLL) })
        // The same answer again (a look-up that was on its way): nothing comes back.
        room.pages = listOf(page(listOf(bubble(1, MemoChatSide.USER, "말"), bubble(2, MemoChatSide.BOT, "답"))))
        c.poll()
        assertTrue(c.items.value.isEmpty())
        val again = chat(db, room)
        assertTrue(again.load().isEmpty())
    }

    @Test
    fun `the merge keeps the order of the numbers, drops the cleared ones and keeps a word still on its way`() {
        val kept = listOf(
            MemoBubble(1, MemoChatSide.USER, "말", MemoDirectiveStatus.DONE),
            MemoBubble(0, MemoChatSide.USER, "보내는 중", MemoDirectiveStatus.SENDING, localId = "m2"),
        )
        val incoming = listOf(
            MemoBubble(2, MemoChatSide.BOT, "답", MemoDirectiveStatus.DONE),
            MemoBubble(1, MemoChatSide.USER, "말", MemoDirectiveStatus.DONE),
        )
        assertEquals(listOf(1, 2, 0), memoBubblesMerge(kept, incoming).map { it.seq })
        // The word on its way goes when its own number comes back.
        val answered = incoming + MemoBubble(3, MemoChatSide.USER, "보내는 중", MemoDirectiveStatus.RECEIVED, localId = "m2")
        assertEquals(listOf(1, 2, 3), memoBubblesMerge(kept, answered).map { it.seq })
        // Cleared numbers are not drawn again.
        assertEquals(listOf(2), memoBubblesMerge(kept, incoming, cleared = setOf(1)).filter { it.seq > 0 }.map { it.seq })
        // At most MEMO_CHAT_KEEP rows are kept (the newest).
        val many = (1..MEMO_CHAT_KEEP + 10).map { MemoBubble(it, MemoChatSide.BOT, "답 $it", MemoDirectiveStatus.DONE) }
        val merged = memoBubblesMerge(emptyList(), many)
        assertEquals(MEMO_CHAT_KEEP, merged.size)
        assertEquals(MEMO_CHAT_KEEP + 10, merged.last().seq)
    }

    @Test
    fun `the phone remembers at most the newest cleared numbers`() = runTest {
        val db = db()
        val room = Room()
        val c = chat(db, room)
        repeat(4) { round ->
            val rows = (1..MEMO_CHAT_KEEP).map { MemoBubble(round * MEMO_CHAT_KEEP + it, MemoChatSide.BOT, "답", MemoDirectiveStatus.DONE) }
            db.setState("chat_bubbles", JSONArray(rows.map { it.toJson() }).toString())
            c.load()
            assertEquals(MEMO_CHAT_KEEP, c.clear())
        }
        val ids = JSONArray(db.state("chat_cleared")).let { a -> (0 until a.length()).map { a.optInt(it) } }
        assertEquals(MEMO_CHAT_CLEARED_KEEP, ids.size)
        assertEquals(4 * MEMO_CHAT_KEEP, ids.last())
    }

    // ---------------------------------------------------------------- 비교 · "네" · 기능 꺼짐

    @Test
    fun `a question with a comparison shows what would be written and asks for a 네`() = runTest {
        val db = db()
        val compare = JSONObject().put("전", "한 줄 본문").put("후", "고친 본문\n두 줄째").put("잘림", false)
        val room = Room(
            pages = listOf(
                page(
                    listOf(
                        bubble(1, MemoChatSide.USER, "3번 본문을 다듬어 줘"),
                        JSONObject(bubble(2, MemoChatSide.SERVER, "많이 바뀌어요. 이렇게 고칠까요?", "질문").toString()).put("비교", compare),
                    ),
                ),
            ),
        )
        val c = chat(db, room)
        c.load()
        c.poll()
        val asked = c.items.value.last()
        assertTrue(asked.asksYes)
        assertEquals(MemoCompare("한 줄 본문", "고친 본문\n두 줄째", false), asked.compare)
        assertEquals("본문 1줄 6자 → 2줄 10자", memoCompareSummary(asked.compare!!))
        assertEquals("본문 1줄 6자 → 2줄 10자 · 본문이 길어 일부만 보여요", memoCompareSummary(asked.compare!!.copy(truncated = true)))
        assertEquals("\"네\"라고 보내면 그대로 적용돼요 · 10분이 지나면 취소돼요", MEMO_CHAT_COMPARE_NOTICE)
        // The quick answer is the plain word the server waits for (규약-대화 §5).
        assertEquals("네", MEMO_CHAT_YES)
        c.send(MEMO_CHAT_YES)
        assertEquals("네", room.ops.last { it.op == MEMO_OP_CHAT }.args["문장"])
        // A word of the user is never a question of the server.
        assertFalse(c.items.value.first { it.isUser }.asksYes)
    }

    @Test
    fun `the feature switched off says so in Korean and nothing is kept as sent`() = runTest {
        val db = db()
        val room = Room(off = true)
        val c = chat(db, room)
        c.load()
        val sent = c.send("한 마디")!!
        assertEquals(MemoDirectiveStatus.REFUSED, sent.status)
        assertEquals("메모봇 대화 기능이 서버에서 꺼져 있어요 · \"지시\"로 바꿔 보내 주세요", c.off.value)
        assertNull(memoChatOffText("대화 문장이 너무 길다(2100자, 최대 2000자)"))
        assertEquals("메모봇 대화 기능이 서버에서 꺼져 있어요 · \"지시\"로 바꿔 보내 주세요", memoChatOffText("기능 꺼짐: 메모봇 대화(JUNE_MEMO_CHAT)"))
    }

    // ---------------------------------------------------------------- 요청조회

    @Test
    fun `the change requests are read and shown with their Korean state`() = runTest {
        val db = db()
        val room = Room()
        room.requests = JSONArray(
            listOf(
                JSONObject().put("번호", 3).put("종류", "규칙").put("대상", "메모프로필/SOUL.md").put("요약", "답은 세 문장 이내").put("상태", "검토대기"),
                JSONObject().put("번호", 2).put("종류", "모델").put("대상", "메모프로필/config.yaml").put("요약", "모델 바꾸기").put("상태", "반려")
                    .put("반려사유", "시험 기록이 없어요"),
            ),
        )
        val c = chat(db, room)
        val list = c.loadRequests()
        assertEquals(listOf(3, 2), list.map { it.number })
        assertEquals("메모봇 변경 요청 2건", memoRequestHeadText(list))
        assertEquals("3번 · 답은 세 문장 이내", memoRequestCardLine(list[0]))
        assertEquals("규칙 · 메모프로필/SOUL.md · 검토대기", memoRequestCardDetail(list[0]))
        assertEquals("모델 · 메모프로필/config.yaml · 반려 · 시험 기록이 없어요", memoRequestCardDetail(list[1]))
        // The op is a read and takes no argument; the phone has no op that changes a state (규약-대화 §7).
        val op = room.ops.last { it.op == MEMO_OP_REQUEST_POLL }
        assertTrue(op.args.isEmpty())
        assertFalse(op.isChange)
        // A request that comes with a bubble is read without being asked for.
        room.pages = listOf(page(listOf(bubble(1, MemoChatSide.SERVER, "변경 요청 4번을 올렸어요.", requests = listOf(4)))))
        room.requests = JSONArray(listOf(JSONObject().put("번호", 4).put("종류", "어휘").put("대상", "색인/분류어휘.json").put("요약", "낱말 추가").put("상태", "검토중")))
        c.load()
        c.poll()
        assertEquals(listOf(4), c.items.value.single().requests)
        assertEquals(listOf(4), c.requests.value.map { it.number })
    }

    @Test
    fun `app24 - the change requests of v2 are drawn as they come, a skill of the memo bot is not one`() = runTest {
        // 규약-대화 v2 §6: the memo bot's own skills are done in the chat (작업결과), the skills of others, SOUL.md and
        // config.yaml still come as requests. The card draws what the server sends, whatever the kind.
        val db = db()
        val room = Room()
        room.requests = JSONArray(
            listOf(
                JSONObject().put("번호", 5).put("종류", "스킬").put("대상", "skills/memo-save").put("요약", "저장 순서 바꾸기").put("상태", "관리자승인대기"),
                JSONObject().put("번호", 4).put("종류", "규칙").put("대상", "메모프로필/SOUL.md").put("요약", "답은 짧게").put("상태", "적용완료"),
            ),
        )
        val c = chat(db, room)
        val list = c.loadRequests()
        assertEquals("5번 · 저장 순서 바꾸기", memoRequestCardLine(list[0]))
        assertEquals("스킬 · skills/memo-save · 관리자승인대기", memoRequestCardDetail(list[0]))
        assertEquals("규칙 · 메모프로필/SOUL.md · 적용완료", memoRequestCardDetail(list[1]))
        // A skill the memo bot made itself comes as a work card of a bubble, not as a request.
        room.pages = listOf(
            page(
                listOf(
                    bubble(1, MemoChatSide.SERVER, "스킬을 만들었어요.")
                        .put("작업결과", JSONArray(listOf(JSONObject().put("종류", "스킬생성").put("대상", "가짜-스킬").put("결과", "새 스킬을 만들었어요").put("되돌리기", true)))),
                ),
            ),
        )
        c.load()
        c.poll()
        assertEquals(emptyList<Int>(), c.items.value.single().requests)
        assertEquals("스킬생성 · 가짜-스킬 · 새 스킬을 만들었어요", memoWorkCardLine(c.items.value.single().works.single()))
        assertEquals(1, room.ops.count { it.op == MEMO_OP_REQUEST_POLL })
    }

    // ---------------------------------------------------------------- 작업결과 (규약-대화 v2 §3)

    private fun work(kind: String, target: String, result: String, undo: Boolean = true) =
        JSONObject().put("종류", kind).put("대상", target).put("결과", result).put("되돌리기", undo)

    @Test
    fun `app24 - the work cards of a server bubble are read, kept and come back after a restart`() = runTest {
        val db = db()
        val room = Room()
        val c = chat(db, room)
        c.load()
        room.pages = listOf(
            page(
                listOf(
                    bubble(1, MemoChatSide.USER, "약국 메모 만들고 7번은 지워 줘"),
                    bubble(2, MemoChatSide.SERVER, "새 노트 만들었어요: #41 가짜 약국. 7번을 휴지통으로 옮겼어요.")
                        .put("요약", JSONObject().put("노트작성", JSONArray(listOf(JSONObject().put("번호", 41)))))
                        .put(
                            "작업결과",
                            JSONArray(
                                listOf(
                                    work("노트작성", "#41", "새 노트 '가짜 약국' 를 받은편지함 에 만들었어요"),
                                    work("노트삭제", "#7", "휴지통으로 옮겼어요"),
                                    // A card that says nothing is left out; one without 되돌리기 cannot be taken back.
                                    JSONObject().put("종류", " ").put("대상", "").put("결과", ""),
                                    work("스킬보관", "가짜-스킬", "보관했어요", undo = false),
                                ),
                            ),
                        ),
                    bubble(3, MemoChatSide.SERVER, "태그를 달았어요."),
                ),
            ),
        )
        c.poll()
        val server = c.items.value.first { it.seq == 2 }
        assertEquals(
            listOf(
                MemoWorkResult("노트작성", "#41", "새 노트 '가짜 약국' 를 받은편지함 에 만들었어요", true),
                MemoWorkResult("노트삭제", "#7", "휴지통으로 옮겼어요", true),
                MemoWorkResult("스킬보관", "가짜-스킬", "보관했어요", false),
            ),
            server.works,
        )
        assertEquals("노트작성 · #41 · 새 노트 '가짜 약국' 를 받은편지함 에 만들었어요", memoWorkCardLine(server.works[0]))
        // With cards, the 요약 line is not drawn again under the bubble; without them it is (서버 20차 and before).
        assertEquals("", memoBubbleStatusText(server))
        val plain = c.items.value.first { it.seq == 3 }
        assertEquals(emptyList<MemoWorkResult>(), plain.works)
        // The phone keeps the cards: a new chat of the same database has them.
        val again = chat(db, room)
        again.load()
        assertEquals(server.works, again.items.value.first { it.seq == 2 }.works)
        // A bubble kept by 앱22/23 (no 작업결과 in the json) still reads, without cards.
        val old = MemoBubble.fromJson(JSONObject().put("순번", 9).put("쪽", MemoChatSide.SERVER).put("글", "옛 말풍선").put("상태", "완료"))!!
        assertEquals(emptyList<MemoWorkResult>(), old.works)
        assertFalse(keys(MemoBubble(1, MemoChatSide.SERVER, "글", "완료").toJson()).contains("작업결과"))
    }

    @Test
    fun `app24 - five work cards are shown, the rest wait behind one press`() {
        val works = (1..8).map { MemoWorkResult("노트작성", "#$it", "만들었어요", true) }
        val (first, hidden) = memoWorkShown(works, expanded = false)
        assertEquals(MEMO_WORK_CARDS, first.size)
        assertEquals(5, MEMO_WORK_CARDS)
        assertEquals(listOf("#1", "#2", "#3", "#4", "#5"), first.map { it.target })
        assertEquals(3, hidden)
        assertEquals("외 3건 더 보기", memoWorkMoreText(hidden))
        assertEquals(works to 0, memoWorkShown(works, expanded = true))
        // Five or fewer: all of them, nothing to open.
        assertEquals(works.take(5) to 0, memoWorkShown(works.take(5), expanded = false))
        // Empty parts are left out of the line.
        assertEquals("되돌리기 · 직전 작업을 되돌렸어요", memoWorkCardLine(MemoWorkResult("되돌리기", "", "직전 작업을 되돌렸어요")))
    }

    // ---------------------------------------------------------------- the look-ups and the list

    @Test
    fun `the chat waits longer between look-ups than the instructions do`() {
        // The first look-up is later, and every step waits longer than the one of 지시 at the same age.
        assertEquals(5_000L, memoChatPollDelayMs(t0, t0))
        assertEquals(3_000L, memoDirectivePollDelayMs(t0, t0))
        assertEquals(4_000L, memoChatPollDelayMs(t0, t0 + 10_000))
        assertEquals(8_000L, memoChatPollDelayMs(t0, t0 + 70_000))
        assertEquals(20_000L, memoChatPollDelayMs(t0, t0 + 200_000))
        // The instructions stop asking after three minutes, the chat after ten.
        assertNull(memoDirectivePollDelayMs(t0, t0 + 200_000))
        assertNull(memoChatPollDelayMs(t0, t0 + 601_000))
    }

    @Test
    fun `a bubble says what it is doing, with the seconds while the memo bot thinks`() {
        val sent = MemoBubble(1, MemoChatSide.USER, "말", MemoDirectiveStatus.RECEIVED, sentMs = t0)
        assertEquals("메모봇이 받았어요 (12초)", memoBubbleStatusText(sent, t0 + 12_000))
        assertEquals("메모봇이 생각하는 중… (40초)", memoBubbleStatusText(sent.copy(status = MemoDirectiveStatus.WORKING), t0 + 40_000))
        assertEquals("보내는 중… (2초)", memoBubbleStatusText(sent.copy(status = MemoDirectiveStatus.SENDING), t0 + 2_000))
        // 앱33 추가요청 1 ④: after ten minutes the phone has stopped asking, and the line says that (not that the bot
        // is slow) — the seconds of a look-up that is no longer happening would only mislead.
        assertEquals(MEMO_CHECK_STOPPED_TEXT, memoBubbleStatusText(sent, t0 + 601_000))
        assertEquals("답을 받지 못했어요", memoBubbleStatusText(sent.copy(status = MemoDirectiveStatus.FAILED), t0 + 1_000))
        // A finished word says nothing more, and an answer shows the summary of what was done.
        assertEquals("", memoBubbleStatusText(sent.copy(status = MemoDirectiveStatus.DONE), t0 + 1_000))
        assertEquals("태그추가 장보기", memoBubbleStatusText(MemoBubble(2, MemoChatSide.SERVER, "달았어요", "완료", summary = "태그추가 장보기")))
    }

    @Test
    fun `the bubbles are rows of the same list, in the order of their numbers`() {
        val group = MemoProposalGroup.of(MemoProposal("p1", "분류", "새 태그 #장보기", "대기", noteId = "n1", seenMs = t0 + 1_000))
        val directive = MemoDirective("d1", "지시", MemoDirectiveStatus.DONE, sentMs = t0 + 2_000)
        val bubbles = listOf(
            MemoBubble(1, MemoChatSide.USER, "말", MemoDirectiveStatus.DONE, timeIso = "2026-10-19T09:00:00+09:00"),
            MemoBubble(2, MemoChatSide.BOT, "답", MemoDirectiveStatus.DONE, timeIso = ""),
            MemoBubble(3, MemoChatSide.SERVER, "했어요", MemoDirectiveStatus.DONE, timeIso = "2026-10-19T09:00:40+09:00"),
        )
        val keys = memoPanelTimeline(listOf(group), listOf(directive), bubbles).map { it.key }
        assertEquals(listOf("g-p1", "d-d1", "c-1", "c-2", "c-3"), keys)
        // Without bubbles the list is the one of 앱18 (the same call as before).
        assertEquals(listOf("g-p1", "d-d1"), memoPanelTimeline(listOf(group), listOf(directive)).map { it.key })
        // A bubble still on its way has its own key, so it is not mixed with a numbered one.
        val sending = listOf(MemoBubble(0, MemoChatSide.USER, "보내는 중", MemoDirectiveStatus.SENDING, sentMs = t0 + 9_000, localId = "m9"))
        assertEquals(listOf("c0-m9"), memoPanelTimeline(emptyList(), emptyList(), sending).map { it.key })
    }

    @Test
    fun `a bubble is placed by its own time, and one without a readable time follows the word before it`() {
        // 2026-10-19 09:00 KST = 00:00 UTC of the same day.
        assertEquals(1_792_368_000_000L, memoIsoMs("2026-10-19T09:00:00+09:00"))
        assertEquals(1_792_368_000_000L, memoIsoMs("2026-10-19T00:00:00Z"))
        assertEquals(memoIsoMs("2026-10-19T09:00:00+09:00"), memoIsoMs(memoIso(1_792_368_000_000L)))
        assertNull(memoIsoMs("어제"))
        assertNull(memoIsoMs(""))
        val times = memoBubbleTimes(
            listOf(
                MemoBubble(1, MemoChatSide.USER, "말", MemoDirectiveStatus.DONE, timeIso = "2026-10-19T09:00:00+09:00", sentMs = t0),
                MemoBubble(2, MemoChatSide.BOT, "답", MemoDirectiveStatus.DONE),
                MemoBubble(3, MemoChatSide.SERVER, "했어요", MemoDirectiveStatus.DONE, timeIso = "2026-10-19T09:00:40+09:00"),
            ),
        ).map { it.second }
        assertEquals(listOf(1_792_368_000_000L, 1_792_368_000_000L, 1_792_368_040_000L), times)
    }

    // ---------------------------------------------------------------- the line over the input

    @Test
    fun `the line and the question of 지우기 count the instructions and the bubbles`() {
        val directives = listOf(
            MemoDirective("a", "지시 1", MemoDirectiveStatus.DONE),
            MemoDirective("b", "지시 2", MemoDirectiveStatus.WORKING),
        )
        val bubbles = listOf(
            MemoBubble(1, MemoChatSide.USER, "말", MemoDirectiveStatus.RECEIVED),
            MemoBubble(2, MemoChatSide.BOT, "답", MemoDirectiveStatus.DONE),
        )
        // Without bubbles everything says what it said in 앱18 (the instruction mode is not touched).
        assertEquals("메모봇에 한 지시 2건", memoPanelHeadText(2, 0))
        assertEquals(memoDirectivesClearText(directives), memoPanelClearText(directives, emptyList()))
        assertEquals("메모봇과의 대화 2줄", memoPanelHeadText(0, 2))
        assertEquals("메모봇에 한 지시 2건 · 대화 2줄", memoPanelHeadText(2, 2))
        assertEquals(
            "지시 2건과 대화 2줄을 화면에서 지울까요? 메모봇이 하는 일과 서버 기록에는 영향이 없어요\n진행 중 2건도 화면에서만 지워져요",
            memoPanelClearText(directives, bubbles),
        )
        assertEquals(
            "대화 2줄을 화면에서 지울까요? 메모봇이 하는 일과 서버 기록에는 영향이 없어요\n진행 중 1건도 화면에서만 지워져요",
            memoPanelClearText(emptyList(), bubbles),
        )
    }

    // ---------------------------------------------------------------- the ops (앱21 and before are untouched)

    @Test
    fun `the ops of the chat follow the protocol and the instruction op is the one of 앱21`() {
        val chat = MemoOp.chat("c1", "한 마디", "m1")
        assertEquals("대화", chat.op)
        assertEquals(mapOf("대화id" to "c1", "문장" to "한 마디", "메시지id" to "m1", "초기화" to null), chat.args)
        assertEquals(setOf("op", "요청id", "대화id", "문장", "메시지id"), keys(chat.toJson("r1")))
        assertEquals("한 마디", chat.toJson("r1").optString("문장"))
        assertTrue(chat.isChange)
        val reset = MemoOp.chat("c1", null, "m2", reset = true)
        assertEquals(setOf("op", "요청id", "대화id", "메시지id", "초기화"), keys(reset.toJson("r1")))
        assertTrue(reset.toJson("r1").optBoolean("초기화"))
        val poll = MemoOp.chatPoll("c1", 7)
        assertEquals(mapOf("대화id" to "c1", "마지막순번" to 7), poll.args)
        assertEquals(7, poll.toJson("r1").optInt("마지막순번"))
        assertFalse(poll.isChange)
        assertTrue(MemoOp.requestPoll().args.isEmpty())
        assertFalse(MemoOp.requestPoll().isChange)
        // 회귀: the instruction ops are exactly the ones of 앱21.
        assertEquals(mapOf("id" to "d1", "문장" to "책상 태그 삭제"), MemoOp.directive("d1", "책상 태그 삭제").args)
        assertEquals("지시", MemoOp.directive("d1", "책상 태그 삭제").op)
        assertEquals(setOf("op", "요청id", "id", "문장"), keys(MemoOp.directive("d1", "책상 태그 삭제").toJson("r1")))
        assertEquals(mapOf("ids" to listOf("d1")), MemoOp.directivePoll(listOf("d1")).args)
        assertEquals("[\"d1\"]", MemoOp.directivePoll(listOf("d1")).toJson("r1").optJSONArray("ids").toString())
        assertFalse(MemoOp.directivePoll(listOf("d1")).isChange)
    }

    @Test
    fun `a chat id is made once per phone and stays inside the server id rule`() = runTest {
        val db = db()
        val room = Room()
        val c = chat(db, room)
        c.load()
        c.send("한 마디")
        val first = room.ops.first { it.op == MEMO_OP_CHAT }.args["대화id"] as String
        c.send("또 한 마디")
        assertEquals(first, room.ops.last { it.op == MEMO_OP_CHAT }.args["대화id"])
        assertTrue(first.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        assertEquals("abc123-c-aaaaaa", memoNewChatId("abc 123!") { 0 })
    }
}
