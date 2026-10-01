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
 * the queue with the room event `app.june.queue_op` ({"rid","op":"steer"|"cancel"|"detach_photo","id"[,"photo"]}).
 * Nothing here uses /sync, so the device's encryption keys are never touched.
 */
internal const val JUNE_QUEUE_STATE_TYPE = "app.june.queue"
private const val OP_TYPE = "app.june.queue_op"

/** A list whose heartbeat is older than this is ignored (gateway gone): hidden messages show again. */
private const val STALE_SECONDS = 180L

/** Placements remembered per room while the app runs. */
private const val MAX_PLACEMENTS = 500

/** A picture of a queued item: the Matrix event it came from and the gateway's cached file name (for detach_photo). */
internal data class JuneQueuePhoto(val id: String, val name: String)

internal data class JuneQueueItem(
    val id: String,
    val ids: List<String>,
    val kind: String,
    val editable: Boolean,
    val edited: Boolean,
    val photos: List<JuneQueuePhoto> = emptyList(),
)

internal data class JuneQueueResult(val rid: String, val op: String, val id: String, val ok: Boolean, val err: String?)

/** A message that left the queue but has not taken effect yet (steered, or its turn is starting). */
internal data class JuneHeldItem(val id: String, val ids: List<String>)

/** The message [id] (and its batch [ids]) took effect right after the bot event [after]. */
internal data class JunePlacement(val id: String, val ids: List<String>, val after: String)

