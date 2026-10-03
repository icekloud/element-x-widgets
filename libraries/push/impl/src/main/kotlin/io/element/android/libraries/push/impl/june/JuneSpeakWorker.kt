/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.binding
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.workmanager.api.WorkManagerRequestBuilder
import io.element.android.libraries.workmanager.api.WorkManagerRequestWrapper
import io.element.android.libraries.workmanager.api.WorkManagerScheduler
import io.element.android.libraries.workmanager.api.WorkManagerWorkerType
import io.element.android.libraries.workmanager.api.di.MetroWorkerFactory
import io.element.android.libraries.workmanager.api.di.WorkerKey
import io.element.android.services.toolbox.api.sdk.BuildVersionSdkIntProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/** Longest time the keep alive work waits for the lines to be read. */
internal val JUNE_SPEAK_KEEP_ALIVE = 90.seconds

private const val WORK_NAME = "june_speak"

/**
 * Element June: keeps the process running while the 🔊 lines are read aloud, so it is not frozen by the system when the app is in the
 * background (the notification work that received the message may end before the reading does).
 */
fun interface JuneSpeakKeepAlive {
    fun start()
}

@ContributesBinding(AppScope::class)
class DefaultJuneSpeakKeepAlive(
    private val workManagerScheduler: WorkManagerScheduler,
    private val buildVersionSdkIntProvider: BuildVersionSdkIntProvider,
    @AppCoroutineScope
    private val coroutineScope: CoroutineScope,
) : JuneSpeakKeepAlive {
    override fun start() {
        coroutineScope.launch {
            workManagerScheduler.submit(JuneSpeakRequestBuilder(buildVersionSdkIntProvider))
        }
    }
}

internal class JuneSpeakRequestBuilder(
    private val buildVersionSdkIntProvider: BuildVersionSdkIntProvider,
) : WorkManagerRequestBuilder {
    override suspend fun build(): Result<List<WorkManagerRequestWrapper>> {
        val request = OneTimeWorkRequestBuilder<JuneSpeakWorker>()
            .apply {
                // Like the notification work: expedited work only from Android 12 on, before it would need a foreground notification
                if (buildVersionSdkIntProvider.isAtLeast(Build.VERSION_CODES.S)) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        // Append: if a work is just finishing when new lines arrive, another one runs after it
        val type = WorkManagerWorkerType.Unique(name = WORK_NAME, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success(listOf(WorkManagerRequestWrapper(request, type)))
    }
}

@AssistedInject
class JuneSpeakWorker(
    @Assisted params: WorkerParameters,
    @ApplicationContext context: Context,
    private val juneSpeaker: JuneSpeaker,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Timber.d("JuneSpeakWorker started")
        juneSpeaker.awaitIdle(JUNE_SPEAK_KEEP_ALIVE)
        return Result.success()
    }

    @ContributesIntoMap(AppScope::class, binding = binding<MetroWorkerFactory.WorkerInstanceFactory<*>>())
    @WorkerKey(JuneSpeakWorker::class)
    @AssistedFactory
    interface Factory : MetroWorkerFactory.WorkerInstanceFactory<JuneSpeakWorker>
}
