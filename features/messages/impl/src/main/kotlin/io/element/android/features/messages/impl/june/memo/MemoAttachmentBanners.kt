/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * The two one-line notices of the attachment queue (A5, 설계 §e), read when the screen opens and when the notes change:
 * attachments that could not be uploaded (with "다시 시도"), and low phone storage with the manual clean button.
 * Nothing is ever deleted on its own.
 */
@Composable
internal fun MemoAttachmentBanners(local: MemoLocalBackend, localVersion: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(0) }
    var clean by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(local, localVersion) {
        failed = local.failedAttachments()
        val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        clean = if (stat != null && memoLowStorage(stat.availableBytes, stat.totalBytes)) local.uploadedOriginals() else null
    }
    if (failed > 0) {
        MemoNoticeLine("첨부 ${failed}개를 못 올렸습니다", "다시 시도") { scope.launch { local.retryFailedAttachments() } }
    }
    clean?.let { (count, bytes) ->
        val action = if (count > 0) "올림 끝난 원본 ${count}개(${memoSizeText(bytes)}) 정리" else null
        MemoNoticeLine("저장공간 부족", action) {
            scope.launch { local.cleanUploadedOriginals { path -> File(path).delete() } }
        }
    }
}

@Composable
private fun MemoNoticeLine(text: String, action: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgSubtleSecondary)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
        if (action != null) {
            Text(action, modifier = Modifier.clickable(onClick = onAction).padding(8.dp), color = ElementTheme.colors.textActionAccent)
        }
    }
}
