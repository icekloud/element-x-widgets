/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.api

/**
 * Refreshes the home screen widgets (room button and room list).
 */
interface WidgetUpdater {
    /**
     * Request a refresh of all the widgets. Calls are debounced.
     */
    fun requestUpdate()
}
