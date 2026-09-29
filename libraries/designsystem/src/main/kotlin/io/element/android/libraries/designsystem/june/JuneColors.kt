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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import io.element.android.compound.theme.Theme
import io.element.android.compound.tokens.generated.SemanticColors
import io.element.android.compound.tokens.generated.compoundColorsDark

/**
 * Element June: the colour of [slot] for the current theme, or null to keep the original colour.
 * "Light only" defaults (lavender backgrounds) are not applied to the dark theme unless the user picked a colour.
 */
@Composable
fun juneColor(slot: JuneSettings.ColorSlot): Color? {
    val context = LocalContext.current
    if (LocalJuneAperture.current) return apertureSlotColor(slot)
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

/** Element June: true inside a room using the Aperture (GLaDOS) theme. */
val LocalJuneAperture = staticCompositionLocalOf { false }

private val ApertureOrange = Color(0xFFFF9A00)

private fun apertureSlotColor(slot: JuneSettings.ColorSlot): Color? = when (slot) {
    JuneSettings.ColorSlot.TopBar -> Color(0xFF16171B)
    JuneSettings.ColorSlot.OwnBubble -> Color(0xFF2B2418)
    JuneSettings.ColorSlot.OtherBubble -> Color(0xFF1E2126)
    JuneSettings.ColorSlot.BubbleBorder -> Color(0xFF5A4318)
    else -> null
}

/** Aperture Science palette: near black test chamber, white panels, orange light. */
private val apertureColors: SemanticColors = compoundColorsDark.copy(
    bgCanvasDefault = Color(0xFF0E0F12),
    bgAccentRest = ApertureOrange,
    bgBadgeAccent = ApertureOrange,
    iconAccentPrimary = ApertureOrange,
    textActionAccent = ApertureOrange,
    textLinkExternal = ApertureOrange,
    borderAccentPrimary = ApertureOrange,
)

/**
 * Element June: wraps a room screen. Rooms whose name matches a keyword of Settings > June 꾸미기 > 방별 테마
 * (default "GLaDOS") get the dark Aperture theme; other rooms are unchanged.
 */
@Composable
fun JuneRoomTheme(roomName: String?, content: @Composable () -> Unit) {
    if (!JuneSettings.isApertureRoom(LocalContext.current, roomName)) {
        content()
        return
    }
    ElementTheme(
        theme = Theme.Dark,
        applySystemBarsUpdate = false,
        compoundDark = apertureColors,
    ) {
        CompositionLocalProvider(LocalJuneAperture provides true) {
            content()
        }
    }
}
