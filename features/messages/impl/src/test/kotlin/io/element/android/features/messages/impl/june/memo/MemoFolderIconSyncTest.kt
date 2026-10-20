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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱15 A: the folder icon picked on the phone goes up with `sync_push` (`폴더아이콘[]`). Made up data. */
class MemoFolderIconSyncTest : RobolectricTest() {
    private var now = 1_760_000_000_000L

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    /**
     * The memo bot room. [icons] holds what a server of 서버 10차 keeps (null: an old server that ignores the key);
     * its pull answer lists [folders] with the icons it holds. The answer shape of `sync_push` is the same for both.
     */
    private class IconRoom(
        var online: Boolean = true,
        val icons: MutableMap<String, String>? = mutableMapOf(),
        val folders: MutableList<String> = mutableListOf(),
    ) : MemoSyncTransport {
        val pushes = mutableListOf<Map<String, Any?>>()

        override suspend fun call(op: MemoOp): MemoResponse {
            if (!online) throw MemoSendException("off")
            return when (op.op) {
                "sync_pull" -> MemoResponse.Sync("r", pullAnswer())
                "sync_push" -> {
                    pushes += op.args
                    (op.args["폴더아이콘"] as? JSONArray)?.let { take(it) }
                    val results = JSONArray()
                    val changes = op.args["변경"] as JSONArray
                    for (i in 0 until changes.length()) {
                        results.put(JSONObject().put("id", changes.getJSONObject(i).getString("id")).put("상태", "수락"))
                    }
                    MemoResponse.SyncAck("r", JSONObject().put("결과", results).put("제안결과", JSONArray()).put("서버순번", 0))
                }
                else -> MemoResponse.Error("r", op.op, "모름")
            }
        }

        private fun take(items: JSONArray) {
            val kept = icons ?: return
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val path = item.getString("경로")
                val icon = item.getString("아이콘")
                if (icon.isEmpty()) kept.remove(path) else kept[path] = icon
            }
        }

        private fun pullAnswer(): JSONObject {
            val list = JSONArray()
            folders.forEach { list.put(JSONObject().put("경로", it).put("아이콘", icons?.get(it).orEmpty())) }
            return JSONObject().put("노트", JSONArray()).put("기준", 1).put("폴더", list)
        }

        override suspend fun fetchProposals(): List<JSONObject>? = if (online) emptyList() else null

        override suspend fun upload(bytes: ByteArray): String = throw MemoSendException("no uploads here")

        override suspend fun maxUploadSize(): Long? = null

