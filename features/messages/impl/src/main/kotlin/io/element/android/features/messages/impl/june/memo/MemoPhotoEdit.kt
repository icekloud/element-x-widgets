/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

/*
 * 앱10 D (관리자: "사진 첨부된 노트를 편집시에 사진을 편집(추가,삭제)할 방법이 없음"): the photos of the note editor.
 * Nothing changes before [저장]: a removed photo is only marked (undo brings it back), a picked one is only remembered.
 * The save is one normal note change: the photos to drop by key and the new files, which go up later like the photos
 * of a new note (the note first, the photo after, 앱8).
 */

/** `edit` op argument: the keys ([memoAttachmentKey]) of the photos removed in the editor. */
internal const val MEMO_ARG_PHOTOS_REMOVED = "사진삭제"

/** `edit` op argument: the new photos, copied next to the database ([memoPhotoArg]). */
internal const val MEMO_ARG_PHOTOS_ADDED = "사진추가"

/**
 * The key of a photo of the screen, the same as [MemoLocalAttachment.key] of the stored one: the mxc once uploaded,
 * the phone file before.
 */
internal fun memoAttachmentKey(a: MemoAttachment): String = a.mxc.removePrefix(MEMO_LOCAL_FILE_PREFIX)

/**
 * The editor state of the photos: [existing] as the note has them, [removed] keys marked for removal, [picked] the new
 * picks (content uris as text) in order.
 */
internal data class MemoPhotoEdit(
    val existing: List<MemoAttachment>,
    val removed: Set<String> = emptySet(),
    val picked: List<String> = emptyList(),
) {
    val changed: Boolean get() = removed.isNotEmpty() || picked.isNotEmpty()

    /** How many photos the note will have after [저장]. */
    val count: Int get() = existing.count { memoAttachmentKey(it) !in removed } + picked.size

    fun isRemoved(a: MemoAttachment): Boolean = memoAttachmentKey(a) in removed

    /** ✕ on a photo of the note, or "되돌리기" on one already marked. */
    fun toggle(a: MemoAttachment): MemoPhotoEdit {
        val key = memoAttachmentKey(a)
        return copy(removed = if (key in removed) removed - key else removed + key)
    }

    /** New picks are added at the end; the same uri twice is kept once. */
    fun pick(uris: List<String>): MemoPhotoEdit = copy(picked = (picked + uris).distinct())

    /** ✕ on a new pick: it is simply forgotten (nothing was copied yet). */
    fun unpick(uri: String): MemoPhotoEdit = copy(picked = picked - uri)

    /** The keys sent with the save (only those the note still has). */
    val removedKeys: List<String> get() = existing.map(::memoAttachmentKey).filter { it in removed }
}

internal fun memoPhotoArg(a: MemoLocalAttachment): Map<String, Any?> =
    linkedMapOf("id" to a.id, "경로" to a.localPath, "mime" to a.mime, "크기" to a.bytes)

/** The new photos of an `edit` op ([MEMO_ARG_PHOTOS_ADDED]), waiting for their upload (로컬만). */
internal fun memoPhotosOf(v: Any?): List<MemoLocalAttachment> = (v as? List<*>).orEmpty().mapNotNull { item ->
    val m = item as? Map<*, *> ?: return@mapNotNull null
    val path = (m["경로"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    MemoLocalAttachment(
        id = (m["id"] as? String)?.takeIf { it.isNotEmpty() } ?: path.substringAfterLast('/'),
        localPath = path,
        mime = m["mime"] as? String,
        bytes = (m["크기"] as? Number)?.toLong(),
    )
}

/** Drops the photos of [removed] keys (mxc, phone file or id: a photo may have gone up while the editor was open). */
internal fun memoWithoutPhotos(list: List<MemoLocalAttachment>, removed: Set<String>): List<MemoLocalAttachment> =
    list.filterNot { it.key in removed || it.id in removed || it.localPath?.let { p -> p in removed } == true }

/**
 * The kind after a photo edit: a text note that gets photos becomes a photo note (its card then shows them), a photo
 * note without photos any more goes back to what its text is. Other kinds (voice, link) stay.
 */
internal fun memoKindAfterPhotos(kind: String, body: String, attachments: List<MemoLocalAttachment>): String = when {
    kind == MEMO_KIND_TEXT && attachments.isNotEmpty() -> MEMO_KIND_PHOTO
    kind == MEMO_KIND_PHOTO && attachments.isEmpty() -> memoKindOf(body, emptyList())
    else -> kind
}

internal const val MEMO_KIND_TEXT = "텍스트"
internal const val MEMO_KIND_PHOTO = "사진"

/** True when the user changed what the date shown follows: the text, the title or the photo list (앱10). */
internal fun memoUserChanged(old: MemoNoteRecord, new: MemoNoteRecord): Boolean =
    old.title != new.title || old.body != new.body || old.attachments.map { it.key } != new.attachments.map { it.key }
