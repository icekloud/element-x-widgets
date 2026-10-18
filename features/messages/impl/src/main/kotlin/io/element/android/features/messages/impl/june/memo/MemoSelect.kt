/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * 앱11: the pure parts of the pinned strip (A·B), the cards with photos (D) and the selection mode (C). The screens are
 * in MemoPinStrip.kt. 앱17 D: the tab swipe of 추가요청 2 is gone (the cards are pushed to the left for a review).
 */

// ---------------------------------------------------------------- B: what a note shows in one short line

/**
 * The line of a note in a list, a strip or a sheet title (앱11 B): the title (or the start of the body), else
 * `#번호  날짜`, else the kind and the date. Never the note id (the old fallback of an untitled note without text).
 */
internal fun memoItemLine(item: MemoItem): String {
    val title = item.title.takeIf { it.isNotBlank() && it != item.id }
    if (title != null) return memoNumbered(item.number, title)
    val date = item.shortDate
    return if (item.number > 0) "#${item.number}$MEMO_HEAD_GAP$date" else listOf(memoKindIcon(item.kind) + " " + item.kind, date).joinToString(MEMO_HEAD_GAP)
}

/**
 * 앱15 H (관리자: "메모 고유번호(ex.#14)는 나오지않게"): the line of one pinned note in the tray, without its number:
 * the title, else the first line of its text, else its kind and date. One line, the tray cuts it with "…".
 */
internal fun memoPinRowText(item: MemoItem): String {
    val title = item.title.takeIf { it.isNotBlank() && it != item.id }
    if (title != null) return title.lineSequence().first().trim()
    val text = memoCardText(item)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    if (text != null) return text
    return listOf(memoKindIcon(item.kind) + " " + item.kind, item.shortDate).joinToString(MEMO_HEAD_GAP)
}

/** 앱15 H: the tray unfolded is at most this part of the screen height; more pinned notes scroll inside it. */
internal const val MEMO_PIN_TRAY_MAX_PART = 0.4f

/** 앱15 H: the undo banner after a pin is taken off outside the editor stays this long. */
internal const val MEMO_UNDO_SHOW_MS = 5_000L

/** True when the note has no text to show: only its photos (앱11 B: the strip shows a thumbnail for it). */
internal fun memoPhotoOnly(item: MemoItem): Boolean {
    val noTitle = item.untitled || item.title.isBlank() || item.title == item.id
    return noTitle && item.preview is MemoPreview.Photo && memoCardText(item) == null
}

/** Test tags of the 앱11 screens (MemoPinStrip.kt). */
internal object MemoA11Tags {
    const val PIN_STRIP = "memo_pin_strip"
    const val PIN_CELL = "memo_pin_cell"
    const val CARD_PHOTOS = "memo_card_photos"
    const val SELECT_BAR = "memo_select_bar"
    const val SELECT_MARK = "memo_select_mark"

    /** 앱15 H: the 📌 that takes the pin off (card, tray row) and the undo banner. */
    const val PIN_OFF = "memo_pin_off"
    const val UNDO = "memo_undo"

    /** 앱16 B: the 📌 button of the open note, there for every note (pins and unpins). */
    const val PIN_TOGGLE = "memo_pin_toggle"
}

/** 앱16 B: the 📌 button of an open note: filled when pinned, and whether a press does anything now. */
internal data class MemoPinButton(val pinned: Boolean, val enabled: Boolean) {
    /** What the press does, said by the screen reader. */
    val description: String get() = if (pinned) "고정 해제" else "메모 고정"
}

/**
 * 앱16 B (관리자: "메모고정 버튼이 따로 안보임"): the open note shows its 📌 button whether it is pinned or not, so it can be
 * pinned from there too. Not while editing (앱13 B·15 G: the reading head is gone then); not on a note in the trash
 * unless it is still pinned (only taking the pin off makes sense there, as in 앱15 H); in the selection mode it is shown
 * but does nothing (앱15 H).
 */
internal fun memoPinButton(
    pinned: Boolean,
    trashed: Boolean,
    editing: Boolean,
    selecting: Boolean,
): MemoPinButton? = when {
    editing -> null
    trashed && !pinned -> null
    else -> MemoPinButton(pinned = pinned, enabled = !selecting)
}

/** 앱16 B: the banner after a press on the 📌 button (the same banner as 앱15 H, with 되돌리기). */
internal fun memoPinUndoText(pinnedNow: Boolean): String = if (pinnedNow) "고정했습니다" else "고정을 해제했습니다"

/** One cell of the pinned strip: a small photo (photo-only notes) or a short text. */
internal sealed interface MemoPinCell {
    val id: String

