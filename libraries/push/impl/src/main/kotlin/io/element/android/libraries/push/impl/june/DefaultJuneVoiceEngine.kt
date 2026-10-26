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
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.di.annotations.ApplicationContext

/**
 * Element June: the [JuneSpeechEngine] the reader uses. It reads with the voice chosen in the settings (the phone engine by default,
 * or one of the two Korean voices built into the app) and falls back to the phone engine for the lines that voice cannot read.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultJuneVoiceEngine(
    @ApplicationContext context: Context,
    phone: DefaultJuneSpeechEngine,
    builtIn: SupertonicJuneSpeechEngine,
    samsung: SamsungJuneSpeechEngine,
    noteStore: JuneSpeakNoteStore,
) : JuneSpeechEngine by JuneVoiceFallbackEngine(
    builtIn = builtIn,
    phone = phone,
    chosenEngine = { JuneSettings.btSpeakEngine(context) },
    onNote = { note -> noteStore.note(note) },
    samsung = samsung,
)
