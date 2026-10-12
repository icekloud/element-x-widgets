/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema

/** A folder row of the local database (설계 §a `folder`). */
internal data class MemoFolderRow(val path: String, val icon: String, val source: String, val order: Int = 0, val deleted: Boolean = false)

/** Per folder numbers counted from the notes (the phone counts them itself, 설계 §h index). */
internal data class MemoFolderCount(val path: String, val count: Int, val pinned: Int, val recent: List<String>)

/** One waiting upload of the outbox (설계 §a `outbox`). One row per target: a newer change replaces it (설계 §k-4 ②). */
internal data class MemoOutboxRow(
    val seq: Long,
    val kind: String,
    val target: String,
    val payload: String,
    val createdMs: Long,
    val updatedMs: Long,
    val tries: Int,
    val nextTryMs: Long = 0,
    val lastError: String? = null,
)

internal const val MEMO_OUTBOX_NOTE = "note"
internal const val MEMO_OUTBOX_FOLDER = "folder"

/** One developer log line of the sync (설계 §k-6). Counts and timings only, never note content. */
internal data class MemoSyncLogRow(val atMs: Long, val kind: String, val notes: Int, val tookMs: Long?, val result: String?)

/** How many sync log lines are kept (요구서 A4: the last 50). */
internal const val MEMO_SYNC_LOG_ROWS = 50

/**
 * Element June memo bot local database `june_memo.db` (설계 §a): notes, tags, attachments, suggestions, folders,
 * sync state and the outbox. Plain SQL on the SQLDelight driver (SQLCipher on the phone, JDBC in the unit tests).
 * Every write goes through one transaction (설계 §k-5 저장).
 */
internal class MemoDb(sqlDriver: SqlDriver) : TransacterImpl(sqlDriver) {
    // ---- notes

    fun note(id: String): MemoNoteRecord? = query("SELECT $NOTE_COLS FROM note WHERE id = ?", listOf(id), ::readNote).firstOrNull()?.let { n ->
        n.copy(tags = tagsOf(listOf(id))[id].orEmpty(), attachments = attachmentsOf(listOf(id))[id].orEmpty())
    }

    /** The note made from the Matrix input event [eventId] (record tab targets before v2). */
    fun noteIdByOrigin(eventId: String): String? =
        query("SELECT id FROM note WHERE origin_event = ? LIMIT 1", listOf(eventId)) { c -> c.getString(0)!! }.firstOrNull()

    /** Writes [n] with its tags and attachments (replaces them). */
    fun putNote(n: MemoNoteRecord) = transaction { writeNote(n) }

    fun writeNote(n: MemoNoteRecord) {
        val m = n.modified
        exec(
            "INSERT OR REPLACE INTO note($NOTE_COLS, body_plain, preview, content_hash) VALUES (${marks(NOTE_COL_COUNT + 3)})",
            listOf(
                n.id,
                n.folder,
                n.title,
                n.body,
                n.kind,
                n.createdMs,
                n.revs.toJson(),
                m.ms,
                m.device,
                m.seq,
                n.deleted,
                n.pinned,
                n.pinSuggested,
                n.pinDismissed,
                n.linkJson,
                n.headerJson,
                n.originEvent,
                n.scheduleState,
                n.serverRev,
                n.dirty,
                n.bodyPlain,
                n.bodyPlain.take(MEMO_PREVIEW_CHARS),
                n.contentHash,
            ),
        )
        exec("DELETE FROM note_tag WHERE note_id = ?", listOf(n.id))
        n.tags.distinct().forEach { exec("INSERT INTO note_tag(note_id, tag) VALUES (?, ?)", listOf(n.id, it)) }
        exec("DELETE FROM attachment WHERE note_id = ?", listOf(n.id))
        n.attachments.forEachIndexed { i, a ->
            exec(
                "INSERT INTO attachment(id, note_id, idx, local_path, mxc, file_json, mime, bytes, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(a.id, n.id, i, a.localPath, a.mxc, a.fileJson, a.mime, a.bytes, a.state),
            )
        }
    }

