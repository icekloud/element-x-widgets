/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.credentials.impl

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

internal enum class KeyState { TRUSTED, CHANGED }

internal data class KeyView(val bot: String, val key: BotKey, val state: KeyState)

/** Bots that browse for the user (Hermes profiles). Bots that published a transport key are added to this list. */
internal val DEFAULT_BOTS = listOf("commander", "research", "life", "planner", "strategist", "briefing", "glados", "coder")

private const val PUT_TIMEOUT_MILLIS = 120_000L

/** Element June: state and actions of the credentials screen. Never logs or exposes a password. */
internal class CredentialsController(
    /** Shows the biometric / device-credential prompt with the given reason; true when unlocked. */
    private val authenticate: suspend (String) -> Boolean,
    private val store: CredentialStore,
    private val client: VaultTransportClient,
) {

    var entries by mutableStateOf<List<CredentialEntry>>(emptyList())
        private set
    var keys by mutableStateOf<Map<String, KeyView>>(emptyMap())
        private set
    var busy by mutableStateOf(false)
        private set
    var keyBackend by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)

    init {
        store.load()
        refreshEntries()
    }

    val hasInFlight: Boolean
        get() = entries.any { e -> e.remote.values.any { it.rid != null && it.status in setOf(RemoteStatus.SENDING, RemoteStatus.DELETING) } }

    fun knownBots(extra: List<String> = emptyList()): List<String> = (DEFAULT_BOTS + keys.keys.sorted() + extra).distinct()

    private fun refreshEntries() {
        entries = store.entries
        keyBackend = CredentialCrypto.backendLabel()
    }

    private fun setRemote(id: String, bot: String, transform: (RemoteState) -> RemoteState?) {
        store.updateEntry(id) { cur ->
            val next = transform(cur.remote[bot] ?: RemoteState())
            val remote = if (next == null) cur.remote - bot else cur.remote + (bot to next)
            if (cur.deleted && remote.isEmpty()) null else cur.copy(remote = remote)
        }
        refreshEntries()
    }

    // ---- keys ---------------------------------------------------------------------------------
    suspend fun refreshKeys() {
        val found = client.discoverKeys()
        if (found == null) {
            message = "봇 키를 불러오지 못했습니다(네트워크 또는 로그인 상태 확인)."
            return
        }
        val out = mutableMapOf<String, KeyView>()
        found.groupBy { it.bot }.forEach { (bot, candidates) ->
            val pin = store.pins[bot]
            val match = candidates.firstOrNull { it.kid == pin }
            out[bot] = when {
                match != null -> KeyView(bot, match, KeyState.TRUSTED)
                pin == null -> {
                    // trust on first use
                    store.pin(bot, candidates.first().kid)
                    KeyView(bot, candidates.first(), KeyState.TRUSTED)
                }
                else -> KeyView(bot, candidates.first(), KeyState.CHANGED)
            }
        }
        keys = out
    }

    fun trustNewKey(bot: String) {
        val view = keys[bot] ?: return
        store.pin(bot, view.key.kid)
        keys = keys + (bot to view.copy(state = KeyState.TRUSTED))
    }

    // ---- local crypto -------------------------------------------------------------------------
    private suspend fun <T> crypto(reason: String, block: () -> T): T? {
        return try {
            try {
                withContext(Dispatchers.Default) { block() }
            } catch (e: UserNotAuthenticatedException) {
                if (!authenticate(reason)) {
                    message = "잠금 해제가 취소되었습니다."
                    return null
                }
                withContext(Dispatchers.Default) { block() }
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            CredentialCrypto.resetKey()
            message = "기기 잠금 설정이 바뀌어 암호화 키가 무효화되었습니다. 저장된 비밀번호는 복구할 수 없으니 각 항목을 수정해 비밀번호를 다시 입력하세요."
            null
        } catch (e: Exception) {
            Timber.w("June credentials: crypto failed (%s)", e.javaClass.simpleName)
            message = "암호화 처리 실패(${e.javaClass.simpleName}). 화면 잠금이 설정되어 있는지 확인하세요."
            null
        }
    }

    // ---- add / edit / delete --------------------------------------------------------------------
    /** Returns true when saved. [password] empty on edit = keep the stored one. */
    suspend fun save(
        existing: CredentialEntry?,
        originInput: String,
        labelInput: String,
        identifierInput: String,
        password: String,
        bots: List<String>,
        deviceSecure: Boolean,
    ): Boolean {
        val origin = CredentialFormat.normalizeOrigin(originInput)
        val identifier = identifierInput.trim()
        val error = when {
            origin == null -> "사이트 주소가 올바르지 않습니다(예: https://example.com)."
            identifier.isEmpty() -> "ID를 입력하세요."
            existing == null && password.isEmpty() -> "비밀번호를 입력하세요."
            bots.isEmpty() -> "대상 봇을 하나 이상 고르세요."
            !deviceSecure -> "기기 화면 잠금(PIN·패턴·비밀번호)을 먼저 설정해야 암호화 키를 만들 수 있습니다."
            else -> null
        }
        if (error != null || origin == null) {
            message = error
            return false
        }
        val label = labelInput.trim().ifEmpty { origin.substringAfter("://") }
        val id = existing?.id ?: UUID.randomUUID().toString()
        var ciphertext = existing?.ciphertext ?: ByteArray(0)
        var iv = existing?.iv ?: ByteArray(0)
        var first = existing?.firstChar.orEmpty()
        var length = existing?.length ?: 0
        if (password.isNotEmpty()) {
            val bytes = password.toByteArray(Charsets.UTF_8)
            try {
                val sealed = crypto("비밀번호를 암호화해 저장합니다") { CredentialCrypto.encrypt(bytes, id.toByteArray(Charsets.UTF_8)) } ?: return false
                ciphertext = sealed.first
                iv = sealed.second
            } finally {
                bytes.fill(0)
            }
            first = CredentialFormat.firstChar(password)
            length = CredentialFormat.length(password)
        }
        val contentChanged = existing == null || password.isNotEmpty() || existing.origin != origin ||
            existing.identifier != identifier || existing.label != label
        val old = existing?.remote.orEmpty()
        val remote = mutableMapOf<String, RemoteState>()
        for (bot in bots) {
            val prev = old[bot]
            remote[bot] = if (!contentChanged && prev != null && prev.status != RemoteStatus.DELETE_PENDING) {
                prev
            } else {
                RemoteState(status = RemoteStatus.PENDING, itemId = prev?.itemId)
            }
        }
        for ((bot, state) in old) {
            // bot removed from the targets: delete its vault item too
            if (bot !in bots && state.itemId != null) remote[bot] = state.copy(status = RemoteStatus.DELETE_PENDING, rid = null, error = null)
        }
        val entry = CredentialEntry(
            id = id, origin = origin, label = label, identifier = identifier,
            identifierType = CredentialFormat.identifierType(identifier),
            firstChar = first, length = length, ciphertext = ciphertext, iv = iv,
            bots = bots, remote = remote,
        )
        store.update { list -> if (existing == null) list + entry else list.map { if (it.id == id) entry else it } }
        message = null
        refreshEntries()
        return true
    }

    /** Deletes locally and asks every bot that holds a copy to delete it from its vault. */
    suspend fun delete(entry: CredentialEntry) {
        if (entry.remote.values.any { it.status == RemoteStatus.SENDING }) {
            // the bot may be creating a vault item right now: wait for its id so it can be deleted too
            message = "전송 결과를 기다린 뒤 다시 삭제하세요."
            return
        }
        store.updateEntry(entry.id) { cur ->
            val remote = cur.remote.filterValues { it.itemId != null }
                .mapValues { (_, s) -> s.copy(status = RemoteStatus.DELETE_PENDING, rid = null, error = null) }
            if (remote.isEmpty()) {
                null
            } else {
                cur.copy(deleted = true, ciphertext = ByteArray(0), iv = ByteArray(0), firstChar = "", length = 0, bots = emptyList(), remote = remote)
            }
        }
        refreshEntries()
        send(onlyId = entry.id)
    }

    // ---- send -------------------------------------------------------------------------------------
    /** Sends pending work (all entries, or one entry: then every target bot gets the current value again). */
    suspend fun send(onlyId: String? = null) {
        if (busy) return
        busy = true
        try {
            refreshKeys()
            for (e in store.entries.filter { onlyId == null || it.id == onlyId }) {
                for (bot in e.needsDelete()) sendDelete(e.id, bot)
            }
            val puts = store.entries
                .filter { !it.deleted && (onlyId == null || it.id == onlyId) }
                .map { e -> e to if (onlyId != null) e.bots else e.needsPut() }
                .filter { it.second.isNotEmpty() }
            if (puts.isEmpty()) return
            if (!authenticate("봇 금고로 보낼 비밀번호를 복호화합니다")) {
                message = "잠금 해제가 취소되어 보내지 않았습니다."
                return
            }
            for ((e, bots) in puts) {
                val plain = crypto("비밀번호를 복호화합니다") { CredentialCrypto.decrypt(e.ciphertext, e.iv, e.id.toByteArray(Charsets.UTF_8)) } ?: continue
                try {
                    val password = String(plain, Charsets.UTF_8)
                    for (bot in bots) sendPut(e.id, bot, password)
                } finally {
                    plain.fill(0)
                }
            }
        } finally {
            busy = false
            refreshEntries()
        }
    }

    private fun keyFor(id: String, bot: String): BotKey? {
        val view = keys[bot]
        if (view == null || view.state != KeyState.TRUSTED) {
            val code = if (view == null) "no_key" else "key_changed"
            setRemote(id, bot) {
                val status = if (it.status == RemoteStatus.DELETE_PENDING) it.status else RemoteStatus.FAILED
                it.copy(status = status, error = code)
            }
            return null
        }
        return view.key
    }

    private suspend fun sendPut(id: String, bot: String, password: String) {
        val e = store.entries.firstOrNull { it.id == id } ?: return
        val key = keyFor(id, bot) ?: return
        val rid = UUID.randomUUID().toString().replace("-", "")
        val payload = JSONObject()
            .put("op", "put").put("origin", e.origin).put("label", e.label)
            .put("identifier", e.identifier).put("identifier_type", e.identifierType)
            .put("password", password)
        e.remote[bot]?.itemId?.let { payload.put("replace_id", it) }
        val envelope = try {
            client.seal(key, rid, payload)
        } catch (ex: Exception) {
            Timber.w("June credentials: seal failed (%s)", ex.javaClass.simpleName)
            null
        } finally {
            payload.remove("password")
        }
        val eventId = envelope?.let { client.sendEnvelope(key.roomId, it) }
        setRemote(id, bot) {
            if (eventId == null) {
                it.copy(status = RemoteStatus.FAILED, error = "send_failed")
            } else {
                it.copy(status = RemoteStatus.SENDING, rid = rid, roomId = key.roomId, eventId = eventId, sentAt = System.currentTimeMillis(), error = null)
            }
        }
    }

    private suspend fun sendDelete(id: String, bot: String) {
        val e = store.entries.firstOrNull { it.id == id } ?: return
        val itemId = e.remote[bot]?.itemId
        if (itemId == null) {
            setRemote(id, bot) { null }
            return
        }
        val key = keyFor(id, bot) ?: return
        val rid = UUID.randomUUID().toString().replace("-", "")
        val envelope = runCatching { client.seal(key, rid, JSONObject().put("op", "delete").put("id", itemId)) }.getOrNull()
        val eventId = envelope?.let { client.sendEnvelope(key.roomId, it) }
        setRemote(id, bot) {
            if (eventId == null) {
                it.copy(error = "send_failed")
            } else {
                it.copy(status = RemoteStatus.DELETING, rid = rid, roomId = key.roomId, eventId = eventId, sentAt = System.currentTimeMillis(), error = null)
            }
        }
    }

    /** Picks up the bots' results for requests in flight (and late answers to timed-out puts). */
    suspend fun poll() {
        val watch = store.entries.flatMap { e ->
            e.remote.filter { (_, s) ->
                s.rid != null && s.roomId != null &&
                    (s.status == RemoteStatus.SENDING || s.status == RemoteStatus.DELETING || (s.status == RemoteStatus.FAILED && s.error == "timeout"))
            }.map { Triple(e.id, it.key, it.value) }
        }
        if (watch.isEmpty()) return
        val cache = mutableMapOf<Pair<String, String>, List<OpResult>?>()
        for ((id, bot, s) in watch) {
            val roomId = s.roomId ?: continue
            val results = cache.getOrPut(bot to roomId) { client.fetchResults(bot, roomId) } ?: continue
            val r = results.firstOrNull { it.rid == s.rid }
            if (r == null) {
                if (s.status != RemoteStatus.FAILED && System.currentTimeMillis() - s.sentAt > PUT_TIMEOUT_MILLIS) {
                    setRemote(id, bot) {
                        if (it.status == RemoteStatus.DELETING) it.copy(status = RemoteStatus.DELETE_PENDING, rid = null, error = "timeout")
                        else it.copy(status = RemoteStatus.FAILED, error = "timeout")
                    }
                }
                continue
            }
            if (s.status == RemoteStatus.DELETING) {
                setRemote(id, bot) { if (r.ok) null else it.copy(status = RemoteStatus.DELETE_PENDING, rid = null, error = r.error ?: "failed") }
            } else {
                setRemote(id, bot) {
                    if (r.ok) it.copy(status = RemoteStatus.SAVED, itemId = r.itemId ?: it.itemId, error = null)
                    else it.copy(status = RemoteStatus.FAILED, error = r.error ?: "failed")
                }
            }
            s.eventId?.let { client.redact(roomId, it) }
        }
    }
}
