/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.core.data.tryOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Element June memo sync engine (A4, 이벤트규약 v2 §2·§3, 설계 §b·§c·§k-4).
 *
 * The phone owns its notes; the server is the other side of the sync and the background reviewer. Everything here is
 * pure logic over [MemoDb] and JSON, so the unit tests and the server interoperability test can run it without Android
 * and without Matrix: the transport only has to pass `sync_pull` / `sync_push` arguments and hand the answers back.
 *
 * What the engine does:
 * - builds the `sync_pull` arguments (기준 + cursor + 구간요약) and applies the `app.june.memo.sync` answer
 * - builds the `sync_push` arguments from the outbox and applies the `app.june.memo.sync_ack` answer
 * - merges with [mergeMemoNote] (four bundles, latest of each wins — 관리자 확정 Q4)
 * - keeps the clock offset (`서버시각 - 내 시각`) so stamps made offline stay comparable with the server ones
 * - holds failed uploads back with a growing delay (back-off) instead of retrying in a loop
 * - writes one line per request to the developer log (counts and timings only, never note content)
 */
internal object MemoSync {
    const val STATE_BASE = "sync_base"
    const val STATE_CURSOR = "sync_cursor"
    const val STATE_SKEW = "clock_skew"
    const val STATE_SERVER_SEQ = "server_seq"
    const val STATE_PHOTO_REPAIR = "photo_repair_a11"

    /** Uploads of one `sync_push` (the server reads at most `sy.PAGE` = 200 of them). */
    const val PUSH_BATCH = 50

    /** Back-off delays of a failed upload, by try count (설계 §k-4 ③). The last one repeats. */
    val BACKOFF_MS = longArrayOf(5_000, 15_000, 60_000, 300_000, 900_000)

    // ---- state

    fun base(db: MemoDb): Long = db.state(STATE_BASE)?.toLongOrNull() ?: 0

    fun cursor(db: MemoDb): String? = db.state(STATE_CURSOR)?.takeIf { it.isNotEmpty() }

    /** `서버시각 - 내 시각` in millis. Added to the phone clock when a change stamp is made (이벤트규약 v2 §3). */
    fun skewMs(db: MemoDb): Long = db.state(STATE_SKEW)?.toLongOrNull() ?: 0

    /** The phone time corrected with the last known server offset. */
    fun correctedNow(db: MemoDb, nowMs: Long): Long = nowMs + skewMs(db)

    /** The last server sequence we saw. Larger than [base] means the server holds changes we do not have yet. */
    fun serverSeq(db: MemoDb): Long = db.state(STATE_SERVER_SEQ)?.toLongOrNull() ?: 0

    /** True when a `sync_pull` is worth making right now (설계 §k-4: no polling, this answers "is there anything new"). */
    fun needsPull(db: MemoDb): Boolean = serverSeq(db) > base(db) || cursor(db) != null

    /**
     * 앱11 E: once per phone, the next pull asks for every note again (기준 0, the server sends its full copies with the
     * keys). A photo list the 앱10 phone emptied kept the same stamps, so the compare sync cannot see it; the full copy
     * fills it in again ([mergeMemoNote]). Returns true when it moved 기준 back now.
     */
    fun repairPhotosOnce(db: MemoDb): Boolean {
        if (db.state(STATE_PHOTO_REPAIR) != null) return false
        db.setState(STATE_PHOTO_REPAIR, "1")
        db.setState(STATE_BASE, "0")
        db.setState(STATE_CURSOR, "")
        return true
    }

    // ---- sync_pull

    /**
     * The `sync_pull` arguments. The compare summary is left out while a paged answer is still running (the server
     * only needs it once per round) and when the phone has no notes at all (the first full download).
     */
    fun pullArgs(db: MemoDb, device: String, withSegments: Boolean = true): JSONObject {
        val args = JSONObject().put("기준", base(db)).put("기기", device)
        cursor(db)?.let { args.put("cursor", it) }
        if (withSegments && cursor(db) == null) {
            val summary = segmentSummaryJson(db)
            if (summary.length() > 0) args.put("구간요약", summary)
        }
        return args
    }

