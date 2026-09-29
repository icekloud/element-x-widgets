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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
        val savedCenter = repository.store.getBotPickerCenter()
        val radiusDp = repository.store.getBotPickerRadius()
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
                savedCenter = savedCenter,
                radiusDp = radiusDp,
                onCenterMoved = { repository.store.saveBotPickerCenter(it) },
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

/** Positions of the bot nodes (fully opened), in the picker window coordinates. */
private class PickerLayout(
    val anchor: Offset,
    val radius: Float,
    val angles: List<Float>,
    val centers: List<Offset>,
    val start: Float,
    val sweep: Float,
    val full: Boolean,
)

/**
 * Put [n] nodes on one arc around [anchor], only where the nodes fit on screen, so they never overlap
 * or get pushed against the screen edge.
 */
private fun computeLayout(w: Float, h: Float, anchor: Offset, n: Int, radiusDp: Float, px: (Float) -> Float): PickerLayout {
    val margin = px(46f)
    val spacing = px(84f)
    val maxSweep = (150.0 * PI / 180.0).toFloat()
    val steps = 180
    val step = (2 * PI / steps).toFloat()
    fun fits(a: Float, r: Float): Boolean {
        val x = anchor.x + cos(a) * r
        val y = anchor.y + sin(a) * r
        return x in margin..(w - margin) && y in margin..(h - margin - px(14f))
    }
    val base = atan2(h / 2 - anchor.y, w / 2 - anchor.x)
    var r = px(radiusDp)
    var start = base
    var sweep = 0f
    var full = false
    repeat(5) {
        val flags = BooleanArray(steps) { fits(it * step, r) }
        if (flags.all { it }) {
            full = true
            start = base
            sweep = (2 * PI).toFloat()
        } else {
            full = false
            val s0 = flags.indexOfFirst { !it }
            var best = 0
            var bestStart = 0
            var cur = 0
            var curStart = 0
            for (k in 1..steps) {
                val i = (s0 + k) % steps
                if (flags[i]) {
                    if (cur == 0) curStart = i
                    cur++
                    if (cur > best) {
                        best = cur
                        bestStart = curStart
                    }
                } else {
                    cur = 0
                }
            }
            val runLen = max(0, best - 1) * step
            sweep = min(runLen, maxSweep)
            start = bestStart * step + (runLen - sweep) / 2
        }
        val gaps = if (full) n else max(1, n - 1)
        val needed = if (sweep > 0.01f) gaps * spacing / sweep else r * 2
        if (needed <= r || n <= 1) return@repeat
        r = min(needed, min(w, h) * 0.95f)
    }
    val angles = List(n) { i ->
        when {
            full -> start + (2 * PI).toFloat() * i / n
            n <= 1 -> start + sweep / 2
            else -> start + sweep * i / (n - 1)
        }
    }
    val centers = angles.map { a -> Offset(anchor.x + cos(a) * r, anchor.y + sin(a) * r) }
    return PickerLayout(anchor, r, angles, centers, start, sweep, full)
}

private fun angleDiff(a: Float, b: Float): Float = abs(atan2(sin(a - b), cos(a - b)))

/** Index of the node the drag direction points at, or null when it is between the selection zones. */
private fun nodeForDirection(layout: PickerLayout, angle: Float): Int? {
    val n = layout.angles.size
    if (n == 0) return null
    val zone = when {
        layout.full -> (PI / n).toFloat()
        n <= 1 -> (PI / 3).toFloat()
        else -> layout.sweep / (n - 1) / 2 + (8 * PI / 180).toFloat()
    }.coerceAtLeast((18 * PI / 180).toFloat())
    val best = layout.angles.indices.minByOrNull { angleDiff(layout.angles[it], angle) } ?: return null
    return best.takeIf { angleDiff(layout.angles[it], angle) <= zone }
}

/**
 * Element June: HUD style picker (thin lavender rings, ticks and a glow on the pointed bot).
 * Tap a bot, or touch anywhere and swipe toward a bot: past a short distance the bot in that
 * direction lights up and is opened on release.
 */
