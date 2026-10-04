/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.ui.strings.CommonStrings

/**
 * Element June: the X shown on the left of the composer while recording a voice message.
 * Pressing it discards the recording without sending anything.
 */
@Composable
fun VoiceMessageCancelButtonIcon(
    modifier: Modifier = Modifier,
) {
    Icon(
        modifier = modifier.size(24.dp),
        imageVector = CompoundIcons.Close(),
        contentDescription = stringResource(CommonStrings.action_cancel),
        tint = ElementTheme.colors.iconSecondary,
    )
}

@PreviewsDayNight
@Composable
internal fun VoiceMessageCancelButtonIconPreview() = ElementPreview {
    IconButton(onClick = {}) {
        VoiceMessageCancelButtonIcon()
    }
}
