/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.core.data.tryOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Element June memo bot v2: the note as the phone keeps it (the phone is the original, 설계 §a), and the merge rule
 * shared with the server (설계 §c, 이벤트규약 v2 §5).
 */

/** `삭제` values (이벤트규약 v2 §4): 0 none, 1 trash (a normal change), 2 tombstone (content removed, row kept). */
internal const val MEMO_DELETED_NONE = 0
internal const val MEMO_DELETED_TRASH = 1
internal const val MEMO_DELETED_TOMBSTONE = 2

/** The four bundles that merge separately (이벤트규약 v2 §5). */
internal enum class MemoBundle(val key: String) {
    Body("본문"),
    Class("분류"),
    Mark("표식"),
    Attach("첨부"),
}

/**
 * One change stamp `[time, device, seq]`. Compared like the server (`rev_key`): whole seconds first (the protocol
 * carries ISO times with seconds), then the per device sequence, then the device name. Both sides pick the same winner.
 */
internal data class MemoRev(val ms: Long, val device: String, val seq: Long) : Comparable<MemoRev> {
    override fun compareTo(other: MemoRev): Int =
        compareValuesBy(this, other, { Math.floorDiv(it.ms, MILLIS_PER_SECOND) }, { it.seq }, { it.device })

    fun toJson(): JSONArray = JSONArray().put(ms).put(device).put(seq)

    companion object {
        val ZERO = MemoRev(0, "", 0)

        fun parse(a: JSONArray?): MemoRev? {
            if (a == null || a.length() < 1) return null
            return MemoRev(a.optLong(0), a.optString(1), a.optLong(2))
        }
    }
}

private const val MILLIS_PER_SECOND = 1000L

/** The stamps of the four bundles. The note time is the largest one. */
internal data class MemoRevs(val body: MemoRev, val classes: MemoRev, val mark: MemoRev, val attach: MemoRev) {
    operator fun get(b: MemoBundle): MemoRev = when (b) {
        MemoBundle.Body -> body
        MemoBundle.Class -> classes
        MemoBundle.Mark -> mark
        MemoBundle.Attach -> attach
    }

    fun with(b: MemoBundle, rev: MemoRev): MemoRevs = when (b) {
        MemoBundle.Body -> copy(body = rev)
        MemoBundle.Class -> copy(classes = rev)
        MemoBundle.Mark -> copy(mark = rev)
        MemoBundle.Attach -> copy(attach = rev)
    }

    val max: MemoRev get() = MemoBundle.entries.map(::get).max()

    fun toJson(): String = JSONObject().apply { MemoBundle.entries.forEach { put(it.key, get(it).toJson()) } }.toString()

    companion object {
        fun all(rev: MemoRev) = MemoRevs(rev, rev, rev, rev)

        /** Missing bundles take [fallback] (an old note without `rev`, 이벤트규약 v2 §4). */
        fun parse(json: String?, fallback: MemoRev): MemoRevs {
            val o = json?.let { tryOrNull { JSONObject(it) } } ?: return all(fallback)
            fun one(b: MemoBundle) = MemoRev.parse(o.optJSONArray(b.key)) ?: fallback
            return MemoRevs(one(MemoBundle.Body), one(MemoBundle.Class), one(MemoBundle.Mark), one(MemoBundle.Attach))
        }
    }
}

/** An attachment of a note. Before it is uploaded only [localPath] is known (state 로컬만). */
internal data class MemoLocalAttachment(
    val id: String,
    val mxc: String? = null,
    val localPath: String? = null,
    val fileJson: String? = null,
    val mime: String? = null,
    val bytes: Long? = null,
    val state: String = MEMO_ATTACH_LOCAL,
    /** Failed upload tries in a row (A5): at [MEMO_ATTACH_MAX_TRIES] the attachment becomes [MEMO_ATTACH_FAILED]. */
    val tries: Int = 0,
    /** When a failed upload may be tried again (column `retry_at_ms`), 0 = now. The screen retry waits for it too. */
    val retryAtMs: Long = 0,
    /** Why the last upload failed (column `err`), shown on the "못 올렸습니다" line. Never the file content. */
    val error: String? = null,
) {
    /** Union key of the `첨부` bundle: the mxc once uploaded (이벤트규약 v2 §5), the local file before. */
    val key: String get() = mxc ?: localPath ?: id
}