        fun sentIcons(): List<Pair<String, String>> = pushes.mapNotNull { it["폴더아이콘"] as? JSONArray }.flatMap { a ->
            (0 until a.length()).map { a.getJSONObject(it).getString("경로") to a.getJSONObject(it).getString("아이콘") }
        }
    }

    private fun runner(db: MemoDb, room: IconRoom) = MemoSyncRunner(db, room, clock = { now }, readFile = { null })

    private fun iconRows(db: MemoDb) = db.outbox().filter { it.kind == MEMO_OUTBOX_FOLDER_ICON }

    private suspend fun pick(store: MemoStore, path: String, icon: String) {
        assertTrue(store.request(memoFolderIconOp(path, icon)) is MemoResult.Ok)
    }

    private suspend fun shownIcon(store: MemoStore, path: String): String? {
        store.refreshIndex()
        return store.index.value.folder(path)?.icon
    }

    @Test
    fun `a picked icon goes up once with sync_push and the row leaves after the ack`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        val room = IconRoom(folders = mutableListOf("여행", "운동"))
        assertTrue(store.request(MemoOp.mkdir("운동", null)) is MemoResult.Ok)
        pick(store, "운동", "🏃")
        val r = runner(db, room)
        assertTrue(r.hasPendingUploads())
        assertTrue(r.push())
        assertEquals(listOf("운동" to "🏃"), room.sentIcons())
        // Only the icon change goes: the folder row of mkdir is not sent, the notes array is empty.
        assertEquals(0, (room.pushes.single()["변경"] as JSONArray).length())
        assertTrue(iconRows(db).isEmpty())
        assertFalse(r.hasPendingUploads())
        assertEquals("🏃", room.icons!!["운동"])
        // The server holds it now: the phone row is clean and a pull keeps the same icon.
        assertTrue(r.syncNow())
        assertEquals("🏃", shownIcon(store, "운동"))
        // Nothing left to send: no second push with the icon.
        r.push()
        assertEquals(1, room.sentIcons().size)
    }

    @Test
    fun `a built-in folder can get its icon on the server too, a newer choice replaces the waiting one`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        val room = IconRoom(folders = mutableListOf("여행"))
        pick(store, "여행", "🧳")
        pick(store, "여행", "🏖")
        assertEquals(1, iconRows(db).size)
        assertTrue(runner(db, room).push())
        assertEquals(listOf("여행" to "🏖"), room.sentIcons())
    }

    @Test
    fun `an old server ignores the key, the phone keeps its icon over the empty icons it sends`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        val room = IconRoom(icons = null, folders = mutableListOf("운동"))
        assertTrue(store.request(MemoOp.mkdir("운동", null)) is MemoResult.Ok)
        pick(store, "운동", "🏃")
        val r = runner(db, room)
        assertTrue(r.push())
        // Same answer shape: the row goes (it is not sent in a loop), the icon stays on the phone.
        assertTrue(iconRows(db).isEmpty())
        assertTrue(r.syncNow())
        assertEquals("🏃", db.folders().first { it.path == "운동" }.icon)
        assertEquals("🏃", shownIcon(store, "운동"))
    }

    @Test
    fun `before the upload the phone's icon wins over the server's, after it the server's newer icon comes in`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        val room = IconRoom(icons = mutableMapOf("운동" to "🔥"), folders = mutableListOf("운동"))
        assertTrue(store.request(MemoOp.mkdir("운동", null)) is MemoResult.Ok)
        pick(store, "운동", "🏃")
        // A pull alone (the push not made yet): the waiting choice wins.
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray()).put("기준", 1).put("폴더", JSONArray().put(JSONObject().put("경로", "운동").put("아이콘", "🔥"))), now)
        assertEquals("🏃", shownIcon(store, "운동"))
        val r = runner(db, room)
        assertTrue(r.syncNow())
        assertEquals("🏃", room.icons!!["운동"])
        // Another phone changes it on the server later: ② the server's own icon now shows here.
        room.icons["운동"] = "⚽"
        assertTrue(r.syncNow())
        assertEquals("⚽", shownIcon(store, "운동"))
    }

    @Test
    fun `back to the default is sent as an empty icon and the server's old icon does not come back over it`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        val room = IconRoom(icons = mutableMapOf("운동" to "🏃"), folders = mutableListOf("운동"))
        assertTrue(store.request(MemoOp.mkdir("운동", null)) is MemoResult.Ok)
        val r = runner(db, room)
        assertTrue(r.syncNow())
        assertEquals("🏃", shownIcon(store, "운동"))
        pick(store, "운동", "")
        // The screen pulls before it pushes: the old server icon must not land on the phone in between.
        MemoSync.applySync(db, JSONObject().put("노트", JSONArray()).put("기준", 1).put("폴더", JSONArray().put(JSONObject().put("경로", "운동").put("아이콘", "🏃"))), now)
        assertEquals("", db.folders().first { it.path == "운동" }.icon)
        assertTrue(r.syncNow())
        assertEquals(listOf("운동" to ""), room.sentIcons())
        assertNull(room.icons!!["운동"])
        // ④ the name rule again, on the phone and after the next pull.
        assertTrue(r.syncNow())
        assertEquals("💪", shownIcon(store, "운동"))
    }

    @Test
    fun `a renamed or removed folder's icon is not sent`() = runTest {
        val db = db()
        val b = backend(db)
        val store = MemoStore(b)
        val room = IconRoom(folders = mutableListOf("운동", "요가"))
        assertTrue(store.request(MemoOp.mkdir("운동", null)) is MemoResult.Ok)
        assertTrue(store.request(MemoOp.mkdir("독서", null)) is MemoResult.Ok)
        pick(store, "운동", "🧘")
        pick(store, "독서", "📖")
        assertTrue(store.request(MemoOp.renameDir("운동", "요가")) is MemoResult.Ok)
        assertTrue(store.request(MemoOp.rmdir("독서")) is MemoResult.Ok)
        assertTrue(runner(db, room).push())
        assertTrue(room.sentIcons().isEmpty())
        assertTrue(iconRows(db).isEmpty())
        // The icon followed the rename on the phone (앱14), it just is not uploaded.
        assertEquals("🧘", shownIcon(store, "요가"))
    }

    @Test
    fun `with the server off the icon waits for the same back-off as the notes and goes up later`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        val room = IconRoom(online = false, folders = mutableListOf("운동"))
        assertTrue(store.request(MemoOp.mkdir("운동", null)) is MemoResult.Ok)
        pick(store, "운동", "🏃")
        val r = runner(db, room)
        assertFalse(r.push())
        assertEquals(1, iconRows(db).single().tries)
        assertTrue(r.hasPendingUploads())
        assertNotNull(r.nextRetryInMs())
        room.online = true
        r.push()
        assertTrue(room.sentIcons().isEmpty())
        now += MemoSync.BACKOFF_MS[0] + 1
        assertTrue(r.push())
        assertEquals(listOf("운동" to "🏃"), room.sentIcons())
        assertFalse(r.hasPendingUploads())
    }

    @Test
    fun `an icon picked again while the push was on its way stays for the next push`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        assertTrue(store.request(MemoOp.mkdir("운동", null)) is MemoResult.Ok)
        pick(store, "운동", "🏃")
        val sent = MemoSync.dueFolderIcons(db, db.outbox())
        assertEquals(1, sent.size)
        pick(store, "운동", "⚽")
        MemoSync.applyFolderIconAck(db, sent)
        assertEquals("⚽", JSONObject(iconRows(db).single().payload).getString("아이콘"))
        assertEquals(mapOf("운동" to "⚽"), MemoSync.waitingFolderIcons(db))
    }

    @Test
    fun `push arguments carry the icons only when there are some`() {
        val icons = JSONArray().put(JSONObject().put("경로", "운동").put("아이콘", "🏃"))
        val args = MemoSync.pushArgs(emptyList(), "폰A", null, icons)!!
        assertEquals(0, args.getJSONArray("변경").length())
        assertEquals("🏃", args.getJSONArray("폴더아이콘").getJSONObject(0).getString("아이콘"))
        assertFalse(MemoSync.pushArgs(emptyList(), "폰A", JSONArray().put(JSONObject().put("id", "p")))!!.has("폴더아이콘"))
        assertNull(MemoSync.pushArgs(emptyList(), "폰A", JSONArray(), JSONArray()))
    }

    @Test
    fun `the order of the icon sources stays the same (picked, server, built-in, name rule, plain)`() {
        // ① a waiting choice beats the server's icon, ② a server icon beats what the phone stored, an empty one does not
        assertEquals("🏃", memoMergedFolderIcon("🔥", "🏃", "✈️"))
        assertEquals("", memoMergedFolderIcon("🔥", "", "✈️"))
        assertEquals("🔥", memoMergedFolderIcon("🔥", null, "✈️"))
        assertEquals("✈️", memoMergedFolderIcon("", null, "✈️"))
        // ③ built-in table, ④ name rule, ⑤ plain folder
        assertEquals("✈️", memoFolderIcon("여행", ""))
        assertEquals("💪", memoFolderIcon("운동", ""))
        assertEquals(MEMO_FOLDER_ICON_DEFAULT, memoFolderIcon("가나다", ""))
    }
}
