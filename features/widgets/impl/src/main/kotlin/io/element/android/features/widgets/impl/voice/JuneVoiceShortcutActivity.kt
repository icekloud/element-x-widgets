/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import timber.log.Timber

/**
 * Element June: the "커맨더 음성" launcher icon. It draws nothing: it asks [JuneVoiceService] to start the
 * recording (or to send the one in progress) and finishes at once, the same way the bot picker icon acts
 * straight from the home screen.
 */
class JuneVoiceShortcutActivity : ComponentActivity() {
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCapture()
        } else {
            JuneVoiceNotifications(this).also { it.ensureChannel() }.showOutcome(JuneVoiceOutcome.MissingPermission)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCapture()
            finish()
        } else {
            // A service cannot ask for a permission, this transparent screen does it
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCapture() {
        val intent = JuneVoiceService.toggleIntent(this)
        runCatchingExceptions {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.onFailure {
            Timber.tag("JuneVoice").e(it, "Cannot start the voice shortcut service")
            JuneVoiceNotifications(this).also { notifications -> notifications.ensureChannel() }
                .showOutcome(JuneVoiceOutcome.RecorderError)
        }
    }
}