    data class Photo(override val id: String, val photo: MemoAttachment, val count: Int) : MemoPinCell
    data class Text(override val id: String, val text: String) : MemoPinCell
}

internal fun memoPinCell(item: MemoItem): MemoPinCell {
    val p = item.preview as? MemoPreview.Photo
    return if (p != null && memoPhotoOnly(item)) MemoPinCell.Photo(item.id, p.cells.first(), p.count) else MemoPinCell.Text(item.id, memoItemLine(item))
}

/** List key of the record tab's pinned strip (앱11 A): no folder can have this name (a folder path never starts with it). */
internal const val MEMO_PINNED_LIST = "\u0000pinned"

// ---------------------------------------------------------------- D: the photos under the text of a card

/**
 * The photos a card draws under its text (앱11 D): null when the preview is already the photos (photo notes without
 * text keep their look) or when the note has none. It does not look at the kind the server gave.
 */
internal fun memoCardPhotos(preview: MemoPreview?, attachments: List<MemoLocalAttachment>): MemoPreview.Photo? {
    if (preview is MemoPreview.Photo) return null
    val shown = attachments.mapNotNull { it.toScreen() }
    val first = shown.firstOrNull() ?: return null
    // 앱20: every photo for the viewer; the card draws only [memoCardCells].
    return MemoPreview.Photo(first.mxc, attachments.size, first.fileJson, shown)
}

// ---------------------------------------------------------------- C: the selection mode

/** The selection mode of the memo screen (앱11 C). Ending it drops the whole selection. */
internal data class MemoSelect(val active: Boolean = false, val ids: List<String> = emptyList()) {
    val count: Int get() = ids.size

    fun start() = copy(active = true)

    fun end() = MemoSelect()

    fun toggle(id: String) = if (!active) this else copy(ids = if (id in ids) ids - id else ids + id)

    fun all(visible: List<String>) = if (!active) this else copy(ids = ids + visible.filter { it !in ids })

    fun none() = copy(ids = emptyList())

    fun isSelected(id: String) = id in ids
}

/** The selection mode per memo bot room: the top bar icon turns it on and off, the memo screen follows. */
internal object MemoSelectHub {
    private val active = MutableStateFlow<Set<String>>(emptySet())

    val rooms: StateFlow<Set<String>> = active.asStateFlow()

    fun toggle(roomId: String) = set(roomId, roomId !in active.value)

    fun set(roomId: String, on: Boolean) {
        active.update { if (on) it + roomId else it - roomId }
    }
}

/** Ops of the bulk actions: one op, one transaction for every selected note (앱11 C). */
internal const val MEMO_OP_BULK_TRASH = "bulk_trash"
internal const val MEMO_OP_BULK_TAG = "bulk_tag"
internal const val MEMO_OP_BULK_MOVE = "bulk_move"

internal fun memoBulkTrash(ids: List<String>) = MemoOp(MEMO_OP_BULK_TRASH, linkedMapOf("ids" to ids))

internal fun memoBulkTag(ids: List<String>, tags: List<String>) = MemoOp(MEMO_OP_BULK_TAG, linkedMapOf("ids" to ids, "tags" to tags))

internal fun memoBulkMove(ids: List<String>, folder: String) = MemoOp(MEMO_OP_BULK_MOVE, linkedMapOf("ids" to ids, "folder" to folder))

/**
 * One note after a bulk action, null when it does not change (no stamp, no upload): trash = the `표식` bundle, tags
 * and folder = the `분류` bundle, like the one-note ops. Tags are only added; a tombstone never changes.
 */
internal fun memoBulkChange(op: MemoOp, n: MemoNoteRecord, rev: MemoRev): MemoNoteRecord? {
    if (n.deleted == MEMO_DELETED_TOMBSTONE) return null
    return when (op.op) {
        MEMO_OP_BULK_TRASH -> if (n.deleted == MEMO_DELETED_TRASH) null else n.copy(deleted = MEMO_DELETED_TRASH, revs = n.revs.with(MemoBundle.Mark, rev))
        MEMO_OP_BULK_TAG -> {
            val add = memoArgStrings(op.args["tags"]).map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() && it !in n.tags }.distinct()
            if (add.isEmpty()) null else n.copy(tags = n.tags + add, revs = n.revs.with(MemoBundle.Class, rev))
        }
        MEMO_OP_BULK_MOVE -> {
            val to = op.args["folder"] as? String ?: return null
            if (to == n.folder) null else n.copy(folder = to, revs = n.revs.with(MemoBundle.Class, rev))
        }
        else -> null
    }
}

internal fun memoArgStrings(v: Any?): List<String> = (v as? List<*>)?.map { it.toString() } ?: emptyList()
