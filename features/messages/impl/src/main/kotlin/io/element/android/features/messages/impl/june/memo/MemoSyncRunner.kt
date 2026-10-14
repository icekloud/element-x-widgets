/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.core.data.tryOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What one press of the sync button did. [Partial]: the notes went up, some photos wait for a later try (앱8). */
internal enum class MemoButtonSync { Done, Partial, Failed, TimedOut, Busy }

/** The short line the sync button shows after a press (앱6 항목 2). */
internal fun memoSyncButtonText(result: MemoButtonSync): String = when (result) {
    MemoButtonSync.Done -> "동기화했습니다"
    MemoButtonSync.Partial -> "노트는 올렸습니다 · 사진은 나중에 다시 시도합니다"
    MemoButtonSync.Failed -> "동기화하지 못했습니다 · 서버에 닿지 못함"
    MemoButtonSync.TimedOut -> "동기화가 ${MemoSyncRunner.BUTTON_TIMEOUT_MS / 1000}초 안에 끝나지 않았습니다 · 나중에 다시 시도합니다"
    MemoButtonSync.Busy -> "이미 동기화하고 있습니다"
}

/** One press of the sync button in numbers (앱8 항목 1 d): what went up, what came down, the photos still waiting. */
internal data class MemoSyncReport(
    val result: MemoButtonSync,
    val sent: Int = 0,
    val received: Int = 0,
    val photosLeft: Int = 0,
)

/** The line shown after every press (앱8 항목 1 d): never nothing. */
internal fun memoSyncReportText(r: MemoSyncReport): String = when (r.result) {
    MemoButtonSync.Done -> when {
        r.sent == 0 && r.received == 0 -> "동기화했습니다 · 바뀐 것 없음"
        else -> "동기화했습니다 · 올림 ${r.sent}건 · 받음 ${r.received}건"
    }
    MemoButtonSync.Partial -> "노트 ${r.sent}건을 올렸습니다 · 사진 ${r.photosLeft}장은 나중에 다시 시도합니다"
    MemoButtonSync.Failed -> if (r.sent > 0) {
        "노트 ${r.sent}건만 올렸습니다 · 서버에 닿지 못함"
    } else {
        memoSyncButtonText(MemoButtonSync.Failed)
    }
    else -> memoSyncButtonText(r.result)
}

/** What the sync needs from the memo bot room. The room backend implements it; the tests use a fake. */
internal interface MemoSyncTransport {
    /** Sends [op] and returns its answer. Throws when it cannot be sent or no answer comes in time. */
    suspend fun call(op: MemoOp): MemoResponse

    /** The `제안[]` items of the `app.june.memo.index` state (split keys included), null on a transient error. */
    suspend fun fetchProposals(): List<JSONObject>?

    /** Uploads already encrypted bytes to the media repository and returns the `mxc://` uri. */
    suspend fun upload(bytes: ByteArray): String

    /** The server upload limit in bytes (`m.upload.size`), null when it cannot be read right now. */
    suspend fun maxUploadSize(): Long?
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
    /** The size of a local file without reading it, null when unknown (then the stored size is used). */
    private val fileSize: (String) -> Long? = { path -> File(path).takeIf { it.isFile }?.length() },
    /** One line per step (앱8 항목 1 e): counts and step names only, never a token, an address or a key. */
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val buttonRunning = AtomicBoolean(false)

    /** When the last pull round ended well, so a press right after the screen's own pull does not pull again. */
    @Volatile
    private var lastPullOkMs = 0L

    /** Notes that came down during the current round (the press report). */
    private var receivedInRound = 0

    private fun device(): String = memoDeviceOf(db)

    /** True when something waits for the server: notes, suggestion answers or attachments (the screen retry test). */
    fun hasPendingUploads(): Boolean =
        db.outbox().any { it.kind == MEMO_OUTBOX_NOTE || it.kind == MEMO_OUTBOX_PROPOSAL } || hasPendingAttachments()

    /** True when attachments still wait for their upload (the one-time job test, 관리자 결정 1-1 (가)). */
    fun hasPendingAttachments(): Boolean = db.pendingAttachments().isNotEmpty()

    /** Millis until the earliest waiting upload (note, answer or attachment) may be tried again, null when nothing waits. */
    fun nextRetryInMs(): Long? {
        val rows = db.outbox().filter { it.kind == MEMO_OUTBOX_NOTE || it.kind == MEMO_OUTBOX_PROPOSAL }
        val times = rows.map { it.nextTryMs } + db.pendingAttachments().map { it.second.retryAtMs }
        if (times.isEmpty()) return null
        return (times.min() - clock()).coerceAtLeast(0)
    }

