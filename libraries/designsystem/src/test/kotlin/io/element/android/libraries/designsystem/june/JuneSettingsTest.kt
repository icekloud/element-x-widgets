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
import org.junit.Before
import org.junit.Test

class JuneSettingsTest : RobolectricTest() {
    private val setting = JuneSettings.SWIPE_STEER_PERCENT
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs().edit().clear().commit()
        JuneSettings.resetInt(context, setting)
    }

    private fun prefs() = context.getSharedPreferences("june_settings", Context.MODE_PRIVATE)

    @Test
    fun `the swipe to steer setting goes from 20 to 70 percent, 40 by default, by 5`() {
        assertThat(setting.default).isEqualTo(40)
        assertThat(setting.min).isEqualTo(20)
        assertThat(setting.max).isEqualTo(70)
        assertThat(setting.step).isEqualTo(5)
        assertThat(setting.sliderSteps).isEqualTo(9)
    }

    @Test
    fun `a saved value in the range is kept, ends included`() {
        assertThat(setting.sanitize(20)).isEqualTo(20)
        assertThat(setting.sanitize(55)).isEqualTo(55)
        assertThat(setting.sanitize(70)).isEqualTo(70)
    }

    @Test
    fun `a missing or out of range saved value gives the default`() {
        assertThat(setting.sanitize(null)).isEqualTo(40)
        assertThat(setting.sanitize(19)).isEqualTo(40)
        assertThat(setting.sanitize(71)).isEqualTo(40)
        assertThat(setting.sanitize(0)).isEqualTo(40)
        assertThat(setting.sanitize(-5)).isEqualTo(40)
        assertThat(setting.sanitize(Int.MAX_VALUE)).isEqualTo(40)
    }

    @Test
    fun `a picked value is rounded to the step and kept in the range`() {
        assertThat(setting.clamp(42f)).isEqualTo(40)
        assertThat(setting.clamp(43f)).isEqualTo(45)
        assertThat(setting.clamp(10f)).isEqualTo(20)
        assertThat(setting.clamp(100f)).isEqualTo(70)
        assertThat(setting.clamp(Float.NaN)).isEqualTo(40)
        assertThat(setting.clamp(Float.NEGATIVE_INFINITY)).isEqualTo(20)
        assertThat(setting.clamp(Float.POSITIVE_INFINITY)).isEqualTo(70)
    }

    @Test
    fun `slider positions map to the range and back`() {
        assertThat(setting.toSlider(20)).isEqualTo(0f)
        assertThat(setting.toSlider(70)).isEqualTo(1f)
        assertThat(setting.toSlider(45)).isEqualTo(0.5f)
        assertThat(setting.fromSlider(0f)).isEqualTo(20)
        assertThat(setting.fromSlider(1f)).isEqualTo(70)
        assertThat(setting.fromSlider(0.4f)).isEqualTo(40)
        assertThat(setting.fromSlider(-1f)).isEqualTo(20)
        assertThat(setting.fromSlider(2f)).isEqualTo(70)
    }

    @Test
    fun `reading the saved value: broken values are ignored`() {
        assertThat(setting.read(prefs())).isNull()
        prefs().edit().putInt(setting.key, 55).commit()
        assertThat(setting.read(prefs())).isEqualTo(55)
        prefs().edit().putInt(setting.key, 95).commit()
        assertThat(setting.read(prefs())).isNull()
        prefs().edit().putInt(setting.key, 5).commit()
        assertThat(setting.read(prefs())).isNull()
        // Saved with another type (damaged file, older version)
        prefs().edit().putString(setting.key, "forty").commit()
        assertThat(setting.read(prefs())).isNull()
        prefs().edit().putFloat(setting.key, 0.4f).commit()
        assertThat(setting.read(prefs())).isNull()
    }

    @Test
    fun `a value set by the user is saved on the device and read right away`() {
        assertThat(JuneSettings.intValue(context, setting)).isEqualTo(40)
        assertThat(JuneSettings.isCustomised(setting)).isFalse()
        assertThat(JuneSettings.swipeSteerFraction(context)).isEqualTo(0.4f)

        JuneSettings.setInt(context, setting, 25)
        assertThat(JuneSettings.intValue(context, setting)).isEqualTo(25)
        assertThat(JuneSettings.isCustomised(setting)).isTrue()
        assertThat(JuneSettings.swipeSteerFraction(context)).isEqualTo(0.25f)
        assertThat(prefs().getInt(setting.key, -1)).isEqualTo(25)
    }

    @Test
    fun `a value set out of the range is kept in it`() {
        JuneSettings.setInt(context, setting, 99)
        assertThat(JuneSettings.intValue(context, setting)).isEqualTo(70)
        assertThat(prefs().getInt(setting.key, -1)).isEqualTo(70)
        JuneSettings.setInt(context, setting, 3)
        assertThat(JuneSettings.intValue(context, setting)).isEqualTo(20)
    }

    @Test
    fun `back to the default removes the saved value`() {
        JuneSettings.setInt(context, setting, 60)
        JuneSettings.resetInt(context, setting)
        assertThat(JuneSettings.intValue(context, setting)).isEqualTo(40)
        assertThat(JuneSettings.isCustomised(setting)).isFalse()
        assertThat(prefs().contains(setting.key)).isFalse()
    }
}
