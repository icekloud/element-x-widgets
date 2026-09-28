/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.element.android.libraries.designsystem.june.JuneSettings

@Composable
internal fun RoomAvatar(name: String, bitmap: Bitmap?, size: Dp) {
    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = name,
            modifier = GlanceModifier.size(size).cornerRadius(size / 2),
        )
    } else {
        Box(
            modifier = GlanceModifier.size(size).cornerRadius(size / 2).background(GlanceTheme.colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().trimStart('#', '!', '@').take(1).uppercase(),
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = (size.value * 0.45f).sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

@Composable
internal fun UnreadBadge(count: Long) {
    if (count <= 0) return
    Box(
        modifier = GlanceModifier
            .cornerRadius(10.dp)
            .background(ColorProvider(JuneSettings.colorOf(LocalContext.current, JuneSettings.ColorSlot.WidgetBadge)))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
    }
}
