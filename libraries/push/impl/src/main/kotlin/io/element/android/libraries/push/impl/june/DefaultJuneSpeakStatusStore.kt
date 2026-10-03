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

/**
 * Element June: saves the outcome of the last attempt to read a 🔊 line on the device (june_settings), for instance "읽음 19:36", shown
 * under the "블루투스 이어폰 연결 시 🔊 줄 읽기" setting. Only the outcome and the time are saved, never the message text.
 */
@ContributesBinding(AppScope::class)
class DefaultJuneSpeakStatusStore(
    @ApplicationContext private val context: Context,
    private val systemClock: SystemClock,
) : JuneSpeakStatusStore {
    override fun record(result: JuneSpeakResult) {
        JuneSettings.setBtSpeakStatus(context, juneSpeakResultText(result, systemClock.epochMillis()))
    }
}
