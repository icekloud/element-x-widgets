/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val POLL_MILLIS = 800L

/**
 * Element June memo bot: the real backend, the memo bot room (이벤트규약 v1).
 *
 * - index: GET of the state event `app.june.memo.index` (plus the split keys, 규약 §1 ②)
 * - memo_op: PUT of a room message event, like the other June ops (kill, steer)
 * - answers: the first of ① [deliver] (answer events seen in the open room timeline, already decrypted by the SDK)
 *   and ② a poll of `/messages` filtered on the answer types (works while the room is not encrypted)
 *
 * The access token never leaves this class and is never logged.
 */
internal class MemoRoomBackend(
    private val sessionStore: SessionStore,
    private val roomId: String,
) : MemoBackend {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<MemoResponse>>()

    private fun open(base: String, path: String, token: String, method: String): HttpURLConnection {
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private val room: String get() = URLEncoder.encode(roomId, "UTF-8")

    /** GET of a state event content: the JSON, null when absent (404), or throws on a transient error. */
    private suspend fun state(type: String, key: String): JSONObject? {
        val s = sessionStore.getLatestSession() ?: error("no session")
        val path = "/_matrix/client/v3/rooms/$room/state/$type/" + URLEncoder.encode(key, "UTF-8").replace("+", "%20")
        val conn = open(s.homeserverUrl, path, s.accessToken, "GET")
        try {
            return when (conn.responseCode) {
                200 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                404 -> null
                else -> error("http ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun fetchIndex(): MemoIndex? = withContext(Dispatchers.IO) {
        try {
            val main = state(MEMO_INDEX_TYPE, "")?.let(::parseMemoIndex) ?: return@withContext MemoIndex.EMPTY
            if (main.splitKeys.isEmpty()) return@withContext main
            mergeMemoIndex(main, main.splitKeys.mapNotNull { key -> state(MEMO_INDEX_TYPE, key) })
        } catch (e: Exception) {
            Timber.d("June memo: index fetch failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** The raw `제안[]` items of the index (main + split keys), for the local suggestion table (A5). */
    suspend fun fetchProposalItems(): List<JSONObject>? = withContext(Dispatchers.IO) {
        try {
            val main = state(MEMO_INDEX_TYPE, "") ?: return@withContext emptyList()
            val parts = listOf(main) + parseMemoIndex(main).splitKeys.mapNotNull { key -> state(MEMO_INDEX_TYPE, key) }
            parts.flatMap { part ->
                val a = part.optJSONArray("제안") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            }
        } catch (e: Exception) {
            Timber.d("June memo: proposals fetch failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** An answer seen in the room timeline (the store may be waiting for it). */
    fun deliver(response: MemoResponse) {
        waiting[response.requestId]?.complete(response)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun call(op: MemoOp, requestId: String): MemoResponse {
        val answer = CompletableDeferred<MemoResponse>()
        waiting[requestId] = answer
        try {
            val since = send(op, requestId)
            while (true) {
                val polled = poll(requestId, since)
                if (polled != null) return polled
                val pushed = select<MemoResponse?> {
                    answer.onAwait { it }
                    onTimeout(POLL_MILLIS) { null }
                }
                if (pushed != null) return pushed
            }
        } finally {
            waiting.remove(requestId)
        }
    }

    /** Sends the op; returns the pagination token from which the answer is looked for (null: from the latest). */
    private suspend fun send(op: MemoOp, requestId: String): String? = withContext(Dispatchers.IO) {
        val s = sessionStore.getLatestSession() ?: throw MemoSendException("세션이 없습니다")
        val since = runCatchingExceptions { latestToken(s.homeserverUrl, s.accessToken) }.getOrNull()
        val txn = UUID.randomUUID().toString().replace("-", "")
        val conn = open(s.homeserverUrl, "/_matrix/client/v3/rooms/$room/send/$MEMO_OP_TYPE/$txn", s.accessToken, "PUT")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(op.toJson(requestId).toString().toByteArray()) }
            if (conn.responseCode !in 200..299) throw MemoSendException("보내지 못했습니다 (${conn.responseCode})")
        } catch (e: MemoSendException) {
            throw e
        } catch (e: Exception) {
            Timber.d("June memo: send failed (%s)", e.javaClass.simpleName)
            throw MemoSendException("네트워크 오류로 보내지 못했습니다")
        } finally {
            conn.disconnect()
        }
        since
    }

    private fun answerFilter(): String = URLEncoder.encode(
        JSONObject().put("types", JSONArray(MEMO_ANSWER_TYPES)).put("limit", 20).toString(),
        "UTF-8",
    )

    /** The `end` token of the newest answer event, so the poll only reads what comes after the request. */
    private fun latestToken(base: String, token: String): String? {
        val conn = open(base, "/_matrix/client/v3/rooms/$room/messages?dir=b&limit=1&filter=${answerFilter()}", token, "GET")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return json.optString("start").takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun poll(requestId: String, since: String?): MemoResponse? = withContext(Dispatchers.IO) {
        try {
            val s = sessionStore.getLatestSession() ?: return@withContext null
            val from = since?.let { "&from=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val dir = if (since != null) "f" else "b"
            val conn = open(s.homeserverUrl, "/_matrix/client/v3/rooms/$room/messages?dir=$dir&limit=30$from&filter=${answerFilter()}", s.accessToken, "GET")
            try {
                if (conn.responseCode != 200) return@withContext null
                val chunk = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("chunk") ?: return@withContext null
                (0 until chunk.length())
                    .asSequence()
                    .mapNotNull { i -> chunk.optJSONObject(i) }
                    .mapNotNull { e -> e.optJSONObject("content")?.let { parseMemoResponse(e.optString("type"), it) } }
                    .firstOrNull { it.requestId == requestId }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Timber.d("June memo: poll failed (%s)", e.javaClass.simpleName)
            null
        }
    }
}

internal val MEMO_ANSWER_TYPES = MEMO_RESPONSE_TYPES

/**
 * The sync transport over the memo bot room (A4): the same send + answer path as the screens used in v1, with a
 * timeout, the proposals of the index state and the media upload of the encrypted attachments (A5).
 */
internal class MemoRoomSyncTransport(
    private val sessionStore: SessionStore,
    private val room: MemoRoomBackend,
    private val timeoutMillis: Long = MEMO_SYNC_TIMEOUT_MILLIS,
) : MemoSyncTransport {
    override suspend fun call(op: MemoOp): MemoResponse =
        withTimeout(timeoutMillis) { room.call(op, UUID.randomUUID().toString()) }

    override suspend fun fetchProposals(): List<JSONObject>? = room.fetchProposalItems()

    override suspend fun upload(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val s = sessionStore.getLatestSession() ?: throw MemoSendException("세션이 없습니다")
        val conn = URL(s.homeserverUrl.trimEnd('/') + "/_matrix/media/v3/upload").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${s.accessToken}")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) throw MemoSendException("올리지 못했습니다 (${conn.responseCode})")
            val uri = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("content_uri")
            if (!uri.startsWith("mxc://")) throw MemoSendException("올린 주소가 없습니다")
            uri
        } finally {
            conn.disconnect()
        }
    }

    /** `m.upload.size` of the media config (authenticated v1 path, then the older v3 one). Null when neither answers. */
    override suspend fun maxUploadSize(): Long? = withContext(Dispatchers.IO) {
        val s = sessionStore.getLatestSession() ?: return@withContext null
        listOf("/_matrix/client/v1/media/config", "/_matrix/media/v3/config").firstNotNullOfOrNull { path ->
            val conn = URL(s.homeserverUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Authorization", "Bearer ${s.accessToken}")
                if (conn.responseCode != 200) return@firstNotNullOfOrNull null
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optLong("m.upload.size").takeIf { it > 0 }
            } catch (e: Exception) {
                Timber.d("June memo: media config not read (%s)", e.javaClass.simpleName)
                null
            } finally {
                conn.disconnect()
            }
        }
    }
}

/** A sync answer can take longer than a screen read: the server reads its files at that time (규약 §3). */
internal const val MEMO_SYNC_TIMEOUT_MILLIS = 30_000L
