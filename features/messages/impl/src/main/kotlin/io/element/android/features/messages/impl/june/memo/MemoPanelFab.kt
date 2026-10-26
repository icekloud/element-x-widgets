/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.june.JuneMemoButtonSpot
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.designsystem.theme.components.Icon
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 앱32 (추가요청 3, 관리자: "플로팅 버튼(꾹 누르면 이동 가능한형태)로 제안탭 접근 가능한 방식으로 수정"): the floating
 * proposal button of the memo screen. A tap opens the proposal screen — the same thing the list icon of the top bar
 * does, which stays exactly as it was (추가요청 2 ①, 3 ④). A long press lifts it (one vibration, a little bigger) and
 * it follows the finger; where it is let go it stays, and it is still there the next time the screen is opened
 * (결정기록 D13). It never leaves the area it is drawn in and keeps [MEMO_FAB_MARGIN] from its edges.
 */

/** The gap kept between the floating button and the edges of the area it may move in. */
private val MEMO_FAB_MARGIN = 12.dp

/** A button let go this close to an edge is put against it. */
private val MEMO_FAB_SNAP = 24.dp

/** How much bigger the button is while it moves. */
private const val MEMO_FAB_MOVING_SCALE = 1.12f

private val MEMO_FAB_SIZE = 44.dp

/** 추가요청 3 ③: the default place — the right edge, a little below the middle (clear of the top bar and the composer). */
internal val MEMO_FAB_DEFAULT = JuneMemoButtonSpot(1f, 0.65f)

/** The screen reader actions: a step up, down, left or right (label, x step, y step). */
private val MEMO_FAB_STEPS = listOf(
    Triple("제안 버튼 위로 이동", 0f, -1f),
    Triple("제안 버튼 아래로 이동", 0f, 1f),
    Triple("제안 버튼 왼쪽으로 이동", -1f, 0f),
    Triple("제안 버튼 오른쪽으로 이동", 1f, 0f),
)

/**
 * 추가요청 3 ⑤: when the floating button is drawn. It is hidden while the editor has the whole screen, while a sheet
 * or a photo is open over the screen, and while the proposal screen it opens is already open. It stays in the
 * selection mode (the proposals can still be opened from there).
 */
internal fun memoPanelFabVisible(
    fullEditor: Boolean,
    sheetOpen: Boolean,
    viewerOpen: Boolean,
    panelOpen: Boolean,
): Boolean = !fullEditor && !sheetOpen && !viewerOpen && !panelOpen

/** 추가요청 3 ①: what the screen reader says about the button, the same words as the list icon of the top bar. */
internal fun memoPanelFabDescription(count: Int): String = if (count > 0) "제안 ${count}건" else "제안"

/** 추가요청 3 ①: the badge text, the same rule as the top bar badge; null = no badge. */
internal fun memoPanelFabBadge(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

@Composable
internal fun MemoPanelFab(roomId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val counts by MemoPanelHub.waiting.collectAsState()
    val count = counts[roomId] ?: 0
    val saved = JuneSettings.memoPanelFabSpot(context)
    BoxWithConstraints(modifier.fillMaxSize().padding(MEMO_FAB_MARGIN)) {
        val haptic = LocalHapticFeedback.current
        val snap = with(LocalDensity.current) { MEMO_FAB_SNAP.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val freeWidth = (constraints.maxWidth - size.width).toFloat().coerceAtLeast(0f)
        val freeHeight = (constraints.maxHeight - size.height).toFloat().coerceAtLeast(0f)
        val placed = saved ?: MEMO_FAB_DEFAULT
        // The top left of the button while it moves (null = it is not moving)
        var moving by remember { mutableStateOf<Offset?>(null) }
        val left = moving?.x ?: placed.left(freeWidth)
        val top = moving?.y ?: placed.top(freeHeight)
        val start by rememberUpdatedState(Offset(left, top))
        val room by rememberUpdatedState(Offset(freeWidth, freeHeight))
        val currentPlaced by rememberUpdatedState(placed)
        val onMove: (JuneMemoButtonSpot) -> Unit = { spot -> JuneSettings.setMemoPanelFabSpot(context, spot) }
        val currentOnMove by rememberUpdatedState(onMove)
        Box(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .onSizeChanged { size = it }
                .graphicsLayer {
                    val scale = if (moving != null) MEMO_FAB_MOVING_SCALE else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) {
                    // Watched in the first pass, before the button inside: a tap or a scroll stays with them. Once the
                    // long press lifts the button, every move and the release are consumed here, so the tap is
                    // cancelled (a lifted button never opens the proposals) and the list below does not scroll.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val shortGesture = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var ended = false
                            while (!ended) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                                ended = change == null || !change.pressed ||
                                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            }
                            true
                        }
                        if (shortGesture != null) return@awaitEachGesture
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        moving = start
                        var held = true
                        while (held) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                held = false
                            } else {
                                val delta = change.position - change.previousPosition
                                change.consume()
                                held = change.pressed
                                val now = moving ?: start
                                moving = Offset(
                                    JuneMemoButtonSpot.keepIn(now.x + delta.x, room.x),
                                    JuneMemoButtonSpot.keepIn(now.y + delta.y, room.y),
                                )
                            }
                        }
                        moving?.let { currentOnMove(JuneMemoButtonSpot.dropped(it.x, it.y, room.x, room.y, snap)) }
                        moving = null
                    }
                }
                .semantics(mergeDescendants = true) {
                    customActions = MEMO_FAB_STEPS.map { (label, dx, dy) ->
                        CustomAccessibilityAction(label) {
                            currentOnMove(currentPlaced.nudge(dx * JuneMemoButtonSpot.STEP, dy * JuneMemoButtonSpot.STEP))
                            true
                        }
                    }
                }
                .testTag(MemoTags.PANEL_FAB),
        ) {
            Box(
                modifier = Modifier
                    .size(MEMO_FAB_SIZE)
                    .background(ElementTheme.colors.bgActionPrimaryRest, CircleShape)
                    // A plain tap opens the proposals; while the button is being moved the gesture above consumes
                    // every event, so this press is cancelled (the same way the home screen button works, 앱19).
                    .clickable { MemoPanelHub.open(roomId) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CompoundIcons.ListBulleted(),
                    contentDescription = memoPanelFabDescription(count),
                    tint = ElementTheme.colors.iconOnSolidPrimary,
                )
            }
            memoPanelFabBadge(count)?.let { text ->
                MemoBadge(
                    text,
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp).testTag(MemoTags.PANEL_FAB_BADGE),
                )
            }
        }
    }
}