    /** One tile list page (folder + AND tags, newest first). [cursor] is the opaque value of the previous page. */
    fun list(folder: String, tags: List<String>, sort: String?, cursor: String?, limit: Int = MEMO_PAGE_SIZE): MemoPage {
        val where = StringBuilder(
            when (folder) {
                MEMO_TRASH -> "deleted = 1"
                MEMO_ALL_FOLDERS -> "deleted = 0"
                else -> "deleted = 0 AND folder = ?"
            }
        )
        val args = mutableListOf<Any?>()
        if (folder != MEMO_TRASH && folder != MEMO_ALL_FOLDERS) args += folder
        tagFilter(tags, where, args)
        val column = if (sort == "수정") "modified_ms" else "created_ms"
        val order: String
        var offset = 0
        if (sort == "제목") {
            order = "title ASC, id ASC"
            offset = cursor?.removePrefix("o:")?.toIntOrNull() ?: 0
        } else {
            order = "$column DESC, id DESC"
            val c = cursor?.split(':', limit = 3)
            if (c != null && c.size == 3 && c[0] == "k") {
                where.append(" AND ($column < ? OR ($column = ? AND id < ?))")
                args.addAll(listOf(c[1].toLong(), c[1].toLong(), c[2]))
            }
        }
        val rows = query("SELECT $ROW_COLS, $column FROM note WHERE $where ORDER BY $order LIMIT ? OFFSET ?", args + listOf(limit + 1, offset)) { c ->
            readRow(c) to c.getLong(ROW_COL_COUNT)!!
        }
        val page = rows.take(limit)
        val next = when {
            rows.size <= limit -> null
            sort == "제목" -> "o:${offset + limit}"
            else -> page.last().let { (row, v) -> "k:$v:${row.id}" }
        }
        return MemoPage(items(page.map { it.first }), next)
    }

    /** Local search (설계 §k-5): LIKE on title, plain body, tags and folder, newest first, [limit] per page. */
    fun search(searchQuery: MemoSearchQuery, nowMs: Long, cursor: String?, limit: Int = MEMO_PAGE_SIZE): MemoPage {
        val q = searchQuery.normalized()
        val words = q.text.split(' ', '\n', '\t').filter { it.isNotBlank() }
        val where = StringBuilder(if (q.includeHidden) "deleted IN (0, 1)" else "deleted = 0 AND folder <> ?")
        val args = mutableListOf<Any?>()
        if (!q.includeHidden) args += MEMO_INBOX
        words.forEach { w ->
            where.append(
                " AND (title LIKE ? ESCAPE '\\' OR body_plain LIKE ? ESCAPE '\\' OR folder LIKE ? ESCAPE '\\'" +
                    " OR EXISTS (SELECT 1 FROM note_tag t WHERE t.note_id = id AND t.tag LIKE ? ESCAPE '\\'))",
            )
            val like = "%" + likeEscape(w) + "%"
            args.addAll(listOf(like, like, like, like))
        }
        tagFilter(q.tags, where, args)
        val kinds = MEMO_KINDS.filter { it in q.kinds }
        if (kinds.isNotEmpty()) {
            where.append(" AND kind IN (${marks(kinds.size)})")
            args.addAll(kinds)
        }
        q.folder?.let {
            where.append(" AND (folder = ? OR folder LIKE ? ESCAPE '\\')")
            args.addAll(listOf(it, likeEscape(it) + "/%"))
        }
        q.period?.let { p ->
            val (from, to) = memoPeriodRange(p, nowMs)
            where.append(" AND created_ms >= ? AND created_ms < ?")
            args.addAll(listOf(from, to))
        }
        if (q.schedule) where.append(" AND schedule_state IN ('제안있음', '수락됨')")
        if (q.pinned) where.append(" AND pinned = 1")
        val offset = cursor?.toIntOrNull() ?: 0
        val rows = query(
            "SELECT $ROW_COLS, body_plain FROM note WHERE $where ORDER BY created_ms DESC, id DESC LIMIT ? OFFSET ?",
            args + listOf(limit + 1, offset),
        ) { c -> readRow(c) to c.getString(ROW_COL_COUNT).orEmpty() }
        val page = rows.take(limit)
        val items = items(page.map { it.first }).mapIndexed { i, item ->
            val match = memoSnippet(words.firstOrNull(), item, page[i].second)
            item.copy(snippet = match?.first ?: "", matchField = match?.second)
        }
        return MemoPage(items, if (rows.size > limit) (offset + limit).toString() else null)
    }

