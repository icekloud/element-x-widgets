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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** 앱7 항목 1: notes are saved without a title; lists show the start of the body; the review may suggest one. */
class MemoUntitledTest : RobolectricTest() {
    private var now = 1_791_772_260_000L

    private fun setup(): Triple<MemoDb, MemoLocalBackend, MemoStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MemoDb.Schema.create(driver)
        val db = MemoDb(driver)
        val backend = MemoLocalBackend({ db }, clock = { now }, dispatcher = EmptyCoroutineContext)
        return Triple(db, backend, MemoStore(backend))
    }

    private fun titleSuggestion(id: String, noteId: String, title: String) = MemoSuggestionRow(
        id = id,
        noteId = noteId,
        kind = "제목",
        payload = JSONObject().put("id", id).put("종류", "제목").put("노트", noteId).put("제안내용", JSONObject().put("제목", title)).toString(),
        status = MEMO_SUGGESTION_WAITING,
        createdMs = now,
    )

    @Test
    fun `a typed note keeps an empty title and the list shows the start of its body, marked untitled`() = runTest {
        val (db, backend, store) = setup()
        val id = backend.create("텍스트가 너무 작음\n둘째 줄")
        assertEquals("", db.note(id)!!.title)
        val key = MemoListKey.of(MEMO_INBOX, emptyList())
        store.loadFirst(key)
        val item = store.list(key).items.single()
        assertEquals("텍스트가 너무 작음", item.title)
        assertTrue(item.untitled)
        // The record card shows the body once: no title in its top line.
        assertEquals("", memoCardHeadTitle(item, memoCardText(item)))
        // Still nothing stored: the shown line is for the screen only.
        assertEquals("", db.note(id)!!.title)
        val opened = backend.call(MemoOp.get(id), "g") as MemoResponse.Note
        assertEquals("", opened.note.header.optString("제목"))
    }

    @Test
    fun `a title the user writes is kept and shown as a title`() = runTest {
        val (db, backend, store) = setup()
        val id = backend.create("본문만")
        assertTrue(store.request(MemoOp.edit(MemoTarget(id), "본문만", "직접 쓴 제목", emptyList())) is MemoResult.Ok)
        assertEquals("직접 쓴 제목", db.note(id)!!.title)
        val key = MemoListKey.of(MEMO_INBOX, emptyList())
        store.loadFirst(key)
        assertFalse(store.list(key).items.single().untitled)
    }

    @Test
    fun `search finds an untitled note by its body, not as a title match`() = runTest {
        val (_, backend, store) = setup()
        val id = backend.create("교토 빵집 가와라마치")
        val r = store.search(MemoSearchQuery(text = "교토", includeHidden = true)) as MemoResult.Ok
        val item = (r.response as MemoResponse.Search).page.items.single()
        assertEquals(id, item.id)
        assertEquals("본문", item.matchField)
    }

    @Test
    fun `an untitled note goes up with an empty title and a pulled empty title stays empty`() = runTest {
        val (db, backend, _) = setup()
        val id = backend.create("장보기")
        val up = JSONObject(db.outbox().single().payload)
        assertEquals("", up.optString("제목"))
        // The same change coming back from the server keeps the title empty.
        val back = memoChangeFromJson(up)!!
        assertEquals("", back.title)
    }

    @Test
    fun `an accepted title suggestion is stored, a title already written wins`() = runTest {
        val (db, backend, _) = setup()
        val a = backend.create("오사카 카레 맛집 목록")
        val b = backend.create("다른 메모")
        db.putSuggestion(titleSuggestion("t1", a, "오사카 카레 맛집"))
        db.putSuggestion(titleSuggestion("t2", b, "지어낸 제목"))
        backend.call(MemoOp.edit(MemoTarget(b), "다른 메모", "내 제목", emptyList()), "e")
        assertTrue(backend.call(MemoOp.accept("t1"), "r1") is MemoResponse.Ok)
        backend.call(MemoOp.accept("t2"), "r2")
        assertEquals("오사카 카레 맛집", db.note(a)!!.title)
        assertEquals("내 제목", db.note(b)!!.title)
    }

    @Test
    fun `the display title is the first non empty line, cut, and the id when there is nothing`() {
        assertEquals("둘째", memoDisplayTitle("", "\n  둘째  \n셋째", "n1"))
        assertEquals("x".repeat(MEMO_TITLE_CHARS), memoDisplayTitle("", "x".repeat(99), "n1"))
        // 앱11 B: never the note id; the screens draw `#번호  날짜` instead.
        assertEquals("", memoDisplayTitle("", "", "n1"))
        assertEquals("제목", memoDisplayTitle("제목", "본문", "n1"))
    }
}
