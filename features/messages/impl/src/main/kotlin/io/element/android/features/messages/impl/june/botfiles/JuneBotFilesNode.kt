/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.botfiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.EventId

@ContributesNode(RoomScope::class)
@AssistedInject
class JuneBotFilesNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val presenter: JuneBotFilesPresenter,
) : Node(buildContext, plugins = plugins) {
    interface Callback : Plugin {
        fun openBotFile(file: JuneBotFile)
        fun viewBotFileInTimeline(eventId: EventId)
    }

    private val callback: Callback = callback()

    @Composable
    override fun View(modifier: Modifier) {
        JuneBotFilesView(
            state = presenter.present(),
            onBackClick = this::navigateUp,
            onFileClick = callback::openBotFile,
            onViewInChatClick = { callback.viewBotFileInTimeline(it.eventId) },
            modifier = modifier,
        )
    }
}
