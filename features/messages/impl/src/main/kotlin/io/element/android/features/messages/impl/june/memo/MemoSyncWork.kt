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
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.workmanager.api.WorkManagerRequestBuilder
import io.element.android.libraries.workmanager.api.WorkManagerRequestWrapper
import io.element.android.libraries.workmanager.api.WorkManagerScheduler
import io.element.android.libraries.workmanager.api.WorkManagerWorkerType
import io.element.android.libraries.workmanager.api.di.MetroWorkerFactory
import io.element.android.libraries.workmanager.api.di.WorkerKey
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
            val room = MemoRoomBackend(sessionStore, roomId)
            room to MemoSyncRunner(MemoDatabase.get(context), MemoRoomSyncTransport(sessionStore, room))
        }

    fun runner(context: Context, sessionStore: SessionStore, roomId: String): MemoSyncRunner = entry(context, sessionStore, roomId).second

    fun room(context: Context, sessionStore: SessionStore, roomId: String): MemoRoomBackend = entry(context, sessionStore, roomId).first

    /** Leaving the screen with something still to send: one job, run when the network is there (not a periodic one). */
    fun scheduleUpload(scheduler: WorkManagerScheduler) {
        scope.launch {
            runCatching { scheduler.submit(MemoUploadRequestBuilder) }
                .onFailure { Timber.d("June memo: upload job not scheduled (%s)", it.javaClass.simpleName) }
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

/** Sends what the memo screen left behind (attachments, notes, suggestion answers), then ends (설계 §e·§k-4). */
@AssistedInject
class MemoUploadWorker(
    @Assisted params: WorkerParameters,
    @ApplicationContext context: Context,
    private val sessionStore: SessionStore,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val roomId = JuneSettings.memoRoomId(applicationContext) ?: return Result.success()
        val runner = MemoSyncHub.runner(applicationContext, sessionStore, roomId)
        runner.push(force = true)
        // WorkManager backs off (30 s doubling, capped at 5 h); after MAX_RUNS tries the rest waits for the next screen open.
        return if (runner.hasPendingUploads() && runAttemptCount + 1 < MAX_RUNS) Result.retry() else Result.success()
    }

    @ContributesIntoMap(AppScope::class, binding = binding<MetroWorkerFactory.WorkerInstanceFactory<*>>())
    @WorkerKey(MemoUploadWorker::class)
    @AssistedFactory
    interface Factory : MetroWorkerFactory.WorkerInstanceFactory<MemoUploadWorker>

    private companion object {
        const val MAX_RUNS = 10
    }
}
