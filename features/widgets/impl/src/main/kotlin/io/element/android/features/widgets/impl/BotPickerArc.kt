/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Element June: where the bot picker puts its [n] nodes around the centre, in pixels of the picker window. Moved out
 * of BotPickerActivity unchanged (앱16 A) so the places can be told apart by their height on screen in plain tests.
 */
data class BotPickerArc(
    val radius: Float,
    val angles: List<Float>,
    val start: Float,
    val sweep: Float,
    val full: Boolean,
)

/**
 * Put [n] nodes on one arc around the anchor ([anchorX], [anchorY]), only where the nodes fit on screen, so they never
 * overlap or get pushed against the screen edge.
 */
fun botPickerArc(
    w: Float,
    h: Float,
    anchorX: Float,
    anchorY: Float,
    n: Int,
    radiusDp: Float,
    px: (Float) -> Float,
): BotPickerArc {
    val margin = px(46f)
    val spacing = px(84f)
    val maxSweep = (150.0 * PI / 180.0).toFloat()
    val steps = 180
    val step = (2 * PI / steps).toFloat()
    fun fits(a: Float, r: Float): Boolean {
        val x = anchorX + cos(a) * r
        val y = anchorY + sin(a) * r
        return x in margin..w - margin && y in margin..h - margin - px(14f)
    }
    val base = atan2(h / 2 - anchorY, w / 2 - anchorX)
    var r = px(radiusDp)
    var start = base
    var sweep = 0f
    var full = false
    repeat(5) {
        val flags = BooleanArray(steps) { fits(it * step, r) }
        if (flags.all { it }) {
            full = true
            start = base
            sweep = (2 * PI).toFloat()
        } else {
            full = false
            val s0 = flags.indexOfFirst { !it }
            var best = 0
            var bestStart = 0
            var cur = 0
            var curStart = 0
            for (k in 1..steps) {
                val i = (s0 + k) % steps
                if (flags[i]) {
                    if (cur == 0) curStart = i
                    cur++
                    if (cur > best) {
                        best = cur
                        bestStart = curStart
                    }
                } else {
                    cur = 0
                }
            }
            val runLen = max(0, best - 1) * step
            sweep = min(runLen, maxSweep)
            start = bestStart * step + (runLen - sweep) / 2
        }
        val gaps = if (full) n else max(1, n - 1)
        val needed = if (sweep > 0.01f) gaps * spacing / sweep else r * 2
        if (needed <= r || n <= 1) return@repeat
        r = min(needed, min(w, h) * 0.95f)
    }
    val angles = List(n) { i ->
        when {
            full -> start + (2 * PI).toFloat() * i / n
            n <= 1 -> start + sweep / 2
            else -> start + sweep * i / (n - 1)
        }
    }
    return BotPickerArc(r, angles, start, sweep, full)
}

/** The node centres of [arc] around the anchor, as (x, y) pixels. */
fun botPickerArcCenters(arc: BotPickerArc, anchorX: Float, anchorY: Float): List<Pair<Float, Float>> =
    arc.angles.map { a -> (anchorX + cos(a) * arc.radius) to (anchorY + sin(a) * arc.radius) }

/**
 * 앱16 A (관리자: "위에서부터는 지금 화면 기준 위에서부터"): the node indexes from the top of the screen to the bottom,
 * by the drawn centres: a smaller y is higher; on the same height (whole pixels) the left one comes first.
 * The arc turns with the place of the icon, so the order number alone never tells which node is up.
 */
fun botPickerScreenOrder(centers: List<Pair<Float, Float>>): List<Int> =
    centers.indices.sortedWith(
        compareBy<Int> { centers[it].second.roundToInt() }.thenBy { centers[it].first }.thenBy { it },
    )
