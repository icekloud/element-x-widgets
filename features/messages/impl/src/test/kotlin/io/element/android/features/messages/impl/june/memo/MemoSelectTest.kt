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
 * 앱11: the pinned strip (A·B), the photos under the text of a card (D), the selection mode with its bulk actions
 * (C), the folder icons (추가요청 1), the tab swipe (추가요청 2) and the instruction look-ups (추가요청 3).
 * All data is made up.
 */
class MemoSelectTest : RobolectricTest() {
    private var now = 1_791_772_260_000L // 2026-10-12T11:31:00+09:00

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    private suspend fun MemoLocalBackend.add(text: String, photos: Int = 0): String {
        now += 1_000
        val files = (1..photos).map { MemoLocalAttachment(id = "a$it-$now", localPath = "/p/$now-$it.jpg", mime = "image/jpeg", bytes = 3) }
        return create(text, files)
    }

    private fun noteOutbox(db: MemoDb) = db.outbox().filter { it.kind == MEMO_OUTBOX_NOTE }

    private fun item(id: String = "n1", title: String = "", number: Int = 0, untitled: Boolean = title.isEmpty()) =
        MemoItem(id, "텍스트", title, null, "2026-10-15T15:23:48+09:00", emptyList(), MEMO_INBOX, number = number, untitled = untitled)

    // ---------------------------------------------------------------- B: no note id on any screen

    @Test
    fun `an untitled note without text never shows its id - number and date, or kind and date`() {
        assertEquals("", memoDisplayTitle("", "", "20261015-152348-test"))
        assertEquals("첫 줄", memoDisplayTitle("", "\n첫 줄\n둘째", "20261015-152348-test"))
        assertEquals("#7  10-15", memoItemLine(item(number = 7)))
        assertEquals("📝 텍스트  10-15", memoItemLine(item()))
        // An old screen value equal to the id is not shown either.
        assertEquals("#3  10-15", memoItemLine(item(id = "x-1", title = "x-1", number = 3, untitled = false)))
        assertEquals("#3  교토 빵집", memoItemLine(item(title = "교토 빵집", number = 3, untitled = false)))
    }

    @Test
    fun `the strip shows a thumbnail for a photo-only note, text for the others`() = runTest {
        val db = db()
        val b = backend(db)
        val photo = b.add("", photos = 3)
        val withText = b.add("사진 설명", photos = 1)
        val text = b.add("그냥 글")
        listOf(photo, withText, text).forEach { b.call(MemoOp.pin(MemoTarget(it)), "p") }
        val items = db.list(MEMO_PINNED_LIST, emptyList(), null, null).items.associateBy { it.id }
        val cell = memoPinCell(items.getValue(photo))
        assertTrue(cell is MemoPinCell.Photo)
        assertEquals(3, (cell as MemoPinCell.Photo).count)
        assertEquals(MemoPinCell.Text(withText, "사진 설명"), memoPinCell(items.getValue(withText)))
        assertEquals(MemoPinCell.Text(text, "그냥 글"), memoPinCell(items.getValue(text)))
        assertFalse(memoPinCell(items.getValue(photo)).let { it is MemoPinCell.Text && photo in it.text })
    }

    // ---------------------------------------------------------------- A: the pinned notes of every folder

    @Test
    fun `the record tab strip has the pinned notes of every folder, not the trash, newest first`() = runTest {
        val db = db()
        val b = backend(db)
        b.call(MemoOp.mkdir("여행", null), "m")
        val a = b.add("가")
        val c = b.add("나")
        val trashed = b.add("다")
        b.add("고정 안 함")
        b.call(MemoOp.move(MemoTarget(c), "여행"), "mv")
        listOf(a, c, trashed).forEach { b.call(MemoOp.pin(MemoTarget(it)), "p") }
        b.call(MemoOp.trash(MemoTarget(trashed)), "t")
        assertEquals(listOf(c, a), db.list(MEMO_PINNED_LIST, emptyList(), null, null).items.map { it.id })
        // Nothing pinned: the strip has nothing to show (the screen draws no area then).
        listOf(a, c).forEach { b.call(MemoOp.unpin(MemoTarget(it)), "u") }
        assertTrue(db.list(MEMO_PINNED_LIST, emptyList(), null, null).items.isEmpty())
    }

    // ---------------------------------------------------------------- D: photos under the text

