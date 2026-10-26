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
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.designsystem.june.JuneVoiceEngineChoice
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

private const val TAG = "JuneVoicePreview"

/** The sentence read by the "내장 음성 미리듣기" button: it has a time and a percentage, so the Korean reading can be heard too. */
internal const val JUNE_VOICE_PREVIEW_TEXT = "좋은 아침입니다. 오늘 오후 3시에 회의가 있고, 비가 올 확률은 20%입니다."

/** Element June: reads a fixed sentence with the chosen voice, to hear it and see how long it takes on this phone. */
fun interface JuneVoicePreview {
    fun play()
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultJuneVoicePreview(
    @ApplicationContext private val context: Context,
    private val engine: SupertonicJuneSpeechEngine,
    private val samsungEngine: SamsungJuneSpeechEngine,
    private val phoneEngine: DefaultJuneSpeechEngine,
    private val noteStore: JuneSpeakNoteStore,
    @AppCoroutineScope
    private val coroutineScope: CoroutineScope,
) : JuneVoicePreview {
    /** Only one preview at a time: pressing the button twice does not read the sentence over itself. */
    private val lock = Mutex()

    override fun play() {
        coroutineScope.launch {
            if (!lock.tryLock()) {
                Timber.tag(TAG).d("A preview is already playing")
                return@launch
            }
            try {
                JuneSettings.setBtSpeakVoiceNote(context, "미리듣기 준비 중…")
                read()
            } finally {
                lock.unlock()
            }
        }
    }

    private suspend fun read() {
        // The preview keeps the audio focus like a read line does, so the music of other apps is lowered while it plays
        val focused = runCatchingExceptions { phoneEngine.requestFocus() }.getOrDefault(false)
        // 앱30: the preview is read with the chosen voice, so the Samsung one can be heard too. The phone engine has nothing to
        // preview, so the built-in voice is read then, as it was before.
        val fallback = JuneVoiceFallbackEngine(
            builtIn = engine,
            phone = phoneEngine,
            chosenEngine = { JuneSettings.btSpeakEngine(context).takeIf { it != JuneVoiceEngineChoice.Phone } ?: JuneVoiceEngineChoice.BuiltIn },
            onNote = { note -> noteStore.note("미리듣기 · $note") },
            samsung = samsungEngine,
        )
        try {
            val failure = fallback.open()
            if (failure != null) {
                noteStore.note("미리듣기 실패(${failure.label})")
                return
            }
            fallback.speak(JUNE_VOICE_PREVIEW_TEXT)
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "The preview could not be read")
            noteStore.note("미리듣기 실패")
        } finally {
            if (!focused) Timber.tag(TAG).d("The preview played without the audio focus")
            runCatchingExceptions { fallback.close() }
        }
    }
}