internal const val MEMO_ATTACH_LOCAL = "로컬만"
internal const val MEMO_ATTACH_UPLOADING = "올리는중"
internal const val MEMO_ATTACH_DONE = "올림"
internal const val MEMO_ATTACH_FAILED = "실패"

/** Upload tries in a row before an attachment is marked [MEMO_ATTACH_FAILED] (설계 §e). */
internal const val MEMO_ATTACH_MAX_TRIES = 10

/**
 * The upload size limit used when the server does not tell its `m.upload.size` (결정기록 앱5): 50 MiB, the Synapse
 * default. A bigger file is not read at all and stays [MEMO_ATTACH_FAILED] with the reason (관리자 결정 1-2 (나)).
 */
internal const val MEMO_ATTACH_DEFAULT_MAX_BYTES = 50L shl 20

// The reasons of a 실패 attachment, shown after "첨부 N개를 못 올렸습니다" (no file name, no content).
internal const val MEMO_ATTACH_ERR_MISSING = "원본 파일이 없습니다"
internal const val MEMO_ATTACH_ERR_READ = "원본 파일을 읽지 못했습니다"
internal const val MEMO_ATTACH_ERR_MEMORY = "파일이 너무 커서 처리하지 못했습니다"
internal const val MEMO_ATTACH_ERR_UPLOAD = "${MEMO_ATTACH_MAX_TRIES}번 올리지 못했습니다"

internal fun memoAttachTooBig(size: Long, limit: Long) = "파일이 너무 큽니다 (${memoSizeText(size)} · 상한 ${memoSizeText(limit)})"

/** The "못 올렸습니다" line of the memo screen, with the reason of one of them when it is known. */
internal fun memoFailedAttachmentsText(count: Int, reason: String?): String =
    "첨부 ${count}개를 못 올렸습니다" + (reason?.let { " · $it" } ?: "")

/** Free space under which the memo screen warns (설계 §e: 1 GB or 5 % of the total, whichever is smaller). */
internal fun memoLowStorage(availableBytes: Long, totalBytes: Long): Boolean =
    availableBytes <= minOf(1L shl 30, totalBytes / 20)

/** "1.2GB" / "350MB" for the clean button. */
internal fun memoSizeText(bytes: Long): String = if (bytes >= 1L shl 30) {
    String.format(java.util.Locale.ROOT, "%.1fGB", bytes / (1L shl 30).toDouble())
} else {
    "${bytes / (1L shl 20)}MB"
}

/** A note row of the local database with its tags and attachments. */
internal data class MemoNoteRecord(
    val id: String,
    val folder: String,
    val title: String,
    val body: String,
    val kind: String,
    val createdMs: Long,
    val revs: MemoRevs,
    val deleted: Int = MEMO_DELETED_NONE,
    val pinned: Boolean = false,
    val pinSuggested: Boolean = false,
    val pinDismissed: Boolean = false,
    val tags: List<String> = emptyList(),
    val attachments: List<MemoLocalAttachment> = emptyList(),
    val linkJson: String? = null,
    val headerJson: String? = null,
    val originEvent: String? = null,
    val scheduleState: String? = null,
    val serverRev: String? = null,
    val dirty: Boolean = false,
) {
    val modified: MemoRev get() = revs.max
    val bodyPlain: String get() = memoPlainText(body)

    /**
     * The server `내용해시` of this note: the stored body (one trailing newline, [memoStoredBody]) + the uploaded mxc
     * list. The server computes it the same way on every path (이벤트규약 §5 v2.1, 관리자 결정 2026-10-13), so the
     * value is equal on both sides. It is only a hint for "same": which side is newer is decided by the bundle revs.
     */
    val contentHash: String get() = memoContentHash(memoStoredBody(body), attachments.mapNotNull { it.mxc })

    /** A tombstone keeps only the id and the stamps (설계 §c-3). */
    fun tombstone(rev: MemoRev): MemoNoteRecord = copy(
        title = "",
        body = "",
        tags = emptyList(),
        attachments = emptyList(),
        linkJson = null,
        deleted = MEMO_DELETED_TOMBSTONE,
        pinned = false,
        pinSuggested = false,
        revs = MemoRevs.all(rev),
    )
}

