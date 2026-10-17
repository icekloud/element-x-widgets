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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱14 A·B·C: one icon function for every screen, the name rules, and the icon picked on the phone. Made up data. */
class MemoFolderIconsTest : RobolectricTest() {
    private var now = 1_760_000_000_000L

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    /** The pull answer of a server that sends every folder with an empty icon, like the real one today. */
    private fun pullWithEmptyIcons(vararg paths: String): JSONObject {
        val folders = JSONArray()
        paths.forEach { folders.put(JSONObject().put("경로", it).put("아이콘", "")) }
        return JSONObject().put("노트", JSONArray()).put("기준", 1).put("폴더", folders)
    }

    // ---------------------------------------------------------------- B: the tables, pure functions

    @Test
    fun `every built-in folder has its icon`() {
        val expected = mapOf(
            MEMO_INBOX to "📥",
            "여행" to "✈️",
            "쇼핑·구매" to "🛒",
            "아이디어" to "💡",
            "집·생활" to "🏠",
            "일·공부" to "📚",
            "사람" to "👤",
            "링크보관" to "🔗",
            MEMO_ARCHIVE to "📦",
            MEMO_TRASH to "🗑",
        )
        assertEquals(expected, MEMO_BUILTIN_FOLDERS.toMap())
        expected.forEach { (path, icon) -> assertEquals(path, icon, memoFolderIcon(path, "")) }
    }

    @Test
    fun `the name rules give an icon that fits, in Korean and English, anywhere in the name`() {
        val cases = mapOf(
            "운동" to "💪",
            "헬스 기록" to "💪",
            "식단" to "🍽",
            "맛집 리스트" to "🍽",
            "병원" to "🏥",
            "가계부" to "💰",
            "재테크" to "💰",
            "독서" to "📖",
            "영화" to "🎬",
            "드라마 추천" to "🎬",
            "음악" to "🎵",
            "자동차" to "🚗",
            "회의록" to "💼",
            "육아" to "🧒",
            "강아지" to "🐾",
            "취미" to "🎨",
            "운세" to "🔮",
            "Workout" to "💪",
            "Movie list" to "🎬",
            "아이디어 노트" to "💡",
        )
        cases.forEach { (name, icon) -> assertEquals(name, icon, memoRuleFolderIcon(name)) }
    }

    @Test
    fun `a one letter word only matches a whole part of the name`() {
        assertEquals("📖", memoRuleFolderIcon("책"))
        assertEquals("💰", memoRuleFolderIcon("돈"))
        assertEquals("🔮", memoRuleFolderIcon("점"))
        assertEquals("💊", memoRuleFolderIcon("약"))
        assertEquals("🚗", memoRuleFolderIcon("차 정비"))
        // 점심, 약속, 차이: not 🔮, 💊, 🚗 (약속 is 📅 by its own word).
        assertNull(memoRuleFolderIcon("점심"))
        assertEquals("📅", memoRuleFolderIcon("약속"))
        assertNull(memoRuleFolderIcon("차이"))
        // A short latin word too: education has no cat in it.
        assertNull(memoRuleFolderIcon("education"))
        assertEquals("🐾", memoRuleFolderIcon("cat"))
    }

    @Test
    fun `a sub folder goes by its last name, the folders under the trash or archive by that folder`() {
        assertEquals("💪", memoAutoFolderIcon("일·공부/운동"))
        assertEquals("✈️", memoAutoFolderIcon("여행"))
        assertEquals("📁", memoAutoFolderIcon("여행/교토"))
        assertEquals("🗑", memoAutoFolderIcon("$MEMO_TRASH/태그복구"))
        assertEquals("📦", memoAutoFolderIcon("$MEMO_ARCHIVE/2025"))
    }

    @Test
    fun `a name no rule knows, an empty or blank name, gives the plain folder`() {
        assertEquals(MEMO_FOLDER_ICON_DEFAULT, memoAutoFolderIcon("가나다라"))
        assertEquals(MEMO_FOLDER_ICON_DEFAULT, memoAutoFolderIcon(""))
        assertEquals(MEMO_FOLDER_ICON_DEFAULT, memoAutoFolderIcon("   "))
        assertNull(memoRuleFolderIcon(""))
        assertNull(memoRuleFolderIcon("  "))
    }

