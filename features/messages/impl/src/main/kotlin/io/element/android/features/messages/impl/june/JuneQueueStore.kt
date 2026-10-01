/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Element June: the bot's busy queue, as published by the gateway in the room state event
 * `app.june.queue` (ids only, no message text: state events are not encrypted).
 *
 * The server is the only source of truth. The app reads the state when the timeline changes
 * (a message sent, a 👀/✅ reaction, a bot reply) and every 20 s as a safety net, and controls
 * the queue with the room event `app.june.queue_op` ({"rid","op":"steer"|"cancel","id"}).
 * Nothing here uses /sync, so the device's encryption keys are never touched.
 */
internal const val JUNE_QUEUE_STATE_TYPE = "app.june.queue"
private const val OP_TYPE = "app.june.queue_op"

/** A list whose heartbeat is older than this is ignored (gateway gone): hidden messages show again. */
private const val STALE_SECONDS = 180L

internal data class JuneQueueItem(
    val id: String,
    val ids: List<String>,
    val kind: String,
    val editable: Boolean,
    val edited: Boolean,
)

internal data class JuneQueueResult(val rid: String, val op: String, val id: String, val ok: Boolean, val err: String?)

internal data class JuneQueueSnapshot(
    val enabled: Boolean,
    val rev: Long,
    val items: List<JuneQueueItem>,
    val results: List<JuneQueueResult>,
    val ts: Long,
    val fetchedAtMillis: Long,
) {
    fun liveItems(nowMillis: Long): List<JuneQueueItem> =
        if (enabled && nowMillis / 1000 - ts < STALE_SECONDS) items else emptyList()
}

