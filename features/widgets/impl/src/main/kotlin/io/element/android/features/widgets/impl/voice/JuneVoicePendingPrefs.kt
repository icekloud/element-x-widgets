/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import io.element.android.features.widgets.impl.WidgetStore
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Element June: keeps the recording that could not be sent in the widget preferences, so it survives the
 * service being stopped and can be sent again from the notification.
 */
class JuneVoicePendingPrefs(
    private val store: WidgetStore,
) : JuneVoicePendingStore {
    override fun save(recording: JuneVoiceRecording) {
        store.saveVoiceShortcutPending(
            path = recording.file.path,
            mimeType = recording.mimeType,
            durationMs = recording.duration.inWholeMilliseconds,
            waveform = recording.waveform,
        )
    }

    override fun load(): JuneVoiceRecording? {
        val stored = store.getVoiceShortcutPending() ?: return null
        val file = File(stored.path)
        if (!file.exists()) {
            store.saveVoiceShortcutPending(null)
            return null
        }
        return JuneVoiceRecording(
            file = file,
            mimeType = stored.mimeType,
            waveform = stored.waveform,
            duration = stored.durationMs.milliseconds,
        )
    }

    override fun clear() {
        store.saveVoiceShortcutPending(null)
    }
}