    private fun tagFilter(tags: List<String>, where: StringBuilder, args: MutableList<Any?>) {
        val t = tags.distinct()
        if (t.isEmpty()) return
        where.append(" AND id IN (SELECT note_id FROM note_tag WHERE tag IN (${marks(t.size)}) GROUP BY note_id HAVING COUNT(DISTINCT tag) = ?)")
        args.addAll(t)
        args.add(t.size)
    }

    private fun items(rows: List<MemoRow>): List<MemoItem> {
        val ids = rows.map { it.id }
        val tags = tagsOf(ids)
        val attachments = attachmentsOf(ids)
        return rows.map { it.toItem(tags[it.id].orEmpty(), attachments[it.id].orEmpty()) }
    }

    private fun tagsOf(ids: List<String>): Map<String, List<String>> = inChunks(ids) { chunk ->
        query("SELECT note_id, tag FROM note_tag WHERE note_id IN (${marks(chunk.size)}) ORDER BY rowid", chunk) { c -> c.getString(0)!! to c.getString(1)!! }
    }.groupBy({ it.first }, { it.second })

    private fun attachmentsOf(ids: List<String>): Map<String, List<MemoLocalAttachment>> = inChunks(ids) { chunk ->
        query(
            "SELECT note_id, id, mxc, local_path, file_json, mime, bytes, state FROM attachment WHERE note_id IN (${marks(chunk.size)}) ORDER BY note_id, idx",
            chunk,
        ) { c ->
            c.getString(0)!! to MemoLocalAttachment(
                id = c.getString(1)!!,
                mxc = c.getString(2),
                localPath = c.getString(3),
                fileJson = c.getString(4),
                mime = c.getString(5),
                bytes = c.getLong(6),
                state = c.getString(7) ?: MEMO_ATTACH_LOCAL,
            )
        }
    }.groupBy({ it.first }, { it.second })

    private fun <T> inChunks(ids: List<String>, f: (List<String>) -> List<T>): List<T> =
        if (ids.isEmpty()) emptyList() else ids.distinct().chunked(MAX_IN).flatMap(f)

    // ---- folders and tags

    fun folders(): List<MemoFolderRow> = query("SELECT path, icon, source, order_idx, deleted FROM folder ORDER BY order_idx, path", emptyList()) { c ->
        MemoFolderRow(c.getString(0)!!, c.getString(1)!!, c.getString(2)!!, c.getLong(3)!!.toInt(), c.getLong(4) == 1L)
    }

    fun putFolder(f: MemoFolderRow, rev: MemoRev, dirty: Boolean) = exec(
        "INSERT OR REPLACE INTO folder(path, icon, source, order_idx, deleted, modified_ms, modified_device, modified_seq, dirty)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(f.path, f.icon, f.source, f.order, f.deleted, rev.ms, rev.device, rev.seq, dirty),
    )

    /** Note counts of every folder that has notes (trash = notes with 삭제 1) and the three newest titles. */
    fun folderCounts(): List<MemoFolderCount> {
        val counts = query(
            "SELECT CASE WHEN deleted = 1 THEN ? ELSE folder END AS f, COUNT(*), SUM(pinned) FROM note WHERE deleted IN (0, 1) GROUP BY f",
            listOf(MEMO_TRASH),
        ) { c -> Triple(c.getString(0)!!, c.getLong(1)!!.toInt(), (c.getLong(2) ?: 0L).toInt()) }
        val recent = query(
            "SELECT f, title FROM (SELECT CASE WHEN deleted = 1 THEN ? ELSE folder END AS f, title," +
                " ROW_NUMBER() OVER (PARTITION BY CASE WHEN deleted = 1 THEN ? ELSE folder END ORDER BY created_ms DESC, id DESC) AS n" +
                " FROM note WHERE deleted IN (0, 1)) WHERE n <= 3 ORDER BY f, n",
            listOf(MEMO_TRASH, MEMO_TRASH),
        ) { c -> c.getString(0)!! to c.getString(1)!! }.groupBy({ it.first }, { it.second })
        return counts.map { (path, n, pinned) -> MemoFolderCount(path, n, pinned, recent[path].orEmpty()) }
    }

