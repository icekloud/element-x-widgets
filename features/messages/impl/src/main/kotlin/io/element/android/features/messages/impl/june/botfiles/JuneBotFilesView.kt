/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.botfiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar

/** Element June: how close to the end of the list the older files start loading. */
private const val LOAD_MORE_THRESHOLD = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JuneBotFilesView(
    state: JuneBotFilesState,
    onBackClick: () -> Unit,
    onFileClick: (JuneBotFile) -> Unit,
    onViewInChatClick: (JuneBotFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = "봇이 보낸 파일",
                navigationIcon = { BackButton(onClick = onBackClick) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val files = state.files) {
                is AsyncData.Failure -> CenteredText("파일 목록을 불러오지 못했습니다.")
                is AsyncData.Success -> BotFileList(
                    files = files.data,
                    state = state,
                    onFileClick = onFileClick,
                    onViewInChatClick = onViewInChatClick,
                )
                else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun BotFileList(
    files: List<JuneBotFile>,
    state: JuneBotFilesState,
    onFileClick: (JuneBotFile) -> Unit,
    onViewInChatClick: (JuneBotFile) -> Unit,
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    // Keep loading older history while the end of the list is on screen, so files further back show up on their own.
    LaunchedEffect(nearEnd, state.hasMoreToLoad, state.isLoadingMore, files.size) {
        if (nearEnd && state.hasMoreToLoad && !state.isLoadingMore) {
            state.eventSink(JuneBotFilesEvent.LoadMore)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        itemsIndexed(files, key = { _, file -> file.key }) { index, file ->
            BotFileRow(
                file = file,
                isDownloading = file.key in state.downloading,
                onClick = { onFileClick(file) },
                onViewInChatClick = { onViewInChatClick(file) },
                onDownloadClick = { state.eventSink(JuneBotFilesEvent.Download(file)) },
            )
            if (index < files.lastIndex) {
                HorizontalDivider()
            }
        }
        item(key = "footer") {
            ListFooter(
                isEmpty = files.isEmpty(),
                isLoadingMore = state.isLoadingMore || state.hasMoreToLoad,
            )
        }
    }
}

@Composable
private fun ListFooter(isEmpty: Boolean, isLoadingMore: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = "이전 파일을 불러오는 중…",
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
            isEmpty -> Text(
                text = "이 대화에서 봇이 보낸 파일이 없습니다.",
                style = ElementTheme.typography.fontBodyMdRegular,
                color = ElementTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            else -> Text(
                text = "처음 파일까지 모두 표시했습니다.",
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun BotFileRow(
    file: JuneBotFile,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onViewInChatClick: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = file.kind.icon(),
            contentDescription = null,
            tint = ElementTheme.colors.iconSecondary,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = file.mediaInfo.filename,
                style = ElementTheme.typography.fontBodyMdMedium,
                color = ElementTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(file.dateLabel, file.mediaInfo.formattedFileSize).filter { it.isNotBlank() }.joinToString(" · "),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
        IconButton(onClick = onViewInChatClick) {
            Icon(imageVector = CompoundIcons.Chat(), contentDescription = "채팅에서 보기")
        }
        IconButton(onClick = onDownloadClick, enabled = !isDownloading) {
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(imageVector = CompoundIcons.Download(), contentDescription = "다운로드")
            }
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = ElementTheme.typography.fontBodyMdRegular,
            color = ElementTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun JuneBotFileKind.icon(): ImageVector = when (this) {
    JuneBotFileKind.Image -> CompoundIcons.Image()
    JuneBotFileKind.Video -> CompoundIcons.VideoCall()
    JuneBotFileKind.Audio -> CompoundIcons.Audio()
    JuneBotFileKind.File -> CompoundIcons.Document()
}