    /** `구간요약` of 이벤트규약 v2 §2: per segment the hash of its `<id>:<rev요약>` lines and how many there are. */
    fun segmentSummaryJson(db: MemoDb, segments: Int = MEMO_SEGMENTS): JSONArray {
        val out = JSONArray()
        db.segmentSummary(segments).toSortedMap().forEach { (segment, value) ->
            out.put(JSONObject().put("구간", segment.toString()).put("해시", value.first).put("수", value.second))
        }
        return out
    }

    /**
     * Applies one `app.june.memo.sync` answer: stores the notes, folders and tags, moves 기준/cursor on and works out
     * what the mismatched segments ask for.
     */
    fun applySync(db: MemoDb, content: JSONObject, nowMs: Long): MemoSyncResult {
        val started = nowMs
        var merged = 0
        val notes = content.optJSONArray("노트") ?: JSONArray()
        db.transaction {
            for (i in 0 until notes.length()) {
                val change = notes.optJSONObject(i) ?: continue
                if (applyChange(db, change)) merged++
            }
            content.optJSONArray("폴더")?.let { applyFolders(db, it) }
            content.optJSONArray("태그")?.let { applyTags(db, it) }
        }
        val next = content.optString("다음cursor").takeIf { it.isNotEmpty() && it != "null" }
        db.setState(STATE_CURSOR, next.orEmpty())
        // 기준 only moves on the last page: a round that breaks off starts again at the same point (규약 §3).
        if (next == null) db.setState(STATE_BASE, content.optLong("기준", base(db)).toString())
        if (content.optBoolean("되감김")) {
            // The server lost its log or was rebuilt: drop 기준 and let the compare sync fix the difference.
            // Notes are never deleted because of this (규약 §3 되감김).
            db.setState(STATE_BASE, "0")
        }
        content.optString("서버시각").takeIf { it.isNotEmpty() }?.let { memoParseIso(it) }?.let {
            db.setState(STATE_SKEW, (it - nowMs).toString())
        }
        val mismatch = mismatchedIds(db, content.optJSONArray("어긋난구간"))
        db.log(nowMs, "sync_pull", merged, nowMs - started, if (next != null) "더있음" else "끝")
        return MemoSyncResult(
            applied = merged,
            morePages = next != null,
            rewound = content.optBoolean("되감김"),
            wantedIds = mismatch.wanted,
            pushIds = mismatch.push,
            truncatedIds = truncatedIds(notes),
        )
    }

    /** The ids the server sent cut short (`잘림: true`): the app fills them in one by one with `op:get` (규약 §3). */
    private fun truncatedIds(notes: JSONArray): List<String> = (0 until notes.length())
        .mapNotNull { notes.optJSONObject(it) }
        .filter { it.optBoolean("잘림") }
        .mapNotNull { it.optString("id").takeIf { id -> id.isNotEmpty() } }

    /**
     * Stores one `노트변경`. Returns true when the local database changed.
     *
     * The merge is [mergeMemoNote] — the same rule the server runs, so both sides end up with the same note. A note
     * that is already what the server sent is not written again (no feedback loop, 규약 §3 `같음`).
     */
    fun applyChange(db: MemoDb, change: JSONObject, withoutBody: Boolean = false): Boolean {
        val sent = memoChangeFromJson(change) ?: return false
        // 앱10: the number and the user time are kept apart from the note row (no dirty flag, no upload).
        val metaChanged = memoApplyNoteMeta(db, change)
        // A header stored by 앱9 may still hold the server number: compared without it, like the one that comes in.
        val mine = db.note(sent.id)?.let { it.copy(headerJson = memoHeaderJsonWithoutMeta(it.headerJson)) }
        if (mine == null) {
            if (withoutBody) return metaChanged
            db.writeNote(sent.copy(dirty = false))
            return true
        }
        // 진단앱수신: the note of a `병합` ack has no body (서버 `note_to_change(body=False)`): its empty 본문 is not the
        // server text, so our body bundle stays as it is. When the server one is newer, [needsBody] fetches it.
        val incoming = if (withoutBody && sent.body.isEmpty()) sent.withBodyBundleOf(mine) else sent
        val merged = mergeMemoNote(mine, incoming)
        // Dirty means exactly "we hold something the server does not": the merged note differs from what it sent
        // (a bundle of ours won). When they are the same, the upload can be skipped (규약 §3 `같음`, no feedback loop).
        val dirty = !memoSameNote(merged, incoming)
        val changedHere = !memoSameNote(merged, mine)
        db.writeNote(merged.copy(dirty = dirty, serverRev = incoming.contentHash))
        return changedHere || metaChanged
    }

