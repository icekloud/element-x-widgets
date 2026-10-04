/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.textcomposer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.wysiwyg.display.TextDisplay
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Element June: while recording a voice message, the record button sends it at once and the X on the left discards it.
 */
class TextComposerVoiceQuickSendTest : RobolectricTest() {
    @Test
    fun `pressing the record button again while recording sends the recording`() = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageRecorderEvent>()
        var deleteCount = 0
        var sendVoiceMessageCount = 0
        setTextComposer(
            voiceMessageState = aRecordingState(),
            onVoiceRecorderEvent = { events += it },
            onDeleteVoiceMessage = { deleteCount++ },
            onSendVoiceMessage = { sendVoiceMessageCount++ },
        )
        onNodeWithContentDescription(getString(R.string.june_a11y_voice_message_stop_and_send)).performClick()

        assertThat(events).containsExactly(VoiceMessageRecorderEvent.Send)
        assertThat(deleteCount).isEqualTo(0)
        assertThat(sendVoiceMessageCount).isEqualTo(0)
    }

    @Test
    fun `while recording the stop recording button is not offered anymore`() = runAndroidComposeUiTest {
        setTextComposer(voiceMessageState = aRecordingState())
        onNodeWithContentDescription(getString(R.string.june_a11y_voice_message_stop_and_send)).assertIsDisplayed()
        onNodeWithContentDescription(getString(CommonStrings.a11y_voice_message_stop_recording)).assertDoesNotExist()
        onNodeWithContentDescription(getString(CommonStrings.a11y_delete)).assertDoesNotExist()
    }

    @Test
    fun `pressing the X while recording cancels the recording`() = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageRecorderEvent>()
        var deleteCount = 0
        var sendVoiceMessageCount = 0
        setTextComposer(
            voiceMessageState = aRecordingState(),
            onVoiceRecorderEvent = { events += it },
            onDeleteVoiceMessage = { deleteCount++ },
            onSendVoiceMessage = { sendVoiceMessageCount++ },
        )
        onNodeWithContentDescription(getString(CommonStrings.action_cancel)).performClick()

        assertThat(events).containsExactly(VoiceMessageRecorderEvent.Cancel)
        assertThat(deleteCount).isEqualTo(0)
        assertThat(sendVoiceMessageCount).isEqualTo(0)
    }

    @Test
    fun `the X has a touch target of at least 48dp`() = runAndroidComposeUiTest {
        setTextComposer(voiceMessageState = aRecordingState())
        onNodeWithContentDescription(getString(CommonStrings.action_cancel))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the preview keeps its delete and send buttons`() = runAndroidComposeUiTest {
        var deleteCount = 0
        var sendVoiceMessageCount = 0
        setTextComposer(
            voiceMessageState = aPreviewState(),
            onDeleteVoiceMessage = { deleteCount++ },
            onSendVoiceMessage = { sendVoiceMessageCount++ },
        )
        onNodeWithContentDescription(getString(CommonStrings.action_cancel)).assertDoesNotExist()
        onNodeWithContentDescription(getString(CommonStrings.action_send_voice_message)).performClick()
        onNodeWithContentDescription(getString(CommonStrings.a11y_delete)).performClick()

        assertThat(sendVoiceMessageCount).isEqualTo(1)
        assertThat(deleteCount).isEqualTo(1)
    }

    @Test
    fun `an idle composer still starts a recording`() = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageRecorderEvent>()
        setTextComposer(
            voiceMessageState = VoiceMessageState.Idle,
            onVoiceRecorderEvent = { events += it },
        )
        onNodeWithContentDescription(getString(CommonStrings.a11y_voice_message_record)).performClick()
        onNodeWithContentDescription(getString(CommonStrings.action_cancel)).assertDoesNotExist()

        assertThat(events).containsExactly(VoiceMessageRecorderEvent.Start)
    }

    private fun AndroidComposeUiTest<ComponentActivity>.getString(resId: Int): String {
        return activity!!.getString(resId)
    }

    private fun aRecordingState() = VoiceMessageState.Recording(
        duration = 3.seconds,
        levels = persistentListOf(0.1f, 0.5f, 0.3f),
    )

    private fun aPreviewState() = VoiceMessageState.Preview(
        isSending = false,
        isPlaying = false,
        showCursor = false,
        playbackProgress = 0f,
        time = 3.seconds,
        duration = 3.seconds,
        waveform = persistentListOf(0.1f, 0.5f, 0.3f),
    )

    private fun AndroidComposeUiTest<ComponentActivity>.setTextComposer(
        voiceMessageState: VoiceMessageState,
        onVoiceRecorderEvent: (VoiceMessageRecorderEvent) -> Unit = {},
        onSendVoiceMessage: () -> Unit = {},
        onDeleteVoiceMessage: () -> Unit = {},
    ) {
        setContent {
            TextComposer(
                state = aTextEditorStateMarkdown(initialText = "", initialFocus = true),
                voiceMessageState = voiceMessageState,
                composerMode = MessageComposerMode.Normal,
                onRequestFocus = {},
                onSendMessage = {},
                onResetComposerMode = {},
                onAddAttachment = {},
                onDismissTextFormatting = {},
                onVoiceRecorderEvent = onVoiceRecorderEvent,
                onVoicePlayerEvent = {},
                onSendVoiceMessage = onSendVoiceMessage,
                onDeleteVoiceMessage = onDeleteVoiceMessage,
                onError = {},
                onTyping = {},
                onReceiveSuggestion = {},
                onSelectRichContent = null,
                resolveMentionDisplay = { _, _ -> TextDisplay.Plain },
                resolveAtRoomMentionDisplay = { TextDisplay.Plain },
            )
        }
    }
}
