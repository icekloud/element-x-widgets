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
        val extra = counts.keys.filter { it !in known }.sorted().map { MemoFolderRow(it, "", "사용자", Int.MAX_VALUE) }
        val folders = (rows + extra).map { f ->
            val own = counts[f.path]
            val total = counts.values.filter { it.path == f.path || it.path.startsWith(f.path + "/") }.sumOf { it.count }
            MemoFolder(
                path = f.path,
                // 앱14 A·B: the stored icon (picked here or sent by the server), else the built-in table, the name
                // rules, 📁. Every screen draws this value.
                icon = memoFolderIcon(f.path, f.icon),
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
            runCatchingJson(s.payload)?.let(::parseMemoProposal)?.copy(status = status, seenMs = s.createdMs)
        }
        return MemoIndex(folders, proposals)
    }

    private fun note(rid: String, id: String): MemoResponse {
        val n = db.note(id)?.takeIf { it.deleted != MEMO_DELETED_TOMBSTONE } ?: return MemoResponse.Error(rid, "get", "노트를 찾지 못했습니다")
        val header = memoHeaderJsonWithoutMeta(n.headerJson)?.let { runCatchingJson(it) } ?: JSONObject()
        // 앱10: the number and the user's last change, kept beside the note (MemoNoteMeta), for the note screen.
        val meta = memoNoteMeta(db, n.id)
        if (meta.number > 0) header.put(MEMO_FIELD_NUMBER, meta.number)
        if (meta.userModifiedMs > 0) header.put(MEMO_FIELD_USER_MODIFIED, memoIso(meta.userModifiedMs))
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
        // 앱13 D: copies of one picture stored by the 앱12 editor are drawn once (the rows themselves stay).
        val files = memoDistinctPhotos(n.attachments).mapNotNull { it.toScreen() }
        // 앱15 F: the small sync mark of the head.
        val waiting = memoNoteWaiting(n.id, db.waitingNoteIds(listOf(n.id)), n.attachments)
        return MemoResponse.Note(rid, MemoNote(n.id, header, n.body, files, truncated = false, syncWaiting = waiting))
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
                    val changed = noteChange(op, n, rev)
                    // 앱10: text, title or photos changed by the user: the date shown becomes this change.
                    if (memoUserChanged(n, changed)) memoMarkUserModified(db, id, MemoSync.correctedNow(db, now))
                    save(changed, now)
                    ok(id)
                }
                // 앱11 C: the selection mode. Every selected note changes in this one transaction (all or nothing),
                // each with its own stamp and outbox row, like the one-note ops; notes that do not change are left out.
                MEMO_OP_BULK_TRASH, MEMO_OP_BULK_TAG, MEMO_OP_BULK_MOVE -> {
                    val to = a["folder"] as? String
                    val badTarget = to == null || to == MEMO_TRASH || !folderExists(to)
                    if (op.op == MEMO_OP_BULK_MOVE && badTarget) return@transactionWithResult err("폴더가 없습니다: $to")
                    var changed = 0
                    memoArgStrings(a["ids"]).distinct().forEach { id ->
                        val n = db.note(id) ?: return@forEach
                        val m = memoBulkChange(op, n, db.nextRev(device(), now)) ?: return@forEach
                        save(m, now)
                        changed++
                    }
                    ok(null, changed.toString())
                }
                "mkdir" -> {
                    val path = (a["path"] as String).trim('/')
                    if (path.isEmpty()) return@transactionWithResult err("폴더 이름이 없습니다")
                    if (db.folders().any { it.path == path && !it.deleted }) return@transactionWithResult err("이미 있는 폴더입니다")
                    // 앱14 B: no icon given = none stored; the screens work it out from the name.
                    saveFolder(MemoFolderRow(path, (a["icon"] as? String).orEmpty(), "사용자", nextOrder()), now)
                    ok(null, path)
                }
                // 앱14 C: the icon picked for a folder (empty = back to the default). 앱15 A: it also waits for the next
                // `sync_push` (`폴더아이콘[]`); a newer choice for the same folder replaces the waiting one.
                MEMO_OP_FOLDER_ICON -> {
                    val path = (a["path"] as? String).orEmpty().trim('/')
                    val icon = memoChosenFolderIcon(a["icon"]) ?: return@transactionWithResult err("고를 수 없는 아이콘입니다")
                    val row = folderRowFor(path) ?: return@transactionWithResult err("폴더가 없습니다")
                    saveFolder(row.copy(icon = icon), now)
                    val upload = JSONObject().put("경로", path).put("아이콘", icon)
                    db.enqueue(MEMO_OUTBOX_FOLDER_ICON, path, upload.toString(), now)
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
                    // 앱30 ③: when this answer was pressed, for the diagnosis line (id tail and times only).
                    memoAnswerPressed(db, id, answer, now)
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
                // 앱10 D: photos removed and added in the editor, a normal change of the `첨부` bundle (new ones go up later).
                // 앱13 D: taking out a photo takes out its stored copies too, and a copy of a photo the note has is not added.
                val removed = memoRemovedWithCopies(m.attachments, strings(a[MEMO_ARG_PHOTOS_REMOVED]).toSet())
                val added = memoPhotosOf(a[MEMO_ARG_PHOTOS_ADDED])
                if (removed.isNotEmpty() || added.isNotEmpty()) {
                    val kept = memoWithoutPhotos(m.attachments, removed)
                    val ids = kept.map { it.id }.toSet()
                    val photos = kept + memoNewPhotos(kept, added.filter { it.id !in ids })
                    m = m.copy(attachments = photos, revs = m.revs.with(MemoBundle.Attach, rev))
                    // The kind follows the body bundle: a text note that got photos shows them on its card.
                    val kind = memoKindAfterPhotos(m.kind, m.body, photos)
                    if (kind != m.kind) m = m.copy(kind = kind, revs = m.revs.with(MemoBundle.Body, rev))
                }
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
     * [적용] of a `분류` (tags / folder) or `제목` suggestion: written here first, like a change typed on the phone, so it
     * shows at once and goes up with its own stamp. Other kinds (schedule, folder creation) are carried out by the server
     * when it gets the answer. 앱10 B: no suggestion changes the body text (관리자: "메모 내용은 건드리지 말 것").
     */
    private fun applySuggestion(s: MemoSuggestionRow, now: Long) {
        val n = s.noteId?.let { db.note(it) }?.takeIf { it.deleted == MEMO_DELETED_NONE } ?: return
        val content = runCatchingJson(s.payload)?.optJSONObject("제안내용") ?: return
        when (s.kind) {
            "분류" -> {
                val tags = content.optJSONArray("태그")?.let { t -> (0 until t.length()).map { t.optString(it) }.filter { it.isNotEmpty() } }
                val folder = content.optString("폴더").trim('/').takeIf { it.isNotEmpty() }
                if (tags.isNullOrEmpty() && folder == null) return
                folder?.let { if (!folderExists(it)) saveFolder(MemoFolderRow(it, "", "사용자", nextOrder()), now) }
                val rev = db.nextRev(device(), now)
                save(n.copy(tags = tags?.distinct() ?: n.tags, folder = folder ?: n.folder, revs = n.revs.with(MemoBundle.Class, rev)), now)
            }
            "제목" -> {
                // 앱7 항목 1: the review's title for an untitled note. A title the user wrote in the meantime wins.
                val title = content.optString("제목").trim()
                if (title.isEmpty() || n.title.isNotEmpty()) return
                val rev = db.nextRev(device(), now)
                // 규약 추가 결정 2: accepting a title is the user's change (the date shown moves on).
                memoMarkUserModified(db, n.id, MemoSync.correctedNow(db, now))
                save(n.copy(title = title, revs = n.revs.with(MemoBundle.Body, rev)), now)
            }
            // 앱10 B: an old `첨부분석` card (the server makes no new ones since 서버 4차) changes nothing here. Its
            // answer still goes to the server, which clears the card; the photo text stays on the server side.
            else -> Unit
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
        MemoSync.enqueueNote(db, dirty, now)
    }

    private fun saveFolder(f: MemoFolderRow, now: Long) {
        db.putFolder(f, db.nextRev(device(), now), dirty = true)
        val payload = JSONObject().put("경로", f.path).put("아이콘", f.icon).put("삭제", if (f.deleted) 1 else 0)
        db.enqueue(MEMO_OUTBOX_FOLDER, f.path, payload.toString(), now)
    }

    private fun nextOrder(): Int = (db.folders().maxOfOrNull { it.order } ?: 0) + 1

    private fun folderExists(path: String) = db.folders().any { it.path == path && !it.deleted } || path == MEMO_INBOX || path == MEMO_ARCHIVE

    /** 앱14 C: the stored row of [path], or a new one for a folder that only exists through its notes. */
    private fun folderRowFor(path: String): MemoFolderRow? {
        if (path.isEmpty()) return null
        db.folders().firstOrNull { it.path == path && !it.deleted }?.let { return it }
        val hasNotes = db.notesInFolder(path, withSubfolders = false).isNotEmpty()
        return if (hasNotes || folderExists(path)) MemoFolderRow(path, "", "사용자", nextOrder()) else null
    }

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
