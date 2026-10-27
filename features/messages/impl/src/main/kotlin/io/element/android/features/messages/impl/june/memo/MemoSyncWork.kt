/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.binding
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.designsystem.june.JuneMemoCheckLog
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.workmanager.api.WorkManagerRequestBuilder
import io.element.android.libraries.workmanager.api.WorkManagerRequestWrapper
import io.element.android.libraries.workmanager.api.WorkManagerScheduler
import io.element.android.libraries.workmanager.api.WorkManagerWorkerType
import io.element.android.libraries.workmanager.api.di.MetroWorkerFactory
import io.element.android.libraries.workmanager.api.di.WorkerKey
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

@ContributesTo(AppScope::class)
interface MemoSyncBindings {
    fun memoSyncSessionStore(): SessionStore
    fun memoSyncWorkManagerScheduler(): WorkManagerScheduler

    /** 진단앱수신: the opened memo room, for the reader of the encrypted answers ([MemoAnswerReader]). */
    fun memoSyncActiveRooms(): ActiveRoomsHolder
}

/**
 * One sync runner per memo room for the whole process, so the screen and the one-time upload job share the same lock
 * and the same room transport (the answers the screen sees in the timeline reach the waiting call, 설계 §k-4).
 */
internal object MemoSyncHub {
    private val runners = mutableMapOf<String, Pair<MemoRoomBackend, MemoSyncRunner>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    private fun entry(context: Context, sessionStore: SessionStore, roomId: String): Pair<MemoRoomBackend, MemoSyncRunner> =
        runners.getOrPut(roomId) {
            installCheckLog(context)
            val room = MemoRoomBackend(sessionStore, roomId)
            room to MemoSyncRunner(
                MemoDatabase.get(context),
                MemoRoomSyncTransport(sessionStore, room),
                // 앱8 항목 1 e: step names and counts only (no token, address or key ever reaches this line).
                log = { Timber.i("June memo sync: %s", it) },
                // 앱28 추가요청 1: the settings diagnosis item (shown only, read by nothing else).
                diag = { JuneSettings.setMemoDiag(context, it) },
            )
        }

    fun runner(context: Context, sessionStore: SessionStore, roomId: String): MemoSyncRunner = entry(context, sessionStore, roomId).second

    fun room(context: Context, sessionStore: SessionStore, roomId: String): MemoRoomBackend = entry(context, sessionStore, roomId).first

    private val directives = mutableMapOf<String, MemoDirectives>()

    /** 앱9 D: the instructions of the proposal area, one per room for the process (the sends go on across screens). */
    @Synchronized
    fun directives(context: Context, sessionStore: SessionStore, roomId: String): MemoDirectives = directives.getOrPut(roomId) {
        MemoDirectives(
            MemoDatabase.get(context),
            MemoRoomSyncTransport(sessionStore, room(context, sessionStore, roomId)),
            log = { Timber.i("June memo sync: %s", it) },
        )
    }

    private val chats = mutableMapOf<String, MemoChat>()

    /** 앱22: the chat of the proposal area, one per room for the process (the mode and the bubbles live across screens). */
    @Synchronized
    fun chat(context: Context, sessionStore: SessionStore, roomId: String): MemoChat = chats.getOrPut(roomId) {
        MemoChat(
            MemoDatabase.get(context),
            MemoRoomSyncTransport(sessionStore, room(context, sessionStore, roomId)),
            log = { Timber.i("June memo sync: %s", it) },
        )
    }

    private val reviewers = mutableMapOf<String, MemoReviewer>()

    /**
     * 앱17: the review requests of a room, one per room for the process (the 3 s repeat guard holds across screens).
     * The notes still waiting go up first (one `sync_push`, photos later as usual), so the server has the note and
     * reviews what the phone shows — a new note too: its id is made on the phone and is the same on the server.
     */
    @Synchronized
    fun reviewer(context: Context, sessionStore: SessionStore, roomId: String): MemoReviewer = reviewers.getOrPut(roomId) {
        val runner = runner(context, sessionStore, roomId)
        MemoReviewer(
            MemoRoomSyncTransport(sessionStore, room(context, sessionStore, roomId)),
            prepare = { if (runner.hasPendingUploads()) runner.pushNotes() },
            log = { Timber.i("June memo sync: %s", it) },
        )
    }

    /**
     * 앱33 추가요청 1 ⑤: the look-up lines go to a txt file of the app's own storage, off the screen thread. Put in
     * once for the process; the memo code itself decides what a line says and how many are kept.
     */
    @Synchronized
    private fun installCheckLog(context: Context) {
        if (checkLogOn) return
        checkLogOn = true
        val app = context.applicationContext
        MemoCheckLog.install { line ->
            scope.launch {
                tryOrNull(onException = { Timber.d("June memo: check line not written (%s)", it.javaClass.simpleName) }) {
                    JuneMemoCheckLog.write(app, memoCheckLogTrim(JuneMemoCheckLog.read(app), line))
                }
            }
        }
    }

    private var checkLogOn = false

    /** Leaving the screen with attachments still to upload: one job, run when the network is there (not a periodic one). */
    fun scheduleUpload(scheduler: WorkManagerScheduler) {
        scope.launch {
            tryOrNull(onException = { Timber.d("June memo: upload job not scheduled (%s)", it.javaClass.simpleName) }) {
                scheduler.submit(MemoUploadRequestBuilder)
            }
        }
    }
}

internal object MemoUploadRequestBuilder : WorkManagerRequestBuilder {
    private const val WORK_NAME = "june_memo_upload"

    override suspend fun build(): Result<List<WorkManagerRequestWrapper>> {
        val request = OneTimeWorkRequestBuilder<MemoUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: a job already waiting sends everything that is due when it runs; no second one is queued.
        return Result.success(listOf(WorkManagerRequestWrapper(request, WorkManagerWorkerType.Unique(WORK_NAME, ExistingWorkPolicy.KEEP))))
    }
}

/**
 * Uploads the attachments the memo screen left behind, then ends (설계 §e·§k-4). Only the attachments (관리자 결정
 * 2026-10-13 1-1 (가)): the notes and the suggestion answers wait for the next screen open, where the `sync_ack` can
 * be read from the room timeline even when the room is encrypted.
 */
@AssistedInject
class MemoUploadWorker(
    @Assisted params: WorkerParameters,
    @ApplicationContext context: Context,
    private val sessionStore: SessionStore,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val roomId = JuneSettings.memoRoomId(applicationContext) ?: return Result.success()
        val runner = MemoSyncHub.runner(applicationContext, sessionStore, roomId)
        runner.pushAttachments()
        // WorkManager backs off (30 s doubling, capped at 5 h); after MAX_RUNS tries the rest waits for the next screen open.
        return if (runner.hasPendingAttachments() && runAttemptCount + 1 < MAX_RUNS) Result.retry() else Result.success()
    }

    @ContributesIntoMap(AppScope::class, binding = binding<MetroWorkerFactory.WorkerInstanceFactory<*>>())
    @WorkerKey(MemoUploadWorker::class)
    @AssistedFactory
    interface Factory : MetroWorkerFactory.WorkerInstanceFactory<MemoUploadWorker>

    private companion object {
        const val MAX_RUNS = 10
    }
}