internal class JuneQueueClient(private val sessionStore: SessionStore) {
    private fun open(base: String, path: String, token: String, method: String): HttpURLConnection {
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    /** The current queue state, an empty one when the room has none, or null on a transient error. */
    suspend fun fetch(roomId: String): JuneQueueSnapshot? = withContext(Dispatchers.IO) {
        try {
            val s = sessionStore.getLatestSession() ?: return@withContext null
            val room = URLEncoder.encode(roomId, "UTF-8")
            val conn = open(s.homeserverUrl, "/_matrix/client/v3/rooms/$room/state/$JUNE_QUEUE_STATE_TYPE/", s.accessToken, "GET")
            try {
                when (conn.responseCode) {
                    200 -> parse(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
                    404 -> JuneQueueSnapshot(false, 0, emptyList(), emptyList(), 0, System.currentTimeMillis())
                    else -> null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Timber.d("June queue: fetch failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** Sends a control; returns its request id, or null when it could not be sent. */
    suspend fun sendOp(roomId: String, op: String, itemId: String): String? = withContext(Dispatchers.IO) {
        try {
            val s = sessionStore.getLatestSession() ?: return@withContext null
            val room = URLEncoder.encode(roomId, "UTF-8")
            val rid = UUID.randomUUID().toString()
            val txn = rid.replace("-", "")
            val conn = open(s.homeserverUrl, "/_matrix/client/v3/rooms/$room/send/$OP_TYPE/$txn", s.accessToken, "PUT")
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("v", 1).put("rid", rid).put("op", op).put("id", itemId)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode in 200..299) rid else null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Timber.d("June queue: %s request failed (%s)", op, e.javaClass.simpleName)
            null
        }
    }

    private fun parse(json: JSONObject): JuneQueueSnapshot {
        val items = json.optJSONArray("items")
        val results = json.optJSONArray("results")
        return JuneQueueSnapshot(
            enabled = json.optBoolean("enabled", false),
            rev = json.optLong("rev"),
            items = List(items?.length() ?: 0) { i ->
                val o = items!!.getJSONObject(i)
                val ids = o.optJSONArray("ids")
                JuneQueueItem(
                    id = o.optString("id"),
                    ids = List(ids?.length() ?: 0) { j -> ids!!.optString(j) }.filter { it.isNotEmpty() },
                    kind = o.optString("kind", "text"),
                    editable = o.optBoolean("editable", false),
                    edited = o.optBoolean("edited", false),
                )
            }.filter { it.id.isNotEmpty() },
            results = List(results?.length() ?: 0) { i ->
                val o = results!!.getJSONObject(i)
                JuneQueueResult(
                    rid = o.optString("rid"),
                    op = o.optString("op"),
                    id = o.optString("id"),
                    ok = o.optBoolean("ok", false),
                    err = if (o.isNull("err")) null else o.optString("err"),
                )
            },
            ts = json.optLong("ts"),
            fetchedAtMillis = System.currentTimeMillis(),
        )
    }
}

internal object JuneQueueStore {
    private val snapshots = MutableStateFlow<Map<String, JuneQueueSnapshot>>(emptyMap())
    val all: StateFlow<Map<String, JuneQueueSnapshot>> = snapshots

    /** Set by the panel; the composer uses it to re-check the queue right before sending an edit. */
    @Volatile
    var client: JuneQueueClient? = null

    private val editTargets = ConcurrentHashMap.newKeySet<String>()

    /** Per room: a signature of the newest timeline items (incl. reactions). A change means "look again". */
    private val pulses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pulse: StateFlow<Map<String, Int>> = pulses

    /** Per room: the timeline events currently held back because they wait in the queue (for the panel). */
    private val heldEvents = MutableStateFlow<Map<String, Map<String, TimelineItem.Event>>>(emptyMap())
    val held: StateFlow<Map<String, Map<String, TimelineItem.Event>>> = heldEvents

    fun onTimeline(roomId: String, items: List<TimelineItem>, hidden: Set<String>) {
        var sig = 17
        items.asSequence().take(40).filterIsInstance<TimelineItem.Event>().forEach { e ->
            sig = 31 * sig + (e.eventId?.value ?: e.id.value).hashCode()
            e.reactionsState.reactions.forEach { r -> sig = 31 * sig + (r.key.hashCode() xor r.count) }
        }
        pulses.update { if (it[roomId] == sig) it else it + (roomId to sig) }
        val held = if (hidden.isEmpty()) {
            emptyMap()
        } else {
            items.asSequence().filterIsInstance<TimelineItem.Event>()
                .filter { it.isMine && it.eventId?.value in hidden }
                .associateBy { it.eventId!!.value }
        }
        heldEvents.update { if (it[roomId] == held) it else it + (roomId to held) }
    }

    fun clearEdits(roomId: String) {
        editTargets.removeAll { it.startsWith("$roomId|") }
    }

    suspend fun refresh(roomId: String): JuneQueueSnapshot? {
        val fresh = client?.fetch(roomId) ?: return null
        snapshots.update { map ->
            val old = map[roomId]
            if (old != null && fresh.rev < old.rev) map else map + (roomId to fresh)
        }
        return snapshots.value[roomId]
    }

    /** Ids of every Matrix event still waiting in [roomId]'s queue (hidden from the timeline). */
    fun hiddenIds(map: Map<String, JuneQueueSnapshot>, roomId: String, nowMillis: Long): Set<String> =
        map[roomId]?.liveItems(nowMillis)?.flatMapTo(HashSet<String>()) { it.ids + it.id }.orEmpty()

    fun markEditing(roomId: String, eventId: String) {
        editTargets.add("$roomId|$eventId")
    }

    /**
     * Called right before an edit is sent. Edits started from the queue panel are only sent while the
     * message is still queued; any other edit goes through untouched.
     */
    suspend fun allowEdit(roomId: String, eventId: String): Boolean {
        val key = "$roomId|$eventId"
        if (key !in editTargets) return true
        val snap = refresh(roomId) ?: return true // unknown: the gateway ignores edits of executed messages anyway
        val queued = snap.liveItems(System.currentTimeMillis()).any { it.id == eventId }
        if (queued) editTargets.remove(key)
        return queued
    }

    fun forgetEdit(roomId: String, eventId: String) {
        editTargets.remove("$roomId|$eventId")
    }
}

/**
 * Element June: hide my messages that still wait in the bot's queue (they show up in the queue panel
 * instead) and report timeline changes to the queue panel. Only ids the server lists are hidden, and a
 * stale or disabled list hides nothing, so a message can never stay hidden for good.
 */
internal fun juneHoldQueued(
    roomId: String,
    items: ImmutableList<TimelineItem>,
    queues: Map<String, JuneQueueSnapshot>,
): ImmutableList<TimelineItem> {
    val hidden = JuneQueueStore.hiddenIds(queues, roomId, System.currentTimeMillis())
    JuneQueueStore.onTimeline(roomId, items, hidden)
    if (hidden.isEmpty()) return items
    return items.filterNot { it is TimelineItem.Event && it.isMine && it.eventId?.value in hidden }.toImmutableList()
}

/** Re-evaluates the hidden set now and then, so a list whose gateway went silent stops hiding messages. */
internal fun juneQueueTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(20_000)
    }
}
