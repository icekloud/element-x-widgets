/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.preferences.impl.root

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.emoji.api.picker.NoOpEmojiPickerRenderer
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Before
import org.junit.Test

class JuneBtSpeakPreferenceTest : RobolectricTest() {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        JuneSettings.setBtSpeakEnabled(context, true)
    }

    private fun prefs() = context.getSharedPreferences("june_settings", Context.MODE_PRIVATE)

    @Test
    fun `the setting is saved on the device`() {
        assertThat(JuneSettings.btSpeakEnabled(context)).isTrue()
        JuneSettings.setBtSpeakEnabled(context, false)
        assertThat(JuneSettings.btSpeakEnabled(context)).isFalse()
        assertThat(prefs().getBoolean("bt_speak", true)).isFalse()
        JuneSettings.setBtSpeakEnabled(context, true)
        assertThat(prefs().getBoolean("bt_speak", false)).isTrue()
    }

    @Test
    fun `the switch is on and clicking it turns the reading off and on again`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent { JuneBtSpeakPreference() }
        val switch = onNode(hasText(JUNE_BT_SPEAK_TITLE, substring = true) and isToggleable(), useUnmergedTree = false)
        switch.assertIsOn()
        switch.performClick()
        waitForIdle()
        assertThat(JuneSettings.btSpeakEnabled(context)).isFalse()
        switch.assertIsOff()
        switch.performClick()
        waitForIdle()
        assertThat(JuneSettings.btSpeakEnabled(context)).isTrue()
        switch.assertIsOn()
    }

    @Test
    fun `the item is in the app settings of the settings screen`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            PreferencesRootView(
                state = aPreferencesRootState(eventSink = {}),
                emojiPickerRenderer = NoOpEmojiPickerRenderer,
                onBackClick = {},
                onAddAccountClick = {},
                onOpenAnalytics = {},
                onOpenLockScreenSettings = {},
                onOpenAbout = {},
                onOpenDeveloperSettings = {},
                onOpenMediaSettings = {},
                onOpenLocationSettings = {},
                onOpenLabs = {},
                onEditProfileClick = {},
                onSecureBackupClick = {},
                onManageAccountClick = {},
                onLinkNewDeviceClick = {},
                onOpenRageShake = {},
                onModerationAndSafetyClick = {},
                onOpenNotificationSettings = {},
                onSignOutClick = {},
                onDeactivateClick = {},
            )
        }
        onNode(hasText(JUNE_BT_SPEAK_TITLE), useUnmergedTree = true).performScrollTo().assertExists()
    }
}
