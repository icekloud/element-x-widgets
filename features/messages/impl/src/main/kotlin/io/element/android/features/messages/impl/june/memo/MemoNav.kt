/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

internal enum class MemoTab { Record, Folders, Search }

/**
 * Where the memo screen is. The Android back key walks this up one step at a time (설계안 §5.2.4):
 * note → folder tiles → parent folder → folder root → record tab → leave the room;
 * search results / tag manager → search empty state → record tab.
 * Switching tabs, sheets and bands never add a step.
 */
internal data class MemoLocation(
    val tab: MemoTab = MemoTab.Record,
    /** Folder open in the folder tab, null = the folder grid (root). */
    val folder: String? = null,
    /** Note open over the current tab. */
    val note: String? = null,
    val editing: Boolean = false,
    /** The search tab shows results (a query was run), not its empty state. */
    val searchResults: Boolean = false,
    val tagManager: Boolean = false,
) {
    /** True while the memo screen consumes the back key (false = the room's own back: leave). */
    val handlesBack: Boolean get() = back() !is MemoBack.Exit

    fun selectTab(tab: MemoTab) = copy(tab = tab)

    fun openFolder(path: String) = copy(tab = MemoTab.Folders, folder = path, note = null, editing = false)

    fun openNote(id: String) = copy(note = id, editing = false)

    fun startEditing() = copy(editing = true)

    fun stopEditing() = copy(editing = false)

    fun closeNote() = copy(note = null, editing = false)

    fun showSearchResults(shown: Boolean) = copy(searchResults = shown)

    fun openTagManager() = copy(tab = MemoTab.Search, tagManager = true)

    /** What the back key does now. */
    fun back(): MemoBack = when {
        // 앱12 B: there is no [저장] button any more. Back ends the editing (what was typed is saved on the way out,
        // 결정기록 2); pressing it again closes the note as before. Nothing is asked.
        note != null && editing -> MemoBack.To(stopEditing())
        note != null -> MemoBack.To(closeNote())
        tab == MemoTab.Folders && folder != null -> {
            val parent = folder.substringBeforeLast('/', "")
            MemoBack.To(copy(folder = parent.ifEmpty { null }))
        }
        tab == MemoTab.Folders -> MemoBack.To(copy(tab = MemoTab.Record))
        tab == MemoTab.Search && tagManager -> MemoBack.To(copy(tagManager = false, searchResults = false))
        tab == MemoTab.Search && searchResults -> MemoBack.To(copy(searchResults = false))
        tab == MemoTab.Search -> MemoBack.To(copy(tab = MemoTab.Record))
        else -> MemoBack.Exit
    }
}

internal sealed interface MemoBack {
    data class To(val state: MemoLocation) : MemoBack
    data object Exit : MemoBack
}

/** A cell of the folder root grid. */
internal sealed interface MemoGridCell {
    data class Folder(val folder: MemoFolder, val shownCount: Int, val secondary: String?, val dimmed: Boolean) : MemoGridCell
    data object NewFolder : MemoGridCell
}

/**
 * The folder root grid, from the index only (요구 E): inbox first ("폴더 없음 N"), the other top level folders
 * in index order, "+ 새 폴더", then the dimmed archive and trash.
 */
internal fun memoRootGrid(index: MemoIndex): List<MemoGridCell> {
    val top = index.folders.filter { '/' !in it.path }
    val inbox = top.firstOrNull { it.path == MEMO_INBOX }
    val regular = top.filter { !it.isSpecial }
    val dimmed = listOfNotNull(top.firstOrNull { it.path == MEMO_ARCHIVE }, top.firstOrNull { it.path == MEMO_TRASH })
    return buildList {
        inbox?.let { add(MemoGridCell.Folder(it, it.total, if (it.unfiledCount > 0) "폴더 없음 ${it.unfiledCount}" else null, dimmed = false)) }
        regular.forEach { add(MemoGridCell.Folder(it, it.total, if (it.source == "사용자") "사용자 생성" else null, dimmed = false)) }
        add(MemoGridCell.NewFolder)
        dimmed.forEach { add(MemoGridCell.Folder(it, it.total, null, dimmed = true)) }
    }
}

/** Direct sub folders of [path]. */
internal fun memoSubfolders(index: MemoIndex, path: String): List<MemoFolder> =
    index.folders.filter { it.parent == path }

/** Folders a note can be moved to (folder sheet): all but the trash, inbox first. */
internal fun memoMoveTargets(index: MemoIndex): List<MemoFolder> =
    index.folders.filter { it.path != MEMO_TRASH }.sortedBy { if (it.path == MEMO_INBOX) 0 else 1 }