    /** Screen shown: pull what changed, then send what waits. Returns false when the server could not be reached. */
    suspend fun syncNow(): Boolean = withTimeoutOrNull(AUTO_TIMEOUT_MS) {
        mutex.withLock {
            log("화면 동기화 시작")
            val pulled = pullLocked()
            val pushed = if (pulled) uploadLocked(force = false).ok else false
            log("화면 동기화 끝 받기=$pulled 올리기=$pushed")
            pulled && pushed
        }
    } ?: false.also { log("화면 동기화 시간 초과") }

    /**
     * The sync button of the memo room top bar (앱6 항목 2): `sync_pull`, the waiting notes at once, then the
     * attachments and the notes they completed, ignoring every back-off. A second press while one runs does nothing
     * ([MemoButtonSync.Busy]); the automatic syncs wait for the same lock, so two syncs never run together.
     *
     * 앱8: the whole press is limited to [BUTTON_TIMEOUT_MS]. The notes go up before any photo (a stuck upload held the
     * lock and the notes behind it before), and when the time runs out during the photos the press ends as
     * [MemoButtonSync.Partial]: the lock and the button are always free again afterwards.
     */
    suspend fun syncByButton(): MemoButtonSync = syncByButtonReport().result

    suspend fun syncByButtonReport(): MemoSyncReport {
        if (!buttonRunning.compareAndSet(false, true)) return MemoSyncReport(MemoButtonSync.Busy)
        val start = clock()
        return try {
            var report: MemoSyncReport? = null
            val finished = withTimeoutOrNull(BUTTON_TIMEOUT_MS) {
                mutex.withLock {
                    receivedInRound = 0
                    log("버튼 동기화 시작")
                    // The screen pulled a moment ago (it opens with one): one pull is enough (앱8 추가 점검).
                    val pulled = if (clock() - lastPullOkMs < RECENT_PULL_MS) true.also { log("받기 생략(방금 받음)") } else pullLocked()
                    // What waits goes up even when the pull failed, like the automatic push after typing.
                    val notes = pushNotesLocked(force = true)
                    var sent = notes.sent
                    report = MemoSyncReport(if (pulled && notes.ok) MemoButtonSync.Done else MemoButtonSync.Failed, sent, receivedInRound, pendingPhotos())
                    val left = BUTTON_TIMEOUT_MS - (clock() - start)
                    val photosOk = withTimeoutOrNull(left.coerceAtLeast(1)) { uploadAttachments(force = true) }
                    if (photosOk == null) log("버튼 동기화: 사진 올리기 시간 초과")
                    // The photos that went up complete their notes: those go up again with the mxc.
                    val again = pushNotesLocked(force = true)
                    sent += again.sent
                    val photosLeft = pendingPhotos()
                    val result = when {
                        !pulled || !notes.ok || !again.ok -> MemoButtonSync.Failed
                        photosOk != true || photosLeft > 0 -> MemoButtonSync.Partial
                        else -> MemoButtonSync.Done
                    }
                    MemoSyncReport(result, sent, receivedInRound, photosLeft)
                }
            }
            // Out of time before the photos: what the notes did is still said; with nothing known, a time out.
            (finished ?: report?.let { it.copy(result = if (it.result == MemoButtonSync.Done) MemoButtonSync.Partial else it.result) }
                ?: MemoSyncReport(MemoButtonSync.TimedOut))
                .also { log("버튼 동기화 끝 ${it.result} 올림=${it.sent} 받음=${it.received} 사진대기=${it.photosLeft} ${clock() - start}ms") }
        } finally {
            buttonRunning.set(false)
        }
    }

    /** Input stopped (2.5 s) or a retry is due: `sync_push`, attachments, then their notes. [force] ignores the back-off. */
    suspend fun push(force: Boolean = false): Boolean = withTimeoutOrNull(AUTO_TIMEOUT_MS) {
        mutex.withLock { uploadLocked(force).ok }
    } ?: false.also { log("올리기 시간 초과") }

    /**
     * The one-time job after the screen went away (관리자 결정 2026-10-13 1-1 (가)): the attachments only, without
     * waiting for their back-off (WorkManager has its own). No `sync_push`: its answer can only be read through the
     * open room timeline in an encrypted room, so the notes and the suggestion answers go up at the next screen open.
     */
    suspend fun pushAttachments(): Boolean = withTimeoutOrNull(JOB_TIMEOUT_MS) {
        mutex.withLock { uploadAttachments(force = true) }
    } ?: false.also { log("사진 작업 시간 초과") }

