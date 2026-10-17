/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Test tags of the 앱17 review screens. */
internal object MemoReviewTags {
    const val SWIPE = "memo_review_swipe"
    const val SWIPE_HINT = "memo_review_swipe_hint"
    const val BUTTON = "memo_review_button"
    const val NOTICE = "memo_review_notice"
    const val BULK = "memo_review_bulk"
}

/**
 * 앱17 A: a note card of a list that can be pushed to the left. Behind it "✨ 검토" shows; let go past
 * [MEMO_REVIEW_SWIPE_FRACTION] of its width it asks for the note's review and comes back to its place (it does not go
 * away like a delete). Only sideways moves are taken, so the list still scrolls and a tap or a long press still works.
 * Nothing to the right. [content] gets the modifier that gives the card itself the screen reader action "AI 검토 요청".
 */
@Composable
internal fun MemoReviewSwipe(controller: MemoController, id: String, folder: String, content: @Composable (Modifier) -> Unit) {
    val allowed = controller.reviewer != null &&
        memoReviewSwipeAllowed(controller.nav.value, controller.select.active, controller.viewer != null, folder)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var wasReached by remember { mutableStateOf(false) }
    val state = rememberDraggableState { delta ->
        offset = memoReviewSwipeOffset(offset, delta, width)
        val reached = memoReviewSwipeReached(offset, width)
        if (reached && !wasReached) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        wasReached = reached
    }
    val currentAllowed by rememberUpdatedState(allowed)
    // Not allowed any more mid-way (the selection mode started): back to the place at once.
    LaunchedEffect(allowed) { if (!allowed) offset = 0f }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat() }
            .draggable(
                state = state,
                orientation = Orientation.Horizontal,
                enabled = allowed,
                onDragStopped = {
                    val go = currentAllowed && memoReviewSwipeReached(offset, width)
                    wasReached = false
                    if (go) controller.review(listOf(id))
                    scope.launch { state.settleTo(offset) { offset = it } }
                },
            )
            .testTag(MemoReviewTags.SWIPE),
    ) {
        if (offset != 0f) {
            val reached = memoReviewSwipeReached(offset, width)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (reached) ElementTheme.colors.bgAccentRest else ElementTheme.colors.bgSubtlePrimary)
                    .testTag(MemoReviewTags.SWIPE_HINT),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    memoReviewSwipeHint(reached),
                    modifier = Modifier.padding(end = 16.dp),
                    style = ElementTheme.typography.fontBodyMdMedium,
                    color = if (reached) ElementTheme.colors.textOnSolidPrimary else ElementTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        // At rest the card is drawn as before; pushed, it covers the hint with the list colour.
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .then(if (offset != 0f) Modifier.background(ElementTheme.colors.bgCanvasDefault) else Modifier),
        ) {
            val action = if (allowed) {
                Modifier.semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(MEMO_REVIEW_ACTION) {
                            controller.review(listOf(id))
                            true
                        },
                    )
                }
            } else {
                Modifier
            }
            content(action)
        }
    }
}

/** Back to the place in a short move; the finger cannot take the card meanwhile. */
private suspend fun DraggableState.settleTo(from: Float, set: (Float) -> Unit) {
    drag(MutatePriority.PreventUserInput) {
        Animatable(from).animateTo(0f, animationSpec = tween(durationMillis = MEMO_REVIEW_BACK_MS)) { set(value) }
    }
}

private const val MEMO_REVIEW_BACK_MS = 200

/**
 * 앱17 C: the review button of an open note, left of its 📌 button and of the same size ([MEMO_PIN_TOGGLE_DP]). Disabled
 * (selection mode): dimmed, a press does nothing.
 */
@Composable
internal fun MemoReviewToggle(button: MemoReviewButton, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MEMO_PIN_TOGGLE_DP.dp)
            .clickable(enabled = button.enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = MEMO_REVIEW_ACTION }
            .testTag(MemoReviewTags.BUTTON),
    ) {
        Text(
            MEMO_REVIEW_MARK,
            style = ElementTheme.typography.fontBodyMdRegular,
            modifier = Modifier.alpha(if (button.enabled) 1f else MEMO_REVIEW_DISABLED_ALPHA),
        )
    }
}

private const val MEMO_REVIEW_DISABLED_ALPHA = 0.4f

/** One notice of the review; [stamp] makes a new one with the same words start its time again. */
internal data class MemoNotice(val text: String, val stamp: Long = System.nanoTime())

/** 앱17: the small notice of a review ("검토를 요청했습니다 (1건)", "검토 완료: 제안 2건") for [MEMO_NOTICE_SHOW_MS]. */
@Composable
internal fun MemoNoticeBanner(controller: MemoController, notice: MemoNotice) {
    LaunchedEffect(notice) {
        delay(MEMO_NOTICE_SHOW_MS)
        if (controller.notice === notice) controller.notice = null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgSubtleSecondary)
            .clickable { controller.notice = null }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(MemoReviewTags.NOTICE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(notice.text, style = ElementTheme.typography.fontBodySmRegular, maxLines = 2)
    }
}
