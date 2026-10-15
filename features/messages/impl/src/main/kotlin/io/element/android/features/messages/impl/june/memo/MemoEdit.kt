/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * 앱12 D·E (관리자: "편집모드 상태에서 다른 화면 이동 시 작성 중인 내용 사라짐", "저장버튼 없애고 마지막 편집상태로 자동 반영"):
 * what the note editor holds and when it writes. Nothing here knows Compose: the screen (MemoSearchTab.kt) only draws
 * this state and calls [MemoEditHub].
 *
 * The input used to live in `remember(note.id)`, so it died with the screen. It now lives in [MemoEditHub] (one entry
 * per note, process lifetime) and is written into the local note 1.5 s after the typing stops, and again whenever the
 * editor leaves the screen. Photos are the one exception: they are applied when the editing ends (결정기록 4).
 */

/** What the editor shows: the text fields as typed and the photo edit of 앱10. */
internal data class MemoEditDraft(
    val title: String = "",
    val tags: String = "",
    val body: String = "",
    val photos: MemoPhotoEdit = MemoPhotoEdit(emptyList()),
)

/**
 * One editing session of a note: [entry] is how the note looked when the editor was opened ([취소] goes back to it),
 * [saved] is what the local note holds now, [draft] is what the user typed. [failure] is the last save error.
 */
internal data class MemoEditState(
    val noteId: String,
    val entry: MemoEditDraft,
    val saved: MemoEditDraft,
    val draft: MemoEditDraft,
    val failure: String? = null,
) {
    /** Text waiting for the auto save (the photos are applied only when the editing ends). */
    val textDirty: Boolean get() = draft.title != saved.title || draft.tags != saved.tags || draft.body != saved.body

    val photosDirty: Boolean get() = draft.photos.changed

    val dirty: Boolean get() = textDirty || photosDirty

    /** True when [취소] has something to undo: typed text, photo marks, or text already written by the auto save. */
    val revertable: Boolean get() = dirty || saved.title != entry.title || saved.tags != entry.tags || saved.body != entry.body

    fun typed(next: MemoEditDraft) = copy(draft = next)
}

/** What one save attempt did: [saved] is the draft the local note now holds (null = nothing written). */
internal data class MemoSaveResult(val saved: MemoEditDraft?, val failure: String? = null)

/** The editor writes the local note this long after the typing stopped (앱12 D). */
internal const val MEMO_EDIT_SAVE_DELAY_MS = 1_500L

/** The editing session of [note] as it opens: the note's own title, tags, body and photos. */
internal fun memoEditDraftOf(note: MemoNote): MemoEditDraft = MemoEditDraft(
    title = note.header.optString("제목"),
    tags = note.tags.joinToString(" ") { "#$it" },
    body = note.body,
    photos = MemoPhotoEdit(note.attachments),
)

/**
 * The `edit` op that brings the note from [saved] to [draft], null when there is nothing to write. [includePhotos]
 * is true only when the editing ends: the photo marks of the editor become `사진삭제` / `사진추가` then (결정기록 4).
 */
internal fun memoEditOp(
    noteId: String,
    saved: MemoEditDraft,
    draft: MemoEditDraft,
    added: List<Map<String, Any?>> = emptyList(),
    includePhotos: Boolean = false,
): MemoOp? {
    val body = draft.body.takeIf { it != saved.body }
    val title = draft.title.takeIf { it != saved.title }
    val tags = memoParseTagInput(draft.tags).takeIf { draft.tags != saved.tags }
    val removed = draft.photos.removedKeys.takeIf { includePhotos && it.isNotEmpty() }
    val picked = added.takeIf { includePhotos && it.isNotEmpty() }
    if (listOf(body, title, tags, removed, picked).all { it == null }) return null
    return MemoOp.edit(
        MemoTarget(noteId = noteId),
        body = body,
        title = title,
        tags = tags,
        photosRemoved = removed,
        photosAdded = picked,
    )
}

/** The op of [취소]: the text goes back to [entry]. The photo marks are only dropped (nothing was written). */
internal fun memoEditCancelOp(noteId: String, saved: MemoEditDraft, entry: MemoEditDraft): MemoOp? =
    memoEditOp(noteId, saved, entry)