    @Test
    fun `a text note with photos has its text and its photos, a photo note keeps its look`() = runTest {
        val db = db()
        val b = backend(db)
        val text = b.add("교토 빵집 메모")
        val added = memoPhotoArg(MemoLocalAttachment("x", localPath = "/p/x.jpg", mime = "image/jpeg", bytes = 3))
        b.call(MemoOp("edit", linkedMapOf("id" to text, MEMO_ARG_PHOTOS_ADDED to listOf(added))), "e")
        val photo = b.add("", photos = 5)
        val items = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.associateBy { it.id }
        val t = items.getValue(text)
        assertEquals("교토 빵집 메모", memoCardText(t))
        // The phone edit made it a photo note (kind follows the photos): the card has both, its text and its photo.
        assertEquals(1, (t.photos ?: t.preview as? MemoPreview.Photo)?.count)
        val p = items.getValue(photo)
        assertTrue(p.preview is MemoPreview.Photo)
        assertNull(p.photos)
        assertEquals(MEMO_CARD_PHOTOS, (p.preview as MemoPreview.Photo).cells.size)
        // The kind the server gave does not matter: "텍스트" with photos and text still shows both.
        val shown = memoLocalPreview("텍스트", "글", null, listOf(MemoLocalAttachment("a", mxc = "mxc://test/1", state = MEMO_ATTACH_DONE)))
        assertEquals(1, memoCardPhotos(shown, listOf(MemoLocalAttachment("a", mxc = "mxc://test/1", state = MEMO_ATTACH_DONE)))?.count)
        assertNull(memoCardPhotos(shown, emptyList()))
    }

    // ---------------------------------------------------------------- C: the selection mode

    @Test
    fun `select, unselect, select all and end - ending drops the selection, off does nothing`() {
        var s = MemoSelect()
        assertEquals(s, s.toggle("a"))
        s = s.start().toggle("a").toggle("b")
        assertEquals(listOf("a", "b"), s.ids)
        s = s.toggle("a")
        assertEquals(listOf("b"), s.ids)
        s = s.all(listOf("a", "b", "c"))
        assertEquals(3, s.count)
        assertTrue(s.isSelected("c"))
        assertEquals(0, s.none().count)
        assertTrue(s.none().active)
        assertEquals(MemoSelect(), s.end())
        assertFalse(s.end().active)
    }

    @Test
    fun `bulk trash - one transaction, one outbox row per note, the trashed one is left, restore works`() = runTest {
        val db = db()
        val b = backend(db)
        val photo = b.add("", photos = 1)
        val x = b.add("가")
        val y = b.add("나")
        b.call(MemoOp.trash(MemoTarget(y)), "t")
        db.outbox().forEach { db.dropOutbox(it.seq) }
        val r = b.call(memoBulkTrash(listOf(photo, x, y, "없는-노트")), "bt") as MemoResponse.Ok
        assertEquals("2", r.path)
        assertTrue(listOf(photo, x, y).all { db.note(it)!!.deleted == MEMO_DELETED_TRASH })
        assertEquals(listOf(photo, x).sorted(), noteOutbox(db).map { it.target }.sorted())
        // The same mark stamp as a one-note trash.
        val n = db.note(x)!!
        assertEquals(n.revs.mark, n.revs.max)
        assertEquals(1, db.note(photo)!!.attachments.size)
        b.call(MemoOp.restore(MemoTarget(x)), "r")
        assertEquals(MEMO_DELETED_NONE, db.note(x)!!.deleted)
    }

    @Test
    fun `bulk tags are only added - a note that already has them does not change`() = runTest {
        val db = db()
        val b = backend(db)
        val x = b.add("가")
        val y = b.add("나")
        b.call(MemoOp.editTags(MemoTarget(y), listOf("교토", "빵")), "t")
        db.outbox().forEach { db.dropOutbox(it.seq) }
        val classY = db.note(y)!!.revs.classes
        b.call(memoBulkTag(listOf(x, y), listOf("#교토", "빵")), "bt")
        assertEquals(listOf("교토", "빵"), db.note(x)!!.tags)
        assertEquals(listOf("교토", "빵"), db.note(y)!!.tags)
        assertEquals(classY, db.note(y)!!.revs.classes)
        assertEquals(listOf(x), noteOutbox(db).map { it.target })
        b.call(memoBulkTag(listOf(x, y), listOf("새")), "bt2")
        assertEquals(listOf("교토", "빵", "새"), db.note(y)!!.tags)
        assertEquals(listOf(x, y).sorted(), noteOutbox(db).map { it.target }.sorted())
        assertEquals(1, noteOutbox(db).count { it.target == x })
    }

