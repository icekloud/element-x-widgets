/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.credentials.impl

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

/** Where one credential stands on one bot's Hermes vault. */
internal enum class RemoteStatus { PENDING, SENDING, SAVED, FAILED, DELETE_PENDING, DELETING }

internal data class RemoteState(
    val status: RemoteStatus = RemoteStatus.PENDING,
    /** Hermes vault item id on that bot (``vault_…``), known after the first successful put. */
    val itemId: String? = null,
    val rid: String? = null,
    val roomId: String? = null,
    val eventId: String? = null,
    val sentAt: Long = 0L,
    val error: String? = null,
)

/**
 * One credential. Plain metadata only, except [ciphertext]/[iv] (AES-GCM under the Keystore key, AAD = [id]).
 * [firstChar] and [length] are the only facts about the password kept in clear (user's decision).
 */
internal data class CredentialEntry(
    val id: String = UUID.randomUUID().toString(),
    val origin: String,
    val label: String,
    val identifier: String,
    val identifierType: String,
    val firstChar: String,
    val length: Int,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val bots: List<String>,
    val remote: Map<String, RemoteState> = emptyMap(),
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val masked: String get() = firstChar + "•".repeat((length - 1).coerceIn(0, 15)) + " (${length}자)"

    /** Bots that still need a put (value not yet on the server) or a delete. */
    fun needsPut(): List<String> = remote.filter { (bot, s) ->
        bot in bots && !deleted && (s.status == RemoteStatus.PENDING || s.status == RemoteStatus.FAILED)
    }.keys.toList()

    fun needsDelete(): List<String> = remote.filter { (_, s) -> s.status == RemoteStatus.DELETE_PENDING }.keys.toList()
}

/** A bot's published transport key as last seen (and possibly pinned). */
internal data class BotKey(val bot: String, val roomId: String, val sender: String, val kid: String, val spki: ByteArray)

internal object CredentialFormat {
    fun firstChar(password: String): String = if (password.isEmpty()) "" else String(Character.toChars(password.codePointAt(0)))

    fun length(password: String): Int = password.codePointCount(0, password.length)

    /** ``scheme://host[:port]`` like Hermes ``normalize_origin``; adds https:// when the scheme is missing. Null when invalid. */
    fun normalizeOrigin(input: String): String? {
        val raw = input.trim().let { if ("://" in it) it else "https://$it" }
        return try {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            val port = uri.port
            if (port == -1 || (scheme == "https" && port == 443) || (scheme == "http" && port == 80)) "$scheme://$host" else "$scheme://$host:$port"
        } catch (e: Exception) {
            null
        }
    }

    fun identifierType(identifier: String): String = when {
        "@" in identifier -> "email"
        identifier.isNotEmpty() && identifier.all { it.isDigit() || it in "+-() " } -> "phone"
        else -> "username"
    }
}

/**
 * Element June: credential list persisted to `noBackupFilesDir/june_credentials.json`
 * (never backed up; the app also disables backup and device transfer globally).
 */
internal class CredentialStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "june_credentials.json")

    var entries: List<CredentialEntry> = emptyList()
        private set
    var pins: Map<String, String> = emptyMap()
        private set

    fun load() {
        val json = try {
            if (file.exists()) JSONObject(file.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        entries = json.optJSONArray("entries")?.let { arr -> List(arr.length()) { readEntry(arr.getJSONObject(it)) } }.orEmpty()
        pins = json.optJSONObject("pins")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
    }

    fun update(transform: (List<CredentialEntry>) -> List<CredentialEntry>) {
        entries = transform(entries)
        save()
    }

    fun updateEntry(id: String, transform: (CredentialEntry) -> CredentialEntry?) = update { list ->
        list.mapNotNull { if (it.id == id) transform(it) else it }
    }

    fun pin(bot: String, kid: String) {
        pins = pins + (bot to kid)
        save()
    }

    private fun save() {
        val json = JSONObject()
            .put("v", 1)
            .put("entries", JSONArray().apply { entries.forEach { put(writeEntry(it)) } })
            .put("pins", JSONObject().apply { pins.forEach { (k, v) -> put(k, v) } })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString())
            tmp.delete()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun writeEntry(e: CredentialEntry) = JSONObject()
        .put("id", e.id).put("origin", e.origin).put("label", e.label)
        .put("identifier", e.identifier).put("identifier_type", e.identifierType)
        .put("first", e.firstChar).put("len", e.length)
        .put("ct", b64(e.ciphertext)).put("iv", b64(e.iv))
        .put("bots", JSONArray(e.bots)).put("deleted", e.deleted).put("updated", e.updatedAt)
        .put("remote", JSONObject().apply {
            e.remote.forEach { (bot, s) ->
                put(bot, JSONObject().put("status", s.status.name).put("item", s.itemId).put("rid", s.rid).put("room", s.roomId)
                    .put("event", s.eventId).put("sent", s.sentAt).put("err", s.error))
            }
        })

    private fun readEntry(o: JSONObject): CredentialEntry {
        val remoteJson = o.optJSONObject("remote") ?: JSONObject()
        val remote = remoteJson.keys().asSequence().associateWith { bot ->
            val r = remoteJson.getJSONObject(bot)
            RemoteState(
                status = runCatching { RemoteStatus.valueOf(r.optString("status")) }.getOrDefault(RemoteStatus.PENDING),
                itemId = r.optStringOrNull("item"),
                rid = r.optStringOrNull("rid"),
                roomId = r.optStringOrNull("room"),
                eventId = r.optStringOrNull("event"),
                sentAt = r.optLong("sent"),
                error = r.optStringOrNull("err"),
            )
        }
        val bots = o.optJSONArray("bots")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        return CredentialEntry(
            id = o.getString("id"), origin = o.getString("origin"), label = o.optString("label"),
            identifier = o.optString("identifier"), identifierType = o.optString("identifier_type", "username"),
            firstChar = o.optString("first"), length = o.optInt("len"),
            ciphertext = unb64(o.getString("ct")), iv = unb64(o.getString("iv")),
            bots = bots, remote = remote, deleted = o.optBoolean("deleted"), updatedAt = o.optLong("updated"),
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else optString(key).ifEmpty { null }
