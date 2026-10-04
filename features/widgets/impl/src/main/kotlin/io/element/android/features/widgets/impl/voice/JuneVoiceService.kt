/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.element.android.features.widgets.impl.WidgetBindings
import io.element.android.features.widgets.impl.WidgetStore
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Element June: records a voice message without any app screen and sends it to the commander room.
 *
 * Started by [JuneVoiceShortcutActivity] (the "커맨더 음성" launcher icon): the first tap starts the recording,
 * the next one stops it and sends it. The ongoing notification shows the elapsed time and offers 보내기 and 취소.
 */
class JuneVoiceService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: WidgetStore
    private lateinit var notifications: JuneVoiceNotifications
    private lateinit var feedback: AndroidJuneVoiceFeedback
    private lateinit var capture: JuneVoiceCapture
    private var tickerJob: Job? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        val repository = bindings<WidgetBindings>().widgetRoomRepository()
        store = repository.store
        notifications = JuneVoiceNotifications(this)
        notifications.ensureChannel()
        feedback = AndroidJuneVoiceFeedback(this, store)
        capture = JuneVoiceCapture(
            recorder = AndroidJuneVoiceRecorderGate(this, serviceScope + Dispatchers.IO),
            sender = JuneVoiceRoomSender(repository),
            feedback = feedback,
            pendingStore = JuneVoicePendingPrefs(store),
            minDuration = VoiceMessageRecorderEvent.Send.minDuration,
            clock = System::currentTimeMillis,
        )
        serviceScope.launch {
            capture.state.collect(::onStateChanged)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives a few seconds to show the notification of a foreground service, whatever the action is.
        startForegroundIfNeeded()
        when (intent?.action) {
            ACTION_SEND -> serviceScope.launch { capture.sendNow() }
            ACTION_CANCEL -> serviceScope.launch { capture.cancel() }
            ACTION_RETRY -> serviceScope.launch { capture.retryPending() }
            ACTION_DISCARD -> serviceScope.launch { capture.discardPending() }
            else -> serviceScope.launch { capture.toggle() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tickerJob?.cancel()
        feedback.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away: keep the recording instead of losing it
        if (capture.state.value is JuneVoiceCaptureState.Recording) {
            serviceScope.launch { capture.keepAndStop() }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun onStateChanged(state: JuneVoiceCaptureState) {
        when (state) {
            is JuneVoiceCaptureState.Recording -> {
                notifications.cancelResult()
                startTicker(state.startedAtMillis)
            }
            JuneVoiceCaptureState.Sending, JuneVoiceCaptureState.Cancelling -> {
                tickerJob?.cancel()
                notifications.update(notifications.sending())
            }
            is JuneVoiceCaptureState.Done -> {
                tickerJob?.cancel()
                feedback.release()
                notifications.showOutcome(state.outcome)
                stopEverything()
            }
            JuneVoiceCaptureState.Idle -> Unit
        }
    }

    /** Refreshes the elapsed time once a second, and stops the recording at the configured maximum length. */
    private fun startTicker(startedAtMillis: Long) {
        tickerJob?.cancel()
        val maxDuration = store.getVoiceShortcutMaxSeconds().seconds
        tickerJob = serviceScope.launch {
            while (true) {
                val elapsed = (System.currentTimeMillis() - startedAtMillis).milliseconds
                if (elapsed >= maxDuration) {
                    capture.stopAtMaxLength()
                    return@launch
                }
                notifications.update(notifications.recording(elapsed))
                delay(TICK_MS)
            }
        }
    }

    private fun startForegroundIfNeeded() {
        if (isForeground) return
        runCatchingExceptions {
            ServiceCompat.startForeground(
                this,
                JuneVoiceNotifications.ONGOING_NOTIFICATION_ID,
                notifications.recording(0.milliseconds),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
            isForeground = true
        }.onFailure {
            // Android 12 and above refuse to start a foreground service from the background in some states
            Timber.tag(TAG).e(it, "Cannot go to the foreground")
            notifications.showOutcome(JuneVoiceOutcome.RecorderError)
            stopEverything()
        }
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    companion object {
        const val ACTION_TOGGLE = "io.element.android.features.widgets.voice.TOGGLE"
        const val ACTION_SEND = "io.element.android.features.widgets.voice.SEND"
        const val ACTION_CANCEL = "io.element.android.features.widgets.voice.CANCEL"
        const val ACTION_RETRY = "io.element.android.features.widgets.voice.RETRY"
        const val ACTION_DISCARD = "io.element.android.features.widgets.voice.DISCARD"
        private const val TAG = "JuneVoice"
        private const val TICK_MS = 1_000L

        fun toggleIntent(context: Context): Intent {
            return Intent(context, JuneVoiceService::class.java).setAction(ACTION_TOGGLE)
        }
    }
}
