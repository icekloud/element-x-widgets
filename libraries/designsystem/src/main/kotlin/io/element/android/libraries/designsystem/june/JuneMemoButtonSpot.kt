/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.june

/**
 * Element June (앱19 추가요청 1): where the memo button of the home screen sits, as parts (0..1) of the room it can move
 * in: [x] 0 = left, 1 = right; [y] 0 = top, 1 = bottom. The room is the safe area less the button itself, so any saved
 * spot stays on the screen when it turns or changes size. [DEFAULT] is the bottom centre (the place it always had).
 */
data class JuneMemoButtonSpot(val x: Float, val y: Float) {
    /** The saved text: "x,y". */
    fun encode(): String = "$x,$y"

    /** One step of the screen reader actions: moved by [dx], [dy] (parts of the room), kept inside. */
    fun nudge(dx: Float, dy: Float): JuneMemoButtonSpot = JuneMemoButtonSpot((x + dx).coerceIn(0f, 1f), (y + dy).coerceIn(0f, 1f))

    /** The left and top of the button in pixels, for a room of [freeWidth] x [freeHeight] pixels. */
    fun left(freeWidth: Float): Float = x * freeWidth.coerceAtLeast(0f)

    fun top(freeHeight: Float): Float = y * freeHeight.coerceAtLeast(0f)

    companion object {
        val DEFAULT = JuneMemoButtonSpot(0.5f, 1f)

        /** The screen reader moves the button by a tenth of the room per action. */
        const val STEP = 0.1f

        /** A saved [raw] spot, or null (= the default place) when there is none or it is broken (not two numbers in 0..1). */
        fun parse(raw: String?): JuneMemoButtonSpot? {
            val parts = raw?.split(',') ?: return null
            if (parts.size != 2) return null
            val x = parts[0].trim().toFloatOrNull() ?: return null
            val y = parts[1].trim().toFloatOrNull() ?: return null
            if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
            return JuneMemoButtonSpot(x, y)
        }

        /**
         * The spot of a button dropped with its left and top at [left], [top] (pixels): kept in the room, and put against
         * an edge when it was dropped within [snap] pixels of it. A room of no size keeps the default part.
         */
        fun dropped(left: Float, top: Float, freeWidth: Float, freeHeight: Float, snap: Float): JuneMemoButtonSpot = JuneMemoButtonSpot(
            part(snapToEdge(left, freeWidth, snap), freeWidth, DEFAULT.x),
            part(snapToEdge(top, freeHeight, snap), freeHeight, DEFAULT.y),
        )

        /** [px] kept between 0 and [free] (the button may not leave the safe area). */
        fun keepIn(px: Float, free: Float): Float = if (px.isNaN()) 0f else px.coerceIn(0f, free.coerceAtLeast(0f))

        /** [px] kept in the room, then against the edge when it is within [snap] of it (no pull from further away). */
        fun snapToEdge(px: Float, free: Float, snap: Float): Float {
            val room = free.coerceAtLeast(0f)
            val kept = keepIn(px, room)
            return when {
                kept <= snap -> 0f
                room - kept <= snap -> room
                else -> kept
            }
        }

        private fun part(px: Float, free: Float, fallback: Float): Float = if (free <= 0f) fallback else (px / free).coerceIn(0f, 1f)
    }
}
