/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text

/** 앱21 ②: the ACTION_VIEW intents of a press on the place box, Google Maps first ([memoPlaceTries]). */
internal fun memoPlaceIntents(map: String): List<Intent> = memoPlaceTries(map).map { (pkg, link) ->
    Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { pkg?.let { setPackage(it) } }
}

/** 앱21 ②: opens [map] in Google Maps, else in the browser; false when the phone has no app for it. */
internal fun memoOpenPlace(map: String, start: (Intent) -> Unit): Boolean = memoFirstThatStarts(memoPlaceIntents(map), start)

/**
 * 앱21 ②: the place box left of the 편집 box, as high as it ([MEMO_EDIT_BOX_HEIGHT_DP]) so the body room under the text
 * stays the same. A rounded outline like the chips of 앱18~20, the name line in medium text, the address under it in
 * small grey text; both end in "…" when long.
 */
@Composable
internal fun MemoPlaceBox(place: MemoPlaceLine, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context: Context = LocalContext.current
    val shape = RoundedCornerShape(MEMO_PLACE_BOX_CORNER_DP.dp)
    val press = place.map?.let { map ->
        Modifier.clickable(role = Role.Button) { if (!memoOpenPlace(map, context::startActivity)) onFailed() }
    } ?: Modifier
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(MEMO_EDIT_BOX_HEIGHT_DP.dp)
            .clip(shape)
            .background(ElementTheme.colors.bgCanvasDefault)
            .border(1.dp, ElementTheme.colors.borderInteractiveSecondary, shape)
            .then(press)
            .padding(horizontal = 10.dp)
            .semantics { contentDescription = place.description }
            .testTag(MemoTags.NOTE_PLACE),
    ) {
        Text(
            place.title,
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (place.address.isNotEmpty()) {
            Text(
                place.address,
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 앱21 ②: the gap between the place box and the 편집 box, and its corners (those of the 편집 box). */
internal const val MEMO_PLACE_BOX_GAP_DP = 8
private const val MEMO_PLACE_BOX_CORNER_DP = 12
