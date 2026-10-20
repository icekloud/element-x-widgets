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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
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
    const val DIRECTIVE_INPUT = "memo_review_directive_input"
    const val DIRECTIVE_COUNT = "memo_review_directive_count"
    const val DIRECTIVE_SEND = "memo_review_directive_send"
    const val DIRECTIVE_CANCEL = "memo_review_directive_cancel"
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

/**
 * 앱21 ① (관리자: "입력창을 통해 메모봇에게 재검토 시 추가로 지시할 내용", "아무것도 지시안하고 보내면 기존 재검토 기능 그대로"):
 * the sheet the review button of an open note opens. A field of a few lines (at most [MEMO_REVIEW_DIRECTIVE_MAX]
 * characters, the count under it), 취소 and 검토. 검토 with an empty field is the review of before. What was typed is
 * given to [onReview] once and kept nowhere: the sheet opens empty every time.
 */
@Composable
internal fun MemoReviewDirectiveSheet(onReview: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    Column(modifier.padding(16.dp)) {
        Text("$MEMO_REVIEW_MARK AI 검토", style = ElementTheme.typography.fontHeadingSmMedium)
        Text(
            "이번 검토에서 메모봇에게 더 알려 줄 것이 있으면 적으세요. 비워 두면 평소처럼 검토합니다.",
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
        )
        TextField(
            value = text,
            onValueChange = { text = memoReviewDirectiveInput(it) },
            placeholder = "예: 음식점 이름과 메뉴를 꼭 찾아줘",
            minLines = MEMO_REVIEW_DIRECTIVE_LINES,
            maxLines = MEMO_REVIEW_DIRECTIVE_MAX_LINES,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(MemoReviewTags.DIRECTIVE_INPUT),
        )
        Text(
            memoReviewDirectiveCount(text),
            style = ElementTheme.typography.fontBodyXsRegular,
            color = ElementTheme.colors.textSecondary,
            modifier = Modifier.align(Alignment.End).padding(top = 2.dp).testTag(MemoReviewTags.DIRECTIVE_COUNT),
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Text("취소", modifier = Modifier.clickable(onClick = onCancel).padding(12.dp).testTag(MemoReviewTags.DIRECTIVE_CANCEL))
            Text(
                "검토",
                color = ElementTheme.colors.textActionAccent,
                modifier = Modifier.clickable { onReview(text) }.padding(12.dp).testTag(MemoReviewTags.DIRECTIVE_SEND),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 앱21 ①: the field of the directive sheet shows this many lines at first and grows up to the second number. */
private const val MEMO_REVIEW_DIRECTIVE_LINES = 2
private const val MEMO_REVIEW_DIRECTIVE_MAX_LINES = 6

/**
 * One notice of the review, shown for [showMs] (앱18 ③: [MEMO_NOTICE_RESULT_SHOW_MS] for a result); [stamp] makes a new
 * one with the same words start its time again.
 */
internal data class MemoNotice(val text: String, val showMs: Long = MEMO_NOTICE_SHOW_MS, val stamp: Long = System.nanoTime())

/** 앱17: the small notice of a review ("검토를 요청했습니다 (1건)", "검토 완료: 제안 2건") for its [MemoNotice.showMs]; a tap closes it. */
@Composable
internal fun MemoNoticeBanner(controller: MemoController, notice: MemoNotice) {
    LaunchedEffect(notice) {
        delay(notice.showMs)
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
        Text(notice.text, style = ElementTheme.typography.fontBodySmRegular, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
