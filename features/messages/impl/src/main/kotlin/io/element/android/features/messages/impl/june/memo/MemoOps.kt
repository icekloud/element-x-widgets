/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import org.json.JSONArray
import org.json.JSONObject

/**
 * One `app.june.memo_op` request (이벤트규약 §2). [args] keeps the order and the Korean names of the protocol.
 * Values are String, Boolean, Int, List<String>, Map<String, Any?> or null (null = not sent).
 */
internal data class MemoOp(val op: String, val args: Map<String, Any?> = emptyMap()) {
    /** Ops that change something on the server (their answer is `app.june.memo.ok`). */
    val isChange: Boolean get() = op !in READ_OPS

    fun toJson(requestId: String): JSONObject {
        val o = JSONObject()
        o.put("op", op)
        o.put("요청id", requestId)
        args.forEach { (k, v) -> toJsonValue(v)?.let { o.put(k, it) } }
        return o
    }

    companion object {
        private val READ_OPS = setOf("list", "get", "search", "tag_list", "지시조회")

        fun list(folder: String, tags: List<String>, sort: String?, cursor: String?) =
            MemoOp("list", linkedMapOf("folder" to folder, "tags" to tags.takeIf { it.isNotEmpty() }, "sort" to sort, "cursor" to cursor))

        fun get(id: String) = MemoOp("get", mapOf("id" to id))
        fun tagList(prefix: String) = MemoOp("tag_list", mapOf("prefix" to prefix))
        fun search(query: MemoSearchQuery, cursor: String?) =
            MemoOp("search", linkedMapOf("q" to query.text, "filters" to query.filters(), "cursor" to cursor))

        /** Note ops: [id] when known, otherwise the Matrix event id of the input (이벤트규약-변경 1). */
        private fun note(op: String, target: MemoTarget, extra: Map<String, Any?> = emptyMap()) =
            MemoOp(op, linkedMapOf<String, Any?>("id" to target.noteId, "이벤트" to target.eventId).apply { putAll(extra) })

        fun move(target: MemoTarget, folder: String) = note("move", target, mapOf("folder" to folder))
        fun editTags(target: MemoTarget, tags: List<String>) = note("edit", target, mapOf("태그" to tags))
        /** 앱10 D: [photosRemoved] keys and [photosAdded] new files ([memoPhotoArg]) of the editor, null = unchanged. */
        fun edit(
            target: MemoTarget,
            body: String?,
            title: String?,
            tags: List<String>?,
            photosRemoved: List<String>? = null,
            photosAdded: List<Map<String, Any?>>? = null,
        ) = note(
            "edit",
            target,
            linkedMapOf(
                "본문" to body,
                "제목" to title,
                "태그" to tags,
                MEMO_ARG_PHOTOS_REMOVED to photosRemoved?.takeIf { it.isNotEmpty() },
                MEMO_ARG_PHOTOS_ADDED to photosAdded?.takeIf { it.isNotEmpty() },
            ),
        )
        fun pin(target: MemoTarget) = note("pin", target)
        fun unpin(target: MemoTarget) = note("unpin", target)
        fun dismissPin(target: MemoTarget) = note("dismiss_pin", target)
        fun trash(target: MemoTarget) = note("trash", target)
        fun restore(target: MemoTarget) = note("restore", target)

        fun mkdir(path: String, icon: String?) = MemoOp("mkdir", linkedMapOf("path" to path, "icon" to icon))
        fun renameDir(path: String, new: String) = MemoOp("rename_dir", linkedMapOf("path" to path, "new" to new))
        fun rmdir(path: String) = MemoOp("rmdir", mapOf("path" to path))

        fun tagCreate(name: String, axis: String?) = MemoOp("tag_create", linkedMapOf("name" to name, "axis" to axis))
        fun tagRename(old: String, new: String) = MemoOp("tag_rename", linkedMapOf("old" to old, "new" to new))
        fun tagMerge(targets: List<String>, into: String) = MemoOp("tag_merge", linkedMapOf("targets" to targets, "into" to into))
        fun tagDelete(name: String) = MemoOp("tag_delete", mapOf("name" to name))

        fun accept(proposalId: String, folder: String? = null) = MemoOp("accept", linkedMapOf("id" to proposalId, "folder" to folder))
        fun dismiss(proposalId: String) = MemoOp("dismiss", mapOf("id" to proposalId))
        fun defer(proposalId: String, date: String?) = MemoOp("defer", linkedMapOf("id" to proposalId, "날짜" to date))

        /** 앱9 (규약-지시 v1): an instruction typed in the proposal area, and the look-up of the results. */
        fun directive(id: String, text: String) = MemoOp("지시", linkedMapOf("id" to id, "문장" to text))
        fun directivePoll(ids: List<String>) = MemoOp("지시조회", mapOf("ids" to ids))
    }
}

/** What a note op points at: the note id, or (from the record tab) the input event of the note. */
internal data class MemoTarget(val noteId: String? = null, val eventId: String? = null) {
    val isKnown: Boolean get() = noteId != null || eventId != null
}

private fun toJsonValue(v: Any?): Any? = when (v) {
    null -> null
    is List<*> -> JSONArray().apply { v.forEach { item -> toJsonValue(item)?.let { put(it) } } }
    is Map<*, *> -> JSONObject().apply { v.forEach { (k, item) -> toJsonValue(item)?.let { put(k.toString(), it) } } }
    else -> v
}