    @Test
    fun `bulk move - the same folder changes nothing, the trash and an unknown folder are refused`() = runTest {
        val db = db()
        val b = backend(db)
        b.call(MemoOp.mkdir("여행", null), "m")
        val x = b.add("가")
        val y = b.add("나")
        b.call(MemoOp.move(MemoTarget(y), "여행"), "mv")
        db.outbox().forEach { db.dropOutbox(it.seq) }
        b.call(memoBulkMove(listOf(x, y), "여행"), "bm")
        assertEquals("여행", db.note(x)!!.folder)
        assertEquals(listOf(x), noteOutbox(db).map { it.target })
        assertTrue(b.call(memoBulkMove(listOf(x), MEMO_TRASH), "bm2") is MemoResponse.Error)
        assertTrue(b.call(memoBulkMove(listOf(x), "없는폴더"), "bm3") is MemoResponse.Error)
        assertEquals("여행", db.note(x)!!.folder)
        // The class stamp is the one of a one-note move.
        val n = db.note(x)!!
        assertEquals(n.revs.classes, n.revs.max)
    }

    @Test
    fun `the store keeps its lists in line with a bulk action`() = runTest {
        val db = db()
        val b = backend(db)
        val store = MemoStore(b)
        val x = b.add("가")
        val y = b.add("나")
        val key = MemoListKey.of(MEMO_ALL_FOLDERS, emptyList())
        store.loadFirst(key)
        store.request(memoBulkTag(listOf(x), listOf("교토")))
        assertEquals(listOf("교토"), store.list(key).items.first { it.id == x }.tags)
        store.request(memoBulkTrash(listOf(x, y)))
        assertTrue(store.list(key).items.isEmpty())
    }

    // ---------------------------------------------------------------- 추가요청 1: folder icons

    @Test
    fun `an empty folder icon from the server shows the default one, a chosen icon stays`() = runTest {
        val db = db()
        val b = backend(db)
        MemoSync.applySync(
            db,
            JSONObject().put("노트", JSONArray()).put("기준", 1).put(
                "폴더",
                JSONArray()
                    .put(JSONObject().put("경로", MEMO_INBOX).put("아이콘", ""))
                    .put(JSONObject().put("경로", "쇼핑").put("아이콘", ""))
                    .put(JSONObject().put("경로", "사람").put("아이콘", "🧑")),
            ),
            now,
        )
        val store = MemoStore(b)
        store.refreshIndex()
        val f = store.index.value.folders.associateBy { it.path }
        assertEquals("📥", f.getValue(MEMO_INBOX).icon)
        // 앱14 B: no icon stored → the name rules (쇼핑 → 🛒).
        assertEquals("🛒", f.getValue("쇼핑").icon)
        assertEquals("🧑", f.getValue("사람").icon)
        // The database keeps what the server sent (no schema or value change).
        assertEquals("", db.folders().first { it.path == "쇼핑" }.icon)
    }

    // ---------------------------------------------------------------- 추가요청 2: swipe between tabs

    @Test
    fun `a horizontal swipe goes to the next or previous tab, not past the ends, not from an edge`() {
        val w = 1000f
        assertEquals(1, memoSwipeDirection(500f, -200f, 10f, w, 40f, 80f, consumed = false))
        assertEquals(-1, memoSwipeDirection(500f, 200f, 10f, w, 40f, 80f, consumed = false))
        assertEquals(0, memoSwipeDirection(500f, -60f, 0f, w, 40f, 80f, consumed = false))
        assertEquals(0, memoSwipeDirection(500f, -200f, 150f, w, 40f, 80f, consumed = false))
        assertEquals(0, memoSwipeDirection(20f, -200f, 0f, w, 40f, 80f, consumed = false))
        assertEquals(0, memoSwipeDirection(980f, 200f, 0f, w, 40f, 80f, consumed = false))
        assertEquals(0, memoSwipeDirection(500f, -200f, 0f, w, 40f, 80f, consumed = true))
        assertEquals(MemoTab.Folders, memoSwipeTab(MemoTab.Record, 1))
        assertEquals(MemoTab.Search, memoSwipeTab(MemoTab.Folders, 1))
        assertEquals(MemoTab.Record, memoSwipeTab(MemoTab.Folders, -1))
        assertNull(memoSwipeTab(MemoTab.Search, 1))
        assertNull(memoSwipeTab(MemoTab.Record, -1))
        assertNull(memoSwipeTab(MemoTab.Folders, 0))
        // An open note, the selection mode or a photo viewer: the screen does not swipe.
        assertFalse(memoSwipeAllowed(MemoLocation(note = "n1"), selecting = false, viewer = false))
        assertFalse(memoSwipeAllowed(MemoLocation(), selecting = true, viewer = false))
        assertFalse(memoSwipeAllowed(MemoLocation(), selecting = false, viewer = true))
        assertTrue(memoSwipeAllowed(MemoLocation(tab = MemoTab.Folders, folder = "여행"), selecting = false, viewer = false))
    }

