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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.element.android.libraries.architecture.bindings

class RoomListWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(SMALL, MEDIUM, LARGE),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val repository = context.bindings<WidgetBindings>().widgetRoomRepository()
        val config = repository.store.getConfig(appWidgetId)
        val rooms = config?.let { runCatching { repository.loadConfiguredRooms(it) }.getOrDefault(emptyList()) }.orEmpty()
        val avatars = if (config == null) {
            emptyMap()
        } else {
            rooms.associate { it.roomId to runCatching { repository.loadAvatar(config.sessionId, it.avatarUrl) }.getOrNull() }
        }
        provideContent {
            GlanceTheme {
                RoomListContent(appWidgetId, config, rooms, avatars)
            }
        }
    }

    companion object {
        val SMALL = DpSize(180.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 250.dp)
        val LARGE = DpSize(320.dp, 250.dp)
    }
}

class RoomListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RoomListWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = WidgetStore(context)
        appWidgetIds.forEach { store.deleteConfig(it) }
    }
}

@Composable
private fun RoomListContent(
    appWidgetId: Int,
    config: WidgetConfig?,
    rooms: List<WidgetRoom>,
    avatars: Map<String, Bitmap?>,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val compact = size.height < RoomListWidget.MEDIUM.height
    val avatarSize = if (compact) 32.dp else 40.dp
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Element June",
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(WidgetIntents.openApp(context))),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                text = "설정",
                modifier = GlanceModifier.clickable(actionStartActivity(WidgetIntents.configure(context, appWidgetId))),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
        if (config == null || config.roomIds.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(WidgetIntents.configure(context, appWidgetId))),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "눌러서 대화방을 선택하세요", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
            }
        } else if (rooms.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "대화방 정보를 불러오는 중…", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(rooms, itemId = { it.roomId.hashCode().toLong() }) { room ->
                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = if (compact) 3.dp else 5.dp)
                            .clickable(actionStartActivity(WidgetIntents.openRoom(context, config.sessionId, room.roomId))),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoomAvatar(room.name, avatars[room.roomId], avatarSize)
                        Spacer(modifier = GlanceModifier.width(10.dp))
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                text = room.name,
                                maxLines = 1,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = if (room.unread > 0) FontWeight.Bold else FontWeight.Medium,
                                ),
                            )
                            if (room.preview.isNotEmpty()) {
                                Text(
                                    text = room.preview,
                                    maxLines = 1,
                                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                                )
                            }
                        }
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        UnreadBadge(room.unread)
                    }
                }
            }
        }
    }
}
