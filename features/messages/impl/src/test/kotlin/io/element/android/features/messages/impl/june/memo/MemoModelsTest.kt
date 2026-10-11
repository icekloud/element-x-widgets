/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// org.json is a stub in plain unit tests of this module (isReturnDefaultValues), hence Robolectric.
class MemoModelsTest : RobolectricTest() {
    @Test
    fun `index parses folders, proposals and the extra fields`() {
        val index = parseMemoIndex(
            JSONObject(
                """{"v":1,"폴더":[{"경로":"여행","아이콘":"✈️","개수":12,"폴더없음수":0,"고정수":2,"최근":["a","b"],"전체":15,"출처":"기본"},
                {"경로":"받은편지함","개수":3,"폴더없음수":2},{"경로":""}],
                "제안":[{"id":"p1","종류":"일정추가","요약":"10/5 빵집","상태":"대기","노트":"n1","날짜":"2026-10-05"},
                {"id":"p2","종류":"폴더생성","요약":"영상","상태":"재검토","폴더":"영상","대상수":5}],"갱신시각":"2026-10-11T20:41:00+09:00"}""",
            ),
        )
        assertEquals(listOf("여행", "받은편지함"), index.folders.map { it.path })
        val trip = index.folder("여행")!!
        assertEquals(12, trip.count)
        assertEquals(15, trip.total)
        assertEquals(listOf("a", "b"), trip.recent)
        assertEquals("📥", index.folder("받은편지함")!!.icon)
        assertEquals(2, index.folder("받은편지함")!!.unfiledCount)
        assertEquals(2, index.proposals.size)
        assertTrue(index.proposals[0].isSchedule)
        assertTrue(index.proposals[1].isFolderCreate)
        assertEquals(5, index.proposals[1].targetCount)
        // Only "대기" ones count for the folder tab badge, "재검토" has no buttons.
        assertEquals(1, index.pendingProposals)
    }

    @Test
    fun `split index merges the per folder state keys`() {
        val main = parseMemoIndex(JSONObject("""{"v":1,"분할":true,"폴더키":["여행"],"제안":[]}"""))
        assertEquals(listOf("여행"), main.splitKeys)
        val merged = mergeMemoIndex(main, listOf(JSONObject("""{"v":1,"폴더":[{"경로":"여행","개수":1},{"경로":"여행/교토","개수":2}]}""")))
        assertEquals(listOf("여행", "여행/교토"), merged.folders.map { it.path })
        assertEquals("여행", merged.folder("여행/교토")!!.parent)
        assertEquals("교토", merged.folder("여행/교토")!!.name)
    }

    @Test
    fun `item previews of the three forms`() {
        val photo = parseMemoItem(JSONObject("""{"id":"a","종류":"사진","제목":"t","미리보기":{"형식":"사진","mxc":"mxc://hs/x","장수":3},"태그":["빵집"],"고정제안":true}"""))!!
        assertEquals(MemoPreview.Photo("mxc://hs/x", 3), photo.preview)
        assertTrue(photo.pinSuggested)
        val link = parseMemoItem(JSONObject("""{"id":"b","미리보기":{"형식":"링크","url":"https://x","제목":null,"이미지":null,"사이트":"tabelog"}}"""))!!
        assertEquals(MemoPreview.Link("https://x", null, null, "tabelog"), link.preview)
        assertEquals("b", link.title)
        val text = parseMemoItem(JSONObject("""{"id":"c","날짜":"2026-10-11T14:30:12+09:00","미리보기":{"형식":"텍스트","글":"앞 90자"},"일정상태":"수락됨"}"""))!!
        assertEquals(MemoPreview.Text("앞 90자"), text.preview)
        assertEquals("10-11", text.shortDate)
        assertTrue(text.hasSchedule)
        assertNull(parseMemoItem(JSONObject("""{"제목":"no id"}""")))
    }

    @Test
    fun `responses are matched by type and request id`() {
        val list = parseMemoResponse(MEMO_LIST_TYPE, JSONObject("""{"요청id":"r1","항목":[{"id":"a"}],"다음cursor":"c2"}""")) as MemoResponse.List
        assertEquals("r1", list.requestId)
        assertEquals("c2", list.page.nextCursor)
        val end = parseMemoResponse(MEMO_LIST_TYPE, JSONObject("""{"요청id":"r1","항목":[],"다음cursor":null}""")) as MemoResponse.List
        assertNull(end.page.nextCursor)
        val note = parseMemoResponse(
            MEMO_NOTE_TYPE,
            JSONObject("""{"요청id":"r2","id":"n","머리말":{"폴더":"여행","태그":["a","b"]},"본문":"# t","첨부":["mxc://1",{"mxc":"mxc://2","file":{"url":"mxc://2"}}]}"""),
        ) as MemoResponse.Note
        assertEquals(listOf("a", "b"), note.note.tags)
        assertEquals("여행", note.note.folder)
        assertEquals(listOf("mxc://1", "mxc://2"), note.note.attachments.map { it.mxc })
        assertTrue(note.note.attachments[1].fileJson!!.contains("mxc://2"))
        assertFalse(note.note.truncated)
        val tags = parseMemoResponse(MEMO_TAGS_TYPE, JSONObject("""{"요청id":"r3","태그":[{"이름":"빵집","수":7,"축":"주제","출처":"봇","동의어":["교토빵집"]}]}""")) as MemoResponse.Tags
        assertEquals(MemoTag("빵집", 7, "주제", "봇", listOf("교토빵집")), tags.tags.single())
        val error = parseMemoResponse(MEMO_ERROR_TYPE, JSONObject("""{"요청id":"r4","op":"move","사유":"폴더가 없습니다"}""")) as MemoResponse.Error
        assertEquals("폴더가 없습니다", error.reason)
        assertNull(parseMemoResponse(MEMO_OK_TYPE, JSONObject("""{"op":"pin"}""")))
    }

    @Test
    fun `whole event json is parsed, other events are ignored`() {
        val ok = parseMemoResponseEvent("""{"type":"app.june.memo.ok","content":{"요청id":"r","op":"mkdir","경로":"반려동물"}}""") as MemoResponse.Ok
        assertEquals("반려동물", ok.path)
        assertNull(parseMemoResponseEvent("""{"type":"m.room.message","content":{"요청id":"r"}}"""))
        assertNull(parseMemoResponseEvent("not json"))
        assertNull(parseMemoResponseEvent(null))
    }
}
