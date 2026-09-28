/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.element.android.features.widgets.impl.config.WidgetConfigActivity

internal object WidgetIntents {
    /**
     * Intent opening the room in this app, using the elementx://open deep link handled by MainActivity.
     */
    fun openRoom(context: Context, sessionId: String, roomId: String): Intent {
        val uri = Uri.parse("elementx://open/${Uri.encode(sessionId)}/${Uri.encode(roomId)}")
        return Intent(Intent.ACTION_VIEW, uri)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun openApp(context: Context): Intent {
        return context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
    }

    fun configure(context: Context, appWidgetId: Int): Intent {
        return Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
