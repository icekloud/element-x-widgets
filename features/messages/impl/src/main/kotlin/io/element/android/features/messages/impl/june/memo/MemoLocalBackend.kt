/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

/**
 * The memo data source of v2 (설계 A2): every op of the screens is answered by the local database, at once and without
 * network. A change is one transaction: the note with the new stamp of its bundle, the dirty flag and one outbox entry
 * (the upload itself is A4). [MemoStore] and the screens do not change: they still talk to a [MemoBackend].
 */
internal class MemoLocalBackend(
    open: () -> MemoDb,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
) : MemoBackend {
    // Opened on first use, always inside [dispatcher] (opening reads the disk).
    private val db by lazy(open)

    override suspend fun fetchIndex(): MemoIndex = withContext(dispatcher) { index() }

    override suspend fun call(op: MemoOp, requestId: String): MemoResponse = withContext(dispatcher) {
        val a = op.args
        when (op.op) {
            "list" -> MemoResponse.List(requestId, db.list(a["folder"] as String, strings(a["tags"]), a["sort"] as? String, a["cursor"] as? String))
            "get" -> note(requestId, a["id"] as String)
            "search" -> MemoResponse.Search(requestId, db.search(searchQueryOf(op), clock(), a["cursor"] as? String))
            "tag_list" -> {
                val prefix = a["prefix"] as? String ?: ""
                MemoResponse.Tags(requestId, db.tags().filter { prefix.isEmpty() || it.name.contains(prefix) })
            }
            else -> change(requestId, op).also { if (it is MemoResponse.Ok) MemoLocalChanges.changed() }
        }
    }

    /**
     * Saves a new note typed or picked in the composer (설계 §f, A3) and returns its id. Title, kind and the plain search
     * text are made here; the bot reviews it later (A4/A5) and its answers only come back as suggestions.
     */
    suspend fun create(
        text: String,
        attachments: List<MemoLocalAttachment> = emptyList(),
        kind: String = memoKindOf(text, attachments),
        folder: String = MEMO_INBOX,
    ): String = withContext(dispatcher) {
        val now = clock()
        val id = db.transactionWithResult {
            var id = memoNewId(now)
            while (db.note(id) != null) id = memoNewId(now)
            val rev = db.nextRev(device(), now)
            val n = MemoNoteRecord(
                id = id,
                folder = folder,
                // 앱7 항목 1: what was typed goes to the body only. The title stays empty until the user writes one
                // or accepts the bot's title suggestion; screens show the start of the body instead (memoDisplayTitle).
                title = "",
                body = text,
                kind = kind,
                createdMs = now,
                revs = MemoRevs.all(rev),
                attachments = attachments,
                dirty = true,
            )
            save(n, now)
            id
        }
        MemoLocalChanges.changed()
        id
    }

    // ---------------------------------------------------------------- attachments (A5, 설계 §e)

    /** Attachments that gave up after [MEMO_ATTACH_MAX_TRIES] tries ("사진 N장을 못 올렸습니다 · 다시 시도"). */
    suspend fun failedAttachments(): Int = withContext(dispatcher) { db.failedAttachmentCount() }

    /** Why one of the failed attachments could not go up (e.g. too big), null when no reason is kept. */
    suspend fun failedAttachmentReason(): String? = withContext(dispatcher) { db.failedAttachmentReason() }

    suspend fun retryFailedAttachments() = withContext(dispatcher) {
        db.retryFailedAttachments()
        MemoLocalChanges.changed()
    }

    /** Uploaded originals still on the phone: how many and how many bytes the clean button would free. */
    suspend fun uploadedOriginals(): Pair<Int, Long> = withContext(dispatcher) {
        val rows = db.uploadedOriginals()
        rows.size to rows.sumOf { it.bytes ?: 0L }
    }

    /**
     * "올림 끝난 원본 정리": removes the phone copy of uploaded attachments only (their mxc stays, they can be fetched
     * again). `로컬만` and `실패` originals are never touched — they are the only copy. Nothing calls this on its own.
     */
    suspend fun cleanUploadedOriginals(delete: (String) -> Boolean): Int = withContext(dispatcher) {
        var n = 0
        db.uploadedOriginals().forEach { a ->
            val path = a.localPath ?: return@forEach
            if (delete(path)) {
                db.dropOriginal(a.id)
                n++
            }
        }
        if (n > 0) MemoLocalChanges.changed()
        n
    }

    // ---------------------------------------------------------------- reads

    private fun index(): MemoIndex {
        val rows = db.folders().filter { !it.deleted }
        val counts = db.folderCounts().associateBy { it.path }
        // Folders that only exist through their notes (a folder removed elsewhere, a note restored from the trash).
        val known = rows.map { it.path }.toSet()
        val extra = counts.keys.filter { it !in known }.sorted().map { MemoFolderRow(it, defaultMemoFolderIcon(it), "사용자", Int.MAX_VALUE) }
        val folders = (rows + extra).map { f ->
            val own = counts[f.path]
            val total = counts.values.filter { it.path == f.path || it.path.startsWith(f.path + "/") }.sumOf { it.count }
            MemoFolder(
                path = f.path,
                icon = f.icon,
                count = own?.count ?: 0,
                unfiledCount = if (f.path == MEMO_INBOX) own?.count ?: 0 else 0,
                pinnedCount = own?.pinned ?: 0,
                recent = own?.recent.orEmpty(),
                total = if (f.path == MEMO_TRASH) own?.count ?: 0 else total,
                source = f.source,
            )
        }
        // Proposals come from the bot review (A5): the waiting ones the last sync copied into the suggestion table.
        // 앱9 C: one answered here whose answer has not reached the server yet stays as "처리중" (locked card).
        val sending = db.outbox().filter { it.kind == MEMO_OUTBOX_PROPOSAL }.map { it.target }.toSet()
        val proposals = db.suggestions().filter { it.status == MEMO_SUGGESTION_WAITING || it.id in sending }.mapNotNull { s ->
            val status = if (s.status == MEMO_SUGGESTION_WAITING) "대기" else MEMO_PROPOSAL_SENDING
            runCatchingJson(s.payload)?.let(::parseMemoProposal)?.copy(status = status)
        }
        return MemoIndex(folders, proposals)
    }

    private fun note(rid: String, id: String): MemoResponse {
        val n = db.note(id)?.takeIf { it.deleted != MEMO_DELETED_TOMBSTONE } ?: return MemoResponse.Error(rid, "get", "노트를 찾지 못했습니다")
        val header = n.headerJson?.let { runCatchingJson(it) } ?: JSONObject()
        header.put("id", n.id)
            .put("생성", memoIso(n.createdMs))
            .put("수정", memoIso(n.modified.ms))
            .put("폴더", n.folder)
            .put("제목", n.title)
            .put("종류", n.kind)
            .put("태그", JSONArray(n.tags))
            .put("고정", n.pinned)
            .put("고정제안", n.pinSuggested)
            .put("삭제", n.deleted)
        n.scheduleState?.let { header.put("일정상태", it) }
        val files = n.attachments.mapNotNull { it.toScreen() }
        return MemoResponse.Note(rid, MemoNote(n.id, header, n.body, files, truncated = false))
    }

    // ---------------------------------------------------------------- changes

    private fun change(rid: String, op: MemoOp): MemoResponse {
        val a = op.args
        fun ok(id: String? = null, path: String? = null) = MemoResponse.Ok(rid, op.op, id, path)
        fun err(reason: String) = MemoResponse.Error(rid, op.op, reason)
        return db.transactionWithResult<MemoResponse> {
            val now = clock()
            when (op.op) {
                "move", "edit", "pin", "unpin", "dismiss_pin", "trash", "restore" -> {
                    val id = targetId(a) ?: return@transactionWithResult err("노트를 찾지 못했습니다")
                    val n = db.note(id)?.takeIf { it.deleted != MEMO_DELETED_TOMBSTONE } ?: return@transactionWithResult err("노트를 찾지 못했습니다")
                    val to = a["folder"] as? String
                    if (op.op == "move" && (to == null || !folderExists(to))) return@transactionWithResult err("폴더가 없습니다: $to")
                    val rev = db.nextRev(device(), now)
                    save(noteChange(op, n, rev), now)
                    ok(id)
                }
                "mkdir" -> {
                    val path = (a["path"] as String).trim('/')
                    if (path.isEmpty()) return@transactionWithResult err("폴더 이름이 없습니다")
                    if (db.folders().any { it.path == path && !it.deleted }) return@transactionWithResult err("이미 있는 폴더입니다")
                    saveFolder(MemoFolderRow(path, a["icon"] as? String ?: defaultMemoFolderIcon(path), "사용자", nextOrder()), now)
                    ok(null, path)
                }
                "rename_dir" -> renameFolder(a["path"] as String, a["new"] as String, now)?.let { ok(null, it) } ?: err("폴더가 없습니다")
                "rmdir" -> {
                    val path = a["path"] as String
                    if (path == MEMO_INBOX || path == MEMO_ARCHIVE || path == MEMO_TRASH) return@transactionWithResult err("기본 폴더는 지울 수 없습니다")
                    db.notesInFolder(path, withSubfolders = true).forEach { id ->
                        val n = db.note(id) ?: return@forEach
                        if (n.deleted == MEMO_DELETED_NONE) save(n.mark(db.nextRev(device(), now)) { copy(deleted = MEMO_DELETED_TRASH) }, now)
                    }
                    db.folders().filter {
                        !it.deleted && (it.path == path || it.path.startsWith(
                        "$path/"
                    ))
                    }.forEach { saveFolder(it.copy(deleted = true), now) }
                    ok(null, path)
                }
                "tag_create" -> {
                    db.putTag(MemoTag(a["name"] as String, 0, a["axis"] as? String, "사용자", emptyList()))
                    ok()
                }
                "tag_rename" -> {
                    val old = a["old"] as String
                    val new = a["new"] as String
                    retag(old, now) { tags -> tags.map { if (it == old) new else it }.distinct() }
                    db.tags().firstOrNull { it.name == old }?.let { db.putTag(it.copy(name = new)) }
                    db.deleteTag(old)
                    ok()
                }
                "tag_merge" -> {
                    val targets = strings(a["targets"])
                    val into = a["into"] as String
                    targets.filter { it != into }.forEach { t ->
                        retag(t, now) { tags -> tags.map { if (it == t) into else it }.distinct() }
                        db.deleteTag(t)
                    }
                    ok()
                }
                "tag_delete" -> {
                    val name = a["name"] as String
                    retag(name, now) { it - name }
                    db.deleteTag(name)
                    ok()
                }
                // A5 (설계 §d-③): the phone answers at once, applies what it can and tells the server at the next sync_push.
                "accept", "dismiss", "defer" -> {
                    val id = a["id"] as? String ?: return@transactionWithResult err("제안을 찾지 못했습니다")
                    val s = db.suggestion(id) ?: return@transactionWithResult err("제안을 찾지 못했습니다")
                    // 앱9 C: one answer per suggestion id. A second press (double tap, the other screen, the copy of a
                    // card) changes nothing and queues nothing: the first answer is already applied and waiting.
                    if (s.status != MEMO_SUGGESTION_WAITING) return@transactionWithResult ok(s.noteId)
                    val answer = when (op.op) {
                        "accept" -> MEMO_SUGGESTION_ACCEPTED
                        "dismiss" -> MEMO_SUGGESTION_DISMISSED
                        else -> MEMO_SUGGESTION_DEFERRED
                    }
                    if (op.op == "accept") applySuggestion(s, now)
                    db.setSuggestionStatus(id, answer)
                    val payload = JSONObject().put("id", id).put("응답", answer)
                    (a["날짜"] as? String)?.let { payload.put("날짜", it) }
                    // 규약 v2 제안응답 has no folder field yet (질문.md): sent anyway, a server that knows it can use it.
                    (a["folder"] as? String)?.let { payload.put("폴더", it) }
                    db.enqueue(MEMO_OUTBOX_PROPOSAL, id, payload.toString(), now)
                    ok(s.noteId)
                }
                else -> err("모르는 op: ${op.op}")
            }
        }
    }

    private fun noteChange(op: MemoOp, n: MemoNoteRecord, rev: MemoRev): MemoNoteRecord {
        val a = op.args
        return when (op.op) {
            "move" -> n.copy(folder = a["folder"] as String, revs = n.revs.with(MemoBundle.Class, rev))
            "edit" -> {
                var m = n
                val body = a["본문"] as? String
                val title = a["제목"] as? String
                if (body != null || title != null) {
                    m = m.copy(body = body ?: m.body, title = title ?: m.title, revs = m.revs.with(MemoBundle.Body, rev))
                }
                if (a["태그"] != null) m = m.copy(tags = strings(a["태그"]).distinct(), revs = m.revs.with(MemoBundle.Class, rev))
                m
            }
            "pin" -> n.mark(rev) { copy(pinned = true, pinSuggested = false) }
            "unpin" -> n.mark(rev) { copy(pinned = false) }
            "dismiss_pin" -> n.mark(rev) { copy(pinSuggested = false, pinDismissed = true) }
            "trash" -> n.mark(rev) { copy(deleted = MEMO_DELETED_TRASH) }
            else -> n.mark(rev) { copy(deleted = MEMO_DELETED_NONE) }
        }
    }

    private fun MemoNoteRecord.mark(rev: MemoRev, f: MemoNoteRecord.() -> MemoNoteRecord) = f().copy(revs = revs.with(MemoBundle.Mark, rev))

    /**
     * [적용] of a `분류` (tags / folder) or `첨부분석` (text added to the body) suggestion: written here first, like a
     * change typed on the phone, so it shows at once and goes up with its own stamp. Other kinds (schedule, folder
     * creation) are carried out by the server when it gets the answer.
     */
    private fun applySuggestion(s: MemoSuggestionRow, now: Long) {
        val n = s.noteId?.let { db.note(it) }?.takeIf { it.deleted == MEMO_DELETED_NONE } ?: return
        val content = runCatchingJson(s.payload)?.optJSONObject("제안내용") ?: return
        when (s.kind) {
            "분류" -> {
                val tags = content.optJSONArray("태그")?.let { t -> (0 until t.length()).map { t.optString(it) }.filter { it.isNotEmpty() } }
                val folder = content.optString("폴더").trim('/').takeIf { it.isNotEmpty() }
                if (tags.isNullOrEmpty() && folder == null) return
                folder?.let { if (!folderExists(it)) saveFolder(MemoFolderRow(it, defaultMemoFolderIcon(it), "사용자", nextOrder()), now) }
                val rev = db.nextRev(device(), now)
                save(n.copy(tags = tags?.distinct() ?: n.tags, folder = folder ?: n.folder, revs = n.revs.with(MemoBundle.Class, rev)), now)
            }
            "제목" -> {
                // 앱7 항목 1: the review's title for an untitled note. A title the user wrote in the meantime wins.
                val title = content.optString("제목").trim()
                if (title.isEmpty() || n.title.isNotEmpty()) return
                val rev = db.nextRev(device(), now)
                save(n.copy(title = title, revs = n.revs.with(MemoBundle.Body, rev)), now)
            }
            "첨부분석" -> {
                val extra = content.optString("본문추가").trimEnd()
                if (extra.isEmpty()) return
                val rev = db.nextRev(device(), now)
                save(n.copy(body = memoNormalizedBody(n.body) + "\n\n" + extra, revs = n.revs.with(MemoBundle.Body, rev)), now)
            }
        }
    }

    /** Changes the tags of every note carrying [tag] (one stamp per note, `분류` bundle). */
    private fun retag(tag: String, now: Long, f: (List<String>) -> List<String>) {
        db.notesWithTag(tag).forEach { id ->
            val n = db.note(id) ?: return@forEach
            save(n.copy(tags = f(n.tags), revs = n.revs.with(MemoBundle.Class, db.nextRev(device(), now))), now)
        }
    }

    /** Renames [from] (and its subfolders, with their notes). Returns the new path, null when [from] does not exist. */
    private fun renameFolder(from: String, new: String, now: Long): String? {
        val rows = db.folders().filter { !it.deleted }
        if (rows.none { it.path == from } && db.notesInFolder(from, withSubfolders = false).isEmpty()) return null
        val parent = from.substringBeforeLast('/', "")
        val to = if ('/' in new || parent.isEmpty()) new.trim('/') else "$parent/$new"
        if (to == from) return to
        fun moved(path: String) = to + path.removePrefix(from)
        rows.filter { it.path == from || it.path.startsWith("$from/") }.forEach { f ->
            saveFolder(f.copy(deleted = true), now)
            saveFolder(f.copy(path = moved(f.path)), now)
        }
        db.notesInFolder(from, withSubfolders = true).forEach { id ->
            val n = db.note(id) ?: return@forEach
            save(n.copy(folder = moved(n.folder), revs = n.revs.with(MemoBundle.Class, db.nextRev(device(), now))), now)
        }
        return to
    }

    // ---------------------------------------------------------------- helpers

    /** Writes [n] as a local change: dirty, and one waiting upload for it (a newer one replaces it). */
    private fun save(n: MemoNoteRecord, now: Long) {
        val dirty = n.copy(dirty = true)
        db.writeNote(dirty)
        db.enqueue(MEMO_OUTBOX_NOTE, n.id, dirty.toChangeJson().toString(), now)
    }

    private fun saveFolder(f: MemoFolderRow, now: Long) {
        db.putFolder(f, db.nextRev(device(), now), dirty = true)
        val payload = JSONObject().put("경로", f.path).put("아이콘", f.icon).put("삭제", if (f.deleted) 1 else 0)
        db.enqueue(MEMO_OUTBOX_FOLDER, f.path, payload.toString(), now)
    }

    private fun nextOrder(): Int = (db.folders().maxOfOrNull { it.order } ?: 0) + 1

    private fun folderExists(path: String) = db.folders().any { it.path == path && !it.deleted } || path == MEMO_INBOX || path == MEMO_ARCHIVE

    private fun targetId(a: Map<String, Any?>): String? =
        (a["id"] as? String)?.takeIf { it.isNotEmpty() } ?: (a["이벤트"] as? String)?.let { db.noteIdByOrigin(it) }

    /** This phone in the change stamps: made once and kept with the database (A4 may switch to the Matrix device id). */
    private fun device(): String = memoDeviceOf(db)

    private fun strings(v: Any?): List<String> = (v as? List<*>)?.map { it.toString() } ?: emptyList()

    private fun runCatchingJson(s: String): JSONObject? = try {
        JSONObject(s)
    } catch (e: org.json.JSONException) {
        null
    }
}