    /** True when the body-less note of an ack carries a newer body than ours: the full note is fetched with `op:get`. */
    fun needsBody(db: MemoDb, change: JSONObject): Boolean {
        val sent = memoChangeFromJson(change) ?: return false
        if (sent.deleted == MEMO_DELETED_TOMBSTONE || sent.body.isNotEmpty()) return false
        val mine = db.note(sent.id) ?: return true
        return sent.revs.body > mine.revs.body
    }

    /** [this] with the body bundle (text, title, kind, link, header and its stamp) of [mine]. */
    private fun MemoNoteRecord.withBodyBundleOf(mine: MemoNoteRecord): MemoNoteRecord = copy(
        title = mine.title,
        body = mine.body,
        kind = mine.kind,
        linkJson = mine.linkJson,
        headerJson = mine.headerJson,
        originEvent = mine.originEvent,
        scheduleState = mine.scheduleState,
        revs = revs.with(MemoBundle.Body, mine.revs.body),
    )

    /**
     * 앱14 A·C: the server's folder list. Its empty icons never wipe what the phone holds (the built-in icons put in
     * with the database, an icon picked here); an icon picked here and not taken by the server yet stays with its stamp
     * ([memoMergedFolderIcon]).
     */
    private fun applyFolders(db: MemoDb, folders: JSONArray) {
        val picked = db.pickedFolderIcons()
        val stored = db.folders().associate { it.path to it.icon }
        for (i in 0 until folders.length()) {
            val f = folders.optJSONObject(i) ?: continue
            val path = f.optString("경로").takeIf { it.isNotEmpty() } ?: continue
            val icon = memoMergedFolderIcon(f.optString("아이콘"), picked[path], stored[path])
            val row = MemoFolderRow(path, icon, f.optString("출처").ifEmpty { "서버" }, i, f.optInt("삭제") == 1)
            if (picked[path] != null) db.moveFolderRow(row) else db.putFolder(row, MemoRev.ZERO, dirty = false)
        }
    }

    private fun applyTags(db: MemoDb, tags: JSONArray) {
        for (i in 0 until tags.length()) {
            val t = tags.optJSONObject(i) ?: continue
            val name = t.optString("이름").takeIf { it.isNotEmpty() } ?: continue
            val synonyms = t.optJSONArray("동의어")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
            db.putTag(
                MemoTag(
                    name = name,
                    count = t.optInt("수"),
                    axis = t.optString("축").takeIf { it.isNotEmpty() },
                    source = t.optString("출처").takeIf { it.isNotEmpty() },
                    synonyms = synonyms,
                )
            )
        }
    }

