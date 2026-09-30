/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.credentials.impl

import android.util.Base64
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

internal const val KEY_STATE_TYPE = "app.june.vault_key"
internal const val STATUS_STATE_TYPE = "app.june.vault_status"
internal const val OP_TYPE = "app.june.vault_op"
internal const val ENVELOPE_ALG = "RSA-OAEP-256+A256GCM"

internal data class OpResult(val rid: String, val op: String, val ok: Boolean, val itemId: String?, val error: String?)

/**
 * Element June: sealed transport of credentials to a bot's Hermes vault over plain Matrix room events.
 *
 * Each bot gateway publishes an RSA-3072 public key as the state event `app.june.vault_key`
 * (state_key = its Hermes profile). The app seals `{op, password, …}` with AES-256-GCM under a fresh
 * key that is wrapped with RSA-OAEP-SHA256 for that bot only, and sends `app.june.vault_op`.
 * The homeserver, the timeline and any other client only ever see ciphertext, whether or not the
 * room is end-to-end encrypted. See the gateway side `june_vault.py` for the exact format.
 */
internal class VaultTransportClient(private val sessionStore: SessionStore) {
    private val random = SecureRandom()

    private fun open(base: String, path: String, token: String, method: String): HttpURLConnection {
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.useCaches = false
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun HttpURLConnection.body(): String = inputStream.bufferedReader().use { it.readText() }

    private fun HttpURLConnection.send(json: JSONObject) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(json.toString().toByteArray()) }
    }

    fun kidOf(spki: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(spki).joinToString("") { "%02x".format(it) }.take(32)

    /** Every bot key visible in the user's joined rooms (validated: kid must match the key bytes). Null on network error. */
    suspend fun discoverKeys(): List<BotKey>? = withContext(Dispatchers.IO) {
        try {
            val s = sessionStore.getLatestSession() ?: return@withContext null
            val rooms = open(s.homeserverUrl, "/_matrix/client/v3/joined_rooms", s.accessToken, "GET").let { c ->
                try {
                    if (c.responseCode != 200) return@withContext null
                    val arr = JSONObject(c.body()).optJSONArray("joined_rooms") ?: JSONArray()
                    List(arr.length()) { arr.getString(it) }.sorted()
                } finally {
                    c.disconnect()
                }
            }
            val out = mutableListOf<BotKey>()
            for (room in rooms) {
                val c = open(s.homeserverUrl, "/_matrix/client/v3/rooms/${enc(room)}/state", s.accessToken, "GET")
                try {
                    if (c.responseCode != 200) continue
                    val events = JSONArray(c.body())
                    for (i in 0 until events.length()) {
                        val e = events.getJSONObject(i)
                        if (e.optString("type") != KEY_STATE_TYPE) continue
                        val content = e.optJSONObject("content") ?: continue
                        val bot = e.optString("state_key")
                        val spki = runCatching { Base64.decode(content.optString("spki"), Base64.DEFAULT) }.getOrNull() ?: continue
                        if (bot.isEmpty() || content.optString("alg") != ENVELOPE_ALG || content.optString("profile") != bot) continue
                        if (kidOf(spki) != content.optString("kid")) continue
                        out += BotKey(bot = bot, roomId = room, sender = e.optString("sender"), kid = content.optString("kid"), spki = spki)
                    }
                } finally {
                    c.disconnect()
                }
            }
            // every candidate; the caller picks the pinned one (or the first) per bot
            out
        } catch (e: Exception) {
            Timber.d("June credentials: key discovery failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** Seals [payload] for [key]. [payload] must already contain `op`; `rid` and `ts` are added here. */
    fun seal(key: BotKey, rid: String, payload: JSONObject): JSONObject {
        payload.put("rid", rid).put("ts", System.currentTimeMillis() / 1000)
        val aesKey = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        try {
            val gcm = Cipher.getInstance("AES/GCM/NoPadding")
            gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            gcm.updateAAD("$OP_TYPE|1|${key.bot}|${key.kid}|$rid".toByteArray(Charsets.UTF_8))
            val ct = gcm.doFinal(plain)
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(key.spki))
            val oaep = Cipher.getInstance("RSA/ECB/OAEPPadding")
            oaep.init(Cipher.ENCRYPT_MODE, pub, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            val ek = oaep.doFinal(aesKey)
            fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return JSONObject()
                .put("v", 1).put("alg", ENVELOPE_ALG).put("to", key.bot).put("kid", key.kid).put("rid", rid)
                .put("ek", b64(ek)).put("iv", b64(iv)).put("ct", b64(ct))
        } finally {
            plain.fill(0)
            aesKey.fill(0)
        }
    }

    /** Sends a sealed envelope. Returns the event id, or null on failure. */
    suspend fun sendEnvelope(roomId: String, envelope: JSONObject): String? = withContext(Dispatchers.IO) {
        try {
            val s = sessionStore.getLatestSession() ?: return@withContext null
            val txn = UUID.randomUUID().toString().replace("-", "")
            val c = open(s.homeserverUrl, "/_matrix/client/v3/rooms/${enc(roomId)}/send/$OP_TYPE/$txn", s.accessToken, "PUT")
            try {
                c.send(envelope)
                if (c.responseCode in 200..299) JSONObject(c.body()).optString("event_id").ifEmpty { null } else null
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Timber.d("June credentials: send failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** Latest results the bot published for us (request ids and outcomes only). Null on error. */
    suspend fun fetchResults(bot: String, roomId: String): List<OpResult>? = withContext(Dispatchers.IO) {
        try {
            val s = sessionStore.getLatestSession() ?: return@withContext null
            val c = open(s.homeserverUrl, "/_matrix/client/v3/rooms/${enc(roomId)}/state/$STATUS_STATE_TYPE/${enc(bot)}", s.accessToken, "GET")
            try {
                when (c.responseCode) {
                    200 -> {
                        val arr = JSONObject(c.body()).optJSONArray("results") ?: JSONArray()
                        List(arr.length()) { i ->
                            val o = arr.getJSONObject(i)
                            OpResult(
                                rid = o.optString("rid"),
                                op = o.optString("op"),
                                ok = o.optBoolean("ok"),
                                itemId = o.optString("id").ifEmpty { null },
                                error = o.optString("err").ifEmpty { null },
                            )
                        }
                    }
                    404 -> emptyList()
                    else -> null
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Timber.d("June credentials: status fetch failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** Redacts our own op event once the bot has consumed it (the ciphertext is useless to others, but keep the timeline clean). */
    suspend fun redact(roomId: String, eventId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val s = sessionStore.getLatestSession() ?: return@withContext false
            val txn = UUID.randomUUID().toString().replace("-", "")
            val c = open(s.homeserverUrl, "/_matrix/client/v3/rooms/${enc(roomId)}/redact/${enc(eventId)}/$txn", s.accessToken, "PUT")
            try {
                c.send(JSONObject().put("reason", "june vault op consumed"))
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Timber.d("June credentials: redact failed (%s)", e.javaClass.simpleName)
            false
        }
    }
}
