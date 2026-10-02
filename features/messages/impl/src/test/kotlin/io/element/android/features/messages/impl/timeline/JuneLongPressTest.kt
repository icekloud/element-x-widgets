/*
 * Copyright (c) 2026 Element June.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JuneLongPressTest {
    private var clicks = 0
    private var longClicks = 0
    private var haptics = 0
    private val handler = JuneLongPressHandler(
        onClick = { clicks++ },
        onLongClick = { longClicks++ },
        onLongPressReached = { haptics++ },
    )

    @Test
    fun `the long press lasts half a second`() {
        assertThat(JUNE_LONG_PRESS_MILLIS).isEqualTo(500L)
        assertThat(juneIsLongPress(0)).isFalse()
        assertThat(juneIsLongPress(499)).isFalse()
        assertThat(juneIsLongPress(500)).isTrue()
        assertThat(juneIsLongPress(501)).isTrue()
    }

    @Test
    fun `a short tap runs onClick only`() {
        handler.onDown()
        handler.onRelease(heldMillis = 80)
        assertThat(clicks).isEqualTo(1)
        assertThat(longClicks).isEqualTo(0)
        assertThat(haptics).isEqualTo(0)
    }

    @Test
    fun `a press released at 499 ms is still a tap`() {
        handler.onDown()
        assertThat(handler.onHeld(499)).isFalse()
        handler.onRelease(heldMillis = 499)
        assertThat(clicks).isEqualTo(1)
        assertThat(longClicks).isEqualTo(0)
        assertThat(haptics).isEqualTo(0)
    }

    @Test
    fun `holding 500 ms runs the long press once with one haptic tick, and the release does not click`() {
        handler.onDown()
        assertThat(handler.onHeld(500)).isTrue()
        assertThat(longClicks).isEqualTo(1)
        assertThat(haptics).isEqualTo(1)
        // Still holding: nothing more
        assertThat(handler.onHeld(750)).isTrue()
        handler.onRelease(heldMillis = 1_000)
        assertThat(clicks).isEqualTo(0)
        assertThat(longClicks).isEqualTo(1)
        assertThat(haptics).isEqualTo(1)
    }

    @Test
    fun `a release at exactly 500 ms before the timer fired is a long press, not a tap`() {
        handler.onDown()
        handler.onRelease(heldMillis = 500)
        assertThat(clicks).isEqualTo(0)
        assertThat(longClicks).isEqualTo(1)
        assertThat(haptics).isEqualTo(1)
    }

    @Test
    fun `a cancelled press runs nothing`() {
        handler.onDown()
        handler.onCancel()
        assertThat(clicks).isEqualTo(0)
        assertThat(longClicks).isEqualTo(0)
        assertThat(haptics).isEqualTo(0)
    }

    @Test
    fun `each press is decided on its own`() {
        handler.onDown()
        handler.onHeld(500)
        handler.onRelease(600)
        handler.onDown()
        handler.onRelease(100)
        handler.onDown()
        handler.onHeld(500)
        handler.onRelease(550)
        assertThat(clicks).isEqualTo(1)
        assertThat(longClicks).isEqualTo(2)
        assertThat(haptics).isEqualTo(2)
    }
}