    @Test
    fun `the order - stored icon, built-in table, name rules, plain folder`() {
        // ①② a stored icon (picked or from the server) wins over the tables.
        assertEquals("⭐", memoFolderIcon("여행", "⭐"))
        assertEquals("⭐", memoFolderIcon("운동", " ⭐ "))
        // A stored 📁 was the old default of a folder made on the phone: not a choice.
        assertEquals("💪", memoFolderIcon("운동", "📁"))
        // ③ the built-in table, ④ the rules, ⑤ the plain folder.
        assertEquals("✈️", memoFolderIcon("여행", ""))
        assertEquals("💪", memoFolderIcon("운동", null))
        assertEquals("📁", memoFolderIcon("가나다", "  "))
    }

    @Test
    fun `the merge keeps a picked icon, takes a real server icon, and an empty server icon wipes nothing`() {
        assertEquals("⭐", memoMergedFolderIcon("", "⭐", "⭐"))
        assertEquals("⭐", memoMergedFolderIcon("🔥", "⭐", "⭐"))
        assertEquals("🔥", memoMergedFolderIcon("🔥", null, "✈️"))
        assertEquals("✈️", memoMergedFolderIcon("", null, "✈️"))
        assertEquals("", memoMergedFolderIcon("", null, null))
    }

    @Test
    fun `the choices are about sixty different icons, without the plain folder`() {
        assertTrue(MEMO_FOLDER_ICON_CHOICES.size in 50..70)
        assertEquals(MEMO_FOLDER_ICON_CHOICES.size, MEMO_FOLDER_ICON_CHOICES.toSet().size)
        assertTrue(MEMO_FOLDER_ICON_DEFAULT !in MEMO_FOLDER_ICON_CHOICES)
        assertNull(memoChosenFolderIcon("not an icon"))
        assertNull(memoChosenFolderIcon(MEMO_FOLDER_ICON_DEFAULT))
        assertEquals("", memoChosenFolderIcon(null))
        assertEquals("", memoChosenFolderIcon(" "))
        assertEquals("⭐", memoChosenFolderIcon("⭐"))
    }

    // ---------------------------------------------------------------- A: the server's empty icons