    // ---------------------------------------------------------------- 추가요청 3: instruction look-ups

    private class Orders(var answer: (MemoOp) -> MemoResponse) : MemoSyncTransport {
        val ops = mutableListOf<MemoOp>()

        override suspend fun call(op: MemoOp): MemoResponse {
            ops += op
            return answer(op)
        }

        override suspend fun fetchProposals(): List<JSONObject>? = null
        override suspend fun upload(bytes: ByteArray): String = "mxc://test/1"
        override suspend fun maxUploadSize(): Long? = null
    }

    @Test
    fun `look-ups start after 3 s and run every 3 s in the first 30 s, the wait is shown in seconds`() {
        val t0 = 1_760_000_000_000L
        assertEquals(3_000L, memoDirectivePollDelayMs(t0, t0))
        assertEquals(3_000L, memoDirectivePollDelayMs(t0, t0 + 20_000))
        assertEquals(5_000L, memoDirectivePollDelayMs(t0, t0 + 40_000))
        assertEquals(15_000L, memoDirectivePollDelayMs(t0, t0 + 90_000))
        assertNull(memoDirectivePollDelayMs(t0, t0 + 181_000))
        val d = MemoDirective("d1", "문장", MemoDirectiveStatus.RECEIVED, sentMs = t0)
        assertEquals("메모봇이 받았어요 · 처리 중… (12초)", memoDirectiveAnswerText(d, t0 + 12_400))
        assertEquals("보내는 중… (2초)", memoDirectiveAnswerText(d.copy(status = MemoDirectiveStatus.SENDING), t0 + 2_000))
        assertTrue(memoDirectiveAnswerText(d, t0 + 200_000).contains("새로고침"))
        assertEquals("끝났어요", memoDirectiveAnswerText(d.copy(status = MemoDirectiveStatus.DONE), t0 + 12_000))
    }

    @Test
    fun `a send cut short is asked about by its id and shows the server answer - never stuck as sending`() = runTest {
        val db = db()
        // The screen went away during the send: the database still says 보내는중 for this process.
        db.setState("directives", JSONArray().put(MemoDirective("d1", "태그 추가 검토", MemoDirectiveStatus.SENDING, sentMs = now).toJson()).toString())
        val room = Orders { op ->
            when (op.op) {
                "지시조회" -> {
                    val row = JSONObject().put("id", "d1").put("상태", "완료").put("메시지", "태그 2개를 더했어요")
                    MemoResponse.Ok("r", "지시조회", null, null, JSONObject().put("결과", JSONArray().put(row)))
                }
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }
        val d = MemoDirectives(db, room, clock = { now }, dispatcher = Dispatchers.Unconfined)
        d.load()
        assertEquals(1, d.poll())
        assertEquals(MemoDirectiveStatus.DONE, d.items.value.single().status)
        assertEquals("태그 2개를 더했어요", memoDirectiveAnswerText(d.items.value.single(), now))
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("d1"), room.ops.single { it.op == "지시조회" }.args["ids"] as List<String>)
    }

    @Test
    fun `an instruction that never reached the server becomes not sent and can go again, a later answer still lands`() = runTest {
        val db = db()
        db.setState("directives", JSONArray().put(MemoDirective("d2", "폴더 정리", MemoDirectiveStatus.NOT_SENT, sentMs = now).toJson()).toString())
        var rows = JSONArray().put(JSONObject().put("id", "d2").put("상태", "실패").put("메시지", "그런 지시를 찾지 못했어요."))
        val room = Orders { op -> MemoResponse.Ok("r", op.op, null, null, JSONObject().put("결과", rows)) }
        val d = MemoDirectives(db, room, clock = { now }, dispatcher = Dispatchers.Unconfined)
        d.load()
        assertEquals(0, d.poll())
        assertEquals(MemoDirectiveStatus.NOT_SENT, d.items.value.single().status)
        assertTrue(d.items.value.single().canResend)
        // A send that timed out on the phone but reached the server: the look-up brings its real state.
        rows = JSONArray().put(JSONObject().put("id", "d2").put("상태", "처리중").put("메시지", ""))
        assertEquals(0, d.poll())
        assertEquals(MemoDirectiveStatus.WORKING, d.items.value.single().status)
        assertTrue(d.nextPollInMs() != null)
    }
}
