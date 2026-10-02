/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.preferences.impl.root

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.emoji.api.picker.NoOpEmojiPickerRenderer
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Before
import org.junit.Test

class JuneSwipeSteerPreferenceTest : RobolectricTest() {
    private val setting = JuneSettings.SWIPE_STEER_PERCENT
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        JuneSettings.resetInt(context, setting)
    }

    @Test
    fun `the summary tells the percent and whether it is the default`() {
        assertThat(juneSwipeSteerSummary(40, isDefault = true)).isEqualTo("행 너비의 40% (기본값)")
        assertThat(juneSwipeSteerSummary(25, isDefault = false)).isEqualTo("행 너비의 25%")
    }

    @Test
    fun `the default value is shown and cannot be reset`() = runAndroidComposeUiTest {
        setContent { JuneSwipeSteerPreference() }
        onNodeWithText("밀어서 스티어링 감도").assertExists()
        onNodeWithText("행 너비의 40% (기본값)").assertExists()
        onNodeWithText("20% ~ 70%").assertExists()
        onNode(hasText("기본값으로") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `moving the slider saves the value and shows it`() = runAndroidComposeUiTest {
        setContent { JuneSwipeSteerPreference() }
        // Slider position 0..1 over 20..70 %: 0.1 is 25 %
        onNode(hasContentDescription("밀어서 스티어링 감도")).performSemanticsAction(SemanticsActions.SetProgress) { it(0.1f) }
        waitForIdle()
        assertThat(JuneSettings.intValue(context, setting)).isEqualTo(25)
        assertThat(JuneSettings.swipeSteerFraction(context)).isEqualTo(0.25f)
        onNodeWithText("행 너비의 25%").assertExists()
        // Both ends
        onNode(hasContentDescription("밀어서 스티어링 감도")).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        waitForIdle()
        assertThat(JuneSettings.intValue(context, setting)).isEqualTo(70)
        onNode(hasContentDescription("밀어서 스티어링 감도")).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        waitForIdle()
        assertThat(JuneSettings.intValue(context, setting)).isEqualTo(20)
    }

    @Test
    fun `back to the default button restores 40 percent`() = runAndroidComposeUiTest {
        JuneSettings.setInt(context, setting, 60)
        setContent { JuneSwipeSteerPreference() }
        onNodeWithText("행 너비의 60%").assertExists()
        onNode(hasText("기본값으로") and hasClickAction()).assertIsEnabled().performClick()
        waitForIdle()
        assertThat(JuneSettings.intValue(context, setting)).isEqualTo(40)
        assertThat(JuneSettings.isCustomised(setting)).isFalse()
        onNodeWithText("행 너비의 40% (기본값)").assertExists()
    }

    @Test
    fun `the item is in the app settings of the settings screen`() = runAndroidComposeUiTest {
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
        onNode(hasText("밀어서 스티어링 감도"), useUnmergedTree = true).performScrollTo().assertExists()
    }
}
