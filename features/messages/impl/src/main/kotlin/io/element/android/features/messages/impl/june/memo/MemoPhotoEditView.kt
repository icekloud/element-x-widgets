/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalLayoutApi::class)

package io.element.android.features.messages.impl.june.memo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/** Test tags of the photo part of the note editor (앱10 D). */
internal object MemoPhotoEditTags {
    const val ADD = "memo_edit_photo_add"
    const val PHOTO = "memo_edit_photo"
    const val REMOVE = "memo_edit_photo_remove"
    const val UNDO = "memo_edit_photo_undo"
    const val PICKED = "memo_edit_photo_picked"
}

/**
 * The photos of the note editor: the note's photos with ✕ (marked, dimmed, "되돌리기" until [저장]), the new picks with
 * ✕, and "＋ 사진" that opens the gallery like the composer. Nothing is written here: [onChange] gives the new state.
 */
@Composable
internal fun MemoEditorPhotos(edit: MemoPhotoEdit, onChange: (MemoPhotoEdit) -> Unit) {
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onChange(edit.pick(uris.map { it.toString() }))
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "사진 ${edit.count}장" + if (edit.changed) " (저장하면 바뀜)" else "",
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
        )
        FlowRow(Modifier.padding(top = 4.dp)) {
            edit.existing.forEach { a ->
                val removed = edit.isRemoved(a)
                MemoEditorPhotoCell(
                    marked = removed,
                    onToggle = { onChange(edit.toggle(a)) },
                    modifier = Modifier.testTag(MemoPhotoEditTags.PHOTO),
                ) {
                    MemoThumbnail(a.mxc, a.fileJson, Modifier.fillMaxSize().alpha(if (removed) 0.35f else 1f), a.localPath)
                }
            }
            edit.picked.forEach { uri ->
                MemoEditorPhotoCell(
                    marked = false,
                    onToggle = { onChange(edit.unpick(uri)) },
                    modifier = Modifier.testTag(MemoPhotoEditTags.PICKED),
                ) {
                    AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Box(
                Modifier
                    .padding(end = 6.dp, bottom = 6.dp)
                    .size(84.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ElementTheme.colors.bgSubtleSecondary)
                    .clickable { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .testTag(MemoPhotoEditTags.ADD),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋ 사진", color = ElementTheme.colors.textActionAccent, style = ElementTheme.typography.fontBodySmRegular)
            }
        }
    }
}

/** One photo square: ✕ in the corner, or "되돌리기" over a photo marked for removal. */
@Composable
private fun MemoEditorPhotoCell(marked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.padding(end = 6.dp, bottom = 6.dp).size(84.dp).clip(RoundedCornerShape(6.dp))) {
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
                    .testTag(MemoPhotoEditTags.UNDO),
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
                    .testTag(MemoPhotoEditTags.REMOVE),
                color = Color.White,
                style = ElementTheme.typography.fontBodySmRegular,
            )
        }
    }
}
