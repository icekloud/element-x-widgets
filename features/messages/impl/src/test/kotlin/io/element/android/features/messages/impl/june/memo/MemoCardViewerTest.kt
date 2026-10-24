/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 앱20: a card draws at most [MEMO_CARD_PHOTOS] cells ("+N" on the last), but a tap on a cell opens every photo of the
 * note in the viewer, at the tapped one. All data is made up.
 */
class MemoCardViewerTest : RobolectricTest() {
    private fun phone(n: Int) = (1..n).map { MemoLocalAttachment(id = "a$it", localPath = "/p/$it.jpg", mime = "image/jpeg", bytes = 3) }

    private fun photoNote(attachments: List<MemoLocalAttachment>) = memoLocalPreview("사진", "", null, attachments) as MemoPreview.Photo

    @Test
    fun `six photos - four cells with +2, the viewer gets all six from the tapped one`() {
        val p = photoNote(phone(6))
        assertEquals(MEMO_CARD_PHOTOS, memoCardCells(p).size)
        assertEquals(listOf("/p/1.jpg", "/p/2.jpg", "/p/3.jpg", "/p/4.jpg"), memoCardCells(p).map { it.localPath })
        assertEquals(2, memoHiddenPhotos(p))
        // The "+2" cell is the fourth: the viewer starts there and the fifth and sixth follow.
        val v = memoCardViewer(p, 3)
        assertEquals((1..6).map { "/p/$it.jpg" }, v.photos.map { it.localPath })
        assertEquals(3, v.start)
        assertEquals("4/6", memoViewerCounter(v.start, v.photos.size))
        assertEquals("1/6", memoViewerCounter(memoCardViewer(p, 0).start, memoCardViewer(p, 0).photos.size))
    }

    @Test
    fun `four photos - four cells, no +, the viewer gets four`() {
        val p = photoNote(phone(4))
        assertEquals(4, memoCardCells(p).size)
        assertEquals(0, memoHiddenPhotos(p))
        val v = memoCardViewer(p, 2)
        assertEquals(4, v.photos.size)
        assertEquals(2, v.start)
        assertEquals("3/4", memoViewerCounter(v.start, v.photos.size))
    }

    @Test
    fun `one and three photos keep their look`() {
        val one = photoNote(phone(1))
        assertEquals(1, memoCardCells(one).size)
        assertEquals(0, memoHiddenPhotos(one))
        assertEquals(listOf("/p/1.jpg"), memoCardViewer(one, 0).photos.map { it.localPath })
        val three = photoNote(phone(3))
        assertEquals(3, memoCardCells(three).size)
        assertEquals(0, memoHiddenPhotos(three))
        assertEquals(3, memoCardViewer(three, 1).photos.size)
        // The server list form (only the first photo is sent): one cell and "+2", the viewer has that one.
        val server = MemoPreview.Photo("mxc://test/x", 3)
        assertEquals(1, memoCardCells(server).size)
        assertEquals(2, memoHiddenPhotos(server))
        assertEquals(listOf("mxc://test/x"), memoCardViewer(server, 0).photos.map { it.mxc })
    }

    @Test
    fun `uploaded, uploading and phone only photos all reach the viewer in their order`() {
        val mixed = listOf(
            MemoLocalAttachment("a1", mxc = "mxc://test/1", fileJson = "{}", state = MEMO_ATTACH_DONE),
            MemoLocalAttachment("a2", mxc = "mxc://test/2", localPath = "/p/2.jpg", fileJson = "{}", state = MEMO_ATTACH_DONE),
            MemoLocalAttachment("a3", localPath = "/p/3.jpg", state = MEMO_ATTACH_UPLOADING),
            MemoLocalAttachment("a4", localPath = "/p/4.jpg", state = MEMO_ATTACH_LOCAL),
            MemoLocalAttachment("a5", localPath = "/p/5.jpg", state = MEMO_ATTACH_FAILED),
            MemoLocalAttachment("a6", localPath = "/p/6.jpg", state = MEMO_ATTACH_LOCAL),
        )
        val p = photoNote(mixed)
        assertEquals(6, p.count)
        assertEquals(4, memoCardCells(p).size)
        assertEquals(2, memoHiddenPhotos(p))
        val v = memoCardViewer(p, 3)
        assertEquals(
            listOf("mxc://test/1", "mxc://test/2") + (3..6).map { "${MEMO_LOCAL_FILE_PREFIX}/p/$it.jpg" },
            v.photos.map { it.mxc },
        )
        assertEquals(listOf(null, "/p/2.jpg", "/p/3.jpg", "/p/4.jpg", "/p/5.jpg", "/p/6.jpg"), v.photos.map { it.localPath })
        assertEquals("4/6", memoViewerCounter(v.start, v.photos.size))
    }

    @Test
    fun `the photos under the text of a card - four cells with +2, the viewer gets all six`() {
        val shown = memoLocalPreview("텍스트", "교토 빵집", null, phone(6))
        val p = memoCardPhotos(shown, phone(6))!!
        assertEquals(4, memoCardCells(p).size)
        assertEquals(2, memoHiddenPhotos(p))
        val v = memoCardViewer(p, 3)
        assertEquals(6, v.photos.size)
        assertEquals("/p/6.jpg", v.photos.last().localPath)
        assertEquals("4/6", memoViewerCounter(v.start, v.photos.size))
    }
}
