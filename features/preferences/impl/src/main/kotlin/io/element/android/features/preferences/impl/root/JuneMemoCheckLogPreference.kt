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
import io.element.android.libraries.androidutils.system.startSharePlainTextIntent
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.june.JuneMemoCheckLog
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text

internal const val JUNE_MEMO_CHECK_TITLE = "메모 확인 진단 기록 내보내기"
internal const val JUNE_MEMO_CHECK_EMPTY = "아직 기록 없음 · 제안 화면에서 지시를 보내면 쌓입니다"
internal const val JUNE_MEMO_CHECK_SHARE_TITLE = "메모 확인 진단 기록"

/** The line under the item: how many lines are ready to be shared, or that there are none yet. */
internal fun juneMemoCheckSupportingText(lines: Int): String =
    if (lines <= 0) JUNE_MEMO_CHECK_EMPTY else "지시조회·검토 확인 ${lines}줄 · 눌러서 내보내기"

/**
 * 앱33 추가요청 1 ⑤: the lines the memo look-ups wrote are **not** shown here — a press shares the txt file's text
 * with another app (채팅 등), so the admin can take it off the phone. Nothing else in the app reads them.
 */
@Composable
internal fun JuneMemoCheckLogPreference(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lines = JuneMemoCheckLog.read(context).size
    ListItem(
        modifier = modifier,
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Share())),
        content = { Text(JUNE_MEMO_CHECK_TITLE) },
        supportingContent = { Text(juneMemoCheckSupportingText(lines)) },
        onClick = {
            val text = JuneMemoCheckLog.text(context)
            if (text.isNotBlank()) {
                context.startSharePlainTextIntent(
                    activityResultLauncher = null,
                    chooserTitle = JUNE_MEMO_CHECK_SHARE_TITLE,
                    text = text,
                    subject = JUNE_MEMO_CHECK_SHARE_TITLE,
                )
            }
        },
    )
}
