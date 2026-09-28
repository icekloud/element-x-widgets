/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import io.element.android.features.messages.impl.timeline.model.bubble.BubbleState
import io.element.android.features.messages.impl.timeline.model.bubble.BubbleStatePreviewParam
import io.element.android.libraries.core.extensions.to01
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.modifiers.onKeyboardContextMenuAction
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.text.toDp
import io.element.android.libraries.designsystem.text.toPx
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.messageFromMeBackground
import io.element.android.libraries.designsystem.theme.messageFromOtherBackground
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.testtags.testTag
import io.element.android.libraries.ui.utils.a11y.isTalkbackActive
import io.element.android.libraries.ui.utils.graphics.drawInLayer

private val BUBBLE_RADIUS = 12.dp
private val avatarRadius = AvatarSize.TimelineSender.dp / 2

private val MIN_BUBBLE_WIDTH = 80.dp

@Composable
fun MessageEventBubble(
    state: BubbleState,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    customBackgroundColor: Color? = null,
    borderColor: Color? = null,
    // Element June: make the bubble take the maximum allowed width
    fillWidth: Boolean = false,
    // Element June: square corners where batch pictures touch each other
    squareTop: Boolean = false,
    squareBottom: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val clickableModifier = if (isTalkbackActive()) {
        Modifier
    } else {
        Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                indication = ripple(),
                interactionSource = interactionSource
            )
            .onKeyboardContextMenuAction(onLongClick)
    }

    // Element June: no avatar cut-out, it would break the outline
    val cutTopStart = false
    // Ignore state.isHighlighted for now, we need a design decision on it.
    val backgroundBubbleColor by rememberUpdatedState(customBackgroundColor ?: MessageEventBubbleDefaults.backgroundBubbleColor(state.isMine))
    val bubbleShape = remember(state, squareTop, squareBottom) {
        if (squareTop || squareBottom) {
            val top = if (squareTop) 0.dp else BUBBLE_RADIUS
            val bottom = if (squareBottom) 0.dp else BUBBLE_RADIUS
            RoundedCornerShape(top, top, bottom, bottom)
        } else {
            MessageEventBubbleDefaults.shape(false, state.groupPosition, state.isMine)
        }
    }
    val radiusPx = (avatarRadius + SENDER_AVATAR_BORDER_WIDTH).toPx()
    val yOffsetPx = -(NEGATIVE_MARGIN_FOR_BUBBLE + avatarRadius).toPx()

    // Element June: no bubble background, a thin gray outline separates messages
    val updatedBorderColor by rememberUpdatedState(borderColor ?: ElementTheme.colors.borderInteractiveSecondary)
    BoxWithConstraints(
        modifier = modifier
            .drawWithCache {
                // Calculate the outline of the background and cache it
                val outline = bubbleShape.createOutline(size, layoutDirection, this)

                onDrawWithContent {
                    // Draw the contents in a layer to be able to clip them with the same outline
                    // For some reason, doing this clipping outside a layer messes up with the touch events
                    drawInLayer(
                        composingStrategy = CompositingStrategy.Offscreen,
                        outline = outline,
                        clip = true,
                    ) {
                        // Draw the background first, so that it's behind the content
                        drawRect(backgroundBubbleColor)

                        // Then draw the content on top of it
                        drawContent()

                        // Draw border color, if any
                        updatedBorderColor?.let { drawOutline(outline, it, style = Stroke(width = 1.dp.toPx())) }

                        // And then clip the top start corner if needed to make room for the avatar
                        if (cutTopStart) {
                            drawCircle(
                                color = Color.Black,
                                center = Offset(
                                    x = if (layoutDirection == LayoutDirection.Rtl) size.width else 0f,
                                    y = yOffsetPx,
                                ),
                                radius = radiusPx,
                                blendMode = BlendMode.Clear,
                            )
                        }
                    }
                }
            },
        // Need to set the contentAlignment again (it's already set in TimelineItemEventRow), for the case
        // when content width is low.
        contentAlignment = if (state.isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .testTag(TestTags.messageBubble)
                .widthIn(
                    min = MIN_BUBBLE_WIDTH,
                    // Element June: own messages keep the original width so they stay on the right
                    // Element June: a full-width bubble must use the whole outline width, otherwise the content stops ~3% short
                    // of the right border (wider right gap everywhere: text, reply box, file row)
                    max = (constraints.maxWidth * when {
                        state.isMine -> MessageEventBubbleDefaults.OWN_BUBBLE_WIDTH_RATIO
                        fillWidth -> 1f
                        else -> MessageEventBubbleDefaults.BUBBLE_WIDTH_RATIO
                    })
                        .toInt()
                        .toDp()
                )
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .then(clickableModifier),
            content = content,
        )
    }
}

