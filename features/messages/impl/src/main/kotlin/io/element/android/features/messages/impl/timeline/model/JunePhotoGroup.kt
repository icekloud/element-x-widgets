/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.model

import androidx.compose.runtime.Immutable

/**
 * Element June: where a picture sits in a run of pictures drawn as one message bubble (see juneGroupPhotos).
 * Only the drawing is shared: every picture stays its own event.
 *
 * @param index 0 for the oldest picture, the top of the bubble.
 * @param size number of pictures in the run, at least 2.
 */
@Immutable
data class JunePhotoGroup(
    val index: Int,
    val size: Int,
) {
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == size - 1
}