    /** Tags in use with their counts (most used first), plus tags created without notes. */
    fun tags(): List<MemoTag> {
        val meta = query("SELECT name, axis, source, synonyms FROM tag", emptyList()) { c ->
            MemoTag(c.getString(0)!!, 0, c.getString(1), c.getString(2), c.getString(3)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty())
        }.associateBy { it.name }
        val used = query(
            "SELECT t.tag, COUNT(*) FROM note_tag t JOIN note n ON n.id = t.note_id WHERE n.deleted = 0 GROUP BY t.tag ORDER BY 2 DESC, t.tag",
            emptyList(),
        ) { c -> c.getString(0)!! to c.getLong(1)!!.toInt() }
        val inUse = used.map { (name, n) -> (meta[name] ?: MemoTag(name, 0, null, null, emptyList())).copy(count = n) }
        return inUse + meta.values.filter { m -> used.none { it.first == m.name } }.sortedBy { it.name }
    }

    fun putTag(tag: MemoTag) = exec(
        "INSERT OR REPLACE INTO tag(name, axis, source, synonyms) VALUES (?, ?, ?, ?)",
        listOf(tag.name, tag.axis, tag.source, tag.synonyms.joinToString("\n")),
    )

    fun deleteTag(name: String) = exec("DELETE FROM tag WHERE name = ?", listOf(name))

    /** Ids of the live and trashed notes carrying [tag]. */
    fun notesWithTag(tag: String): List<String> =
        query("SELECT note_id FROM note_tag WHERE tag = ?", listOf(tag)) { c -> c.getString(0)!! }

    fun notesInFolder(path: String, withSubfolders: Boolean): List<String> = query(
        "SELECT id FROM note WHERE deleted IN (0, 1) AND (folder = ?" + if (withSubfolders) " OR folder LIKE ? ESCAPE '\\')" else ")",
        if (withSubfolders) listOf(path, likeEscape(path) + "/%") else listOf(path),
    ) { c -> c.getString(0)!! }

    // ---- sync state, stamps and outbox

    fun state(key: String): String? = query("SELECT value FROM sync_state WHERE key = ?", listOf(key)) { c -> c.getString(0) }.firstOrNull()

    fun setState(key: String, value: String) = exec("INSERT OR REPLACE INTO sync_state(key, value) VALUES (?, ?)", listOf(key, value))

    /** A new change stamp of this device: [nowMs] with the next per device sequence number (설계 §c-2). */
    fun nextRev(device: String, nowMs: Long): MemoRev {
        val seq = (state(STATE_SEQ)?.toLongOrNull() ?: 0L) + 1
        setState(STATE_SEQ, seq.toString())
        return MemoRev(nowMs, device, seq)
    }

    /** Queues [target] for the next upload. A waiting entry of the same target is replaced (one upload per note). */
    fun enqueue(kind: String, target: String, payload: String, nowMs: Long) = exec(
        "INSERT INTO outbox(kind, target, payload_json, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?)" +
            " ON CONFLICT(kind, target) DO UPDATE SET payload_json = excluded.payload_json, updated_ms = excluded.updated_ms, tries = 0, next_try_ms = 0",
        listOf(kind, target, payload, nowMs, nowMs),
    )

    fun outbox(): List<MemoOutboxRow> = query(
        "SELECT seq, kind, target, payload_json, created_ms, updated_ms, tries, next_try_ms, last_error FROM outbox ORDER BY seq",
        emptyList()
    ) { c ->
        MemoOutboxRow(
            seq = c.getLong(0)!!,
            kind = c.getString(1)!!,
            target = c.getString(2)!!,
            payload = c.getString(3)!!,
            createdMs = c.getLong(4)!!,
            updatedMs = c.getLong(5)!!,
            tries = c.getLong(6)!!.toInt(),
            nextTryMs = c.getLong(7) ?: 0,
            lastError = c.getString(8),
        )
    }

