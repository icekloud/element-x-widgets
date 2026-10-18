/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.june

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** 앱19 추가요청 1·2: the spot of the home memo button (parts ↔ pixels, kept in the safe area, edges, saved value, reset). */
class JuneMemoButtonSpotTest : RobolectricTest() {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        JuneSettings.resetMemoButtonSpot(context)
    }

    @After
    fun tearDown() {
        JuneSettings.resetMemoButtonSpot(context)
    }

    @Test
    fun `the default spot is the bottom centre`() {
        assertThat(JuneMemoButtonSpot.DEFAULT).isEqualTo(JuneMemoButtonSpot(0.5f, 1f))
        assertThat(JuneMemoButtonSpot.DEFAULT.left(800f)).isEqualTo(400f)
        assertThat(JuneMemoButtonSpot.DEFAULT.top(1600f)).isEqualTo(1600f)
    }

    @Test
    fun `parts become pixels of the room and back`() {
        val spot = JuneMemoButtonSpot(0.25f, 0.5f)
        assertThat(spot.left(800f)).isEqualTo(200f)
        assertThat(spot.top(1000f)).isEqualTo(500f)
        assertThat(JuneMemoButtonSpot.dropped(200f, 500f, 800f, 1000f, snap = 10f)).isEqualTo(spot)
    }

    @Test
    fun `the same parts stay on the screen when it turns`() {
        val spot = JuneMemoButtonSpot(1f, 1f)
        // portrait, then landscape: always at the far corner of the room, never past it
        assertThat(spot.left(600f)).isEqualTo(600f)
        assertThat(spot.left(1500f)).isEqualTo(1500f)
        assertThat(spot.top(400f)).isEqualTo(400f)
    }

    @Test
    fun `a drop outside the safe area is kept inside`() {
        assertThat(JuneMemoButtonSpot.dropped(-50f, 5000f, 800f, 1000f, snap = 0f)).isEqualTo(JuneMemoButtonSpot(0f, 1f))
        assertThat(JuneMemoButtonSpot.keepIn(-3f, 100f)).isEqualTo(0f)
        assertThat(JuneMemoButtonSpot.keepIn(130f, 100f)).isEqualTo(100f)
        assertThat(JuneMemoButtonSpot.keepIn(40f, 100f)).isEqualTo(40f)
        assertThat(JuneMemoButtonSpot.keepIn(Float.NaN, 100f)).isEqualTo(0f)
    }

    @Test
    fun `a drop near an edge is put against it, further away it stays`() {
        assertThat(JuneMemoButtonSpot.snapToEdge(20f, 800f, snap = 24f)).isEqualTo(0f)
        assertThat(JuneMemoButtonSpot.snapToEdge(780f, 800f, snap = 24f)).isEqualTo(800f)
        assertThat(JuneMemoButtonSpot.snapToEdge(30f, 800f, snap = 24f)).isEqualTo(30f)
        assertThat(JuneMemoButtonSpot.snapToEdge(400f, 800f, snap = 24f)).isEqualTo(400f)
    }

    @Test
    fun `a room of no size keeps the default parts`() {
        assertThat(JuneMemoButtonSpot.dropped(10f, 10f, 0f, 0f, snap = 0f)).isEqualTo(JuneMemoButtonSpot.DEFAULT)
    }

    @Test
    fun `a broken saved value is the default place`() {
        listOf(null, "", "x", "0.5", "0.5,0.5,0.5", "a,b", "1.5,0.5", "0.5,-0.1", "NaN,0.5", "Infinity,1").forEach {
            assertThat(JuneMemoButtonSpot.parse(it)).isNull()
        }
        assertThat(JuneMemoButtonSpot.parse("0.2,0.8")).isEqualTo(JuneMemoButtonSpot(0.2f, 0.8f))
        assertThat(JuneMemoButtonSpot.parse(JuneMemoButtonSpot(0.3f, 0.7f).encode())).isEqualTo(JuneMemoButtonSpot(0.3f, 0.7f))
    }

    @Test
    fun `a screen reader step moves a tenth and never leaves the room`() {
        assertThat(JuneMemoButtonSpot(0.5f, 0.5f).nudge(0f, -JuneMemoButtonSpot.STEP)).isEqualTo(JuneMemoButtonSpot(0.5f, 0.4f))
        assertThat(JuneMemoButtonSpot.DEFAULT.nudge(0f, JuneMemoButtonSpot.STEP)).isEqualTo(JuneMemoButtonSpot.DEFAULT)
        assertThat(JuneMemoButtonSpot(0f, 0f).nudge(-JuneMemoButtonSpot.STEP, 0f)).isEqualTo(JuneMemoButtonSpot(0f, 0f))
    }

    @Test
    fun `the spot is saved, read back after a restart and reset to the default`() {
        assertThat(JuneSettings.memoButtonSpot(context)).isNull()
        JuneSettings.setMemoButtonSpot(context, JuneMemoButtonSpot(0.1f, 0.2f))
        assertThat(JuneSettings.memoButtonSpot(context)).isEqualTo(JuneMemoButtonSpot(0.1f, 0.2f))
        val saved = context.getSharedPreferences("june_settings", Context.MODE_PRIVATE).all.values.map { it.toString() }
        assertThat(saved).contains("0.1,0.2")

        JuneSettings.resetMemoButtonSpot(context)
        assertThat(JuneSettings.memoButtonSpot(context)).isNull()
        val after = context.getSharedPreferences("june_settings", Context.MODE_PRIVATE).all.values.map { it.toString() }
        assertThat(after).doesNotContain("0.1,0.2")
    }

    @Test
    fun `a saved spot outside the room is kept inside`() {
        JuneSettings.setMemoButtonSpot(context, JuneMemoButtonSpot(1.4f, -2f))
        assertThat(JuneSettings.memoButtonSpot(context)).isEqualTo(JuneMemoButtonSpot(1f, 0f))
    }
}
