/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.components.ATimelineItemEventRow
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContentPreviewParam
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.protection.ProtectedView
import io.element.android.features.messages.impl.timeline.protection.coerceRatioWhenHidingContent
import io.element.android.features.messages.impl.timeline.util.handleAsyncImageStateChange
import io.element.android.libraries.designsystem.components.blurhash.blurHashBackground
import io.element.android.libraries.designsystem.modifiers.onKeyboardContextMenuAction
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.matrix.ui.media.contentvalidation.ContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.LocalEventContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.NoopContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.NoopEventContentValidationCache
import io.element.android.libraries.matrix.ui.media.contentvalidation.collectOverallState
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.a11y.isTalkbackActive

@Composable
fun TimelineItemImageView(
    content: TimelineItemImageContent,
    hideMediaContent: Boolean,
    onContentClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onShowContentClick: () -> Unit,
    contentValidationState: ContentValidationState,
    modifier: Modifier = Modifier,
) {
    val a11yLabel = stringResource(CommonStrings.common_image)
    val description = content.caption?.let { "$a11yLabel: $it" } ?: a11yLabel
    // Element June: every picture of a batch gets the same width so the stack looks like one block,
    // whatever its own aspect ratio and whether it carries the caption
    val batchSize = content.juneBatchPosition()?.let {
        val ratio = (content.aspectRatio ?: DEFAULT_ASPECT_RATIO).coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
        DpSize(JUNE_BATCH_WIDTH_DP.dp, (JUNE_BATCH_WIDTH_DP / ratio).coerceIn(MIN_HEIGHT_IN_DP.toFloat(), MAX_HEIGHT_IN_DP / 2f).dp)
    }
    Column(
        modifier = modifier
            .then(if (batchSize != null) Modifier.width(batchSize.width) else Modifier)
            .wrapContentWidth(Alignment.CenterHorizontally)
    ) {
        val containerModifier = if (content.showCaption) {
            Modifier.clip(RoundedCornerShape(10.dp))
        } else {
            Modifier
        }

        val eventContentValidation by contentValidationState.collectOverallState()
        val isContentBeingValidated = !eventContentValidation.isValidated()
        TimelineItemAspectRatioBox(
            modifier = containerModifier
                .then(if (batchSize != null) Modifier.size(batchSize) else Modifier)
                .blurHashBackground(content.blurhash, alpha = 0.9f)
                .align(Alignment.CenterHorizontally),
            aspectRatio = coerceRatioWhenHidingContent(content.aspectRatio, hideMediaContent),
            // Element June: pictures sent together are shown at half height, stacked
            maxHeight = if (content.juneBatchPosition() != null) MAX_HEIGHT_IN_DP / 2 else MAX_HEIGHT_IN_DP,
        ) {
            if (isContentBeingValidated) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                ProtectedView(
                    hideContent = hideMediaContent,
                    onShowClick = onShowContentClick,
                ) {
                    var isLoaded by remember { mutableStateOf(false) }
                    AsyncImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isLoaded) Modifier.background(Color.White) else Modifier)
                            .then(
                                if (!isTalkbackActive() && onContentClick != null) {
                                    Modifier
                                        .combinedClickable(
                                            onClick = onContentClick,
                                            onLongClick = onLongClick,
                                        )
                                        .onKeyboardContextMenuAction(onLongClick)
                                } else {
                                    Modifier
                                }
                            ),
                        model = content.thumbnailMediaRequestData,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        contentDescription = description,
                        onState = { state ->
                            val url = content.thumbnailMediaRequestData.source?.safeUrl
                            if (url != null) {
                                handleAsyncImageStateChange(
                                    state = state,
                                    onLoaded = { isLoaded = true },
                                    updateContentValidationState = { contentValidationState.update(url, it) },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineItemImageViewPreview(@PreviewParameter(TimelineItemImageContentPreviewParam::class) content: TimelineItemImageContent) = ElementPreview {
    TimelineItemImageView(
        content = content,
        hideMediaContent = false,
        onShowContentClick = {},
        onContentClick = {},
        onLongClick = {},
        contentValidationState = NoopContentValidationState(),
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemImageViewHideMediaContentPreview() = ElementPreview {
        TimelineItemImageView(
            content = aTimelineItemImageContent(),
            hideMediaContent = true,
            onShowContentClick = {},
            onContentClick = {},
            onLongClick = {},
            contentValidationState = NoopContentValidationState(),
        )
}

@PreviewsDayNight
@Composable
internal fun ATimelineItemEventRowPreview() = ElementPreview {
    CompositionLocalProvider(LocalEventContentValidationState provides NoopEventContentValidationCache()) {
        Column {
            sequenceOf(false, true).forEach { isMine ->
                ATimelineItemEventRow(
                    event = aTimelineItemEvent(
                        isMine = isMine,
                        content = aTimelineItemImageContent(
                            filename = "image.jpg",
                            caption = "A long caption that may wrap into several lines",
                            width = 40,
                            height = 20,
                            aspectRatio = 40f / 20f,
                        ),
                        groupPosition = TimelineItemGroupPosition.Last,
                    ),
                )
            }
            ATimelineItemEventRow(
                event = aTimelineItemEvent(
                    isMine = false,
                    content = aTimelineItemImageContent(
                        filename = "image.jpg",
                        caption = "Narrow image with null aspectRatio",
                        width = 80,
                        height = 150,
                        aspectRatio = null,
                    ),
                    groupPosition = TimelineItemGroupPosition.Last,
                ),
            )
        }
    }
}

private const val JUNE_BATCH_WIDTH_DP = 260f

private val JUNE_BATCH_FILENAME = Regex("^june-[0-9a-f]{6,32}-(\\d{1,3})of(\\d{1,3})\\.[A-Za-z0-9]{1,5}$")

/**
 * Element June: (index, total) when this picture was sent as part of a multi-picture batch (june-<id>-<i>of<n> file name).
 */
internal fun TimelineItemImageContent.juneBatchPosition(): Pair<Int, Int>? {
    val match = JUNE_BATCH_FILENAME.matchEntire(filename) ?: return null
    val index = match.groupValues[1].toInt()
    val total = match.groupValues[2].toInt()
    return if (total >= 2 && index in 1..total) index to total else null
}