    fun outboxCount(): Int = query("SELECT COUNT(*) FROM outbox", emptyList()) { c -> c.getLong(0)!!.toInt() }.first()

    /** Compare sync summary (이벤트규약 v2 §2 `구간요약`): per segment the hash of its `<id>:<rev>` lines and the count. */
    fun segmentSummary(segments: Int = MEMO_SEGMENTS): Map<Int, Pair<String, Int>> =
        query("SELECT id, modified_ms, modified_device, modified_seq FROM note", emptyList()) { c ->
            val id = c.getString(0)!!
            memoSegment(id, segments) to memoSegmentLine(id, MemoRev(c.getLong(1)!!, c.getString(2).orEmpty(), c.getLong(3)!!))
        }.groupBy({ it.first }, { it.second }).mapValues { (_, lines) -> memoSegmentHash(lines) to lines.size }

    /** The `<id>:<rev>` summary lines of one segment, what the compare sync sends for a segment the server differs on. */
    fun segmentLines(segment: Int, segments: Int = MEMO_SEGMENTS): List<String> =
        query("SELECT id, modified_ms, modified_device, modified_seq FROM note", emptyList()) { c ->
            val id = c.getString(0)!!
            id to MemoRev(c.getLong(1)!!, c.getString(2).orEmpty(), c.getLong(3)!!)
        }.filter { memoSegment(it.first, segments) == segment }.map { (id, rev) -> memoSegmentLine(id, rev) }

    /** Removes the waiting upload [seq] (it went through). */
    fun dropOutbox(seq: Long) = exec("DELETE FROM outbox WHERE seq = ?", listOf(seq))

    /** The upload failed: counts the try and holds it until [nextTryMs] (back-off, 설계 §k-4 ③). */
    fun failOutbox(seq: Long, nextTryMs: Long, error: String?) =
        exec("UPDATE outbox SET tries = tries + 1, next_try_ms = ?, last_error = ? WHERE seq = ?", listOf(nextTryMs, error, seq))

    /** The waiting uploads that may be tried at [nowMs], oldest first. */
    fun outboxDue(nowMs: Long): List<MemoOutboxRow> = outbox().filter { it.nextTryMs <= nowMs }

    /** Writes one developer log line (설계 §k-6). Note content is never written here, only counts and timings. */
    fun log(atMs: Long, kind: String, notes: Int, tookMs: Long?, result: String?) {
        exec(
            "INSERT INTO sync_log(at_ms, kind, notes, took_ms, result) VALUES (?, ?, ?, ?, ?)",
            listOf(atMs, kind, notes.toLong(), tookMs, result),
        )
        exec("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY id DESC LIMIT ?)", listOf(MEMO_SYNC_LOG_ROWS.toLong()))
    }

    /** The kept log lines, newest first (설계 §k-6: the last [MEMO_SYNC_LOG_ROWS]). */
    fun logs(limit: Int = MEMO_SYNC_LOG_ROWS): List<MemoSyncLogRow> = query(
        "SELECT at_ms, kind, notes, took_ms, result FROM sync_log ORDER BY id DESC LIMIT ?",
        listOf(limit.toLong()),
    ) { c -> MemoSyncLogRow(c.getLong(0)!!, c.getString(1)!!, c.getLong(2)!!.toInt(), c.getLong(3), c.getString(4)) }

    // ---- SQL helpers

    private fun exec(sql: String, args: List<Any?>) {
        driver.execute(null, sql, args.size) { args.forEachIndexed { i, v -> bind(i, v) } }
    }

    private fun <T> query(sql: String, args: List<Any?>, map: (SqlCursor) -> T): List<T> = driver.executeQuery(
        null,
        sql,
        { c ->
            val out = ArrayList<T>()
            while (c.next().value) out.add(map(c))
            QueryResult.Value(out)
        },
        args.size,
    ) { args.forEachIndexed { i, v -> bind(i, v) } }.value

