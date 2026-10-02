/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JuneSwipeSteerTest {
    private fun item(kind: String) = JuneQueueItem("\$q", listOf("\$q"), kind, editable = true, edited = false)

    @Test
    fun `text and picture messages can be steered, other files cannot`() {
        assertThat(juneCanSteer(item("text"))).isTrue()
        assertThat(juneCanSteer(item("photo"))).isTrue()
        assertThat(juneCanSteer(item("file"))).isFalse()
        assertThat(juneCanSteer(item("video"))).isFalse()
    }

    @Test
    fun `a row being edited, waiting for a request or not steerable cannot be pushed`() {
        assertThat(juneCanSwipeSteer(item("text"), editing = false, busy = false)).isTrue()
        assertThat(juneCanSwipeSteer(item("photo"), editing = false, busy = false)).isTrue()
        assertThat(juneCanSwipeSteer(item("text"), editing = true, busy = false)).isFalse()
        assertThat(juneCanSwipeSteer(item("text"), editing = false, busy = true)).isFalse()
        assertThat(juneCanSwipeSteer(item("file"), editing = false, busy = false)).isFalse()
    }

    @Test
    fun `the default threshold is 40 percent of the row width, inclusive`() {
        assertThat(juneSwipeReached(offsetPx = -399f, widthPx = 1000f, fraction = 0.4f)).isFalse()
        assertThat(juneSwipeReached(offsetPx = -400f, widthPx = 1000f, fraction = 0.4f)).isTrue()
        assertThat(juneSwipeReached(offsetPx = -1000f, widthPx = 1000f, fraction = 0.4f)).isTrue()
    }

    @Test
    fun `the threshold follows the setting, inclusive at both ends of its range`() {
        // 20 %, the most sensitive
        assertThat(juneSwipeReached(offsetPx = -199f, widthPx = 1000f, fraction = 0.2f)).isFalse()
        assertThat(juneSwipeReached(offsetPx = -200f, widthPx = 1000f, fraction = 0.2f)).isTrue()
        // 70 %, the least sensitive
        assertThat(juneSwipeReached(offsetPx = -699f, widthPx = 1000f, fraction = 0.7f)).isFalse()
        assertThat(juneSwipeReached(offsetPx = -700f, widthPx = 1000f, fraction = 0.7f)).isTrue()
        // In between
        assertThat(juneSwipeReached(offsetPx = -549f, widthPx = 1000f, fraction = 0.55f)).isFalse()
        assertThat(juneSwipeReached(offsetPx = -550f, widthPx = 1000f, fraction = 0.55f)).isTrue()
    }

    @Test
    fun `a threshold outside 20 to 70 percent falls back to 40 percent`() {
        for (bad in listOf(0f, 0.19f, 0.71f, 1f, -0.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThat(juneSwipeFraction(bad)).isEqualTo(0.4f)
            assertThat(juneSwipeReached(offsetPx = -399f, widthPx = 1000f, fraction = bad)).isFalse()
            assertThat(juneSwipeReached(offsetPx = -400f, widthPx = 1000f, fraction = bad)).isTrue()
        }
        assertThat(juneSwipeFraction(0.2f)).isEqualTo(0.2f)
        assertThat(juneSwipeFraction(0.7f)).isEqualTo(0.7f)
    }

    @Test
    fun `only a push to the left counts, and nothing before the row is measured`() {
        assertThat(juneSwipeReached(offsetPx = 400f, widthPx = 1000f, fraction = 0.4f)).isFalse()
        assertThat(juneSwipeReached(offsetPx = 0f, widthPx = 1000f, fraction = 0.2f)).isFalse()
        assertThat(juneSwipeReached(offsetPx = -400f, widthPx = 0f, fraction = 0.4f)).isFalse()
    }

    @Test
    fun `the handler uses the threshold it was given`() {
        var haptics = 0
        val handler = JuneSwipeSteerHandler { haptics++ }.apply {
            widthPx = 1000f
            fraction = 0.2f
        }
        handler.onDrag(-199f)
        assertThat(handler.isReached).isFalse()
        handler.onDrag(-1f)
        assertThat(handler.isReached).isTrue()
        assertThat(haptics).isEqualTo(1)
        assertThat(handler.onRelease(allowed = true)).isTrue()
        handler.onSettled()
        handler.moveTo(0f)
        // The setting changed to 70 %: 50 % is not enough anymore
        handler.fraction = 0.7f
        handler.onDrag(-500f)
        assertThat(handler.isReached).isFalse()
        assertThat(handler.onRelease(allowed = true)).isFalse()
    }

    @Test
    fun `the row moves only to the left, at most its width`() {
        assertThat(juneSwipeOffset(current = 0f, delta = 50f, widthPx = 1000f)).isEqualTo(0f)
        assertThat(juneSwipeOffset(current = -100f, delta = 30f, widthPx = 1000f)).isEqualTo(-70f)
        assertThat(juneSwipeOffset(current = -100f, delta = 200f, widthPx = 1000f)).isEqualTo(0f)
        assertThat(juneSwipeOffset(current = -900f, delta = -500f, widthPx = 1000f)).isEqualTo(-1000f)
        assertThat(juneSwipeOffset(current = 0f, delta = -50f, widthPx = 0f)).isEqualTo(0f)
    }

    @Test
    fun `haptic feedback runs once when the threshold is crossed, and again after coming back`() {
        var haptics = 0
        val handler = JuneSwipeSteerHandler { haptics++ }.apply { widthPx = 1000f }
        handler.onDrag(-399f)
        assertThat(haptics).isEqualTo(0)
        handler.onDrag(-1f)
        assertThat(haptics).isEqualTo(1)
        handler.onDrag(-300f)
        assertThat(haptics).isEqualTo(1)
        handler.onDrag(600f)
        assertThat(handler.isReached).isFalse()
        handler.onDrag(-500f)
        assertThat(haptics).isEqualTo(2)
    }

    @Test
    fun `letting go far enough steers once, short of it does not`() {
        val handler = JuneSwipeSteerHandler {}.apply { widthPx = 1000f }
        handler.onDrag(-399f)
        assertThat(handler.onRelease(allowed = true)).isFalse()
        assertThat(handler.isSent).isFalse()
        handler.onDrag(-1f)
        assertThat(handler.onRelease(allowed = true)).isTrue()
        assertThat(handler.isSent).isTrue()
        // Already sent: a second release does not send again
        assertThat(handler.onRelease(allowed = true)).isFalse()
    }

    @Test
    fun `letting go far enough does nothing once the row became blocked`() {
        val handler = JuneSwipeSteerHandler {}.apply { widthPx = 1000f }
        handler.onDrag(-800f)
        assertThat(handler.onRelease(allowed = false)).isFalse()
        assertThat(handler.isSent).isFalse()
    }

    @Test
    fun `once the gateway answered, the row can be pushed again`() {
        val handler = JuneSwipeSteerHandler {}.apply { widthPx = 1000f }
        handler.onDrag(-500f)
        assertThat(handler.onRelease(allowed = true)).isTrue()
        handler.onSettled()
        assertThat(handler.isSent).isFalse()
        handler.onDrag(-100f)
        assertThat(handler.onRelease(allowed = true)).isTrue()
    }

    @Test
    fun `the accessibility action sends once`() {
        val handler = JuneSwipeSteerHandler {}
        assertThat(handler.sendNow()).isTrue()
        assertThat(handler.sendNow()).isFalse()
        handler.onSettled()
        assertThat(handler.sendNow()).isTrue()
    }

    @Test
    fun `the hint behind the row tells what letting go does`() {
        assertThat(juneSwipeHint(reached = false, sent = false)).isEqualTo("⏩ 밀어서 스티어링")
        assertThat(juneSwipeHint(reached = true, sent = false)).isEqualTo("⏩ 놓으면 스티어링")
        assertThat(juneSwipeHint(reached = true, sent = true)).isEqualTo("⏩ 스티어링 보냄")
    }
}