    @Test
    fun `the built-in icons stay after a pull with empty icons, every screen gets them from the folder list`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        MemoSync.applySync(db, pullWithEmptyIcons(MEMO_INBOX, "여행", "쇼핑·구매", "운동", "가나다", "$MEMO_TRASH/태그복구"), now)
        // The database still holds the seeded icons (an empty server icon wipes nothing).
        assertEquals("✈️", db.folders().first { it.path == "여행" }.icon)
        store.refreshIndex()
        val index = store.index.value
        assertEquals("✈️", index.folder("여행")!!.icon)
        assertEquals("🛒", index.folder("쇼핑·구매")!!.icon)
        assertEquals("💪", index.folder("운동")!!.icon)
        assertEquals("📁", index.folder("가나다")!!.icon)
        assertEquals("🗑", index.folder("$MEMO_TRASH/태그복구")!!.icon)
        // The chips and lines of a note use the same function.
        assertEquals("✈️ 여행", memoFolderLabel(index, "여행"))
        // A path the list does not have is worked out the same way (last name: 헬스 → 💪).
        assertEquals("💪 일/헬스", memoFolderLabel(index, "일/헬스"))
        assertEquals("📥 $MEMO_INBOX", memoFolderLabel(index, MEMO_INBOX))
    }

    @Test
    fun `a folder made on the phone stores no icon, it shows the one of its name`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        assertTrue(store.request(MemoOp.mkdir("헬스", null)) is MemoResult.Ok)
        assertEquals("", db.folders().first { it.path == "헬스" }.icon)
        store.refreshIndex()
        assertEquals("💪", store.index.value.folder("헬스")!!.icon)
    }

    // ---------------------------------------------------------------- C: the icon picked on the phone

    @Test
    fun `a picked icon is stored, queued with the folder, kept over an empty server icon, and goes back to the default`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        assertTrue(store.request(MemoOp.mkdir("운동", null)) is MemoResult.Ok)
        now += 1_000
        assertTrue(store.request(memoFolderIconOp("운동", "🏃")) is MemoResult.Ok)
        val row = db.folders().first { it.path == "운동" }
        assertEquals("🏃", row.icon)
        // The folder row waits in the outbox with the shape it always had (경로, 아이콘, 삭제).
        val queued = JSONObject(db.outbox().first { it.kind == MEMO_OUTBOX_FOLDER && it.target == "운동" }.payload)
        assertEquals("운동", queued.getString("경로"))
        assertEquals("🏃", queued.getString("아이콘"))
        assertEquals(0, queued.getInt("삭제"))
        // The notes upload does not carry folders (질문 1: the server has no way to take them yet).
        assertTrue(MemoSync.dueUploads(db, now + 60_000).none { it.kind == MEMO_OUTBOX_FOLDER })
        // The server answers with an empty icon for it: the picked one stays.
        MemoSync.applySync(db, pullWithEmptyIcons("운동"), now)
        assertEquals("🏃", db.folders().first { it.path == "운동" }.icon)
        // A server icon does not beat it either while the phone's choice waits.
        MemoSync.applySync(
            db,
            JSONObject().put("노트", JSONArray()).put("기준", 2).put("폴더", JSONArray().put(JSONObject().put("경로", "운동").put("아이콘", "🔥"))),
            now,
        )
        store.refreshIndex()
        assertEquals("🏃", store.index.value.folder("운동")!!.icon)
        // 기본으로 되돌리기: nothing stored, the name rule again; the server's own icon may come back later.
        assertTrue(store.request(memoFolderIconOp("운동", "")) is MemoResult.Ok)
        assertEquals("", db.folders().first { it.path == "운동" }.icon)
        store.refreshIndex()
        assertEquals("💪", store.index.value.folder("운동")!!.icon)
        assertEquals("", JSONObject(db.outbox().first { it.kind == MEMO_OUTBOX_FOLDER && it.target == "운동" }.payload).getString("아이콘"))
    }

    @Test
    fun `an icon outside the choices is refused and wipes nothing, an unknown folder is refused`() = runTest {
        val db = db()
        val store = MemoStore(backend(db))
        assertTrue(store.request(memoFolderIconOp("여행", "<script>")) is MemoResult.Failed)
        assertEquals("✈️", db.folders().first { it.path == "여행" }.icon)
        store.refreshIndex()
        assertEquals("✈️", store.index.value.folder("여행")!!.icon)
        assertTrue(store.request(memoFolderIconOp("없는폴더", "⭐")) is MemoResult.Failed)
        assertTrue(store.request(memoFolderIconOp("", "⭐")) is MemoResult.Failed)
    }

    @Test
    fun `a picked icon follows the folder when it is renamed, and goes with it when it is removed`() = runTest {
        val db = db()
        val b = backend(db)
        val store = MemoStore(b)
        assertTrue(store.request(MemoOp.mkdir("운동", null)) is MemoResult.Ok)
        assertTrue(store.request(memoFolderIconOp("운동", "🧘")) is MemoResult.Ok)
        now += 1_000
        b.create("스쿼트 5세트", folder = "운동")
        assertTrue(store.request(MemoOp.renameDir("운동", "요가")) is MemoResult.Ok)
        store.refreshIndex()
        assertEquals("🧘", store.index.value.folder("요가")!!.icon)
        assertNull(store.index.value.folder("운동"))
        assertTrue(store.request(MemoOp.rmdir("요가")) is MemoResult.Ok)
        store.refreshIndex()
        assertNull(store.index.value.folder("요가"))
        // A folder that only exists through its notes can get an icon too.
        b.create("메모", folder = "드라마")
        assertTrue(store.request(memoFolderIconOp("드라마", "⭐")) is MemoResult.Ok)
        store.refreshIndex()
        assertEquals("⭐", store.index.value.folder("드라마")!!.icon)
    }
}
