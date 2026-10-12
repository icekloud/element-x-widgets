/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.core.data.tryOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the sync needs from the memo bot room. The room backend implements it; the tests use a fake. */
internal interface MemoSyncTransport {
    /** Sends [op] and returns its answer. Throws when it cannot be sent or no answer comes in time. */
    suspend fun call(op: MemoOp): MemoResponse

    /** The `제안[]` items of the `app.june.memo.index` state (split keys included), null on a transient error. */
    suspend fun fetchProposals(): List<JSONObject>?

    /** Uploads already encrypted bytes to the media repository and returns the `mxc://` uri. */
    suspend fun upload(bytes: ByteArray): String
}

/**
 * Runs the A4 sync over a [MemoDb] (설계 §k-4): one `sync_pull` round when the memo screen is shown, one `sync_push`
 * when the input stopped for a while, and the attachment uploads (A5, 설계 §e). It never polls: every call is made
 * by the screen (or the one-time upload job) and ends when there is nothing more to do right now.
 *
 * Failures never reach the screens: the notes are local, so a server that is off only means "later" (no "처리 중").
 */
internal class MemoSyncRunner(
    private val db: MemoDb,
    private val transport: MemoSyncTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readFile: (String) -> ByteArray? = { path -> File(path).takeIf { it.isFile }?.readBytes() },
    private val encrypt: (ByteArray) -> MemoEncryptedFile = ::memoEncryptAttachment,
) {
    private val mutex = Mutex()

    private fun device(): String = memoDeviceOf(db)

    /** True when something waits for the server: notes, suggestion answers or attachments (the one-time job test). */
    fun hasPendingUploads(): Boolean =
        db.outbox().any { it.kind == MEMO_OUTBOX_NOTE || it.kind == MEMO_OUTBOX_PROPOSAL } || db.pendingAttachments().isNotEmpty()

    /** Millis until the earliest waiting upload may be tried again, null when nothing waits. */
    fun nextRetryInMs(): Long? {
        val rows = db.outbox().filter { it.kind == MEMO_OUTBOX_NOTE || it.kind == MEMO_OUTBOX_PROPOSAL }
        if (rows.isEmpty()) return null
        return (rows.minOf { it.nextTryMs } - clock()).coerceAtLeast(0)
    }

    /** Screen shown: pull what changed, then send what waits. Returns false when the server could not be reached. */
    suspend fun syncNow(): Boolean = mutex.withLock {
        val pulled = pullLocked()
        val pushed = if (pulled) uploadLocked(force = false) else false
        pulled && pushed
    }

    /** Input stopped (2.5 s) or a retry is due: attachments then `sync_push`. [force] ignores the back-off (job run). */
    suspend fun push(force: Boolean = false): Boolean = mutex.withLock { uploadLocked(force) }

    // ---------------------------------------------------------------- pull

    private suspend fun pullLocked(): Boolean {
        var withSegments = true
        repeat(MAX_PULL_ROUNDS) {
            val content = when (val r = callOrNull(MemoOp("sync_pull", MemoSync.pullArgs(db, device(), withSegments).toArgs()))) {
                is MemoResponse.Sync -> r.content
                else -> return false
            }
            val result = MemoSync.applySync(db, content, clock())
            // ① server newer / ④ unknown here, and the notes sent cut short: fetched one by one (규약 §3).
            (result.truncatedIds + result.wantedIds).distinct().take(MAX_GETS).forEach { id ->
                val note = callOrNull(MemoOp.get(id)) as? MemoResponse.Note ?: return@forEach
                db.transaction { MemoSync.applyChange(db, memoChangeOfNote(note.note)) }
            }
            // ② we are newer / ③ the server does not have it: queued for the next sync_push.
            result.pushIds.forEach { id -> db.note(id)?.let { MemoSync.enqueueNote(db, it.copy(dirty = true), clock()) } }
            if (result.applied > 0 || result.wantedIds.isNotEmpty() || result.truncatedIds.isNotEmpty()) MemoLocalChanges.changed()
            if (!result.morePages) {
                syncSuggestions()
                return true
            }
            withSegments = false
        }
        return true
    }

    /** Copies the waiting bot suggestions of the index into the local `suggestion` table (설계 §d-③). */
    private suspend fun syncSuggestions() {
        val items = transport.fetchProposals() ?: return
        val now = clock()
        val local = db.suggestions().associateBy { it.id }
        val answering = db.outbox().filter { it.kind == MEMO_OUTBOX_PROPOSAL }.map { it.target }.toSet()
        val seen = mutableSetOf<String>()
        db.transaction {
            items.forEach { o ->
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@forEach
                seen += id
                val mine = local[id]
                // An answer given here stays until the server has it (no flicker back to "waiting").
                if (mine != null && (mine.status != MEMO_SUGGESTION_WAITING || id in answering)) return@forEach
                db.putSuggestion(
                    MemoSuggestionRow(
                        id,
                        o.optString("노트").takeIf { it.isNotEmpty() },
                        o.optString("종류"),
                        o.toString(),
                        MEMO_SUGGESTION_WAITING,
                        mine?.createdMs ?: now
                    )
                )
            }
            // Gone from the index (answered elsewhere, expired): forgotten unless our answer still waits to be sent.
            local.values.filter { it.id !in seen && it.id !in answering }.forEach { db.deleteSuggestion(it.id) }
        }
        MemoLocalChanges.changed()
    }

    // ---------------------------------------------------------------- push

    private suspend fun uploadLocked(force: Boolean): Boolean {
        val attachmentsOk = uploadAttachments()
        val now = clock()
        val due = if (force) db.outbox() else db.outboxDue(now)
        val notes = due.filter { it.kind == MEMO_OUTBOX_NOTE }.take(MemoSync.PUSH_BATCH)
        val answers = due.filter { it.kind == MEMO_OUTBOX_PROPOSAL }
        val args = MemoSync.pushArgs(notes, device(), JSONArray(answers.mapNotNull { tryOrNull { JSONObject(it.payload) } })) ?: return attachmentsOk
        val ack = callOrNull(MemoOp("sync_push", args.toArgs())) as? MemoResponse.SyncAck
        if (ack == null) {
            MemoSync.failUploads(db, notes + answers, now, "서버에 닿지 못함")
            return false
        }
        val result = MemoSync.applyAck(db, ack.content, notes, clock())
        applyProposalResults(ack.content.optJSONArray("제안결과"), answers)
        MemoLocalChanges.changed()
        if (result.pullAgain) pullLocked()
        return attachmentsOk
    }

    private fun applyProposalResults(results: JSONArray?, sent: List<MemoOutboxRow>) {
        val byId = sent.associateBy { it.target }
        val answered = (0 until (results?.length() ?: 0))
            .mapNotNull { results?.optJSONObject(it) }
            .mapNotNull { r -> byId[r.optString("id")]?.let { row -> r to row } }
        db.transaction {
            for ((r, row) in answered) {
                if (r.optString("상태") == "오류") {
                    // The server could not apply it (expired, gone): the local answer stands, nothing is retried.
                    db.dropOutbox(row.seq)
                } else {
                    db.dropOutbox(row.seq)
                    db.deleteSuggestion(row.target)
                }
            }
        }
    }

    /**
     * Uploads the attachments still kept only on the phone (설계 §e: the queue is the attachment table). Each is
     * encrypted here, so the original never sits in the media repository in clear. Stops at the first network error.
     */
    private suspend fun uploadAttachments(): Boolean {
        for ((noteId, a) in db.pendingAttachments()) {
            val bytes = a.localPath?.let(readFile)
            if (bytes == null) {
                db.updateAttachment(a.id, a.mxc, a.fileJson, MEMO_ATTACH_FAILED, a.tries + 1)
                continue
            }
            val sealed = encrypt(bytes)
            val mxc = try {
                transport.upload(sealed.cipher)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val tries = a.tries + 1
                db.updateAttachment(a.id, null, null, if (tries >= MEMO_ATTACH_MAX_TRIES) MEMO_ATTACH_FAILED else MEMO_ATTACH_LOCAL, tries)
                return false
            }
            val now = clock()
            db.transaction {
                db.updateAttachment(a.id, mxc, sealed.fileJson(mxc, a.mime).toString(), MEMO_ATTACH_DONE, 0)
                // The attachment bundle changed (its mxc is now known): a new stamp, and the note goes up again.
                db.note(noteId)?.let { n ->
                    val stamped = n.copy(revs = n.revs.with(MemoBundle.Attach, db.nextRev(device(), now)), dirty = true)
                    db.writeNote(stamped)
                    MemoSync.enqueueNote(db, stamped, now)
                }
            }
            MemoLocalChanges.changed()
        }
        return true
    }

    private suspend fun callOrNull(op: MemoOp): MemoResponse? = try {
        transport.call(op)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Pages of one pull round at most (a first full download of a big archive goes on at the next open). */
        const val MAX_PULL_ROUNDS = 20

        /** Single note fetches of one pull page at most. */
        const val MAX_GETS = 50

        /** Delay between the last local change and the `sync_push` (설계 §k-4 ②). */
        const val PUSH_DELAY_MS = 2_500L
    }
}