/**
 * The merge rule, in one place (관리자 확정 Q4: four bundles, the latest of each wins). To go back to a whole note
 * LWW, only this function changes. The result does not depend on the order of [a] and [b].
 *
 * - each bundle: the larger [MemoRev] wins; equal stamps with different content (only reachable with corrupt data)
 *   keep the larger content text, so the answer stays the same whatever the order
 * - attachments: union by [MemoLocalAttachment.key], the winner's order first
 * - tombstone vs a live note: the later of the tombstone and the latest change of the other side wins the whole note
 *   (관리자 결정 3: delete vs edit is LWW, a later edit brings the note back)
 * - the fields outside the four bundles (kind, server header, origin event, schedule state) follow the body bundle
 *
 * [MemoNoteRecord.dirty] and [MemoNoteRecord.serverRev] are local fields: the caller sets them.
 */
internal fun mergeMemoNote(a: MemoNoteRecord, b: MemoNoteRecord): MemoNoteRecord {
    val aTomb = a.deleted == MEMO_DELETED_TOMBSTONE
    val bTomb = b.deleted == MEMO_DELETED_TOMBSTONE
    if (aTomb && bTomb) {
        val c = compareValuesBy(a, b, { it.revs.mark }, { it.revs.toJson() })
        return if (c >= 0) a else b
    }
    if (aTomb || bTomb) {
        val (tomb, other) = if (aTomb) a to b else b to a
        return if (other.modified > tomb.revs.mark) other else tomb
    }
    fun aWins(bundle: MemoBundle): Boolean {
        // Same stamp for the protocol (same second, sequence and device): the content, then the local milliseconds
        // decide, so the result never depends on the order of a and b.
        val c = compareValuesBy(a, b, { it.revs[bundle] }, { it.bundleText(bundle) }, { it.revs[bundle].ms })
        return c >= 0
    }
    val body = if (aWins(MemoBundle.Body)) a else b
    val classes = if (aWins(MemoBundle.Class)) a else b
    val mark = if (aWins(MemoBundle.Mark)) a else b
    val attach = if (aWins(MemoBundle.Attach)) a else b
    val loser = if (attach === a) b else a
    val keys = attach.attachments.map { it.key }.toSet()
    // The server never knows the phone original: an attachment won by the server side keeps the phone path, type and
    // size of the same attachment (앱6 원인 B: the photo was drawn from the server copy only, or lost its original).
    val phone = loser.attachments.filter { it.localPath != null }.associateBy { it.key }
    val won = attach.attachments.map { w ->
        val p = phone[w.key]
        if (w.localPath != null || p == null) w else w.copy(localPath = p.localPath, mime = w.mime ?: p.mime, bytes = w.bytes ?: p.bytes)
    }
    return body.copy(
        folder = classes.folder,
        tags = classes.tags,
        pinned = mark.pinned,
        pinSuggested = mark.pinSuggested,
        pinDismissed = mark.pinDismissed,
        deleted = mark.deleted,
        attachments = won + loser.attachments.filter { it.key !in keys },
        createdMs = minOf(a.createdMs, b.createdMs),
        revs = MemoRevs(body.revs.body, classes.revs.classes, mark.revs.mark, attach.revs.attach),
    )
}

