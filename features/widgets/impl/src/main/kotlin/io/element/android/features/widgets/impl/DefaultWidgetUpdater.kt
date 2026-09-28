/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import android.content.Context
import androidx.glance.appwidget.updateAll
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.widgets.api.WidgetUpdater
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultWidgetUpdater(
    @ApplicationContext private val context: Context,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
) : WidgetUpdater {
    private var pending: Job? = null

    override fun requestUpdate() {
        pending?.cancel()
        pending = coroutineScope.launch {
            delay(DEBOUNCE_MS)
            runCatching {
                RoomListWidget().updateAll(context)
                RoomButtonWidget().updateAll(context)
            }.onFailure { Timber.w(it, "Widget: update failed") }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