private fun JSONObject.toArgs(): Map<String, Any?> = keys().asSequence().associateWith { opt(it) }

/**
 * A `get` answer (`머리말` + `본문` + `첨부`) as a `노트변경` (이벤트규약 v2 §5), so the same merge stores it. The header
 * holds the stamps (`rev`, `수정기기`, `수정순번`) and the content hash.
 */
internal fun memoChangeOfNote(note: MemoNote): JSONObject {
    val h = note.header
    val o = JSONObject()
        .put("id", note.id)
        .put("폴더", h.optString("폴더"))
        .put("제목", h.optString("제목"))
        .put("본문", note.body)
        .put("태그", h.optJSONArray("태그") ?: JSONArray())
        .put("종류", h.optString("종류"))
        .put("생성", h.optString("생성"))
        .put("수정", h.optString("수정"))
        .put("고정", h.optBoolean("고정"))
        .put("고정제안", h.optBoolean("고정제안"))
        .put("고정제안_무시", h.optBoolean("고정제안_무시"))
        .put("삭제", h.optInt("삭제"))
        .put("기기", h.optString("수정기기").ifEmpty { MEMO_DEVICE_SERVER })
        .put("순번", h.optLong("수정순번"))
        .put("머리말", h)
    h.optJSONObject("rev")?.let { o.put("rev", it) }
    h.optJSONObject("링크")?.let { o.put("링크", it) }
    val files = JSONArray()
    note.attachments.forEach { a ->
        val f = JSONObject().put("mxc", a.mxc)
        a.fileJson?.let { tryOrNull { JSONObject(it) } }?.let { f.put("file", it) }
        files.put(f)
    }
    return o.put("첨부", files)
}
