/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.root

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text

internal const val JUNE_BT_SPEAK_TITLE = "블루투스 이어폰 연결 시 🔊 줄 읽기"
internal const val JUNE_BT_SPEAK_DESCRIPTION = "블루투스 이어폰이 연결돼 있으면 받은 메시지의 🔊 첫 줄을 소리 내어 읽습니다. 무음 모드에서도 미디어 음량으로 읽습니다."

/**
 * Element June: "블루투스 이어폰 연결 시 🔊 줄 읽기" switch (on by default). Stored on the device only.
 */
@Composable
internal fun JuneBtSpeakPreference(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val enabled = JuneSettings.btSpeakEnabled(context)
    ListItem(
        modifier = modifier,
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.VolumeOnSolid())),
        content = { Text(JUNE_BT_SPEAK_TITLE) },
        supportingContent = { Text(JUNE_BT_SPEAK_DESCRIPTION) },
        trailingContent = ListItemContent.Switch(checked = enabled),
        onClick = { JuneSettings.setBtSpeakEnabled(context, !enabled) },
    )
}
