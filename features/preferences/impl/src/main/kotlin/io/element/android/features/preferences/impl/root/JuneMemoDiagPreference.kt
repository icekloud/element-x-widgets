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

internal const val JUNE_MEMO_DIAG_TITLE = "메모 동기화 진단"
internal const val JUNE_MEMO_DIAG_EMPTY = "아직 기록 없음 · 메모 화면을 열면 채워집니다"

/** Element June: the supporting text of the memo diagnosis item, the lines the memo sync wrote last. */
internal fun juneMemoDiagSupportingText(diag: String?): String = if (diag.isNullOrBlank()) JUNE_MEMO_DIAG_EMPTY else diag

/**
 * 앱28 추가요청 1: what the memo sync did with the photos and the proposals, for the next look at a delay (shown only,
 * nothing to press). The lines are made by the memo sync itself after each step and stored on the device.
 */
@Composable
internal fun JuneMemoDiagPreference(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val diag = JuneSettings.memoDiag(context)
    ListItem(
        modifier = modifier,
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Info())),
        content = { Text(JUNE_MEMO_DIAG_TITLE) },
        supportingContent = { Text(juneMemoDiagSupportingText(diag)) },
    )
}
