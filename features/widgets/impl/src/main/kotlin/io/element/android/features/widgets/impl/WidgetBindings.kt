/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.features.widgets.api.WidgetUpdater

@ContributesTo(AppScope::class)
interface WidgetBindings {
    fun widgetRoomRepository(): WidgetRoomRepository
    fun widgetUpdater(): WidgetUpdater
}