    /**
     * Compares one mismatched segment with what we hold: ids to fetch (the server is newer or we do not have it) and
     * ids to upload (we are newer or the server does not have it) — 규약 §3 어긋난구간 ①~④.
     */
    fun mismatchedIds(db: MemoDb, segments: JSONArray?): MemoSegmentDiff {
        if (segments == null || segments.length() == 0) return MemoSegmentDiff(emptyList(), emptyList())
        val wanted = mutableListOf<String>()
        val push = mutableListOf<String>()
        val numbered = (0 until segments.length())
            .mapNotNull { segments.optJSONObject(it) }
            .mapNotNull { item -> item.optString("구간").toIntOrNull()?.let { it to item } }
        for ((number, item) in numbered) {
            val rows = item.optJSONArray("서버") ?: JSONArray()
            val serverRows = (0 until rows.length())
                .mapNotNull { rows.optJSONObject(it) }
                .mapNotNull { row ->
                    val id = row.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    id to MemoRev(
                        memoParseIso(row.optString("수정")) ?: 0,
                        row.optString("기기").ifEmpty { MEMO_DEVICE_SERVER },
                        row.optLong("순번"),
                    )
                }
            val serverIds = serverRows.mapTo(mutableSetOf()) { it.first }
            serverRows.forEach { (id, serverRev) ->
                val mine = db.note(id)
                when {
                    // ① the server is newer, or ④ we do not have it at all: download
                    mine == null || mine.modified < serverRev -> wanted += id
                    // ② we are newer: upload
                    serverRev < mine.modified -> push += id
                }
            }
            // ③ the server does not have it: upload (a note of this segment that the server did not list)
            db.segmentLines(number).mapNotNull { it.substringBefore(':').takeIf { id -> id.isNotEmpty() } }
                .filterNot { it in serverIds }
                .forEach { push += it }
        }
        return MemoSegmentDiff(wanted.distinct(), push.distinct())
    }

    // ---- sync_push

    /** The waiting uploads that may go now, oldest first, at most [PUSH_BATCH]. */
    fun dueUploads(db: MemoDb, nowMs: Long): List<MemoOutboxRow> =
        db.outboxDue(nowMs).filter { it.kind == MEMO_OUTBOX_NOTE }.take(PUSH_BATCH)

    /** The `sync_push` arguments of [rows]. Returns null when there is nothing to send (no empty request is made). */
    fun pushArgs(rows: List<MemoOutboxRow>, device: String, proposals: JSONArray? = null): JSONObject? {
        if (rows.isEmpty() && (proposals == null || proposals.length() == 0)) return null
        val changes = JSONArray()
        rows.forEach { row -> tryOrNull { JSONObject(row.payload) }?.let { changes.put(it) } }
        val args = JSONObject().put("변경", changes).put("기기", device)
        if (proposals != null && proposals.length() > 0) args.put("제안응답", proposals)
        return args
    }

    /**
     * Queues [note] for the next upload (one row per note: a newer change replaces the waiting one, 설계 §k-4 ②). 앱10:
     * the time of the user's last change goes with it (`사용자수정`, 규약 추가 결정 2); the number never goes up.
     */
    fun enqueueNote(db: MemoDb, note: MemoNoteRecord, nowMs: Long) {
        val change = note.toChangeJson()
        memoNoteMeta(db, note.id).userModifiedMs.takeIf { it > 0 }?.let { change.put(MEMO_FIELD_USER_MODIFIED, memoIso(it)) }
        db.enqueue(MEMO_OUTBOX_NOTE, note.id, change.toString(), nowMs)
    }

    /**
     * Applies one `app.june.memo.sync_ack`: removes what went through, holds back what failed, stores the merged notes
     * the server sent back and keeps the clock offset and the server sequence.
     */
    fun applyAck(db: MemoDb, content: JSONObject, sent: List<MemoOutboxRow>, nowMs: Long): MemoAckResult {
        val started = nowMs
        val bySeq = sent.associateBy { it.target }
        val results = content.optJSONArray("결과") ?: JSONArray()
        var accepted = 0
        var rejected = 0
        var mergedBack = 0
        val wanted = mutableListOf<String>()
        db.transaction {
            for (i in 0 until results.length()) {
                val row = results.optJSONObject(i) ?: continue
                val id = row.optString("id")
                val outbox = bySeq[id]
                when (row.optString("상태")) {
                    "수락", "같음" -> {
                        accepted++
                        outbox?.let { db.dropOutbox(it.seq) }
                        db.note(id)?.let { db.writeNote(it.copy(dirty = false, serverRev = it.contentHash)) }
                        // 앱10: a new note gets its number in this answer (규약-번호사진 1).
                        row.optJSONObject("노트")?.let { memoApplyNoteMeta(db, it) }
                    }
                    "병합" -> {
                        mergedBack++
                        outbox?.let { db.dropOutbox(it.seq) }
                        row.optJSONObject("노트")?.let { note ->
                            if (needsBody(db, note)) wanted += id
                            applyChange(db, note, withoutBody = true)
                        }
                    }
                    else -> {
                        rejected++
                        // A rejected note is not retried in a loop: it waits like a failed upload and the reason is kept.
                        outbox?.let { db.failOutbox(it.seq, nextTryMs(it.tries + 1, nowMs), row.optString("사유").take(MEMO_ERROR_CHARS)) }
                    }
                }
            }
        }
        content.optString("서버시각").takeIf { it.isNotEmpty() }?.let { memoParseIso(it) }?.let {
            db.setState(STATE_SKEW, (it - nowMs).toString())
        }
        val serverSeq = content.optLong("서버순번", -1)
        if (serverSeq >= 0) db.setState(STATE_SERVER_SEQ, serverSeq.toString())
        db.log(nowMs, "sync_push", accepted + mergedBack + rejected, nowMs - started, "수락 $accepted 병합 $mergedBack 거절 $rejected")
        return MemoAckResult(accepted, mergedBack, rejected, pullAgain = serverSeq > base(db), wantedIds = wanted)
    }

