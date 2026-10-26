/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.bindings

/**
 * Element June: the "음성 미리듣기" button of the settings. The settings screen cannot call the reader itself (it is in another
 * module), so it sends this broadcast; the time the voice took is then shown under the setting.
 */
class JuneVoicePreviewReceiver : BroadcastReceiver() {
    @Inject lateinit var juneVoicePreview: JuneVoicePreview

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        context.bindings<JuneVoicePreviewBindings>().inject(this)
        juneVoicePreview.play()
    }
}

@ContributesTo(AppScope::class)
interface JuneVoicePreviewBindings {
    fun inject(receiver: JuneVoicePreviewReceiver)
}