    /** Attachments still waiting for their upload (the press report and the card mark). */
    private fun pendingPhotos(): Int = db.pendingAttachments().size

    // ---------------------------------------------------------------- pull

    private suspend fun pullLocked(): Boolean {
        var withSegments = true
        repeat(MAX_PULL_ROUNDS) {
            log("받기 요청")
            val content = when (val r = callOrNull(MemoOp("sync_pull", MemoSync.pullArgs(db, device(), withSegments).toArgs()))) {
                is MemoResponse.Sync -> r.content
                else -> return false.also { log("받기 실패(답 없음)") }
            }
            val result = MemoSync.applySync(db, content, clock())
            receivedInRound += result.applied
            log("받기 적용 ${result.applied}건 · 다시받을 ${result.wantedIds.size + result.truncatedIds.size}건 · 올릴 ${result.pushIds.size}건")
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
                lastPullOkMs = clock()
                return true
            }
            withSegments = false
        }
        lastPullOkMs = clock()
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

    /** What one push step did: [ok] false when the server could not be reached, [sent] notes sent and answered. */
    private data class Pushed(val ok: Boolean, val sent: Int)

    /**
     * 앱8 항목 1 b: the notes first (a note waiting for its photo goes up without it: the server keeps it and adds the
     * photo when the stamped note comes), then the attachments, then the notes the attachments completed. A stuck or
     * failing photo upload no longer holds the notes back.
     */
    private suspend fun uploadLocked(force: Boolean): Pushed {
        val first = pushNotesLocked(force)
        val attachmentsOk = uploadAttachments(force)
        val again = pushNotesLocked(force)
        return Pushed(first.ok && again.ok && attachmentsOk, first.sent + again.sent)
    }

