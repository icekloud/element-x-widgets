/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱28 추가요청 1: the photo upload and proposal receive lines of the settings diagnosis (shown only). */
class MemoDiagTest : RobolectricTest() {
    // 2026-10-25 11:24:25 KST
    private val t0 = 1_761_359_065_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    // ---------------------------------------------------------------- pure text

    @Test
    fun `times are KST, with the date only when it is not today, and none reads 없음`() {
        assertEquals("11:24:25", memoDiagTime(t0, t0))
        assertEquals("10-24 11:24", memoDiagTime(t0 - 86_400_000L, t0))
        assertEquals("없음", memoDiagTime(null, t0))
        assertEquals("없음", memoDiagTime(0, t0))
        // 15:00 UTC is midnight in Seoul: the next day already.
        val utcAfternoon = 1_761_404_400_000L // 2026-10-25T15:00:00Z
        assertEquals("10-26 00:00", memoDiagTime(utcAfternoon, t0))
    }

    @Test
    fun `a reason is one line of at most 80 characters without paths, addresses or ids`() {
        val r = memoDiagReason("올리기", "IOException: /data/user/0/app/files/june_memo/photo-1.jpg at https://host.example/_matrix/media @me:host.example")
        assertTrue(r, r.length <= MEMO_DIAG_REASON_CHARS)
        assertFalse(r, r.contains("/data"))
        assertFalse(r, r.contains("https"))
        assertFalse(r, r.contains("@me"))
        assertTrue(r, r.startsWith("올리기: IOException"))
        val long = memoDiagReason("읽기", "x".repeat(200))
        assertEquals(MEMO_DIAG_REASON_CHARS, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("암호화", memoDiagReason("암호화", null))
        assertEquals("암호화", memoDiagReason("암호화", "   "))
        assertEquals("읽기: a b", memoDiagReason("읽기", "a\n\tb"))
    }

    @Test
    fun `the photo lines say what waits, what failed, the last try and the next one`() {
        val lines = memoPhotoDiagLines(
            MemoPhotoDiag(waiting = 2, failed = 1, noSource = 0, lastTry = MemoDiagMark(t0, result = "올리기: 시간 초과"), nextTryMs = t0 + 60_000),
            t0 + 1_000,
        )
        assertEquals("사진 올리기: 대기 2 · 실패 1", lines[0])
        assertEquals("마지막 시도 11:24:25 올리기: 시간 초과 · 다음 시도 11:25:25", lines[1])
        val none = memoPhotoDiagLines(MemoPhotoDiag(0, 0, 0, null, null), t0)
        assertEquals("시도 기록 없음 · 다음 시도 없음", none[1])
        val due = memoPhotoDiagLines(MemoPhotoDiag(1, 0, 3, null, 0), t0)
        assertEquals("사진 올리기: 대기 1 · 실패 0 · 원본 없음 3", due[0])
        assertTrue(due[1].endsWith("다음 시도 지금 가능"))
    }

    @Test
    fun `the proposal lines say 받은 적 없음 before the first index and list the unsent answers`() {
        val never = memoProposalDiagLines(MemoProposalDiag(null, null, null, null, 0, 0, emptyList(), null), null, t0)
        assertEquals("제안 목록(방 상태): $MEMO_DIAG_NONE", never[0])
        assertEquals("받기(sync_pull): 기록 없음", never[1])
        assertEquals("올리기(sync_push): 기록 없음", never[2])
        assertEquals("폰 제안: 대기 0 · 답함 0 · 서버에 못 전한 답 0", never[3])
        assertEquals("제안결과: $MEMO_DIAG_NONE", never[4])
        assertEquals("응답 읽기: 이번 실행에서 시작 안 함", never[5])

        val d = MemoProposalDiag(
            index = MemoDiagMark(t0, 4),
            indexFail = MemoDiagMark(t0 + 5_000),
            pull = MemoDiagMark(t0 + 4_000, result = "답 없음: 시간 초과"),
            push = MemoDiagMark(t0 + 6_000, 2, "답 없음: 시간 초과"),
            localWaiting = 2,
            localAnswered = 2,
            unsent = listOf(
                MemoUnsentAnswer(t0 + 60_000, 2, "서버에 닿지 못함"),
                MemoUnsentAnswer(0, 0, null),
                MemoUnsentAnswer(0, 1, null),
                MemoUnsentAnswer(0, 1, null),
            ),
            results = MemoDiagMark(t0 - 86_400_000L, 2),
        )
        val lines = memoProposalDiagLines(d, MemoReaderDiag("준비됨", t0, t0 + 2_000), t0 + 10_000)
        assertEquals("제안 목록(방 상태): 11:24:25 받음 4건 · 읽기 실패 11:24:30", lines[0])
        assertEquals("받기(sync_pull): 11:24:29 답 없음: 시간 초과", lines[1])
        assertEquals("올리기(sync_push): 11:24:31 답 없음: 시간 초과", lines[2])
        assertEquals("폰 제안: 대기 2 · 답함 2 · 서버에 못 전한 답 4", lines[3])
        assertEquals(" · 다음 시도 11:25:25 · 시도 2번: 서버에 닿지 못함", lines[4])
        assertEquals(" · 다음 시도 지금 · 아직 보내지 않음", lines[5])
        assertEquals(" · 다음 시도 지금 · 사유 없음", lines[6])
        assertEquals(" · 외 1건", lines[7])
        assertEquals("제안결과: 10-24 11:24 2건", lines[8])
        assertEquals("응답 읽기: 준비됨 11:24:25 · 마지막 응답 11:24:27", lines[9])
    }

    @Test
    fun `an index failure older than the last good read is not shown`() {
        val d = MemoProposalDiag(MemoDiagMark(t0 + 1_000, 1), MemoDiagMark(t0), null, null, 0, 0, emptyList(), null)
        assertEquals("제안 목록(방 상태): 11:24:26 받음 1건", memoProposalDiagLines(d, null, t0 + 2_000)[0])
    }

    @Test
    fun `a mark survives its round trip and a broken one reads as none`() {
        val m = MemoDiagMark(t0, 3, "올림")
        assertEquals(m, MemoDiagMark.parse(m.toJson()))
        assertEquals(MemoDiagMark(t0), MemoDiagMark.parse(MemoDiagMark(t0).toJson()))
        assertNull(MemoDiagMark.parse(null))
        assertNull(MemoDiagMark.parse("{"))
        assertNull(MemoDiagMark.parse("{\"n\":1}"))
    }

    // ---------------------------------------------------------------- what the sync remembers

    /** The memo bot room: sync_pull answers [pull]; sync_push accepts all and answers the proposal answers as [answerState]. */
    private class Room(
        var online: Boolean = true,
        var proposals: List<JSONObject>? = emptyList(),
        var uploadFails: Boolean = false,
        var answerState: String = "수락",
        /** The `제안결과` of a push answer leaves these ids out (an answer the server never confirmed). */
        var unanswered: Set<String> = emptySet(),
    ) : MemoSyncTransport {
        override suspend fun call(op: MemoOp): MemoResponse {
            if (!online) throw MemoSendException("off")
            return when (op.op) {
                "sync_pull" -> MemoResponse.Sync("r", JSONObject().put("노트", JSONArray()).put("기준", 0))
                "sync_push" -> {
                    val changes = op.args["변경"] as JSONArray
                    val results = JSONArray()
                    for (i in 0 until changes.length()) results.put(JSONObject().put("id", changes.getJSONObject(i).getString("id")).put("상태", "수락"))
                    val answers = JSONArray()
                    (op.args["제안응답"] as? JSONArray)?.let { a ->
                        for (i in 0 until a.length()) {
                            val id = a.getJSONObject(i).getString("id")
                            if (id !in unanswered) answers.put(JSONObject().put("id", id).put("상태", answerState))
                        }
                    }
                    MemoResponse.SyncAck("r", JSONObject().put("결과", results).put("제안결과", answers).put("서버순번", 0))
                }
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        override suspend fun fetchProposals(): List<JSONObject>? = if (online) proposals else null

        override suspend fun upload(bytes: ByteArray): String {
            if (!online || uploadFails) throw MemoSendException("off")
            return "mxc://test/1"
        }

        override suspend fun maxUploadSize(): Long? = null
    }

    private fun proposal(id: String, note: String = "n1") = JSONObject().put("id", id).put("종류", "제목").put("노트", note).put("요약", "제목: 새 제목")

    @Test
    fun `the photo try is remembered with its step and the next try, and the text is made again`() = runTest {
        val db = db()
        val local = MemoLocalBackend({ db }, clock = { now }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        local.create("", listOf(MemoLocalAttachment(id = "a1", localPath = "/p/1.jpg")))
        val room = Room(uploadFails = true)
        val texts = mutableListOf<String>()
        val r = MemoSyncRunner(db, room, clock = { now }, readFile = { byteArrayOf(1, 2, 3) }, diag = { texts += it })
        r.push(force = true)
        val photo = memoPhotoDiagOf(db)
        assertEquals(1, photo.waiting)
        assertEquals(0, photo.failed)
        assertEquals("올리기: MemoSendException", photo.lastTry?.result)
        assertEquals(t0 + MemoSync.BACKOFF_MS[0], photo.nextTryMs)
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.last(), texts.last().contains("마지막 시도 11:24:25 올리기: MemoSendException · 다음 시도 11:24:30"))

        room.uploadFails = false
        now += MemoSync.BACKOFF_MS[0]
        r.push(force = true)
        assertEquals("올림", memoPhotoDiagOf(db).lastTry?.result)
        assertEquals(0, memoPhotoDiagOf(db).waiting)
        assertNull(memoPhotoDiagOf(db).nextTryMs)
    }

    @Test
    fun `a photo that cannot be read is remembered as a read failure, without its path`() = runTest {
        val db = db()
        val local = MemoLocalBackend({ db }, clock = { now }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        local.create("", listOf(MemoLocalAttachment(id = "a1", localPath = "/p/secret/1.jpg")))
        val r = MemoSyncRunner(db, Room(), clock = { now }, readFile = { error("cannot open /p/secret/1.jpg") })
        r.push(force = true)
        val d = memoPhotoDiagOf(db)
        assertEquals(1, d.failed)
        assertEquals("읽기: IllegalStateException", d.lastTry?.result)
    }

    @Test
    fun `an attachment with no original and no mxc is counted as without source`() {
        val db = db()
        val n = MemoNoteRecord(
            id = "n1",
            folder = MEMO_INBOX,
            title = "",
            body = "",
            kind = "사진",
            createdMs = t0,
            revs = MemoRevs.all(db.nextRev("p", t0)),
            attachments = listOf(MemoLocalAttachment(id = "a1"), MemoLocalAttachment(id = "a2", localPath = "/p/2.jpg")),
        )
        db.putNote(n)
        val d = memoPhotoDiagOf(db)
        assertEquals(1, d.waiting)
        assertEquals(1, d.noSource)
    }

    @Test
    fun `the index read, the pull and the proposal results are remembered`() = runTest {
        val db = db()
        val room = Room(proposals = listOf(proposal("n1.ttl"), proposal("n2.ttl", "n2")))
        val r = MemoSyncRunner(db, room, clock = { now })
        assertTrue(r.syncNow())
        var d = memoProposalDiagOf(db)
        assertEquals(MemoDiagMark(t0, 2), d.index)
        assertEquals(0, d.pull?.count)
        assertEquals("답 받음 · 적용 0건 · 개별받기 0건", d.pull?.result)
        assertEquals(2, d.localWaiting)
        assertNull(d.results)

        // An answer goes up and the server confirms it.
        val local = MemoLocalBackend({ db }, clock = { now }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        local.call(MemoOp("dismiss", mapOf("id" to "n1.ttl")), "q")
        assertEquals(1, memoProposalDiagOf(db).unsent.size)
        now += 1_000
        assertTrue(r.pushAnswers())
        d = memoProposalDiagOf(db)
        assertEquals(MemoDiagMark(t0 + 1_000, 1, "이 폰 답과 맞음 1건"), d.results)
        assertEquals(MemoDiagMark(t0 + 1_000, 1, "답 받음 · 노트 0건 · 제안응답 1건"), d.push)
        assertTrue(d.unsent.isEmpty())

        // The index cannot be read: the failure time is kept, the last good read too.
        room.proposals = null
        now += 1_000
        r.syncNow()
        d = memoProposalDiagOf(db)
        assertEquals(MemoDiagMark(t0, 2), d.index)
        assertEquals(t0 + 2_000, d.indexFail?.atMs)
    }

    @Test
    fun `a pull without an answer is remembered with why, and the index is then not read`() = runTest {
        val db = db()
        val room = Room(online = false)
        val r = MemoSyncRunner(db, room, clock = { now })
        assertFalse(r.syncNow())
        val d = memoProposalDiagOf(db)
        assertEquals("답 없음: 보내지 못함 MemoSendException", d.pull?.result)
        assertNull(d.index)
        assertTrue(memoProposalDiagLines(d, null, now)[0].endsWith(MEMO_DIAG_NONE))
    }

    @Test
    fun `an answer the server does not confirm stays unsent with its next try`() = runTest {
        val db = db()
        val room = Room(proposals = listOf(proposal("n1.ttl")), unanswered = setOf("n1.ttl"))
        val r = MemoSyncRunner(db, room, clock = { now })
        r.syncNow()
        val local = MemoLocalBackend({ db }, clock = { now }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        local.call(MemoOp("accept", mapOf("id" to "n1.ttl")), "q")
        r.pushAnswers()
        val d = memoProposalDiagOf(db)
        // The push got its answer, without a result for this id: nothing failed, the answer just stays (case b).
        assertEquals(1, d.unsent.size)
        assertEquals(0, d.unsent.single().tries)
        assertNull(d.unsent.single().reason)
        assertEquals(1, d.localAnswered)
        assertNull(d.results)

        room.online = false
        r.pushAnswers()
        val off = memoProposalDiagOf(db).unsent.single()
        assertEquals(1, off.tries)
        assertEquals("서버에 닿지 못함", off.reason)
        assertTrue(off.nextTryMs > now)
        // The push line tells which one it was: here the send itself failed (not a missed answer).
        assertEquals("답 없음: 보내지 못함 MemoSendException", memoProposalDiagOf(db).push?.result)
    }

    @Test
    fun `the whole text has no room, user, path or address in it`() = runTest {
        val db = db()
        val local = MemoLocalBackend({ db }, clock = { now }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        local.create("", listOf(MemoLocalAttachment(id = "a1", localPath = "/data/user/0/x/files/june_memo/photo-1.jpg")))
        val texts = mutableListOf<String>()
        val r = MemoSyncRunner(db, Room(uploadFails = true), clock = { now }, readFile = { byteArrayOf(1) }, diag = { texts += it })
        r.syncNow()
        val text = texts.last()
        assertTrue(text, text.startsWith("기준 11:24:25"))
        listOf("/data", "june_memo", "mxc://", "@", "!").forEach { assertFalse("$it in $text", text.contains(it)) }
    }
}
