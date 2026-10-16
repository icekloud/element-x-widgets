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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱15 H: the pinned tray line (no number) and taking a pin off outside the editor. Made up data. */
class MemoPinRowTest : RobolectricTest() {
    private var now = 1_791_772_260_000L

    private fun db(): MemoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        return MemoDb(driver)
    }

    private fun item(title: String = "", number: Int = 0, text: String? = null) = MemoItem(
        id = "n1",
        kind = "텍스트",
        title = title,
        preview = null,
        date = "2026-10-15T15:23:48+09:00",
        tags = emptyList(),
        folder = MEMO_INBOX,
        number = number,
        text = text,
        untitled = title.isEmpty(),
    )

    @Test
    fun `the tray line never shows the note number`() {
        assertEquals("점심으로 돈까스 먹는 중", memoPinRowText(item(title = "점심으로 돈까스 먹는 중", number = 14)))
        assertFalse(memoPinRowText(item(title = "점심으로 돈까스 먹는 중", number = 14)).contains("#14"))
        // The other lines keep their number (cards, open note).
        assertEquals("#14  점심으로 돈까스 먹는 중", memoItemLine(item(title = "점심으로 돈까스 먹는 중", number = 14)))
    }

    @Test
    fun `without a title the tray shows the first text line, else the kind and date`() {
        assertEquals("첫 줄", memoPinRowText(item(number = 3, text = "\n첫 줄\n둘째 줄")))
        assertEquals("📝 텍스트  10-15", memoPinRowText(item(number = 3)))
        // A long title stays one line (the screen cuts it with "…").
        assertEquals("가".repeat(80), memoPinRowText(item(title = "가".repeat(80) + "\n둘째")))
    }

    @Test
    fun `the tray height cap and the undo time`() {
        assertEquals(0.4f, MEMO_PIN_TRAY_MAX_PART, 0.001f)
        assertEquals(5_000L, MEMO_UNDO_SHOW_MS)
    }

    @Test
    fun `unpin then pin again leaves the note pinned, both go to the outbox as changes`() = runTest {
        val db = db()
        val b = MemoLocalBackend({ db }, clock = { now }, dispatcher = Dispatchers.Unconfined)
        val id = b.create("고정 메모")
        b.call(MemoOp.pin(MemoTarget(id)), "p")
        assertTrue(db.list(MEMO_PINNED_LIST, emptyList(), null, null).items.any { it.id == id })
        // The 📌 press (unpin) takes it out of the tray, 되돌리기 (pin) puts it back.
        now += 1_000
        b.call(MemoOp.unpin(MemoTarget(id)), "u")
        assertTrue(db.list(MEMO_PINNED_LIST, emptyList(), null, null).items.none { it.id == id })
        assertFalse(db.list(MEMO_INBOX, emptyList(), null, null).items.first { it.id == id }.pinned)
        now += 1_000
        b.call(MemoOp.pin(MemoTarget(id)), "p2")
        assertTrue(db.list(MEMO_INBOX, emptyList(), null, null).items.first { it.id == id }.pinned)
        assertTrue(db.outbox().any { it.kind == MEMO_OUTBOX_NOTE && it.target == id })
    }
}
