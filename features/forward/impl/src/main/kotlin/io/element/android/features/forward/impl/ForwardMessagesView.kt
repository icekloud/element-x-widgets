/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.forward.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun ForwardMessagesView(
    state: ForwardMessagesState,
    onForwardSuccess: (List<RoomId>) -> Unit,
) {
    // Element June: optional context comment, sent as "[전달: <room>, 맥락:<comment>]" before the forwarded message
    if (state.pendingRoomIds != null) {
        var comment by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { state.eventSink(ForwardMessagesEvent.CancelForward) },
            title = { Text("전달") },
            text = {
                Column {
                    Text("받는 방에 \"[전달: ${state.sourceName ?: "알 수 없는 방"}]\" 머리말이 함께 갑니다. 맥락을 적으면 머리말에 붙습니다(선택).")
                    androidx.compose.material3.OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("맥락 (선택)") },
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { state.eventSink(ForwardMessagesEvent.ConfirmForward(comment)) }) { Text("전달") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { state.eventSink(ForwardMessagesEvent.CancelForward) }) { Text("취소") }
            },
        )
    }
    AsyncActionView(
        async = state.forwardAction,
        onSuccess = {
            onForwardSuccess(it)
        },
        errorMessage = {
            stringResource(id = CommonStrings.error_unknown)
        },
        onErrorDismiss = {
            state.eventSink(ForwardMessagesEvent.ClearError)
        },
    )
}

@PreviewsDayNight
@Composable
internal fun ForwardMessagesViewPreview(@PreviewParameter(ForwardMessagesStatePreviewParam::class) state: ForwardMessagesState) = ElementPreview {
    ForwardMessagesView(
        state = state,
        onForwardSuccess = {}
    )
}
