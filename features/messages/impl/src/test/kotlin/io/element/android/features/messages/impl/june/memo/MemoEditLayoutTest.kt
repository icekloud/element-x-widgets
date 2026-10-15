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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱13 A·B·C (관리자 2026-10-15): the record card dates like the open note, the editor that takes the whole screen and
 * gives the room composer back when it ends, the smaller fields. All data is made up.
 */
class MemoEditLayoutTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private var now = t0

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun backend(db: MemoDb) = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun `the record card and the open note show the same date lines`() = runTest {
        val db = db()
        val local = backend(db)
        val id = local.create("가슴 운동 세트별 중량")
        val card = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.single()
        val note = (local.call(MemoOp.get(id), "g") as MemoResponse.Note).note
        // Never changed by the user: one line, the same on both screens.
        assertEquals(1, memoItemDateLines(card).size)
        assertEquals(memoNoteDateLines(note), memoItemDateLines(card))
        assertTrue(memoItemDateLines(card).single().startsWith("작성 "))
        // Changed later: two lines, still the same on both screens.
        now += 3_600_000
        local.call(MemoOp.edit(MemoTarget(noteId = id), "다시 쓴 글", null, null), "e")
        val changed = db.list(MEMO_ALL_FOLDERS, emptyList(), null, null).items.single()
        val opened = (local.call(MemoOp.get(id), "g2") as MemoResponse.Note).note
        assertEquals(2, memoItemDateLines(changed).size)
        assertTrue(memoItemDateLines(changed)[1].startsWith("수정 "))
        assertEquals(memoNoteDateLines(opened), memoItemDateLines(changed))
    }

    @Test
    fun `a card that knows only the date shown shows it as the creation, and its head has no date any more`() {
        val item = MemoItem("n", "텍스트", "교토 빵집", null, "2026-10-15T11:31:00+09:00", emptyList(), MEMO_INBOX, number = 7)
        assertEquals(listOf("작성 2026-10-15 11:31"), memoItemDateLines(item))
        assertEquals("#7  교토 빵집", memoRecordTitle(item, null))
        val both = item.copy(created = "2026-10-15T11:31:00+09:00", userModified = "2026-10-15T20:33:00+09:00")
        assertEquals(listOf("작성 2026-10-15 11:31", "수정 2026-10-15 20:33"), memoItemDateLines(both))
        // The note screen reads the same JSON fields the same way.
        val header = JSONObject().put("생성", both.created).put(MEMO_FIELD_USER_MODIFIED, both.userModified)
        assertEquals(memoItemDateLines(both), memoNoteDateLines(MemoNote("n", header, "", emptyList(), false)))
    }

    @Test
    fun `the editor takes the screen only while a note is edited, and every way out gives it back`() {
        val reading = MemoLocation().openNote("n")
        assertFalse(memoEditorFullScreen(MemoLocation()))
        assertFalse(memoEditorFullScreen(reading))
        val editing = reading.startEditing()
        assertTrue(memoEditorFullScreen(editing))
        // Back ends the editing (앱12 B), 취소 does the same, a tab press closes the note.
        val back = (editing.back() as MemoBack.To).state
        assertFalse(memoEditorFullScreen(back))
        assertFalse(memoEditorFullScreen(editing.stopEditing()))
        assertFalse(memoEditorFullScreen(editing.closeNote().selectTab(MemoTab.Folders)))
        assertFalse(memoEditorFullScreen(editing.openFolder("여행")))
        // The keyboard has no say in it: the same location gives the same answer.
        assertEquals(memoEditorFullScreen(editing), memoEditorFullScreen(editing.copy()))
    }

    @Test
    fun `the room composer is hidden for the edited room only, and comes back`() {
        MemoEditingRooms.set("!memo:test", true)
        assertTrue("!memo:test" in MemoEditingRooms.rooms.value)
        assertFalse("!other:test" in MemoEditingRooms.rooms.value)
        MemoEditingRooms.set("!memo:test", false)
        assertFalse("!memo:test" in MemoEditingRooms.rooms.value)
        // Setting it off twice (the editing ends, then the screen leaves) is harmless.
        MemoEditingRooms.set("!memo:test", false)
        assertTrue(MemoEditingRooms.rooms.value.isEmpty())
    }

    @Test
    fun `the date text is about half of 앱12, the title and tag fields about half as high`() {
        // 앱12 drew the dates with fontBodyMdRegular (14 sp).
        assertTrue(MEMO_DATE_TEXT_SP in 7..8)
        // Two date lines are about as high as the two title lines beside them would allow.
        assertTrue(2 * MEMO_DATE_LINE_SP <= 2 * 22)
        assertTrue(memoEditFieldHeight() * 2 <= MEMO_OLD_FIELD_DP + 2)
        assertTrue(MEMO_EDIT_FIELD_TEXT_SP < 16)
        assertEquals(2, MEMO_HEAD_TITLE_LINES)
    }

    @Test
    fun `one field changed is applied to the draft held right now`() {
        val entry = MemoEditDraft(title = "제목", tags = "#a", body = "글")
        MemoEditHub.drop("f1")
        MemoEditHub.open("f1", entry)
        MemoEditHub.change("f1") { it.copy(title = "새 제목") }
        MemoEditHub.change("f1") { it.copy(body = "새 글") }
        val draft = MemoEditHub.state("f1")!!.draft
        assertEquals("새 제목", draft.title)
        assertEquals("새 글", draft.body)
        assertEquals("#a", draft.tags)
        MemoEditHub.drop("f1")
        // No session: nothing happens.
        MemoEditHub.change("f1") { it.copy(title = "x") }
        assertEquals(null, MemoEditHub.state("f1"))
    }
}
