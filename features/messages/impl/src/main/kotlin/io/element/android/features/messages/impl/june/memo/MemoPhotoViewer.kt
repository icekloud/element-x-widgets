/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.request.ImageRequest
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import java.io.File

/** 앱7 항목 2: the photos of one note opened full screen, starting at [start]. */
internal data class MemoViewerRequest(val photos: List<MemoAttachment>, val start: Int = 0)

/**
 * What one photo can be drawn from, in order (앱7 결정기록 2): the phone original while the file is there, then the
 * server copy asked as the whole file ([MediaRequestData.Kind.Content], not a thumbnail) and read through the
 * encrypted file json. The room timeline is not needed.
 */
internal fun memoViewerModels(a: MemoAttachment, isFile: (String) -> Boolean = { File(it).isFile }): List<Any> {
    val local = a.localPath ?: a.mxc.takeIf { it.startsWith(MEMO_LOCAL_FILE_PREFIX) }?.removePrefix(MEMO_LOCAL_FILE_PREFIX)
    return listOfNotNull(
        local?.takeIf(isFile)?.let(::File),
        a.mxc.takeIf { it.isNotEmpty() && !it.startsWith(MEMO_LOCAL_FILE_PREFIX) }
            ?.let { MediaRequestData(MediaSource(it, memoMediaSourceJson(a.fileJson)), MediaRequestData.Kind.Content) },
    )
}

/** One page of the viewer: which source is tried ([at]), and whether it is still loading. Past the last one = failed. */
internal data class MemoViewerPage(val models: List<Any>, val at: Int = 0, val loading: Boolean = models.isNotEmpty()) {
    val failed: Boolean get() = at >= models.size
    val model: Any? get() = models.getOrNull(at)

    fun loaded() = copy(loading = false)

    /** The source failed: the next one is tried, or the page shows [다시 시도]. */
    fun error() = copy(at = at + 1, loading = at + 1 < models.size)

    fun retry(models: List<Any> = this.models) = MemoViewerPage(models)
}

/** "2/4" over the photo. */
internal fun memoViewerCounter(page: Int, count: Int): String = "${page + 1}/$count"

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemoPhotoViewer(
    request: MemoViewerRequest,
    onClose: () -> Unit,
    isFile: (String) -> Boolean = { File(it).isFile },
) {
    val photos = request.photos
    if (photos.isEmpty()) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = request.start.coerceIn(0, photos.lastIndex)) { photos.size }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag(MemoTags.VIEWER)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { it }) { i ->
                MemoViewerPhoto(photos[i], isFile)
            }
            Text(
                memoViewerCounter(pager.currentPage, photos.size),
                color = Color.White,
                style = ElementTheme.typography.fontBodyMdMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).testTag(MemoTags.VIEWER_COUNTER),
            )
            Text(
                "✕ 닫기",
                color = Color.White,
                style = ElementTheme.typography.fontBodyMdMedium,
                modifier = Modifier.align(Alignment.TopEnd).clickable(onClick = onClose).padding(16.dp).testTag(MemoTags.VIEWER_CLOSE),
            )
        }
    }
}

@Composable
private fun MemoViewerPhoto(a: MemoAttachment, isFile: (String) -> Boolean) {
    val context = LocalContext.current
    var page by remember(a) { mutableStateOf(MemoViewerPage(memoViewerModels(a, isFile))) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val model = page.model
        if (page.failed || model == null) {
            Text(
                "사진을 불러오지 못했습니다\n다시 시도",
                color = Color.White,
                modifier = Modifier
                    .clickable { page = page.retry(memoViewerModels(a, isFile)) }
                    .padding(16.dp)
                    .testTag(MemoTags.VIEWER_RETRY),
            )
        } else {
            val at = page.at
            val imageRequest = remember(model, at) {
                ImageRequest.Builder(context)
                    .data(model)
                    .listener(
                        onError = { _, _ -> if (page.at == at) page = page.error() },
                        onSuccess = { _, _ -> if (page.at == at) page = page.loaded() },
                    )
                    .build()
            }
            // Pinch zoom and pan, double tap zoom (telephoto, the same component the room media viewer uses).
            ZoomableAsyncImage(model = imageRequest, contentDescription = null, modifier = Modifier.fillMaxSize())
            if (page.loading) CircularProgressIndicator(Modifier.testTag(MemoTags.VIEWER_LOADING))
        }
    }
}

/** Where a photo tap opens the viewer: the whole row of photos of the note, at the tapped one. */
internal fun Modifier.memoOpenPhoto(controller: MemoController, photos: List<MemoAttachment>, index: Int): Modifier =
    clickable { controller.viewer = MemoViewerRequest(photos, index) }
