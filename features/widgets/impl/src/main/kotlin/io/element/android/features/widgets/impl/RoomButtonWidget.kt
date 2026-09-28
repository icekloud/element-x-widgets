/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.element.android.libraries.architecture.bindings

class RoomButtonWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val repository = context.bindings<WidgetBindings>().widgetRoomRepository()
        val config = repository.store.getConfig(appWidgetId)
        val room = config?.let { runCatching { repository.loadConfiguredRooms(it).firstOrNull() }.getOrNull() }
        val avatar = if (config != null && room != null) runCatching { repository.loadAvatar(config.sessionId, room.avatarUrl) }.getOrNull() else null
        provideContent {
            GlanceTheme {
                RoomButtonContent(appWidgetId, config, room, avatar)
            }
        }
    }
}

class RoomButtonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RoomButtonWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = WidgetStore(context)
        appWidgetIds.forEach { store.deleteConfig(it) }
    }
}

@Composable
private fun RoomButtonContent(appWidgetId: Int, config: WidgetConfig?, room: WidgetRoom?, avatar: Bitmap?) {
    val context = LocalContext.current
    val roomId = config?.roomIds?.firstOrNull()
    val action = if (config != null && roomId != null) {
        actionStartActivity(WidgetIntents.openRoom(context, config.sessionId, roomId))
    } else {
        actionStartActivity(WidgetIntents.configure(context, appWidgetId))
    }
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        if (roomId == null) {
            Text(text = "+", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 24.sp))
        } else {
            RoomAvatar(room?.name ?: "?", avatar, 48.dp)
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                UnreadBadge(room?.unread ?: 0)
            }
        }
    }
}