/** What the local note holds after a save: the typed text, and the photos only when they went with it. */
internal fun memoEditSaved(saved: MemoEditDraft, draft: MemoEditDraft, includePhotos: Boolean): MemoEditDraft =
    draft.copy(photos = if (includePhotos) memoPhotosApplied(draft.photos) else saved.photos)

/** The photo state after its marks were written: the kept photos, no mark and no pick left. */
internal fun memoPhotosApplied(photos: MemoPhotoEdit): MemoPhotoEdit =
    MemoPhotoEdit(photos.existing.filterNot(photos::isRemoved))

/** The small line under the editor: what the auto save is doing right now. */
internal fun memoEditStatus(state: MemoEditState): String = when {
    state.failure != null -> "저장 못 함 · ${state.failure} (다시 시도합니다)"
    state.textDirty -> "자동 저장 대기…"
    state.photosDirty -> "사진은 편집을 마치면 반영됩니다"
    state.saved != state.entry -> "자동 저장됨"
    else -> ""
}

/**
 * A long press on the reading area opens the editor (앱12 C), null when it must do nothing: no note open, already
 * editing, or the selection mode is on.
 */
internal fun memoReadingLongPress(location: MemoLocation, selecting: Boolean): MemoLocation? =
    if (location.note != null && !location.editing && !selecting) location.startEditing() else null

/** "#교토 이동, 빵집" → [교토, 이동, 빵집]. */
internal fun memoParseTagInput(text: String): List<String> =
    text.split(' ', ',', '\n').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()

/**
 * The editing sessions, one per note, outside the screen (앱12 D): the typed text survives a tab switch, the note
 * closing and the app going to the background. Saves are serialised by one lock, and the save that leaves the screen
 * runs in this scope because the screen's own one is already gone by then.
 */
internal object MemoEditHub {
    private val _sessions = MutableStateFlow<Map<String, MemoEditState>>(emptyMap())
    val sessions: StateFlow<Map<String, MemoEditState>> = _sessions.asStateFlow()

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun state(noteId: String): MemoEditState? = _sessions.value[noteId]

    /** Starts the session of [noteId] with [entry]; an open one keeps what the user typed. */
    fun open(noteId: String, entry: MemoEditDraft) {
        _sessions.update { all ->
            if (all[noteId] != null) all else all + (noteId to MemoEditState(noteId, entry, entry, entry))
        }
    }

    fun typed(noteId: String, draft: MemoEditDraft) {
        _sessions.update { all -> all[noteId]?.let { all + (noteId to it.typed(draft)) } ?: all }
    }

    fun drop(noteId: String) {
        _sessions.update { it - noteId }
    }

    /**
     * Writes the session of [noteId] with [persist]. The state is read again afterwards, so text typed while the save
     * was running is kept; [dropWhenDone] ends the session when nothing is left to write.
     */
    suspend fun save(noteId: String, dropWhenDone: Boolean = false, persist: suspend (MemoEditState) -> MemoSaveResult) {
        lock.withLock {
            val before = state(noteId) ?: return
            val result = persist(before)
            val after = state(noteId) ?: return
            val next = after.copy(saved = result.saved ?: after.saved, failure = result.failure)
            if (dropWhenDone && result.failure == null && !next.dirty) drop(noteId) else _sessions.update { it + (noteId to next) }
        }
    }

    /** The same save, started from this object's own scope: the editor left the screen and took its scope with it. */
    fun saveLater(noteId: String, dropWhenDone: Boolean = true, persist: suspend (MemoEditState) -> MemoSaveResult) {
        scope.launch { save(noteId, dropWhenDone, persist) }
    }
}

/**
 * 추가요청 2 (관리자 2026-10-15: "자동저장 기능 도입되면 화면을 떠나기 전까지는 검토하지 않도록"): what the auto save
 * writes goes up only after the editing ended. While [editing] the changes wait in the outbox (one row per note), so
 * the server is not asked to review a note that is still being typed.
 */
internal fun memoPushAllowed(visible: Boolean, editing: Boolean, hasPending: Boolean): Boolean =
    visible && !editing && hasPending
