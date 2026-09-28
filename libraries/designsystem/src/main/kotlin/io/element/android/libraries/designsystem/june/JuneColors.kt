/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.june

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.SemanticColors

/**
 * Element June: the colour of [slot] for the current theme, or null to keep the original colour.
 * "Light only" defaults (lavender backgrounds) are not applied to the dark theme unless the user picked a colour.
 */
@Composable
fun juneColor(slot: JuneSettings.ColorSlot): Color? {
    val context = LocalContext.current
    val color = JuneSettings.colorOf(context, slot)
    if (slot.lightOnly && !ElementTheme.isLightTheme && !JuneSettings.isCustomised(slot)) return null
    return color
}

/** Element June: apply the user's colours to the Compound palette of the whole app. */
@Composable
fun SemanticColors.withJuneColors(): SemanticColors {
    val context = LocalContext.current
    val accent = JuneSettings.colorOf(context, JuneSettings.ColorSlot.Accent)
    val backgroundSlot = JuneSettings.ColorSlot.Background
    val background = JuneSettings.colorOf(context, backgroundSlot).takeIf { isLight || JuneSettings.isCustomised(backgroundSlot) }
    return copy(
        bgAccentRest = accent,
        bgBadgeAccent = accent,
        iconAccentPrimary = accent,
        textActionAccent = accent,
        textLinkExternal = accent,
        borderAccentPrimary = accent,
        bgCanvasDefault = background ?: bgCanvasDefault,
    )
}