/** A search op back to its query (the store sends [MemoOp.search], the local database answers it). */
internal fun searchQueryOf(op: MemoOp): MemoSearchQuery {
    val f = op.args["filters"] as? Map<*, *> ?: emptyMap<String, Any?>()
    fun strings(v: Any?): List<String> = (v as? List<*>)?.map { it.toString() } ?: emptyList()
    val period: MemoPeriod? = when (val p = f["기간"]) {
        "오늘" -> MemoPeriod.Today
        "7일" -> MemoPeriod.Days7
        "30일" -> MemoPeriod.Days30
        is Map<*, *> -> MemoPeriod.Range(p["시작"].toString(), p["끝"].toString())
        else -> null
    }
    return MemoSearchQuery(
        text = op.args["q"] as? String ?: "",
        tags = strings(f["tags"]),
        kinds = strings(f["종류"]).toSet(),
        folder = f["폴더"] as? String,
        period = period,
        schedule = f["일정연결"] == true,
        pinned = f["고정"] == true,
        includeHidden = f["포함"] != null,
    )
}

private const val DEVICE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

internal fun memoNewDeviceId(random: (Int) -> Int = { java.security.SecureRandom().nextInt(it) }): String =
    (1..6).map { DEVICE_CHARS[random(DEVICE_CHARS.length)] }.joinToString("")

/**
 * Tells the open memo screens that the local notes changed (a note typed in the composer, a change on another tab).
 * The screens read the database again; nothing polls (설계 §k).
 */
internal object MemoLocalChanges {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun changed() = _version.update { it + 1 }
}
