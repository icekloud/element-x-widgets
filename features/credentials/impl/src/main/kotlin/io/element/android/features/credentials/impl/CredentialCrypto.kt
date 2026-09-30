/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.credentials.impl

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Element June: local encryption of credential passwords.
 *
 * One AES-256-GCM key lives in the Android Keystore (StrongBox when the device has it, otherwise
 * the TEE). Keystore keys can never be exported. The key requires user authentication (biometric
 * or device credential) within the last [AUTH_SECONDS] seconds for every encrypt/decrypt, so
 * `cipher.init` throws [android.security.keystore.UserNotAuthenticatedException] until the user
 * unlocks; the caller then shows the prompt and retries.
 */
internal object CredentialCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "june_credentials_aes_v1"
    private const val AUTH_SECONDS = 30
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    private fun key(): SecretKey = existingKey() ?: generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            generator.init(builder.build())
            generator.generateKey()
        } catch (e: Exception) {
            // StrongBoxUnavailableException (API 28 class, so not referenced directly): fall back to the TEE.
            if (!strongBox) throw e
            generate(strongBox = false)
        }
    }

    /** Where the key lives, for display ("StrongBox", "보안 하드웨어(TEE)", "소프트웨어", or "아직 없음"). */
    fun backendLabel(): String = try {
        val key = existingKey()
        if (key == null) {
            "아직 없음"
        } else {
            val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "보안 하드웨어(TEE)"
                    else -> "소프트웨어"
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) "보안 하드웨어" else "소프트웨어"
            }
        }
    } catch (e: Exception) {
        "확인 실패(${e.javaClass.simpleName})"
    }

    /** Returns (ciphertext+tag, iv). The IV is chosen by the Keystore. */
    fun encrypt(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        return cipher.doFinal(plain) to cipher.iv
    }

    fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /** Drops the key (after it was permanently invalidated, e.g. the screen lock was removed). */
    fun resetKey() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }
}