    companion object {
        private const val STATE_SEQ = "seq"
        private const val MAX_IN = 500

        val Schema: SqlSchema<QueryResult.Value<Unit>> = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = 1

            override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                MEMO_SCHEMA_V1.forEach { driver.execute(null, it, 0) }
                MemoDb(driver).seedFolders()
                return QueryResult.Unit
            }

            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> =
                QueryResult.Unit
        }
    }

    private fun seedFolders() = MEMO_BUILTIN_FOLDERS.forEachIndexed { i, (path, icon) ->
        putFolder(MemoFolderRow(path, icon, "기본", i), MemoRev.ZERO, dirty = false)
    }
}

private fun SqlPreparedStatement.bind(i: Int, v: Any?) {
    when (v) {
        null -> bindString(i, null)
        is String -> bindString(i, v)
        is Long -> bindLong(i, v)
        is Int -> bindLong(i, v.toLong())
        is Boolean -> bindLong(i, if (v) 1L else 0L)
        else -> bindString(i, v.toString())
    }
}

private fun marks(n: Int) = List(n) { "?" }.joinToString(", ")

private fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

internal const val MEMO_PREVIEW_CHARS = 90

/** The built-in folders (server `BUILTIN_FOLDERS` + archive and trash), created with the database. */
internal val MEMO_BUILTIN_FOLDERS = listOf(
    MEMO_INBOX to "📥",
    "여행" to "✈️",
    "쇼핑·구매" to "🛒",
    "아이디어" to "💡",
    "집·생활" to "🏠",
    "일·공부" to "📚",
    "사람" to "👤",
    "링크보관" to "🔗",
    MEMO_ARCHIVE to "📦",
    MEMO_TRASH to "🗑",
)

private const val NOTE_COLS = "id, folder, title, body, kind, created_ms, rev_json, modified_ms, modified_device, modified_seq, deleted, pinned, " +
    "pin_suggested, pin_dismissed, link_json, header_json, origin_event, schedule_state, server_rev, dirty"
private const val NOTE_COL_COUNT = 20

private fun SqlCursor.bool(i: Int) = getLong(i) == 1L

private fun readNote(c: SqlCursor): MemoNoteRecord {
    val fallback = MemoRev(c.getLong(7)!!, c.getString(8).orEmpty(), c.getLong(9)!!)
    return MemoNoteRecord(
        id = c.getString(0)!!,
        folder = c.getString(1)!!,
        title = c.getString(2)!!,
        body = c.getString(3)!!,
        kind = c.getString(4)!!,
        createdMs = c.getLong(5)!!,
        revs = MemoRevs.parse(c.getString(6), fallback),
        deleted = c.getLong(10)!!.toInt(),
        pinned = c.bool(11),
        pinSuggested = c.bool(12),
        pinDismissed = c.bool(13),
        linkJson = c.getString(14),
        headerJson = c.getString(15),
        originEvent = c.getString(16),
        scheduleState = c.getString(17),
        serverRev = c.getString(18),
        dirty = c.bool(19),
    )
}

/** The columns a list reads: never the body (설계 §k-5). */
private class MemoRow(
    val id: String,
    val folder: String,
    val title: String,
    val preview: String,
    val kind: String,
    val createdMs: Long,
    val pinned: Boolean,
    val pinSuggested: Boolean,
    val scheduleState: String?,
    val linkJson: String?,
) {
    fun toItem(tags: List<String>, attachments: List<MemoLocalAttachment>) = MemoItem(
        id = id,
        kind = kind,
        title = title.ifEmpty { preview.lineSequence().firstOrNull().orEmpty().take(MEMO_TITLE_CHARS).ifEmpty { id } },
        preview = memoLocalPreview(kind, preview, linkJson, attachments),
        date = memoIso(createdMs),
        tags = tags,
        folder = folder,
        pinned = pinned,
        pinSuggested = pinSuggested,
        scheduleState = scheduleState,
    )
}

internal const val MEMO_TITLE_CHARS = 40

private const val ROW_COLS = "id, folder, title, preview, kind, created_ms, pinned, pin_suggested, schedule_state, link_json"
private const val ROW_COL_COUNT = 10

private fun readRow(c: SqlCursor) = MemoRow(
    id = c.getString(0)!!,
    folder = c.getString(1)!!,
    title = c.getString(2)!!,
    preview = c.getString(3).orEmpty(),
    kind = c.getString(4)!!,
    createdMs = c.getLong(5)!!,
    pinned = c.bool(6),
    pinSuggested = c.bool(7),
    scheduleState = c.getString(8),
    linkJson = c.getString(9),
)

