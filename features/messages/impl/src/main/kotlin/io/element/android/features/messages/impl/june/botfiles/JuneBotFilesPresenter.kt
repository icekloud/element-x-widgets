/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.botfiles

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.file.saveToJuneDownloads
import io.element.android.libraries.androidutils.filesize.FileSizeFormatter
import io.element.android.libraries.androidutils.system.toast
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.toFile
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.Timeline
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Element June: the bot file list, read from a media-only timeline of the room that this screen owns. */
@Inject
class JuneBotFilesPresenter(
    private val room: JoinedRoom,
    private val mediaLoader: MatrixMediaLoader,
    private val dateFormatter: DateFormatter,
    private val fileSizeFormatter: FileSizeFormatter,
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : Presenter<JuneBotFilesState> {
    @Composable
    override fun present(): JuneBotFilesState {
        val coroutineScope = rememberCoroutineScope()
        var timeline by remember { mutableStateOf<Timeline?>(null) }
        var files by remember { mutableStateOf<AsyncData<ImmutableList<JuneBotFile>>>(AsyncData.Loading()) }
        var paginationStatus by remember { mutableStateOf(Timeline.PaginationStatus(isPaginating = false, hasMoreToLoad = true)) }
        var downloading by remember { mutableStateOf(emptySet<String>()) }

        LaunchedEffect(Unit) {
            room.createTimeline(CreateTimelineParams.MediaOnly)
                .onSuccess { timeline = it }
                .onFailure {
                    Timber.e(it, "Unable to open the media timeline for the bot file list")
                    files = AsyncData.Failure(it)
                }
        }
        // This screen created the timeline, so this screen closes it.
        DisposableEffect(timeline) {
            val current = timeline
            onDispose { current?.close() }
        }
        LaunchedEffect(timeline) {
            val current = timeline ?: return@LaunchedEffect
            launch { current.backwardPaginationStatus.collect { paginationStatus = it } }
            current.timelineItems.collectLatest { items ->
                files = AsyncData.Success(
                    withContext(dispatchers.computation) {
                        juneBotFiles(items, room.sessionId, dateFormatter, fileSizeFormatter)
                    }
                )
            }
        }

        fun download(file: JuneBotFile) {
            if (file.key in downloading) return
            downloading = downloading + file.key
            coroutineScope.launch {
                val result = mediaLoader.downloadMediaFile(
                    source = file.mediaSource,
                    mimeType = file.mediaInfo.mimeType,
                    filename = file.mediaInfo.filename,
                ).mapCatching { mediaFile ->
                    mediaFile.use {
                        withContext(dispatchers.io) {
                            saveToJuneDownloads(context, it.toFile(), file.mediaInfo.filename, file.mediaInfo.mimeType)
                        }
                    }
                }
                downloading = downloading - file.key
                result
                    .onSuccess { context.toast("다운로드/element 폴더에 저장했습니다: ${file.mediaInfo.filename}") }
                    .onFailure {
                        Timber.e(it, "Unable to save a bot file")
                        context.toast("저장하지 못했습니다: ${file.mediaInfo.filename}")
                    }
            }
        }

        fun handleEvent(event: JuneBotFilesEvent) {
            when (event) {
                JuneBotFilesEvent.LoadMore -> {
                    val current = timeline ?: return
                    if (!paginationStatus.canPaginate) return
                    coroutineScope.launch {
                        current.paginate(Timeline.PaginationDirection.BACKWARDS)
                            .onFailure { Timber.w(it, "Unable to load older bot files") }
                    }
                }
                is JuneBotFilesEvent.Download -> download(event.file)
            }
        }

        return JuneBotFilesState(
            files = files,
            isLoadingMore = paginationStatus.isPaginating,
            hasMoreToLoad = paginationStatus.hasMoreToLoad,
            downloading = downloading.toImmutableSet(),
            eventSink = ::handleEvent,
        )
    }
}