    /** The whole `sync_push` failed (server off, no network): every row waits longer (설계 §k-4 ③, 화면이 보일 때만 다시 시도). */
    fun failUploads(db: MemoDb, rows: List<MemoOutboxRow>, nowMs: Long, error: String?) {
        db.transaction { rows.forEach { db.failOutbox(it.seq, nextTryMs(it.tries + 1, nowMs), error?.take(MEMO_ERROR_CHARS)) } }
        db.log(nowMs, "sync_push", rows.size, null, "실패")
    }

    /** When a row that failed [tries] times may be tried again. */
    fun nextTryMs(tries: Int, nowMs: Long): Long = nowMs + BACKOFF_MS[minOf(tries, BACKOFF_MS.size) - 1]
}

private const val MEMO_ERROR_CHARS = 200

/** What one applied `app.june.memo.sync` asks the caller to do next. */
internal data class MemoSyncResult(
    val applied: Int,
    val morePages: Boolean,
    val rewound: Boolean,
    val wantedIds: List<String> = emptyList(),
    val pushIds: List<String> = emptyList(),
    val truncatedIds: List<String> = emptyList(),
) {
    /** True when another `sync_pull` has to follow right away (a page was left, or a segment differs). */
    val needsAnotherRound: Boolean get() = morePages || wantedIds.isNotEmpty() || truncatedIds.isNotEmpty()
}

/** The ids one mismatched segment asks for (받을 것) and offers (보낼 것). */
internal data class MemoSegmentDiff(val wanted: List<String>, val push: List<String>)

/** What one applied `app.june.memo.sync_ack` ended up doing. */
internal data class MemoAckResult(
    val accepted: Int,
    val merged: Int,
    val rejected: Int,
    val pullAgain: Boolean,
    /** The merged notes whose newer server body the ack did not carry: fetched one by one with `op:get`. */
    val wantedIds: List<String> = emptyList(),
)

/**
 * A `노트변경` (이벤트규약 v2 §5) as a local note row. Unknown or missing fields fall back the way the server does:
 * a note without `rev` uses its `수정`/`기기`/`순번` for all four bundles (§4).
 */