/** Version 1 of the schema (설계 §a + §k-5 indexes). New versions add migrations, they never edit this list. */
private val MEMO_SCHEMA_V1 = listOf(
    """
        CREATE TABLE note (
        id TEXT NOT NULL PRIMARY KEY, folder TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, body_plain TEXT NOT NULL,
        preview TEXT, kind TEXT NOT NULL, created_ms INTEGER NOT NULL, modified_ms INTEGER NOT NULL, modified_device TEXT NOT NULL,
        modified_seq INTEGER NOT NULL, rev_json TEXT NOT NULL, content_hash TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
        pinned INTEGER NOT NULL DEFAULT 0, pin_suggested INTEGER NOT NULL DEFAULT 0, pin_dismissed INTEGER NOT NULL DEFAULT 0,
        link_json TEXT, header_json TEXT, origin_event TEXT, schedule_state TEXT, server_rev TEXT, dirty INTEGER NOT NULL DEFAULT 0)
    """.trimIndent(),
    "CREATE INDEX note_folder_created ON note(folder, created_ms DESC)",
    "CREATE INDEX note_modified ON note(modified_ms DESC)",
    "CREATE INDEX note_deleted_folder ON note(deleted, folder)",
    "CREATE INDEX note_dirty ON note(dirty)",
    "CREATE TABLE note_tag (note_id TEXT NOT NULL, tag TEXT NOT NULL, PRIMARY KEY(note_id, tag))",
    "CREATE INDEX note_tag_tag ON note_tag(tag, note_id)",
    """
        CREATE TABLE attachment (id TEXT NOT NULL PRIMARY KEY, note_id TEXT NOT NULL, idx INTEGER NOT NULL, local_path TEXT, mxc TEXT,
        file_json TEXT, mime TEXT, bytes INTEGER, state TEXT NOT NULL, retry_at_ms INTEGER, err TEXT)
    """.trimIndent(),
    "CREATE INDEX attachment_note ON attachment(note_id, idx)",
    "CREATE INDEX attachment_state ON attachment(state)",
    """
        CREATE TABLE suggestion (id TEXT NOT NULL PRIMARY KEY, note_id TEXT, kind TEXT NOT NULL, payload_json TEXT NOT NULL,
        status TEXT NOT NULL, created_ms INTEGER NOT NULL, expires_ms INTEGER, hidden_until_ms INTEGER)
    """.trimIndent(),
    "CREATE INDEX suggestion_note ON suggestion(note_id, status)",
    """
        CREATE TABLE folder (path TEXT NOT NULL PRIMARY KEY, icon TEXT NOT NULL, source TEXT NOT NULL, order_idx INTEGER NOT NULL DEFAULT 0,
        deleted INTEGER NOT NULL DEFAULT 0, modified_ms INTEGER NOT NULL DEFAULT 0, modified_device TEXT NOT NULL DEFAULT '',
        modified_seq INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0)
    """.trimIndent(),
    "CREATE TABLE tag (name TEXT NOT NULL PRIMARY KEY, axis TEXT, source TEXT, synonyms TEXT)",
    "CREATE TABLE sync_state (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
    // tries / next_try_ms: offline back-off (A4). flush_ms: when the screen went away with changes left, the one-time
    // upload (immediate try, then a one-time job run when connected — 관리자 확정 Q1·Q5) was asked for at that time.
    """
        CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, target TEXT NOT NULL, payload_json TEXT NOT NULL,
        created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0, next_try_ms INTEGER NOT NULL DEFAULT 0,
        last_error TEXT, flush_ms INTEGER)
    """.trimIndent(),
    "CREATE UNIQUE INDEX outbox_target ON outbox(kind, target)",
    // Developer request log (설계 §k-6, A4/A5): kind, number of notes and duration only, never note content.
    "CREATE TABLE sync_log (id INTEGER PRIMARY KEY AUTOINCREMENT, at_ms INTEGER NOT NULL, kind TEXT NOT NULL, notes INTEGER NOT NULL," +
        " took_ms INTEGER, result TEXT)",
)
