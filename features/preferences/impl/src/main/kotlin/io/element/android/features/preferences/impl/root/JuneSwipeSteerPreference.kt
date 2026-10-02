/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Slider
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton

/** Element June: words of the "밀어서 스티어링 감도" setting for [percent] of the row width. */
internal fun juneSwipeSteerSummary(percent: Int, isDefault: Boolean): String =
    "행 너비의 $percent%" + if (isDefault) " (기본값)" else ""

/**
 * Element June: "밀어서 스티어링 감도" — how far a queued message has to be pushed to the left to steer it,
 * 20..70 % of the row width (default 40 %). Stored on the device only; the queue panel uses it from the next push on.
 */
@Composable
internal fun JuneSwipeSteerPreference(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val setting = JuneSettings.SWIPE_STEER_PERCENT
    val percent = JuneSettings.intValue(context, setting)
    val isDefault = !JuneSettings.isCustomised(setting)
    ListItem(
        modifier = modifier,
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ArrowLeft())),
        content = {
            Column {
                Text(
                    style = ElementTheme.typography.fontBodyLgRegular,
                    text = "밀어서 스티어링 감도",
                )
                Text(
                    style = ElementTheme.typography.fontBodyMdRegular,
                    text = "대기 중인 메시지를 왼쪽으로 이만큼 밀면 스티어링합니다. 작을수록 조금만 밀어도 됩니다.",
                )
                Text(
                    style = ElementTheme.typography.fontBodyMdMedium,
                    text = juneSwipeSteerSummary(percent, isDefault),
                )
                Slider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "밀어서 스티어링 감도" },
                    value = setting.toSlider(percent),
                    steps = setting.sliderSteps,
                    onValueChange = { position ->
                        val value = setting.fromSlider(position)
                        if (value != percent) JuneSettings.setInt(context, setting, value)
                    },
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        modifier = Modifier.weight(1f),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = ElementTheme.colors.textSecondary,
                        text = "${setting.min}% ~ ${setting.max}%",
                    )
                    TextButton(
                        text = "기본값으로",
                        size = ButtonSize.Small,
                        enabled = !isDefault,
                        onClick = { JuneSettings.resetInt(context, setting) },
                    )
                }
            }
        },
    )
}