/** The content of one bundle as text, the last tie break of [mergeMemoNote]. */
private fun MemoNoteRecord.bundleText(bundle: MemoBundle): String = when (bundle) {
    MemoBundle.Body -> listOf(title, body, linkJson, kind, headerJson, originEvent, scheduleState).joinToString("\u0000") { it.orEmpty() }
    MemoBundle.Class -> (listOf(folder) + tags).joinToString("\u0000")
    MemoBundle.Mark -> "$deleted|$pinned|$pinSuggested|$pinDismissed"
    MemoBundle.Attach -> attachments.joinToString("\u0000") { "${it.key}|${it.state}|${it.fileJson.orEmpty()}" }
}

/** True when [a] and [b] hold the same note content and stamps (nothing to store, nothing to send). */
internal fun memoSameNote(a: MemoNoteRecord, b: MemoNoteRecord): Boolean =
    a.copy(dirty = false, serverRev = null, attachments = emptyList(), createdMs = 0) ==
        b.copy(dirty = false, serverRev = null, attachments = emptyList(), createdMs = 0) &&
        a.attachments.map { it.key } == b.attachments.map { it.key }

private fun sha1Hex(s: String): String = hex(MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)))

private const val HEX = "0123456789abcdef"

private fun hex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    bytes.forEach {
        val v = it.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return out.toString()
}

/**
 * A note body in the one form the phone keeps: without trailing newlines. Trailing newlines carry no meaning
 * (이벤트규약 §5 v2.1); the server stores the body with exactly one ([memoStoredBody]). Everything the sync stores goes
 * through this, so a note that only differs by trailing newlines is the same note and is never uploaded again (A4 ⑤).
 */
internal fun memoNormalizedBody(body: String): String = body.trimEnd('\n')

/** The body as the server stores it and hashes it: trailing newlines folded into exactly one (`stored_body`). */
internal fun memoStoredBody(body: String): String = memoNormalizedBody(body) + "\n"

/** The server `content_hash`: sha1 of the body, then for each mxc a NUL byte and the mxc. */
internal fun memoContentHash(body: String, mxcs: List<String>): String {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(body.toByteArray(Charsets.UTF_8))
    mxcs.forEach {
        md.update(0.toByte())
        md.update(it.toByteArray(Charsets.UTF_8))
    }
    return hex(md.digest())
}

private val HEADING = Regex("^#{1,6}\\s+")
private val BULLET = Regex("^([-*+>]|\\d+\\.)\\s+")
private val LINK = Regex("!?\\[([^\\]]*)]\\(([^)]*)\\)")
private val EMPHASIS = Regex("[*`~]+")

/** Search text of a markdown body: heading and list marks, emphasis and link syntax removed (설계 §a, LIKE search). */
internal fun memoPlainText(markdown: String): String = markdown.lineSequence()
    .map { it.trim().replace(HEADING, "").replace(BULLET, "") }
    .joinToString("\n")
    .replace(LINK, "$1 $2")
    .replace(EMPHASIS, "")
    .trim()

/** Segment of a note id for the compare sync (이벤트규약 v2 §2): first sha1 byte modulo [segments]. */
internal fun memoSegment(noteId: String, segments: Int = MEMO_SEGMENTS): Int =
    Integer.parseInt(sha1Hex(noteId).substring(0, 2), 16) % segments

internal const val MEMO_SEGMENTS = 16

/** One segment summary line `<id>:<수정>|<기기>|<순번>`, the same text the server hashes. */
internal fun memoSegmentLine(id: String, modified: MemoRev): String =
    "$id:${memoIso(modified.ms)}|${modified.device.ifEmpty { MEMO_DEVICE_SERVER }}|${modified.seq}"

/** Segment hash: sha1 of the sorted lines joined by newlines, first 12 hex chars (server `segment_hash`). */
internal fun memoSegmentHash(lines: List<String>): String = sha1Hex(lines.sorted().joinToString("\n")).take(12)

internal const val MEMO_DEVICE_SERVER = "서버"

