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
    fun `the long press lasts 1 second`() {
        assertThat(JUNE_LONG_PRESS_MILLIS).isEqualTo(1_000L)
        assertThat(juneIsLongPress(0)).isFalse()
        assertThat(juneIsLongPress(999)).isFalse()
        assertThat(juneIsLongPress(1_000)).isTrue()
        assertThat(juneIsLongPress(1_001)).isTrue()
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
    fun `a press released at 999 ms is still a tap`() {
        handler.onDown()
        assertThat(handler.onHeld(999)).isFalse()
        handler.onRelease(heldMillis = 999)
        assertThat(clicks).isEqualTo(1)
        assertThat(longClicks).isEqualTo(0)
        assertThat(haptics).isEqualTo(0)
    }

    @Test
    fun `holding 1000 ms runs the long press once with one haptic tick, and the release does not click`() {
        handler.onDown()
        assertThat(handler.onHeld(1_000)).isTrue()
        assertThat(longClicks).isEqualTo(1)
        assertThat(haptics).isEqualTo(1)
        // Still holding: nothing more
        assertThat(handler.onHeld(1_500)).isTrue()
        handler.onRelease(heldMillis = 2_000)
        assertThat(clicks).isEqualTo(0)
        assertThat(longClicks).isEqualTo(1)
        assertThat(haptics).isEqualTo(1)
    }

    @Test
    fun `a release at exactly 1000 ms before the timer fired is a long press, not a tap`() {
        handler.onDown()
        handler.onRelease(heldMillis = 1_000)
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
        handler.onHeld(1_000)
        handler.onRelease(1_200)
        handler.onDown()
        handler.onRelease(100)
        handler.onDown()
        handler.onHeld(1_000)
        handler.onRelease(1_100)
        assertThat(clicks).isEqualTo(1)
        assertThat(longClicks).isEqualTo(2)
        assertThat(haptics).isEqualTo(2)
    }
}
