/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.picker

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.features.widgets.impl.WidgetBindings
import io.element.android.features.widgets.impl.WidgetIntents
import io.element.android.features.widgets.impl.WidgetRoom
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.designsystem.june.JuneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Element June: "봇 선택" launcher icon. Opens a transparent screen where the bot rooms fan out
 * from the touched icon; tapping one opens that room.
 */
class BotPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JuneSettings.ensureLoaded(this)
        val repository = bindings<WidgetBindings>().widgetRoomRepository()
        val source = intent?.sourceBounds
        val anchorOnScreen = source?.let { Offset(it.exactCenterX(), it.exactCenterY()) }
        setContent {
            val accent = JuneSettings.color(JuneSettings.ColorSlot.Accent)
            var sessionId by remember { mutableStateOf<String?>(null) }
            var rooms by remember { mutableStateOf<List<WidgetRoom>>(emptyList()) }
            var loaded by remember { mutableStateOf(false) }
            val avatars = remember { mutableStateMapOf<String, Bitmap>() }
            LaunchedEffect(Unit) {
                val sid = repository.defaultSessionId()
                if (sid == null) {
                    finish()
                    return@LaunchedEffect
                }
                sessionId = sid
                rooms = withContext(Dispatchers.IO) { repository.botPickerRooms(sid, cacheOnly = true) }
                rooms.forEach { room -> withContext(Dispatchers.IO) { repository.cachedAvatar(room.avatarUrl) }?.let { avatars[room.roomId] = it } }
                val fresh = withContext(Dispatchers.IO) { runCatching { repository.botPickerRooms(sid, cacheOnly = false) }.getOrNull() }
                if (fresh != null) rooms = fresh
                loaded = true
                rooms.filter { it.roomId !in avatars }.forEach { room ->
                    withContext(Dispatchers.IO) { runCatching { repository.loadAvatar(sid, room.avatarUrl) }.getOrNull() }
                        ?.let { avatars[room.roomId] = it }
                }
            }
            BotPickerScreen(
                anchorOnScreen = anchorOnScreen,
                rooms = rooms,
                loaded = loaded,
                avatars = avatars,
                accent = accent,
                onPick = { room ->
                    sessionId?.let { startActivity(WidgetIntents.openRoom(this, it, room.roomId)) }
                    finish()
                },
                onDismiss = ::finish,
            )
        }
    }
}

@Composable
private fun BotPickerScreen(
    anchorOnScreen: Offset?,
    rooms: List<WidgetRoom>,
    loaded: Boolean,
    avatars: Map<String, Bitmap>,
    accent: Color,
    onPick: (WidgetRoom) -> Unit,
    onDismiss: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    }
    val view = LocalView.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val p = progress.value
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                origin = Offset(loc[0].toFloat(), loc[1].toFloat())
            }
            .background(Color.Black.copy(alpha = 0.3f * p.coerceIn(0f, 1f)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        fun px(v: Float) = with(density) { v.dp.toPx() }
        val anchor = anchorOnScreen?.let { it - origin } ?: Offset(w - px(64f), h - px(96f))
        val n = rooms.size
        val radius = px(72f + 22f * n)
        val base = atan2(h / 2 - anchor.y, w / 2 - anchor.x)
        val spread = Math.toRadians(if (n <= 1) 0.0 else 110.0).toFloat()
        val itemW = px(76f)
        val avatar = 56.dp

        // Close button on the touched icon
        Box(
            modifier = Modifier
                .offset { IntOffset((anchor.x - px(24f)).roundToInt(), (anchor.y - px(24f)).roundToInt()) }
                .size(48.dp)
                .clip(CircleShape)
                .background(accent)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        if (loaded && n == 0) {
            Text(
                "봇 대화방을 찾지 못했습니다.\nJune 꾸미기 → 봇 선택 아이콘에서 고르세요.",
                color = Color.White,
                modifier = Modifier
                    .offset { IntOffset(px(24f).roundToInt(), (anchor.y - px(120f)).coerceAtLeast(0f).roundToInt()) }
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
        }

        rooms.forEachIndexed { index, room ->
            val angle = base - spread / 2 + if (n <= 1) spread / 2 else spread * index / (n - 1)
            val cx = anchor.x + cos(angle) * radius * p
            val cy = anchor.y + sin(angle) * radius * p
            val x = (cx - itemW / 2).coerceIn(0f, (w - itemW).coerceAtLeast(0f))
            val y = (cy - px(28f)).coerceIn(0f, (h - px(84f)).coerceAtLeast(0f))
            Column(
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .width(76.dp)
                    .alpha(p.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(room) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val bitmap = avatars[room.roomId]
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = room.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(avatar).clip(CircleShape).border(2.dp, Color.White, CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier.size(avatar).clip(CircleShape).background(accent).border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(room.name.trim().take(1), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    room.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}