@Composable
private fun BotPickerScreen(
    anchorOnScreen: Offset?,
    savedCenter: Pair<Float, Float>?,
    radiusDp: Float,
    onCenterMoved: (Pair<Float, Float>) -> Unit,
    rooms: List<WidgetRoom>,
    loaded: Boolean,
    avatars: Map<String, Bitmap>,
    accent: Color,
    onPick: (WidgetRoom) -> Unit,
    onDismiss: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
    }
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    var dragFrom by remember { mutableStateOf<Offset?>(null) }
    var dragTo by remember { mutableStateOf<Offset?>(null) }
    var movedAnchor by remember { mutableStateOf<Offset?>(null) }
    var moving by remember { mutableStateOf(false) }
    val pick by rememberUpdatedState(onPick)
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(highlighted) {
        if (highlighted != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val density = LocalDensity.current
    val hud = lerp(accent, Color.White, 0.45f)
    val hudDeep = lerp(accent, Color.Black, 0.1f)
    val p = progress.value
    val pa = p.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                origin = Offset(loc[0].toFloat(), loc[1].toFloat())
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        fun px(v: Float) = with(density) { v.dp.toPx() }
        val iconAnchor = anchorOnScreen?.let { it - origin }?.let { Offset(it.x.coerceIn(0f, w), it.y.coerceIn(0f, h)) }
            ?: Offset(w - px(64f), h - px(96f))
        val anchor = movedAnchor ?: savedCenter?.let { Offset(it.first * w, it.second * h) } ?: iconAnchor
        val layout = remember(w, h, anchor, rooms.size, radiusDp) { computeLayout(w, h, anchor, rooms.size, radiusDp, ::px) }
        val currentLayout = rememberUpdatedState(layout)
        val currentRooms = rememberUpdatedState(rooms)
        val moved by rememberUpdatedState(onCenterMoved)
        val dragThreshold = px(40f)
        val nodeRadius = px(28f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val startLayout = currentLayout.value
                        var dragging = false
                        dragFrom = down.position
                        // Long press on the centre: move the centre instead of selecting
                        if ((down.position - startLayout.anchor).getDistance() <= 32.dp.toPx()) {
                            val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var result = -1
                                while (result < 0) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    result = when {
                                        c == null || !c.pressed -> 0
                                        (c.position - down.position).getDistance() > dragThreshold -> 1
                                        else -> -1
                                    }
                                }
                                result
                            }
                            if (outcome == null) {
                                moving = true
                                dragFrom = null
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                var last = startLayout.anchor
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    last = Offset(c.position.x.coerceIn(0f, w), c.position.y.coerceIn(0f, h))
                                    movedAnchor = last
                                    c.consume()
                                    if (!c.pressed) break
                                }
                                moving = false
                                moved(last.x / w to last.y / h)
                                return@awaitEachGesture
                            }
                            if (outcome == 0) {
                                dragFrom = null
                                dismiss()
                                return@awaitEachGesture
                            }
                            dragging = true
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val d = change.position - down.position
                            if (!dragging && d.getDistance() > dragThreshold) dragging = true
                            if (dragging) {
                                dragTo = change.position
                                highlighted = nodeForDirection(currentLayout.value, atan2(d.y, d.x))
                            }
                            change.consume()
                            if (!change.pressed) break
                        }
                        val chosen = highlighted
                        val up = dragTo
                        dragFrom = null
                        dragTo = null
                        val list = currentRooms.value
                        if (dragging) {
                            highlighted = null
                            if (chosen != null && chosen in list.indices) pick(list[chosen])
                        } else {
                            val tap = up ?: down.position
                            val hit = currentLayout.value.centers.indexOfFirst { (it - tap).getDistance() <= nodeRadius + 12.dp.toPx() }
                            if (hit in list.indices) pick(list[hit]) else dismiss()
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(Color(0xFF0B0716).copy(alpha = 0.62f * pa))
                val r = layout.radius * p
                if (layout.centers.isNotEmpty() && r > 1f) {
                    val startDeg: Float
                    val sweepDeg: Float
                    if (layout.full) {
                        startDeg = 0f
                        sweepDeg = 360f
                    } else {
                        startDeg = Math.toDegrees(layout.start.toDouble()).toFloat() - 10f
                        sweepDeg = Math.toDegrees(layout.sweep.toDouble()).toFloat() + 20f
                    }
                    fun arc(radius: Float, color: Color, width: Float, dash: PathEffect? = null) {
                        drawArc(
                            color = color,
                            startAngle = startDeg,
                            sweepAngle = sweepDeg,
                            useCenter = false,
                            topLeft = Offset(layout.anchor.x - radius, layout.anchor.y - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(width = width, pathEffect = dash),
                        )
                    }
                    // Glow band, main ring, inner dashed ring
                    arc(r, hudDeep.copy(alpha = 0.18f * pa), px(22f))
                    arc(r, hud.copy(alpha = 0.55f * pa), px(1.2f))
                    arc(r * 0.42f, hud.copy(alpha = 0.35f * pa), px(1f), PathEffect.dashPathEffect(floatArrayOf(px(3f), px(6f))))
                    arc(r + px(34f), hud.copy(alpha = 0.22f * pa), px(1f))
                    // Ticks on the outer ring
                    var deg = startDeg
                    var k = 0
                    while (deg <= startDeg + sweepDeg) {
                        val a = Math.toRadians(deg.toDouble()).toFloat()
                        val long = k % 3 == 0
                        val r1 = r + px(38f)
                        val r2 = r1 + px(if (long) 9f else 4f)
                        drawLine(
                            color = hud.copy(alpha = (if (long) 0.55f else 0.3f) * pa),
                            start = Offset(layout.anchor.x + cos(a) * r1, layout.anchor.y + sin(a) * r1),
                            end = Offset(layout.anchor.x + cos(a) * r2, layout.anchor.y + sin(a) * r2),
                            strokeWidth = px(1f),
                        )
                        deg += 5f
                        k++
                    }
                    // Faint spokes, and a bright one to the pointed bot
                    layout.centers.forEachIndexed { index, c ->
                        val cc = layout.anchor + (c - layout.anchor) * p
                        val on = index == highlighted
                        drawLine(
                            color = hud.copy(alpha = (if (on) 0.9f else 0.12f) * pa),
                            start = layout.anchor,
                            end = cc,
                            strokeWidth = px(if (on) 2f else 1f),
                        )
                        if (on) {
                            drawCircle(hudDeep.copy(alpha = 0.35f), radius = nodeRadius + px(10f), center = cc, style = Stroke(px(10f)))
                        }
                        drawCircle(
                            color = hud.copy(alpha = (if (on) 1f else 0.7f) * pa),
                            radius = nodeRadius + px(4f),
                            center = cc,
                            style = Stroke(px(if (on) 2f else 1.2f)),
                        )
                    }
                }
                // Swipe trail
                val from = dragFrom
                val to = dragTo
                if (from != null && to != null) {
                    drawLine(hud.copy(alpha = 0.5f), from, to, strokeWidth = px(1.5f))
                    drawCircle(hud.copy(alpha = 0.6f), radius = px(6f), center = from, style = Stroke(px(1.2f)))
                    drawCircle(hud.copy(alpha = 0.9f), radius = px(4f), center = to)
                }
            }

            // Close / centre node
            Box(
                modifier = Modifier
                    .offset { IntOffset((anchor.x - px(22f)).roundToInt(), (anchor.y - px(22f)).roundToInt()) }
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF140E24).copy(alpha = 0.85f))
                    .border(if (moving) 3.dp else 1.5.dp, hud, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = hud, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            if (loaded && rooms.isEmpty()) {
                Text(
                    "봇 대화방을 찾지 못했습니다.\nJune 꾸미기 → 봇 선택 아이콘에서 고르세요.",
                    color = hud,
                    modifier = Modifier
                        .offset { IntOffset(px(24f).roundToInt(), (anchor.y - px(120f)).coerceAtLeast(0f).roundToInt()) }
                        .background(Color(0xFF140E24).copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
            }

            rooms.forEachIndexed { index, room ->
                val c = layout.centers.getOrNull(index) ?: return@forEachIndexed
                val cc = layout.anchor + (c - layout.anchor) * p
                val on = index == highlighted
                val scale = if (on) 1.14f else 1f
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cc.x - nodeRadius).roundToInt(), (cc.y - nodeRadius).roundToInt()) }
                        .size(56.dp)
                        .alpha(pa)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = avatars[room.roomId]
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = room.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape),
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF1C1433)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(room.name.trim().take(1), color = hud, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    room.name,
                    color = if (on) Color.White else hud,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset { IntOffset((cc.x - px(40f)).roundToInt(), (cc.y + nodeRadius + px(6f)).roundToInt()) }
                        .width(80.dp)
                        .alpha(pa)
                        .background(Color(0xFF140E24).copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }

            // Hint under the rings
            Text(
                if (moving) "놓으면 이 위치가 중심이 됩니다" else "탭 또는 스와이프로 선택 · ✕ 길게 눌러 위치 이동",
                color = hud.copy(alpha = 0.7f * pa),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                modifier = Modifier.offset {
                    IntOffset(
                        (anchor.x - px(120f)).coerceIn(px(8f), (w - px(248f)).coerceAtLeast(px(8f))).roundToInt(),
                        (anchor.y + px(30f)).coerceAtMost(h - px(20f)).roundToInt(),
                    )
                },
            )
        }
    }
}
