/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Height the area above the composer keeps even when the composer alone fills the sheet: one row, so the panels stay reachable. */
private val TOP_PEEK_HEIGHT = 48.dp

/**
 * Element June: maximum height of the area above the composer (identity banner, background and queue panels).
 *
 * The composer gets the height it needs first ([bottomMinHeight]); the area above gets what is left of [availableHeight],
 * but never less than [peekHeight] (or its own height when that is smaller). [Constraints.Infinity] means not bounded.
 */
internal fun juneTopAreaMaxHeight(
    availableHeight: Int,
    topNaturalHeight: Int,
    bottomMinHeight: Int,
    peekHeight: Int,
): Int {
    val natural = topNaturalHeight.coerceAtLeast(0)
    if (availableHeight == Constraints.Infinity) return natural
    val left = availableHeight - bottomMinHeight.coerceAtLeast(0)
    return minOf(natural, maxOf(left, minOf(peekHeight, natural))).coerceAtLeast(0)
}

/**
 * Element June: lays out what sits above the composer ([top]) and the composer itself ([bottom]).
 *
 * A plain Column measures its children from the top. When the identity banner, the background and queue panels and the
 * pending pictures together needed more than the composer sheet may use (half of the height left above the keyboard), the
 * composer at the bottom only got the rest and was cut off. Here the composer is given its minimum height first and [top]
 * gets the remaining height, scrolling inside when it does not fit.
 */
@Composable
internal fun JuneComposerStack(
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) { top() }
            Column { bottom() }
        },
        measurePolicy = JuneComposerStackMeasurePolicy,
    )
}

private object JuneComposerStackMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val (topMeasurable, bottomMeasurable) = measurables
        val width = constraints.maxWidth
        val topMax = juneTopAreaMaxHeight(
            availableHeight = constraints.maxHeight,
            topNaturalHeight = topMeasurable.minIntrinsicHeight(width),
            bottomMinHeight = bottomMeasurable.minIntrinsicHeight(width),
            peekHeight = TOP_PEEK_HEIGHT.roundToPx(),
        )
        val topPlaceable = topMeasurable.measure(constraints.copy(minHeight = 0, maxHeight = topMax))
        val bottomMax = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - topPlaceable.height).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        val bottomPlaceable = bottomMeasurable.measure(constraints.copy(minHeight = 0, maxHeight = bottomMax))
        val layoutWidth = constraints.constrainWidth(maxOf(topPlaceable.width, bottomPlaceable.width))
        val layoutHeight = constraints.constrainHeight(topPlaceable.height + bottomPlaceable.height)
        // Placed one under the other like the Column this replaces.
        return layout(layoutWidth, layoutHeight) {
            topPlaceable.place(0, 0)
            bottomPlaceable.place(0, topPlaceable.height)
        }
    }

    // The composer sheet sizes itself from its content's minimum height: report the whole stack, like the Column it replaces.
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.sumOf { it.minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.sumOf { it.maxIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.maxIntrinsicWidth(height) } ?: 0
}