internal data class JuneQueueSnapshot(
    val enabled: Boolean,
    val rev: Long,
    val items: List<JuneQueueItem>,
    val results: List<JuneQueueResult>,
    val ts: Long,
    val fetchedAtMillis: Long,
    val steering: List<JuneHeldItem> = emptyList(),
    val starting: List<JuneHeldItem> = emptyList(),
    val placed: List<JunePlacement> = emptyList(),
) {
    private fun live(nowMillis: Long) = enabled && nowMillis / 1000 - ts < STALE_SECONDS

    fun liveItems(nowMillis: Long): List<JuneQueueItem> = if (live(nowMillis)) items else emptyList()

    fun liveSteering(nowMillis: Long): List<JuneHeldItem> = if (live(nowMillis)) steering else emptyList()

    fun liveStarting(nowMillis: Long): List<JuneHeldItem> = if (live(nowMillis)) starting else emptyList()
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
    suspend fun sendOp(roomId: String, op: String, itemId: String, photo: String? = null): String? = withContext(Dispatchers.IO) {
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
                if (photo != null) body.put("photo", photo)
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

    private fun ids(o: JSONObject): List<String> {
        val ids = o.optJSONArray("ids")
        return List(ids?.length() ?: 0) { j -> ids!!.optString(j) }.filter { it.isNotEmpty() }
    }

    private fun held(json: JSONObject, name: String): List<JuneHeldItem> {
        val arr = json.optJSONArray(name) ?: return emptyList()
        return List(arr.length()) { i -> arr.optJSONObject(i) }
            .mapNotNull { o -> o?.optString("id")?.takeIf { it.isNotEmpty() }?.let { JuneHeldItem(it, ids(o)) } }
    }

    private fun photos(o: JSONObject): List<JuneQueuePhoto> {
        val arr = o.optJSONArray("photos") ?: return emptyList()
        return List(arr.length()) { i -> arr.optJSONObject(i) }.mapNotNull { p ->
            val id = p?.optString("id").orEmpty()
            val name = p?.optString("name").orEmpty()
            if (id.isEmpty() || name.isEmpty()) null else JuneQueuePhoto(id, name)
        }
    }

    private fun placed(json: JSONObject): List<JunePlacement> {
        val arr = json.optJSONArray("placed") ?: return emptyList()
        return List(arr.length()) { i -> arr.optJSONObject(i) }.mapNotNull { o ->
            val id = o?.optString("id").orEmpty()
            val after = o?.optString("after").orEmpty()
            if (id.isEmpty() || after.isEmpty()) null else JunePlacement(id, ids(o!!), after)
        }
    }

    private fun parse(json: JSONObject): JuneQueueSnapshot {
        val items = json.optJSONArray("items")
        val results = json.optJSONArray("results")
        return JuneQueueSnapshot(
            steering = held(json, "steering"),
            starting = held(json, "starting"),
            placed = placed(json),
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
                    photos = photos(o),
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

    /** Per room: every placement seen while the app runs (the server keeps only the newest ones). */
    private val placements = ConcurrentHashMap<String, LinkedHashMap<String, JunePlacement>>()

    fun placedIn(roomId: String): List<JunePlacement> =
        placements[roomId]?.let { synchronized(it) { it.values.toList() } }.orEmpty()

    private fun rememberPlaced(roomId: String, placed: List<JunePlacement>) {
        if (placed.isEmpty()) return
        val map = placements.getOrPut(roomId) { LinkedHashMap() }
        synchronized(map) {
            placed.forEach { map[it.id] = it }
            while (map.size > MAX_PLACEMENTS) map.remove(map.keys.first())
        }
    }

    suspend fun refresh(roomId: String): JuneQueueSnapshot? {
        val fresh = client?.fetch(roomId) ?: return null
        rememberPlaced(roomId, fresh.placed) // before the snapshot update that re-runs the timeline
        snapshots.update { map ->
            val old = map[roomId]
            if (old != null && fresh.rev < old.rev) map else map + (roomId to fresh)
        }
        return snapshots.value[roomId]
    }

    /**
     * Ids of every Matrix event of [roomId] that has not taken effect yet (hidden from the timeline):
     * still queued, steered but not read by the model yet, or out of the queue with its turn starting.
     */
    fun hiddenIds(map: Map<String, JuneQueueSnapshot>, roomId: String, nowMillis: Long): Set<String> {
        val snap = map[roomId] ?: return emptySet()
        val out = HashSet<String>()
        snap.liveItems(nowMillis).forEach { out += it.ids; out += it.id }
        (snap.liveSteering(nowMillis) + snap.liveStarting(nowMillis)).forEach { out += it.ids; out += it.id }
        return out
    }

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

    /** True while [eventId] is being edited from the queue panel: pictures picked then are added to that queued message. */
    fun isPanelEdit(roomId: String, eventId: String): Boolean = "$roomId|$eventId" in editTargets

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
    val shown = if (hidden.isEmpty()) {
        items
    } else {
        items.filterNot { it is TimelineItem.Event && it.isMine && it.eventId?.value in hidden }
    }
    val placed = JuneQueueStore.placedIn(roomId)
    if (placed.isEmpty()) return if (shown === items) items else shown.toImmutableList()
    return juneReorderPlaced(shown, placed).toImmutableList()
}

/**
 * Element June: show each of my messages that waited in the bot's queue (or was steered) right below
 * the bot event it took effect after, instead of where it arrived. [items] is newest first. A move
 * only happens when the anchor and the message are both loaded and the message sits above (older
 * than) its anchor; anything else keeps the timeline order, so a wrong or missing placement can at
 * worst leave a message where Matrix put it.
 */
internal fun juneReorderPlaced(items: List<TimelineItem>, placed: List<JunePlacement>): List<TimelineItem> {
    var list = items
    for (p in placed) {
        val anchor = list.indexOfFirst { it is TimelineItem.Event && it.eventId?.value == p.after }
        if (anchor < 0) continue
        val wanted = p.ids.toHashSet().apply { add(p.id) }
        val moving = list.indices.filter { i ->
            val e = list[i]
            i > anchor && e is TimelineItem.Event && e.isMine && e.eventId?.value in wanted
        }
        if (moving.isEmpty()) continue
        val movingSet = moving.toHashSet()
        val out = ArrayList<TimelineItem>(list.size)
        list.forEachIndexed { i, item ->
            if (i == anchor) moving.forEach { out.add(list[it]) }
            if (i !in movingSet) out.add(item)
        }
        list = out
    }
    return list
}

/** Re-evaluates the hidden set now and then, so a list whose gateway went silent stops hiding messages. */
internal fun juneQueueTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(20_000)
    }
}
