/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text

/**
 * The photos of the note editor: the note's photos with ✕ (marked, dimmed, "되돌리기" until the editing ends), the new
 * picks with ✕, and "＋ 사진" that opens the gallery like the composer. Nothing is written here: [onChange] gives the new
 * state. 앱13 B: one row of small squares that scrolls sideways, under the body, so it never takes the body's height.
 */
@Composable
internal fun MemoEditorPhotos(edit: MemoPhotoEdit, onChange: (MemoPhotoEdit) -> Unit, modifier: Modifier = Modifier) {
    // 앱23: the picker is asked to keep the location in the pictures it hands over (MemoPhotoLocationPicker); the
    // user allows or refuses that in the picker itself, and a refusal only means the photo goes up without one.
    val gallery = rememberLauncherForActivityResult(MemoPickPhotosWithLocation()) { uris ->
        if (uris.isNotEmpty()) onChange(edit.pick(uris.map { it.toString() }))
    }
    val askMediaLocation = rememberMemoMediaLocationAsk()
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        edit.existing.forEach { a ->
            val removed = edit.isRemoved(a)
            MemoEditorPhotoCell(
                marked = removed,
                onToggle = { onChange(edit.toggle(a)) },
                modifier = Modifier.testTag(MemoTags.EDIT_PHOTO),
            ) {
                MemoThumbnail(a.mxc, a.fileJson, Modifier.fillMaxSize().alpha(if (removed) 0.35f else 1f), a.localPath)
            }
        }
        edit.picked.forEach { uri ->
            MemoEditorPhotoCell(
                marked = false,
                onToggle = { onChange(edit.unpick(uri)) },
                modifier = Modifier.testTag(MemoTags.EDIT_PHOTO_PICKED),
            ) {
                AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier
                .padding(end = 6.dp)
                .size(MEMO_EDIT_PHOTO_DP.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ElementTheme.colors.bgSubtleSecondary)
                .clickable {
                    // 앱23: asked here, where the user is actually picking photos — never on opening the screen.
                    askMediaLocation()
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                .testTag(MemoTags.EDIT_PHOTO_ADD),
            contentAlignment = Alignment.Center,
        ) {
            Text("＋ 사진", color = ElementTheme.colors.textActionAccent, style = ElementTheme.typography.fontBodyXsRegular)
        }
    }
}

/** 앱13 B: the side of one photo square of the editor (was 84 dp in a wrapping grid). */
internal const val MEMO_EDIT_PHOTO_DP = 56

/** One photo square: ✕ in the corner, or "되돌리기" over a photo marked for removal. */
@Composable
private fun MemoEditorPhotoCell(marked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.padding(end = 6.dp).size(MEMO_EDIT_PHOTO_DP.dp).clip(RoundedCornerShape(6.dp))) {
        content()
        if (marked) {
            Text(
                "되돌리기",
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag(MemoTags.EDIT_PHOTO_UNDO),
                color = Color.White,
                style = ElementTheme.typography.fontBodyXsRegular,
            )
        } else {
            Text(
                "✕",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 6.dp)
                    .testTag(MemoTags.EDIT_PHOTO_REMOVE),
                color = Color.White,
                style = ElementTheme.typography.fontBodySmRegular,
            )
        }
    }
}