object MessageEventBubbleDefaults {
    fun shape(cutTopStart: Boolean, groupPosition: TimelineItemGroupPosition, isMine: Boolean): Shape {
        // Element June: outline-only bubbles look best with all corners rounded, grouped or not
        if (true) return RoundedCornerShape(BUBBLE_RADIUS)
        val topLeftCorner = if (cutTopStart) 0.dp else BUBBLE_RADIUS
        return when (groupPosition) {
            TimelineItemGroupPosition.First -> if (isMine) {
                RoundedCornerShape(BUBBLE_RADIUS, BUBBLE_RADIUS, 0.dp, BUBBLE_RADIUS)
            } else {
                RoundedCornerShape(topLeftCorner, BUBBLE_RADIUS, BUBBLE_RADIUS, 0.dp)
            }
            TimelineItemGroupPosition.Middle -> if (isMine) {
                RoundedCornerShape(BUBBLE_RADIUS, 0.dp, 0.dp, BUBBLE_RADIUS)
            } else {
                RoundedCornerShape(0.dp, BUBBLE_RADIUS, BUBBLE_RADIUS, 0.dp)
            }
            TimelineItemGroupPosition.Last -> if (isMine) {
                RoundedCornerShape(BUBBLE_RADIUS, 0.dp, BUBBLE_RADIUS, BUBBLE_RADIUS)
            } else {
                RoundedCornerShape(0.dp, BUBBLE_RADIUS, BUBBLE_RADIUS, BUBBLE_RADIUS)
            }
            TimelineItemGroupPosition.None ->
                RoundedCornerShape(
                    topLeftCorner,
                    BUBBLE_RADIUS,
                    BUBBLE_RADIUS,
                    BUBBLE_RADIUS
                )
        }
    }

    @Composable
    fun backgroundBubbleColor(isMine: Boolean): Color {
        // Element June: transparent bubbles (outline only)
        return Color.Transparent
    }

    // Design says: The maximum width of a bubble is still 3/4 of the screen width. But try with 78% now.
    // Element June: use (almost) the full width for message bubbles
    const val BUBBLE_WIDTH_RATIO = 0.97f
    const val OWN_BUBBLE_WIDTH_RATIO = 0.78f
}

@PreviewsDayNight
@Composable
internal fun MessageEventBubblePreview(@PreviewParameter(BubbleStatePreviewParam::class) state: BubbleState) = ElementPreview {
    // Due to position offset, surround with a Box
    Box(
        modifier = Modifier
            .size(width = 240.dp, height = 64.dp)
            .padding(vertical = 8.dp),
        contentAlignment = if (state.isMine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        MessageEventBubble(
            state = state,
            interactionSource = remember { MutableInteractionSource() },
            onClick = {},
            onLongClick = {},
        ) {
            // Render the state as a text to better understand the previews
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = 32.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${state.groupPosition.javaClass.simpleName} isMine:${state.isMine.to01()}",
                    style = ElementTheme.typography.fontBodyXsRegular,
                )
            }
        }
    }
}