    /** One `sync_push` of the notes and suggestion answers that wait (all of them with [force]). */
    private suspend fun pushNotesLocked(force: Boolean): Pushed {
        val now = clock()
        val due = if (force) db.outbox() else db.outboxDue(now)
        val notes = due.filter { it.kind == MEMO_OUTBOX_NOTE }.take(MemoSync.PUSH_BATCH)
        val answers = due.filter { it.kind == MEMO_OUTBOX_PROPOSAL }
        val args = MemoSync.pushArgs(notes, device(), JSONArray(answers.mapNotNull { tryOrNull { JSONObject(it.payload) } }))
            ?: return Pushed(true, 0)
        log("올리기 요청 노트 ${notes.size}건 · 제안응답 ${answers.size}건")
        val ack = callOrNull(MemoOp("sync_push", args.toArgs())) as? MemoResponse.SyncAck
        if (ack == null) {
            log("올리기 실패(답 없음)")
            MemoSync.failUploads(db, notes + answers, now, "서버에 닿지 못함")
            return Pushed(false, 0)
        }
        val result = MemoSync.applyAck(db, ack.content, notes, clock())
        applyProposalResults(ack.content.optJSONArray("제안결과"), answers)
        log("올리기 응답 받음 ${result.accepted} · 합침 ${result.merged} · 거절 ${result.rejected}")
        MemoLocalChanges.changed()
        if (result.pullAgain) pullLocked()
        return Pushed(true, notes.size)
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
     * encrypted here, so the original never sits in the media repository in clear. Stops at the first network error,
     * which holds every waiting attachment back until the failed one's next try ([force] = the job: no waiting).
     */
    private suspend fun uploadAttachments(force: Boolean): Boolean {
        val start = clock()
        val due = db.pendingAttachments().filter { force || it.second.retryAtMs <= start }
        if (due.isEmpty()) return true
        // Asked once per round: a file over the limit is never read (관리자 결정 1-2 (나), no streaming yet).
        val limit = transport.maxUploadSize()?.takeIf { it > 0 } ?: MEMO_ATTACH_DEFAULT_MAX_BYTES
        for ((noteId, a) in due) {
            val sealed = when (val s = seal(a, limit)) {
                is Sealed.Ready -> s.file
                is Sealed.Refused -> {
                    // Trying again cannot help (too big, gone, unreadable): 실패 with the reason, the next one goes on.
                    db.updateAttachment(a.id, a.mxc, a.fileJson, MEMO_ATTACH_FAILED, a.tries + 1, error = s.reason)
                    MemoLocalChanges.changed()
                    continue
                }
            }
            log("사진 올리기 ${sealed.cipher.size}바이트")
            val mxc = try {
                transport.upload(sealed.cipher)
            } catch (e: CancellationException) {
                // The upload's own time out is a failed try (see callOrNull); a cancel of the sync goes up.
                if (e !is TimeoutCancellationException) throw e
                currentCoroutineContext().ensureActive()
                uploadFailed(a)
                log("사진 올리기 시간 초과")
                return false
            } catch (e: Exception) {
                log("사진 올리기 실패 (${e.javaClass.simpleName})")
                uploadFailed(a)
                return false
            }
            log("사진 올림")
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

    /** One failed upload try of [a]: its next try, or 실패 after [MEMO_ATTACH_MAX_TRIES]. */
    private fun uploadFailed(a: MemoLocalAttachment) {
        val tries = a.tries + 1
        val failed = tries >= MEMO_ATTACH_MAX_TRIES
        val retryAt = MemoSync.nextTryMs(tries, clock())
        db.transaction {
            db.updateAttachment(
                a.id,
                null,
                null,
                if (failed) MEMO_ATTACH_FAILED else MEMO_ATTACH_LOCAL,
                tries,
                retryAt,
                if (failed) MEMO_ATTACH_ERR_UPLOAD else null,
            )
            db.holdAttachments(retryAt)
        }
        // The card mark follows the attachment state (앱8 항목 1 d).
        MemoLocalChanges.changed()
    }

    /** One attachment read and encrypted, or the reason it cannot be uploaded. */
    private sealed interface Sealed {
        class Ready(val file: MemoEncryptedFile) : Sealed

        class Refused(val reason: String) : Sealed
    }

    /**
     * Reads and encrypts [a] when it fits under [limit] (the size is checked before reading). Never throws: a read
     * error or a file too big for the memory becomes a reason instead of crashing the screen (GLaDOS 치명 2).
     */
    private fun seal(a: MemoLocalAttachment, limit: Long): Sealed {
        val path = a.localPath ?: return Sealed.Refused(MEMO_ATTACH_ERR_MISSING)
        return try {
            val size = fileSize(path) ?: a.bytes
            if (size != null && size > limit) return Sealed.Refused(memoAttachTooBig(size, limit))
            val bytes = readFile(path) ?: return Sealed.Refused(MEMO_ATTACH_ERR_MISSING)
            if (bytes.size > limit) return Sealed.Refused(memoAttachTooBig(bytes.size.toLong(), limit))
            Sealed.Ready(encrypt(bytes))
        } catch (e: OutOfMemoryError) {
            Sealed.Refused(MEMO_ATTACH_ERR_MEMORY)
        } catch (e: Exception) {
            Sealed.Refused(MEMO_ATTACH_ERR_READ)
        }
    }

    /**
     * 앱8 원인: the answer wait of [MemoSyncTransport.call] ends with a [TimeoutCancellationException], which is a
     * [CancellationException]. Rethrown, it ended the whole sync quietly: no `sync_push` after the pull, and the button
     * neither stopped spinning nor said anything. A time out of the call itself is a failed call; only a cancel of this
     * sync (the screen left, the press limit) still goes up.
     */
    private suspend fun callOrNull(op: MemoOp): MemoResponse? = try {
        transport.call(op)
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        log("${op.op} 답 시간 초과")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("${op.op} 보내지 못함 (${e.javaClass.simpleName})")
        null
    }

    companion object {
        /** Pages of one pull round at most (a first full download of a big archive goes on at the next open). */
        const val MAX_PULL_ROUNDS = 20

        /** Single note fetches of one pull page at most. */
        const val MAX_GETS = 50

        /** Delay between the last local change and the `sync_push` (설계 §k-4 ②). */
        const val PUSH_DELAY_MS = 2_500L

        /** 앱8: one press of the sync button at most; the lock and the button are free again after it. */
        const val BUTTON_TIMEOUT_MS = 90_000L

        /** 앱8: one automatic round (screen shown, input stopped) at most. */
        const val AUTO_TIMEOUT_MS = 120_000L

        /** 앱8: one run of the one-time upload job at most (WorkManager tries again later). */
        const val JOB_TIMEOUT_MS = 300_000L

        /** 앱8: a press within this time after a good pull (the screen's own) does not pull again. */
        const val RECENT_PULL_MS = 10_000L
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