/** ISO 8601 KST with seconds, the protocol time format (`2026-10-11T14:30:12+09:00`). */
internal fun memoIso(ms: Long): String {
    // Plain arithmetic (no formatter object): the compare sync formats every note of the database.
    val local = Math.floorDiv(ms + KST_OFFSET_MS, MILLIS_PER_SECOND)
    val days = Math.floorDiv(local, 86_400L)
    val secs = local - days * 86_400L
    // civil date from days since 1970-01-01 (H. Hinnant)
    val z = days + 719_468
    val era = Math.floorDiv(z, 146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    fun two(v: Long) = if (v < 10) "0$v" else v.toString()
    return "$y-${two(m)}-${two(d)}T${two(secs / 3600)}:${two(secs / 60 % 60)}:${two(secs % 60)}+09:00"
}

private const val DAY_MS = 86_400_000L
private const val KST_OFFSET_MS = 9 * 3_600_000L

/** Start of the KST day of [ms]. */
internal fun memoKstDayStart(ms: Long): Long = Math.floorDiv(ms + KST_OFFSET_MS, DAY_MS) * DAY_MS - KST_OFFSET_MS

/** `[from, to)` epoch millis of a search period, on the note creation time (이벤트규약 §2 `기간`). */
internal fun memoPeriodRange(p: MemoPeriod, nowMs: Long): Pair<Long, Long> {
    val tomorrow = memoKstDayStart(nowMs) + DAY_MS
    return when (p) {
        MemoPeriod.Today -> tomorrow - DAY_MS to tomorrow
        MemoPeriod.Days7 -> tomorrow - 7 * DAY_MS to tomorrow
        MemoPeriod.Days30 -> tomorrow - 30 * DAY_MS to tomorrow
        is MemoPeriod.Range -> {
            val start = memoParseDate(p.start) ?: Long.MIN_VALUE
            val end = memoParseDate(p.end)?.plus(DAY_MS) ?: Long.MAX_VALUE
            start to end
        }
    }
}

/** `YYYY-MM-DD` (KST) to the epoch millis of its start, null when it is not a date. */
internal fun memoParseDate(date: String): Long? {
    val f = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    f.timeZone = TimeZone.getTimeZone("GMT+09:00")
    f.isLenient = false
    return tryOrNull { f.parse(date)?.time }
}

private const val SNIPPET_SIDE = 30

/**
 * Search snippet like the server (이벤트규약 §3 search): one HTML-escaped line with the first match of [word] inside
 * `<mark>…</mark>`, and the field it was found in. Null without a free text word (tag only search).
 */
internal fun memoSnippet(word: String?, item: MemoItem, plain: String): Pair<String, String>? {
    if (word.isNullOrEmpty()) return null
    val fields = listOf("제목" to item.title, "본문" to plain, "태그" to item.tags.joinToString(" "), "폴더" to item.folder)
    val (field, text) = fields.firstOrNull { it.second.contains(word, ignoreCase = true) } ?: return "" to "본문"
    val line = text.replace('\n', ' ')
    val at = line.indexOf(word, ignoreCase = true)
    val from = (at - SNIPPET_SIDE).coerceAtLeast(0)
    val to = (at + word.length + SNIPPET_SIDE).coerceAtMost(line.length)
    val before = if (from > 0) "… " else ""
    val after = if (to < line.length) " …" else ""
    val snippet = before + htmlEscape(line.substring(from, at)) + "<mark>" + htmlEscape(line.substring(at, at + word.length)) +
        "</mark>" + htmlEscape(line.substring(at + word.length, to)) + after
    return snippet to field
}

private fun htmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** The tile preview of a local note: photo (uploaded or still local), link card or the first characters of the text. */
internal fun memoLocalPreview(kind: String, preview: String, linkJson: String?, attachments: List<MemoLocalAttachment>): MemoPreview? {
    val shown = attachments.mapNotNull { it.toScreen() }
    val first = shown.firstOrNull()
    if (first != null && (kind == "사진" || preview.isEmpty())) {
        return MemoPreview.Photo(first.mxc, attachments.size, first.fileJson, shown.take(MEMO_CARD_PHOTOS))
    }
    linkJson?.let { tryOrNull { JSONObject(it) } }?.let { o ->
        val url = o.optString("url")
        if (url.isNotEmpty()) {
            return MemoPreview.Link(
                url,
                o.optString("제목").ifEmpty { null },
                o.optString("이미지").ifEmpty { null },
                o.optString("사이트").ifEmpty { null },
            )
        }
    }
    return if (preview.isEmpty()) null else MemoPreview.Text(preview)
}

/**
 * What the screen draws for one attachment (앱6): the server copy (mxc + EncryptedFile) when uploaded, else the local
 * file; the phone original is kept beside it so it is drawn first while it is there. Null when there is neither.
 */
internal fun MemoLocalAttachment.toScreen(): MemoAttachment? {
    val source = mxc ?: localPath?.let { MEMO_LOCAL_FILE_PREFIX + it } ?: return null
    return MemoAttachment(source, fileJson, localPath)
}

/** The note in the `노트변경` form (이벤트규약 v2 §5): the outbox payload and, in A4, the `sync_push` item. */
internal fun MemoNoteRecord.toChangeJson(): JSONObject {
    val o = JSONObject()
        .put("id", id)
        .put("폴더", folder)
        .put("제목", title)
        .put("본문", body)
        .put("태그", JSONArray(tags))
        .put("종류", kind)
        .put("생성", memoIso(createdMs))
        .put("수정", memoIso(modified.ms))
        .put("고정", pinned)
        .put("고정제안", pinSuggested)
        .put("고정제안_무시", pinDismissed)
        .put("삭제", deleted)
    val files = JSONArray()
    attachments.filter { it.mxc != null }.forEach { a ->
        val f = JSONObject().put("mxc", a.mxc)
        a.mime?.let { f.put("mimetype", it) }
        a.fileJson?.let { tryOrNull { JSONObject(it) } }?.let { f.put("file", it) }
        files.put(f)
    }
    o.put("첨부", files)
    linkJson?.let { tryOrNull { JSONObject(it) } }?.let { o.put("링크", it) }
    val rev = JSONObject()
    MemoBundle.entries.forEach { b ->
        val r = revs[b]
        rev.put(b.key, JSONArray().put(memoIso(r.ms)).put(r.device.ifEmpty { MEMO_DEVICE_SERVER }).put(r.seq))
    }
    return o.put("rev", rev).put("기기", modified.device.ifEmpty { MEMO_DEVICE_SERVER }).put("순번", modified.seq)
}

/** Title of a new note: the first non empty line of its plain text, cut to [MEMO_TITLE_CHARS]. */
internal fun memoTitleOf(text: String): String =
    memoPlainText(text).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(MEMO_TITLE_CHARS).orEmpty()

private val SINGLE_URL = Regex("^https?://\\S+$")

/** Kind of a new note from what was typed (이벤트규약 §3 `종류`): photo, link (only a URL), otherwise text. */
internal fun memoKindOf(text: String, attachments: List<MemoLocalAttachment>): String = when {
    attachments.isNotEmpty() -> "사진"
    SINGLE_URL.matches(text.trim()) -> "링크"
    else -> "텍스트"
}

/** A photo kept on the phone, not uploaded yet: the preview source is this prefix + the local path. */
internal const val MEMO_LOCAL_FILE_PREFIX = "file://"

/** List key of the record tab (A3): the notes of every folder but the trash, newest first. */
internal const val MEMO_ALL_FOLDERS = ""

private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

/** A note id made on the phone, same format as the server (`YYYYMMDD-HHMMSS-xxxx`, 이벤트규약 v2 §0). */
internal fun memoNewId(ms: Long, random: (Int) -> Int = { java.security.SecureRandom().nextInt(it) }): String {
    val iso = memoIso(ms)
    val stamp = iso.substring(0, 10).replace("-", "") + "-" + iso.substring(11, 19).replace(":", "")
    return stamp + "-" + (1..4).map { ID_CHARS[random(ID_CHARS.length)] }.joinToString("")
}
