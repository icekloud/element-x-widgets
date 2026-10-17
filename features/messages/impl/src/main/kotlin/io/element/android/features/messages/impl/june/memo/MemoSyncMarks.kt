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

/**
 * 앱15 F (관리자: "노트에 로컬변경사항 적용한경우 날짜 왼쪽에 작게 동기화대기를 알리는 작은 아이콘 … 3단계"): the small mark
 * left of the dates of a note.
 */
internal enum class MemoSyncMark(val description: String) {
    /** A local change, or a photo, waits for the server (the upload held while editing and a retry after an error too). */
    Waiting("동기화 대기"),

    /** The note, or one of its photos, is on its way to the server right now. */
    Sending("동기화 중"),

    /** Nothing waits: what the phone holds is what the server took. */
    Synced("동기화됨"),
}

/**
 * The mark of one note. [waiting] is null when the screen cannot tell (not a note of the phone database): no mark.
 * A note on its way wins over the waiting state.
 */
internal fun memoSyncMark(waiting: Boolean?, sending: Boolean): MemoSyncMark? = when {
    waiting == null -> null
    sending -> MemoSyncMark.Sending
    waiting -> MemoSyncMark.Waiting
    else -> MemoSyncMark.Synced
}

/**
 * True when [noteId] still waits for the server: a note row in the outbox ([waitingNotes]), or a photo not uploaded
 * yet (also one that failed: the server does not have it).
 */
internal fun memoNoteWaiting(noteId: String, waitingNotes: Set<String>, attachments: List<MemoLocalAttachment>): Boolean =
    noteId in waitingNotes || attachments.any { it.state in MEMO_ATTACH_NOT_UP }

/** The photo states that are not on the server. */
internal val MEMO_ATTACH_NOT_UP = setOf(MEMO_ATTACH_LOCAL, MEMO_ATTACH_UPLOADING, MEMO_ATTACH_FAILED)

/**
 * The notes whose change or photo is being sent right now ([MemoSyncRunner] sets them around `sync_push` and around a
 * photo upload). One flow for the whole screen: the cards read it once, no timer per note.
 */
internal object MemoSendingNotes {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun start(noteIds: Collection<String>) {
        if (noteIds.isNotEmpty()) _ids.update { it + noteIds }
    }

    fun end(noteIds: Collection<String>) {
        if (noteIds.isNotEmpty()) _ids.update { it - noteIds.toSet() }
    }
}
