/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.root

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.june.JuneSamsungVoiceChoice
import io.element.android.libraries.designsystem.june.JuneSettings
import io.element.android.libraries.designsystem.june.JuneVoiceEngineChoice
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text
import timber.log.Timber

internal const val JUNE_VOICE_ENGINE_TITLE = "🔊 음성 엔진"
internal const val JUNE_VOICE_ENGINE_DESCRIPTION =
    "🔊 줄을 읽을 목소리입니다. 누를 때마다 폰 기본 → 내장 → 삼성 순으로 바뀝니다. 폰 기본은 폰에 설치된 음성을, " +
        "내장은 앱 안에 들어 있는 한국어 음성(슈퍼토닉3 F1)을, 삼성은 앱 안에 들어 있는 삼성 한국어 음성을 씁니다. " +
        "고른 음성이 읽지 못한 줄은 폰 기본으로 읽습니다."
internal const val JUNE_SAMSUNG_VOICE_TITLE = "삼성 목소리"
internal const val JUNE_SAMSUNG_VOICE_DESCRIPTION =
    "삼성 음성으로 읽을 목소리입니다. 누를 때마다 1 → 2 → 3 → 4 순으로 바뀝니다. 네 목소리 모두 한국어입니다. " +
        "처음 고른 목소리는 쓰기 전에 음성 파일을 폰에 설치하느라 몇 초 걸리고, 목소리마다 약 44MB를 씁니다. " +
        "바꾼 목소리는 다음 줄부터 적용됩니다."
internal const val JUNE_VOICE_PREVIEW_TITLE = "음성 미리듣기"
internal const val JUNE_VOICE_PREVIEW_DESCRIPTION = "고른 음성으로 짧은 문장을 읽어 목소리와 걸리는 시간을 확인합니다(폰 기본일 때는 내장 음성)."
internal const val JUNE_VOICE_NOTE_PREFIX = "마지막 내장 음성: "

/** The receiver of the push module that reads the preview sentence: the settings screen cannot call the reader itself. */
private const val PREVIEW_RECEIVER = "io.element.android.libraries.push.impl.june.JuneVoicePreviewReceiver"

/** Element June: the supporting text of the engine setting, with which voice is used and what the built-in one did last. */
internal fun juneVoiceEngineSupportingText(choice: JuneVoiceEngineChoice, note: String?): String {
    val head = "$JUNE_VOICE_ENGINE_DESCRIPTION\n지금: ${choice.label}"
    return if (note.isNullOrBlank()) head else "$head\n$JUNE_VOICE_NOTE_PREFIX$note"
}

/** 앱32: the supporting text of the Samsung voice setting, with the number of the voice in use. */
internal fun juneSamsungVoiceSupportingText(voice: JuneSamsungVoiceChoice): String =
    "$JUNE_SAMSUNG_VOICE_DESCRIPTION\n지금: ${voice.label}"

/**
 * 앱27: "🔊 음성 엔진" and the preview button. 앱30: pressing the setting goes through the three voices (폰 기본 → 내장 → 삼성)
 * and the one in use is shown next to it. The phone engine is the default: another voice is only used once it has been chosen
 * here. 앱32: when the Samsung engine is the chosen one, a second line chooses which of its four voices reads, by its number.
 * Stored on the device only.
 */
@Composable
internal fun JuneVoiceEnginePreference(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val choice = JuneSettings.btSpeakEngine(context)
    val note = JuneSettings.btSpeakVoiceNote(context)
    ListItem(
        modifier = modifier,
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Chat())),
        content = { Text(JUNE_VOICE_ENGINE_TITLE) },
        supportingContent = { Text(juneVoiceEngineSupportingText(choice, note)) },
        trailingContent = ListItemContent.Text(choice.shortLabel),
        onClick = { JuneSettings.setBtSpeakEngine(context, choice.next()) },
    )
    if (choice == JuneVoiceEngineChoice.Samsung) {
        val voice = JuneSettings.samsungVoice(context)
        ListItem(
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.UserProfileSolid())),
            content = { Text(JUNE_SAMSUNG_VOICE_TITLE) },
            supportingContent = { Text(juneSamsungVoiceSupportingText(voice)) },
            trailingContent = ListItemContent.Text(voice.shortLabel),
            onClick = { JuneSettings.setSamsungVoice(context, voice.next()) },
        )
    }
    ListItem(
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.VolumeOnSolid())),
        content = { Text(JUNE_VOICE_PREVIEW_TITLE) },
        supportingContent = { Text(JUNE_VOICE_PREVIEW_DESCRIPTION) },
        onClick = { juneSendVoicePreview(context) },
    )
}

private fun juneSendVoicePreview(context: Context) {
    runCatchingExceptions {
        context.sendBroadcast(Intent().setClassName(context, PREVIEW_RECEIVER))
    }.onFailure { Timber.e(it, "Cannot ask for the built-in voice preview") }
}
