/*
 * Copyright (c) 2026 Element June.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import androidx.compose.foundation.Indication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/** Element June: how long ▼ has to be held to go straight to the newest message. Longer than the system long press on purpose. */
internal const val JUNE_LONG_PRESS_MILLIS = 1_000L

/** True when a press held for [heldMillis] is a long press. */
internal fun juneIsLongPress(heldMillis: Long): Boolean = heldMillis >= JUNE_LONG_PRESS_MILLIS

/**
 * Element June: decides between a tap and a long press of [JUNE_LONG_PRESS_MILLIS], so that exactly one of them runs per press.
 *
 * - [onHeld]: the finger is still down after the given time. Once it reaches the long press, [onLongPressReached]
 *   (haptic feedback) and [onLongClick] run, once.
 * - [onRelease]: the finger is lifted. A press shorter than the long press is a tap ([onClick]); a press that already
 *   ran the long press does nothing more.
 * - [onCancel]: the press was cancelled (finger moved away): nothing runs.
 */
internal class JuneLongPressHandler(
    private val onClick: () -> Unit,
    private val onLongClick: () -> Unit,
    private val onLongPressReached: () -> Unit,
) {
    private var isLongPressDone = false

    fun onDown() {
        isLongPressDone = false
    }

    /** Returns true when the long press ran (now or earlier in this press). */
    fun onHeld(heldMillis: Long): Boolean {
        if (!isLongPressDone && juneIsLongPress(heldMillis)) {
            isLongPressDone = true
            onLongPressReached()
            onLongClick()
        }
        return isLongPressDone
    }

    fun onRelease(heldMillis: Long) {
        if (isLongPressDone) return
        // The finger can be lifted right at the limit before the timer fires: it still counts as a long press
        if (!onHeld(heldMillis)) {
            onClick()
        }
    }

    fun onCancel() {
        isLongPressDone = false
    }
}

/**
 * Element June: like combinedClickable, but the long press needs [JUNE_LONG_PRESS_MILLIS] instead of the system timeout.
 * Accessibility services get the click and long click actions directly, without timing.
 */
internal fun Modifier.juneLongPressClickable(
    handler: JuneLongPressHandler,
    interactionSource: MutableInteractionSource,
    indication: Indication?,
    onAccessibilityClick: () -> Unit,
    onAccessibilityLongClick: () -> Unit,
    longClickLabel: String,
): Modifier = this
    .indication(interactionSource, indication)
    .semantics(mergeDescendants = true) {
        role = Role.Button
        onClick {
            onAccessibilityClick()
            true
        }
        onLongClick(label = longClickLabel) {
            onAccessibilityLongClick()
            true
        }
    }
    .pointerInput(handler, interactionSource) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            handler.onDown()
            val press = PressInteraction.Press(down.position)
            interactionSource.tryEmit(press)
            var isCancelled = false
            val up = withTimeoutOrNull(JUNE_LONG_PRESS_MILLIS) {
                waitForUpOrCancellation().also { isCancelled = it == null }
            }
            when {
                up != null -> {
                    up.consume()
                    interactionSource.tryEmit(PressInteraction.Release(press))
                    handler.onRelease(up.uptimeMillis - down.uptimeMillis)
                }
                isCancelled -> {
                    interactionSource.tryEmit(PressInteraction.Cancel(press))
                    handler.onCancel()
                }
                else -> {
                    handler.onHeld(JUNE_LONG_PRESS_MILLIS)
                    // Swallow the rest of the press, so lifting the finger does not count as a tap
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    interactionSource.tryEmit(PressInteraction.Release(press))
                }
            }
        }
    }
