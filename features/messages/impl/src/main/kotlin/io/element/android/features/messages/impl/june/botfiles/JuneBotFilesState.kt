/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.botfiles

import io.element.android.libraries.architecture.AsyncData
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet

data class JuneBotFilesState(
    val files: AsyncData<ImmutableList<JuneBotFile>>,
    val isLoadingMore: Boolean,
    val hasMoreToLoad: Boolean,
    /** Keys of the files being saved right now. */
    val downloading: ImmutableSet<String>,
    val eventSink: (JuneBotFilesEvent) -> Unit,
)

sealed interface JuneBotFilesEvent {
    data object LoadMore : JuneBotFilesEvent
    data class Download(val file: JuneBotFile) : JuneBotFilesEvent
}
