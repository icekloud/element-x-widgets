/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.june.JuneMemoButtonSpot
import io.element.android.libraries.designsystem.theme.components.HorizontalFloatingToolbar
import io.element.android.libraries.designsystem.theme.components.HorizontalFloatingToolbarItem
import kotlin.math.roundToInt

/** Test tag of the memo button of the home screen (the part that moves). */
internal const val JUNE_HOME_MEMO_BUTTON = "june_home_memo_button"

/** The gap kept between the button and the edges of the safe area (the place the bottom bar always had). */
private val MEMO_BUTTON_MARGIN = 16.dp

/** A button dropped this close to an edge is put against it (no pull from further away). */
private val MEMO_BUTTON_SNAP = 24.dp

/** The screen reader actions: a step up, down, left or right (label, x step, y step). */
private val MEMO_BUTTON_STEPS = listOf(
    Triple("메모 버튼 위로 이동", 0f, -1f),
    Triple("메모 버튼 아래로 이동", 0f, 1f),
    Triple("메모 버튼 왼쪽으로 이동", -1f, 0f),
    Triple("메모 버튼 오른쪽으로 이동", 1f, 0f),
)

/** How much bigger the button is while it moves. */
private const val MEMO_BUTTON_MOVING_SCALE = 1.12f

/**
 * Element June (앱19 추가요청 1): the memo button of the home screen, over the room list. A tap opens the memo room
 * ([onClick]); a long press lifts it (one vibration, a bit bigger) and it follows the finger; where it is let go it stays
 * ([onMove] saves the spot, as parts of the room it can move in). It never leaves the safe area (status bar, navigation
 * bar, gesture area) and keeps [MEMO_BUTTON_MARGIN] from its edges. [spot] null is the default place (bottom centre).
 * The screen reader gets four actions that move it a step up, down, left or right.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun JuneMovableMemoButton(
    spot: JuneMemoButtonSpot?,
    onMove: (JuneMemoButtonSpot) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(MEMO_BUTTON_MARGIN)
    ) {
        val haptic = LocalHapticFeedback.current
        val snap = with(LocalDensity.current) { MEMO_BUTTON_SNAP.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val freeWidth = (constraints.maxWidth - size.width).toFloat().coerceAtLeast(0f)
        val freeHeight = (constraints.maxHeight - size.height).toFloat().coerceAtLeast(0f)
        val placed = spot ?: JuneMemoButtonSpot.DEFAULT
        // The top left of the button while it moves (null = not moving)
        var moving by remember { mutableStateOf<Offset?>(null) }
        val left = moving?.x ?: placed.left(freeWidth)
        val top = moving?.y ?: placed.top(freeHeight)
        val start by rememberUpdatedState(Offset(left, top))
        val room by rememberUpdatedState(Offset(freeWidth, freeHeight))
        val currentOnMove by rememberUpdatedState(onMove)
        val currentPlaced by rememberUpdatedState(placed)
        Box(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .onSizeChanged { size = it }
                .zIndex(1f)
                .graphicsLayer {
                    val scale = if (moving != null) MEMO_BUTTON_MOVING_SCALE else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) {
                    // Watched in the first pass, before the button inside: a tap or a scroll stays with them. Once the long
                    // press lifts the button, every move and the release are consumed here, so the tap of the button is
                    // cancelled (a lifted button never opens the memo room) and the room list does not scroll.
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
                    customActions = MEMO_BUTTON_STEPS.map { (label, dx, dy) ->
                        CustomAccessibilityAction(label) {
                            currentOnMove(currentPlaced.nudge(dx * JuneMemoButtonSpot.STEP, dy * JuneMemoButtonSpot.STEP))
                            true
                        }
                    }
                }
                .testTag(JUNE_HOME_MEMO_BUTTON),
        ) {
            HorizontalFloatingToolbar {
                HorizontalFloatingToolbarItem(
                    icon = CompoundIcons.Document(),
                    tooltipLabel = "메모",
                    isSelected = false,
                    onClick = onClick,
                )
            }
        }
    }
}
