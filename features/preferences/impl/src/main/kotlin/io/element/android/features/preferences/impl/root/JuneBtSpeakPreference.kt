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
internal const val JUNE_BT_SPEAK_DESCRIPTION = "블루투스 이어폰이 연결돼 있으면 받은 메시지 앞부분의 🔊 줄을 읽습니다. 무음 모드에서도 미디어 음량으로 읽고, 듣던 음악은 잠시 작아집니다."
internal const val JUNE_BT_SPEAK_STATUS_PREFIX = "마지막 판정: "

/** Element June: the supporting text of the setting, with the outcome of the last attempt to read a 🔊 line when there is one. */
internal fun juneBtSpeakSupportingText(status: String?): String =
    if (status.isNullOrBlank()) JUNE_BT_SPEAK_DESCRIPTION else "$JUNE_BT_SPEAK_DESCRIPTION\n$JUNE_BT_SPEAK_STATUS_PREFIX$status"

/**
 * Element June: "블루투스 이어폰 연결 시 🔊 줄 읽기" switch (on by default), with the outcome of the last attempt to read a 🔊 line below it
 * (for instance "마지막 판정: 블루투스 아님 19:36"), so the reason why nothing was heard can be seen on the phone. Stored on the device only.
 */
@Composable
internal fun JuneBtSpeakPreference(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val enabled = JuneSettings.btSpeakEnabled(context)
    val status = JuneSettings.btSpeakStatus(context)
    ListItem(
        modifier = modifier,
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.VolumeOnSolid())),
        content = { Text(JUNE_BT_SPEAK_TITLE) },
        supportingContent = { Text(juneBtSpeakSupportingText(status)) },
        trailingContent = ListItemContent.Switch(checked = enabled),
        onClick = { JuneSettings.setBtSpeakEnabled(context, !enabled) },
    )
}
