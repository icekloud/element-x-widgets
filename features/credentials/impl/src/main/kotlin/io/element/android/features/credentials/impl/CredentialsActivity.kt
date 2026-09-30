/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.credentials.impl

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ContributesTo(AppScope::class)
interface CredentialsBindings {
    fun credentialsSessionStore(): SessionStore
}

/**
 * Element June: "자격증명 관리" — logins the bots may use, stored encrypted on this device and sent
 * sealed to each bot's Hermes vault. Screenshots and screen recording are blocked, autofill services
 * are excluded, and the password is never shown, copied or logged.
 */
class CredentialsActivity : FragmentActivity() {
    private lateinit var controller: CredentialsController

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        controller = CredentialsController(
            authenticate = ::authenticate,
            store = CredentialStore(this),
            client = VaultTransportClient(bindings<CredentialsBindings>().credentialsSessionStore()),
        )
        setContent {
            // ElementTheme so the designsystem text fields get their Compound colours (light and dark).
            ElementTheme(applySystemBarsUpdate = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CredentialsScreen(
                        controller = controller,
                        isDeviceSecure = { getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true },
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    /** Biometric or device credential. Returns false when cancelled or unavailable. */
    private suspend fun authenticate(reason: String): Boolean = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<Boolean>()
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        }
        val prompt = BiometricPrompt(
            this@CredentialsActivity,
            ContextCompat.getMainExecutor(this@CredentialsActivity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(res: BiometricPrompt.AuthenticationResult) {
                    result.complete(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    result.complete(false)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("자격증명 잠금 해제")
            .setSubtitle(reason)
            .setAllowedAuthenticators(allowed)
            .setConfirmationRequired(false)
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            result.complete(false)
        }
        result.await()
    }
}
