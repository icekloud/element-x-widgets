/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalEncodingApi::class)

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.core.data.tryOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An attachment encrypted the Matrix way (client-server spec "Sending encrypted attachments", v2): AES-256-CTR with a
 * random key and a random IV whose counter half is zero, plus the SHA-256 of the cipher text. The [fileJson] is the
 * `EncryptedFile` the server and the media viewer need to read it back (설계 §e).
 */
internal class MemoEncryptedFile(val cipher: ByteArray, private val key: ByteArray, private val iv: ByteArray) {
    fun fileJson(mxc: String, mime: String?): JSONObject {
        val jwk = JSONObject()
            .put("kty", "oct")
            .put("key_ops", JSONArray(listOf("encrypt", "decrypt")))
            .put("alg", "A256CTR")
            .put("k", Base64.UrlSafe.encode(key).trimEnd('='))
            .put("ext", true)
        val sha = MessageDigest.getInstance("SHA-256").digest(cipher)
        val o = JSONObject()
            .put("url", mxc)
            .put("key", jwk)
            .put("iv", Base64.Default.encode(iv).trimEnd('='))
            .put("hashes", JSONObject().put("sha256", Base64.Default.encode(sha).trimEnd('=')))
            .put("v", "v2")
        mime?.let { o.put("mimetype", it) }
        return o
    }
}

internal fun memoEncryptAttachment(plain: ByteArray, random: SecureRandom = SecureRandom()): MemoEncryptedFile {
    val key = ByteArray(32).also(random::nextBytes)
    // The upper 8 bytes are random, the lower 8 bytes (the counter) start at zero (spec: "the counter must be zero").
    val iv = ByteArray(16).also { random.nextBytes(it) }.also { for (i in 8 until 16) it[i] = 0 }
    return MemoEncryptedFile(memoAesCtr(plain, key, iv), key, iv)
}

/** AES-256-CTR both ways (the same operation decrypts). */
internal fun memoAesCtr(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/CTR/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return c.doFinal(data)
}

/**
 * The json of `MediaSource(url, json)` for a memo attachment. The SDK reads it as a ruma `MediaSource`, which is
 * encrypted only when the top level has a `file` key; a bare `EncryptedFile` (top level `url`) is read as a plain mxc
 * and the downloaded bytes are not decrypted (앱6 원인분석_사진.md). The memo keeps the bare `EncryptedFile` (as the
 * server sends it, without `mimetype`), so it is wrapped here. Anything else is returned as it is.
 */
internal fun memoMediaSourceJson(fileJson: String?): String? {
    val o = fileJson?.let { tryOrNull { JSONObject(it) } } ?: return fileJson
    if (o.has("file") || !o.has("key") || !o.has("iv")) return fileJson
    return JSONObject().put("file", o).toString()
}

/** Reads back the key of a `file` JSON (for the tests and the interoperability check). */
internal fun memoFileKey(file: JSONObject): Pair<ByteArray, ByteArray> {
    fun pad(s: String) = s + "=".repeat((4 - s.length % 4) % 4)
    val k = Base64.UrlSafe.decode(pad(file.getJSONObject("key").getString("k")))
    val iv = Base64.Default.decode(pad(file.getString("iv")))
    return k to iv
}

private const val STATE_DEVICE = "device"

/** This phone in the change stamps: made once and kept with the database (the same value the local backend uses). */
internal fun memoDeviceOf(db: MemoDb): String = db.state(STATE_DEVICE) ?: memoNewDeviceId().also { db.setState(STATE_DEVICE, it) }
