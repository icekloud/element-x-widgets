/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import io.element.android.features.widgets.impl.R
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlin.time.Duration

/**
 * Element June: the notifications of the voice shortcut — the ongoing one while recording (with 보내기 and 취소),
 * and the short ones telling how the recording ended.
 */
class JuneVoiceNotifications(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = context.getSystemService<NotificationManager>()?.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(CHANNEL_ID, "커맨더 음성", NotificationManager.IMPORTANCE_LOW).apply {
            description = "아이콘으로 녹음해 커맨더에게 보낼 때 보이는 알림"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    /** The ongoing notification of a recording, [elapsed] is drawn as mm:ss. */
    fun recording(elapsed: Duration): Notification {
        return baseBuilder()
            .setContentTitle("커맨더 음성 녹음 중")
            .setContentText("${elapsed.asClock()} · 아이콘을 다시 누르면 보냅니다")
            .setOngoing(true)
            .setUsesChronometer(false)
            .addAction(0, "보내기", servicePendingIntent(JuneVoiceService.ACTION_SEND, REQUEST_SEND))
            .addAction(0, "취소", servicePendingIntent(JuneVoiceService.ACTION_CANCEL, REQUEST_CANCEL))
            .build()
    }

    fun sending(): Notification {
        return baseBuilder()
            .setContentTitle("커맨더 음성 보내는 중")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    /**
     * The notification shown once the capture is over; a recording that is kept offers 다시 보내기 and 지우기.
     */
    fun outcome(outcome: JuneVoiceOutcome): Notification? {
        val keepsRecording = outcome == JuneVoiceOutcome.SendFailed || outcome == JuneVoiceOutcome.MaxLengthReached
        val title = when (outcome) {
            JuneVoiceOutcome.Sent -> "커맨더에게 보냈습니다"
            JuneVoiceOutcome.TooShort -> return null
            JuneVoiceOutcome.Cancelled -> return null
            JuneVoiceOutcome.MaxLengthReached -> "최대 길이에 도달해 녹음을 멈췄습니다"
            JuneVoiceOutcome.SendFailed -> "보내지 못했습니다"
            JuneVoiceOutcome.NothingRecorded -> return null
            JuneVoiceOutcome.MissingPermission -> "마이크 권한이 필요합니다"
            JuneVoiceOutcome.InCall -> "통화 중에는 녹음할 수 없습니다"
            JuneVoiceOutcome.MicrophoneBusy -> "마이크를 다른 앱이 쓰고 있습니다"
            JuneVoiceOutcome.RecorderError -> "녹음을 시작하지 못했습니다"
        }
        val builder = baseBuilder()
            .setContentTitle(title)
            .setAutoCancel(true)
        when {
            keepsRecording -> builder
                .setContentText("녹음은 그대로 있습니다")
                .addAction(0, "다시 보내기", servicePendingIntent(JuneVoiceService.ACTION_RETRY, REQUEST_RETRY))
                .addAction(0, "지우기", servicePendingIntent(JuneVoiceService.ACTION_DISCARD, REQUEST_DISCARD))
            outcome == JuneVoiceOutcome.Sent -> builder.setTimeoutAfter(SENT_TIMEOUT_MS)
            else -> builder.setTimeoutAfter(ERROR_TIMEOUT_MS)
        }
        return builder.build()
    }

    // The notification permission is declared by the app; a refused permission only hides these notifications
    @SuppressLint("MissingPermission")
    fun showOutcome(outcome: JuneVoiceOutcome) {
        val notification = outcome(outcome)
        if (notification == null) {
            manager.cancel(RESULT_NOTIFICATION_ID)
            return
        }
        runCatchingExceptions { manager.notify(RESULT_NOTIFICATION_ID, notification) }
    }

    @SuppressLint("MissingPermission")
    fun update(notification: Notification) {
        runCatchingExceptions { manager.notify(ONGOING_NOTIFICATION_ID, notification) }
    }

    fun cancelResult() {
        runCatchingExceptions { manager.cancel(RESULT_NOTIFICATION_ID) }
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.june_voice_shortcut_small)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(true)
            .setShowWhen(false)
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, JuneVoiceService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }

    companion object {
        const val CHANNEL_ID = "JUNE_VOICE_SHORTCUT"
        const val ONGOING_NOTIFICATION_ID = 77_301
        const val RESULT_NOTIFICATION_ID = 77_302
        private const val REQUEST_SEND = 1
        private const val REQUEST_CANCEL = 2
        private const val REQUEST_RETRY = 3
        private const val REQUEST_DISCARD = 4
        private const val SENT_TIMEOUT_MS = 4_000L
        private const val ERROR_TIMEOUT_MS = 8_000L
    }
}

/** mm:ss, the way the composer draws the recording time. */
fun Duration.asClock(): String {
    val totalSeconds = inWholeSeconds
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
