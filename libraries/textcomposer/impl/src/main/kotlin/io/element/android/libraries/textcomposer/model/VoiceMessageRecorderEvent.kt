/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

sealed interface VoiceMessageRecorderEvent {
    data object Start : VoiceMessageRecorderEvent
    data object Stop : VoiceMessageRecorderEvent
    data object Cancel : VoiceMessageRecorderEvent

    /**
     * Element June: stop the recording and send it at once, without the preview step.
     * A recording shorter than [minDuration] is treated as a mistaken tap and discarded without sending.
     */
    data object Send : VoiceMessageRecorderEvent {
        val minDuration: Duration = 700.milliseconds

        /** Whether a recording of [duration] is long enough to be sent rather than discarded. */
        fun isLongEnough(duration: Duration): Boolean = duration >= minDuration
    }
}