internal fun memoChangeFromJson(o: JSONObject): MemoNoteRecord? {
    val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
    val modifiedMs = memoParseIso(o.optString("수정")) ?: 0
    val fallback = MemoRev(modifiedMs, o.optString("기기").ifEmpty { MEMO_DEVICE_SERVER }, o.optLong("순번"))
    val revs = o.optJSONObject("rev")?.let { rev ->
        fun one(b: MemoBundle): MemoRev {
            val a = rev.optJSONArray(b.key) ?: return fallback
            val ms = memoParseIso(a.optString(0)) ?: return fallback
            return MemoRev(ms, a.optString(1).ifEmpty { MEMO_DEVICE_SERVER }, a.optLong(2))
        }
        MemoRevs(one(MemoBundle.Body), one(MemoBundle.Class), one(MemoBundle.Mark), one(MemoBundle.Attach))
    } ?: MemoRevs.all(fallback)
    val tags = o.optJSONArray("태그")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
    val files = o.optJSONArray("첨부")
    val attachments = buildList {
        for (i in 0 until (files?.length() ?: 0)) {
            // 앱11 E: the server writes an item without its key as the plain mxc (every `sync_push` answer). It was
            // skipped here, so the list came in empty and the photo went off the phone.
            val f = files?.optJSONObject(i) ?: files?.optString(i)?.takeIf { it.startsWith("mxc://") }?.let { JSONObject().put("mxc", it) } ?: continue
            val mxc = f.optString("mxc").takeIf { it.isNotEmpty() } ?: continue
            add(
                MemoLocalAttachment(
                    id = mxc,
                    mxc = mxc,
                    fileJson = f.optJSONObject("file")?.toString(),
                    mime = f.optString("mimetype").takeIf { it.isNotEmpty() },
                    state = MEMO_ATTACH_DONE,
                )
            )
        }
    }
    // The server file always ends with a newline; the phone keeps bodies without it, so the same note stays the same.
    val body = memoNormalizedBody(o.optString("본문"))
    val deleted = o.optInt("삭제")
    // The server puts everything outside the known fields, 내용해시 included, under 머리말 (규약 v2 §5).
    val header = o.optJSONObject("머리말")
    val serverHash = (header?.optString("내용해시").takeIf { !it.isNullOrEmpty() } ?: o.optString("내용해시")).takeIf { it.isNotEmpty() }
    return MemoNoteRecord(
        id = id,
        folder = o.optString("폴더").ifEmpty { MEMO_INBOX },
        title = o.optString("제목"),
        body = body,
        kind = o.optString("종류").ifEmpty { memoKindOf(body, attachments) },
        createdMs = memoParseIso(o.optString("생성")) ?: modifiedMs,
        revs = revs,
        deleted = deleted,
        pinned = o.optBoolean("고정"),
        pinSuggested = o.optBoolean("고정제안"),
        pinDismissed = o.optBoolean("고정제안_무시"),
        tags = tags,
        attachments = attachments,
        linkJson = o.optJSONObject("링크")?.toString(),
        headerJson = memoHeaderWithoutMeta(header)?.toString(),
        serverRev = serverHash,
    )
}

/**
 * Epoch millis of a protocol time (`2026-10-11T14:30:12+09:00`, 이벤트규약 v2 §0). The offset is read when it is
 * there, so a server that answers in UTC (`...Z`) is understood too. Returns null when the text is not a time.
 */
internal fun memoParseIso(text: String?): Long? {
    val s = text?.trim().orEmpty()
    if (s.length < 19) return null
    val datePartLooksRight = s[4] == '-' && s[7] == '-'
    val timeStartsHere = s[10] == 'T' || s[10] == ' '
    if (!datePartLooksRight || !timeStartsHere) return null
    return tryOrNull {
        val year = s.substring(0, 4).toInt()
        val month = s.substring(5, 7).toInt()
        val day = s.substring(8, 10).toInt()
        val hour = s.substring(11, 13).toInt()
        val minute = s.substring(14, 16).toInt()
        val second = s.substring(17, 19).toInt()
        // days since 1970-01-01 (H. Hinnant), the inverse of memoIso
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y.toLong(), 400L)
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097 + doe - 719_468
        val utcMs = (days * 86_400L + hour * 3_600L + minute * 60L + second) * 1_000L
        utcMs - offsetMs(s)
    }
}

/** The zone offset in millis of an ISO time, `+09:00` when it has none (the protocol default is KST). */
private fun offsetMs(s: String): Long {
    val zone = s.substring(19)
    if (zone.startsWith("Z") || zone.startsWith("z")) return 0
    val sign = zone.indexOfFirst { it == '+' || it == '-' }
    if (sign < 0) return 9 * 3_600_000L
    val rest = zone.substring(sign + 1).replace(":", "")
    val hours = rest.take(2).toIntOrNull() ?: return 9 * 3_600_000L
    val minutes = rest.drop(2).take(2).toIntOrNull() ?: 0
    val value = (hours * 3_600L + minutes * 60L) * 1_000L
    return if (zone[sign] == '-') -value else value
}
