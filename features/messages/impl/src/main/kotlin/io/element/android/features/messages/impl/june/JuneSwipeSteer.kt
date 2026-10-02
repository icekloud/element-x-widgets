/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Element June: how far (part of the row width) a queued row has to be pushed to the left to steer it. */
internal const val JUNE_SWIPE_STEER_FRACTION = 0.4f

/** The gateway steers text messages and messages with pictures; other files and videos cannot be steered. */
internal fun juneCanSteer(item: JuneQueueItem): Boolean = item.kind == "text" || item.kind == "photo"

/** A queued row can be pushed to steer it unless it is being edited, waits for a request, or cannot be steered. */
internal fun juneCanSwipeSteer(item: JuneQueueItem, editing: Boolean, busy: Boolean): Boolean =
    !editing && !busy && juneCanSteer(item)

/** The row's offset after a horizontal move of [delta] px: only to the left (negative), at most the row width. */
internal fun juneSwipeOffset(current: Float, delta: Float, widthPx: Float): Float =
    if (widthPx <= 0f) 0f else (current + delta).coerceIn(-widthPx, 0f)

/** True when the row at [offsetPx] is pushed far enough to the left to steer when let go. */
internal fun juneSwipeReached(offsetPx: Float, widthPx: Float): Boolean =
    widthPx > 0f && -offsetPx >= widthPx * JUNE_SWIPE_STEER_FRACTION

/** Words shown behind the pushed row. */
internal fun juneSwipeHint(reached: Boolean, sent: Boolean): String = when {
    sent -> "⏩ 스티어링 보냄"
    reached -> "⏩ 놓으면 스티어링"
    else -> "⏩ 밀어서 스티어링"
}

/**
 * Element June: the state of a queued row pushed to the left to steer it ("slide to unlock").
 *
 * - [onDrag]: the finger moves the row (only to the left). [onThresholdReached] (haptic feedback) runs once each time
 *   the row gets past [JUNE_SWIPE_STEER_FRACTION] of its width.
 * - [onRelease]: the finger is lifted. Returns true when the row has to be steered: it is far enough and [allowed].
 *   The row then stays [isSent] until [onSettled] (the gateway answered), otherwise it goes back.
 */
internal class JuneSwipeSteerHandler(private val onThresholdReached: () -> Unit) {
    var offset by mutableFloatStateOf(0f)
        private set
    var isSent by mutableStateOf(false)
        private set
    var widthPx = 0f
    private var wasReached = false

    val isReached: Boolean get() = juneSwipeReached(offset, widthPx)

    fun onDrag(delta: Float) {
        offset = juneSwipeOffset(offset, delta, widthPx)
        val reached = isReached
        if (reached && !wasReached) onThresholdReached()
        wasReached = reached
    }

    fun onRelease(allowed: Boolean): Boolean {
        wasReached = false
        if (isSent || !allowed || !isReached) return false
        isSent = true
        return true
    }

    /** Steer without a gesture (accessibility action). Returns false when already sent. */
    fun sendNow(): Boolean {
        if (isSent) return false
        isSent = true
        return true
    }

    /**
     * The gateway answered (or the request could not be sent): the row goes back to its place. A steered message
     * leaves the queue in the same update, so only a failed one is seen coming back (the error is told by [JuneQueueOps]).
     */
    fun onSettled() {
        isSent = false
    }

    /** Moves the row without haptic feedback (animations). */
    fun moveTo(value: Float) {
        offset = juneSwipeOffset(value, 0f, widthPx)
    }
}

private suspend fun JuneSwipeSteerHandler.animateOffsetTo(target: Float, draggableState: DraggableState) {
    draggableState.drag(MutatePriority.PreventUserInput) {
        Animatable(offset).animateTo(target, animationSpec = tween(durationMillis = 200)) { moveTo(value) }
    }
}

/**
 * Element June: a queued row that can be pushed from right to left to steer the message. Behind it, "⏩ 스티어링" shows up.
 * Only horizontal drags are taken, so the panel still scrolls vertically and the buttons in the row still work.
 *
 * [onSteer] sends the request and calls the given callback once the gateway answered (or the request failed). A failed
 * request brings the row back to its place; a steered message leaves the queue, and its row with it.
 */
@Composable
internal fun JuneSwipeSteerRow(
    canSwipe: Boolean,
    onSteer: (onDone: (ok: Boolean) -> Unit) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val handler = remember { JuneSwipeSteerHandler { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
    val draggableState = rememberDraggableState { delta -> handler.onDrag(delta) }
    val currentCanSwipe by rememberUpdatedState(canSwipe)
    val currentOnSteer by rememberUpdatedState(onSteer)

    fun steer() = currentOnSteer { _ -> handler.onSettled() }

    // Back to its place when the request is over, or when the row cannot be pushed anymore (edited, busy) mid-way
    LaunchedEffect(handler.isSent, canSwipe) {
        if (!handler.isSent && handler.offset != 0f) {
            handler.animateOffsetTo(0f, draggableState)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { handler.widthPx = it.width.toFloat() }
            .semantics(mergeDescendants = true) {
                if (canSwipe) {
                    customActions = listOf(
                        CustomAccessibilityAction("스티어링으로 보내기(왼쪽으로 밀기)") {
                            if (handler.sendNow()) steer()
                            true
                        },
                    )
                }
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = canSwipe && !handler.isSent,
                onDragStopped = {
                    scope.launch {
                        if (handler.onRelease(currentCanSwipe)) {
                            // Send first, then rest at the threshold while waiting for the gateway
                            steer()
                            handler.animateOffsetTo(-handler.widthPx * JUNE_SWIPE_STEER_FRACTION, draggableState)
                        }
                        // Not sent, or already answered while settling
                        if (!handler.isSent) handler.animateOffsetTo(0f, draggableState)
                    }
                },
            ),
    ) {
        if (handler.offset != 0f) {
            val strong = handler.isReached || handler.isSent
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (strong) ElementTheme.colors.bgAccentRest else ElementTheme.colors.bgSubtlePrimary),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    modifier = Modifier.padding(end = 16.dp),
                    text = juneSwipeHint(reached = handler.isReached, sent = handler.isSent),
                    style = ElementTheme.typography.fontBodyMdMedium,
                    color = if (strong) ElementTheme.colors.textOnSolidPrimary else ElementTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(handler.offset.roundToInt(), 0) }
                .background(ElementTheme.colors.bgSubtleSecondary)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
