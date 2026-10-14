/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 진단앱수신: the answers of the memo bot reach the phone, and what they carry is applied.
 *
 * The values are the shape the server really sends (`note_to_change`), with made-up ids, devices and times.
 */
class MemoReceiveTest : RobolectricTest() {
    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private val phoneOld = MemoRev(memoParseIso("2026-10-14T18:58:36+09:00")!!, "폰가짜1", 12)
    private val serverNew = JSONArray().put("2026-10-14T23:55:39+09:00").put("서버").put(16)

    /** The phone stamp as the server writes it back: ISO time, device, sequence (규약 §4). */
    private val phoneOldWire = JSONArray().put("2026-10-14T18:58:36+09:00").put("폰가짜1").put(12)

    private fun phoneNote(id: String, body: String = "사진 메모") = MemoNoteRecord(
        id = id,
        folder = MEMO_INBOX,
        title = "제목 $id",
        body = body,
        kind = "사진",
        createdMs = phoneOld.ms,
        revs = MemoRevs.all(phoneOld),
    )

    /** One `노트변경` of the server after its classification: new folder and tag, the class bundle stamped 23:55. */
    private fun serverChange(id: String, body: String, folder: String = "집·생활") = JSONObject()
        .put("id", id)
        .put("폴더", folder)
        .put("제목", "제목 $id")
        .put("본문", body)
        .put("태그", JSONArray().put("테스트"))
        .put("종류", "사진")
        .put("생성", memoIso(phoneOld.ms))
        .put("수정", "2026-10-14T23:55:39+09:00")
        .put("고정", false)
        .put("고정제안", false)
        .put("삭제", 0)
        .put("첨부", JSONArray())
        .put(
            "rev",
            JSONObject()
                .put("본문", phoneOldWire)
                .put("분류", serverNew)
                .put("표식", phoneOldWire)
                .put("첨부", phoneOldWire),
        )
        .put("기기", "서버")
        .put("순번", 16)
        .put("잘림", false)
        .put("머리말", JSONObject())

    // ---- the merge: the server classification wins over the old phone one

    @Test
    fun `a pull with the server classification moves the note and leaves it clean`() {
        val db = db()
        val id = "20261014-185836-aaaa"
        db.putNote(phoneNote(id))
        val content = JSONObject().put("v", 2).put("노트", JSONArray().put(serverChange(id, "사진 메모"))).put("기준", 31)
        MemoSync.applySync(db, content, phoneOld.ms)
        val stored = db.note(id)!!
        assertEquals("집·생활", stored.folder)
        assertEquals(listOf("테스트"), stored.tags)
        assertFalse(stored.dirty)
        assertEquals(31L, MemoSync.base(db))
    }

    @Test
    fun `a merged ack without the body moves the note but keeps our text`() {
        val db = db()
        val id = "20261014-185836-bbbb"
        db.putNote(phoneNote(id, body = "폰에서 쓴 글").copy(dirty = true))
        MemoSync.enqueueNote(db, phoneNote(id, body = "폰에서 쓴 글"), phoneOld.ms)
        val rows = MemoSync.dueUploads(db, phoneOld.ms)
        // The server sends back the merged note with body=False: 본문 is "".
        val ack = JSONObject()
            .put("결과", JSONArray().put(JSONObject().put("id", id).put("상태", "병합").put("노트", serverChange(id, ""))))
            .put("서버순번", 40)
        val result = MemoSync.applyAck(db, ack, rows, phoneOld.ms)
        val stored = db.note(id)!!
        assertEquals(1, result.merged)
        assertEquals("폰에서 쓴 글", stored.body)
        assertEquals("집·생활", stored.folder)
        assertEquals(listOf("테스트"), stored.tags)
        assertEquals(0, db.outboxCount())
        assertTrue(result.wantedIds.isEmpty())
    }

    @Test
    fun `a merged ack whose server body is newer asks for the note`() {
        val db = db()
        val id = "20261014-185836-cccc"
        db.putNote(phoneNote(id))
        MemoSync.enqueueNote(db, phoneNote(id), phoneOld.ms)
        val rows = MemoSync.dueUploads(db, phoneOld.ms)
        val change = serverChange(id, "")
        change.getJSONObject("rev").put("본문", serverNew)
        val ack = JSONObject()
            .put("결과", JSONArray().put(JSONObject().put("id", id).put("상태", "병합").put("노트", change)))
            .put("서버순번", 41)
        val result = MemoSync.applyAck(db, ack, rows, phoneOld.ms)
        assertEquals(listOf(id), result.wantedIds)
        assertEquals("사진 메모", db.note(id)!!.body)
    }

    // ---- the answer reader: the widget protocol of the SDK driver

    private class FakeDriver : MatrixWidgetDriver {
        override val id = MEMO_READER_WIDGET_ID
        override val incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 10)
        val sent = mutableListOf<JSONObject>()
        override suspend fun run() = Unit
        override suspend fun send(message: String) {
            sent += JSONObject(message)
        }
        override fun close() = Unit
    }

    private fun toWidget(action: String, data: JSONObject) = JSONObject()
        .put("api", "toWidget")
        .put("widgetId", MEMO_READER_WIDGET_ID)
        .put("requestId", "q-$action")
        .put("action", action)
        .put("data", data)
        .toString()

    @Test
    fun `the reader asks to read the answer types only and is ready once granted`() = runTest {
        val driver = FakeDriver()
        val reader = MemoAnswerReader(driver, onAnswer = {})
        reader.handle(toWidget("capabilities", JSONObject()))
        val asked = driver.sent.single().getJSONObject("response").getJSONArray("capabilities")
        assertEquals(MEMO_RESPONSE_TYPES.size, asked.length())
        assertTrue((0 until asked.length()).all { asked.getString(it).startsWith("org.matrix.msc2762.receive.event:app.june.memo.") })
        assertFalse(reader.ready.isCompleted)
        reader.handle(toWidget("notify_capabilities", JSONObject().put("requested", asked).put("approved", asked)))
        assertTrue(reader.ready.getCompleted())
    }

    @Test
    fun `a decrypted sync answer from the driver reaches the waiting call`() = runTest {
        val driver = FakeDriver()
        val got = mutableListOf<MemoResponse>()
        val reader = MemoAnswerReader(driver, onAnswer = { got += it })
        val event = JSONObject()
            .put("type", MEMO_SYNC_TYPE)
            .put("sender", "@bot:example.org")
            .put("event_id", "\$fake")
            .put("room_id", "!fake:example.org")
            .put("content", JSONObject().put("요청id", "r-1").put("노트", JSONArray()).put("기준", 5))
        reader.handle(toWidget("send_event", event))
        val answer = got.single() as MemoResponse.Sync
        assertEquals("r-1", answer.requestId)
        assertEquals(5, answer.content.getInt("기준"))
        // Every request of the driver gets its answer, or the driver would wait for it.
        assertTrue(driver.sent.single().has("response"))
    }

    @Test
    fun `other events and our own answers are not taken for memo answers`() = runTest {
        val driver = FakeDriver()
        val got = mutableListOf<MemoResponse>()
        val reader = MemoAnswerReader(driver, onAnswer = { got += it })
        reader.handle(toWidget("send_event", JSONObject().put("type", "m.room.message").put("content", JSONObject().put("body", "x"))))
        reader.handle(JSONObject().put("api", "fromWidget").put("action", "content_loaded").put("response", JSONObject()).toString())
        reader.handle("not json")
        assertTrue(got.isEmpty())
        assertEquals(1, driver.sent.size)
    }
}
