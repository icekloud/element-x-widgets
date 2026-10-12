/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoNavTest : RobolectricTest() {
    private fun MemoLocation.pressBack(): MemoLocation = when (val b = back()) {
        is MemoBack.To -> b.state
        MemoBack.ConfirmDiscard -> error("confirm expected to be handled")
        MemoBack.Exit -> error("exit")
    }

    @Test
    fun `back chain of the folder tab, six steps`() {
        var s = MemoLocation().openFolder("여행").openFolder("여행/교토").openNote("n1")
        // note → folder tiles (교토)
        s = s.pressBack()
        assertEquals(MemoLocation(tab = MemoTab.Folders, folder = "여행/교토"), s)
        // sub folder → parent folder
        s = s.pressBack()
        assertEquals("여행", s.folder)
        // top folder → folder root
        s = s.pressBack()
        assertEquals(null, s.folder)
        assertEquals(MemoTab.Folders, s.tab)
        // folder root → record tab
        s = s.pressBack()
        assertEquals(MemoLocation(), s)
        // record tab → leave the room (the room screen handles it)
        assertEquals(MemoBack.Exit, s.back())
        assertFalse(s.handlesBack)
    }

    @Test
    fun `search results and tag manager go back to the empty state, then the record tab`() {
        var s = MemoLocation().selectTab(MemoTab.Search).showSearchResults(true)
        s = s.pressBack()
        assertEquals(MemoLocation(tab = MemoTab.Search), s)
        s = s.pressBack()
        assertEquals(MemoTab.Record, s.tab)
        var t = MemoLocation().openTagManager()
        assertTrue(t.handlesBack)
        t = t.pressBack()
        assertEquals(MemoLocation(tab = MemoTab.Search), t)
        // A note opened from the results goes back to the results.
        val n = MemoLocation(tab = MemoTab.Search, searchResults = true).openNote("x").pressBack()
        assertEquals(MemoLocation(tab = MemoTab.Search, searchResults = true), n)
    }

    @Test
    fun `editing with changes asks once`() {
        val s = MemoLocation().openFolder("여행").openNote("n").startEditing().edited(true)
        assertEquals(MemoBack.ConfirmDiscard, s.back())
        assertEquals(MemoLocation(tab = MemoTab.Folders, folder = "여행"), s.afterDiscardConfirmed())
        // Without changes: straight back.
        assertEquals(MemoLocation(tab = MemoTab.Folders, folder = "여행"), s.edited(false).pressBack())
    }

    @Test
    fun `switching tabs adds no step`() {
        val s = MemoLocation().openFolder("여행").selectTab(MemoTab.Search).selectTab(MemoTab.Record)
        // Back from the record tab still leaves, the folder stays remembered for the folder tab.
        assertEquals(MemoBack.Exit, s.back())
        assertEquals("여행", s.selectTab(MemoTab.Folders).folder)
    }

    @Test
    fun `root grid from the index only`() {
        val backend = FakeMemoBackend()
        val index = kotlinx.coroutines.runBlocking { backend.fetchIndex()!! }
        val cells = memoRootGrid(index)
        val first = cells.first() as MemoGridCell.Folder
        assertEquals(MEMO_INBOX, first.folder.path)
        assertEquals("폴더 없음 2", first.secondary)
        // Sub folders are not on the root grid; the trip folder counts its sub folder notes.
        val names = cells.filterIsInstance<MemoGridCell.Folder>().map { it.folder.path }
        assertFalse("여행/교토" in names)
        val newIndex = cells.indexOf(MemoGridCell.NewFolder)
        assertEquals(listOf(MEMO_ARCHIVE, MEMO_TRASH), cells.drop(newIndex + 1).map { (it as MemoGridCell.Folder).folder.path })
        assertTrue(cells.drop(newIndex + 1).all { (it as MemoGridCell.Folder).dimmed })
        val user = cells.filterIsInstance<MemoGridCell.Folder>().first { it.folder.path == "반려동물" }
        assertEquals("사용자 생성", user.secondary)
        assertEquals(listOf("여행/교토"), memoSubfolders(index, "여행").map { it.path })
        assertFalse(memoMoveTargets(index).any { it.path == MEMO_TRASH })
    }

    @Test
    fun `bands show only when there are proposals`() {
        val index = MemoIndex(
            folders = emptyList(),
            proposals = listOf(
                MemoProposal("p1", "일정추가", "s", "대기", noteId = "n1"),
                MemoProposal("p2", "폴더생성", "s", "대기", folder = "여행/오사카"),
            ),
        )
        assertEquals(2, memoRootBand(index).size)
        assertEquals(listOf("p2"), memoFolderBand(index, "여행", emptySet()).map { it.id })
        assertEquals(listOf("p1", "p2"), memoFolderBand(index, "여행", setOf("n1")).map { it.id })
        assertTrue(memoFolderBand(index, "쇼핑", emptySet()).isEmpty())
        assertTrue(memoRootBand(MemoIndex.EMPTY).isEmpty())
    }
}
