/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.services.toolbox.api.systemclock.SystemClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Element June: keeps what the built-in voice did with the last line (how long it took, or why the phone engine read it instead),
 * shown under the "🔊 음성 엔진" setting. Only the outcome and the time are saved, never the message text.
 */
fun interface JuneSpeakNoteStore {
    fun note(text: String)
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@ContributesBinding(AppScope::class)
class DefaultJuneSpeakNoteStore(
    @ApplicationContext private val context: Context,
    private val systemClock: SystemClock,
) : JuneSpeakNoteStore {
    override fun note(text: String) {
        val time = Instant.ofEpochMilli(systemClock.epochMillis()).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
        JuneSettings.setBtSpeakVoiceNote(context, "$text $time")
    }
}
