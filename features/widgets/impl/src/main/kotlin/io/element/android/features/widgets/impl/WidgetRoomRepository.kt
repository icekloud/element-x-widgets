/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.Inject
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.roomlist.LatestEventValue
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.libraries.matrix.api.timeline.item.event.EventContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.StickerContent
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Loads the room data for the widgets from the Matrix session, falling back to the local cache.
 */
@Inject
class WidgetRoomRepository(
    @ApplicationContext private val context: Context,
    private val matrixClientProvider: MatrixClientProvider,
    private val sessionStore: SessionStore,
) {
    val store = WidgetStore(context)

    suspend fun defaultSessionId(): String? {
        return sessionStore.getLatestSession()?.userId ?: sessionStore.getAllSessions().firstOrNull()?.userId
    }

    /**
     * Return all the rooms of the session, sorted by recent activity (used by the configuration screen).
     */
    suspend fun loadAllRooms(sessionId: String): List<WidgetRoom> {
        val rooms = fetchRooms(sessionId)
        return if (rooms != null) {
            rooms.sortedByDescending { it.timestamp ?: 0L }
        } else {
            store.getCachedRooms(sessionId).values.sortedByDescending { it.timestamp ?: 0L }
        }
    }

    /**
     * Return the configured rooms, in the order chosen by the user.
     */
    suspend fun loadConfiguredRooms(config: WidgetConfig): List<WidgetRoom> {
        val byId = fetchRooms(config.sessionId)?.associateBy { it.roomId } ?: store.getCachedRooms(config.sessionId)
        return config.roomIds.mapNotNull { byId[it] }
    }

    /**
     * Return the configured rooms from the local cache only (fast, no network), in the order chosen by the user.
     */
    fun cachedConfiguredRooms(config: WidgetConfig): List<WidgetRoom> {
        val byId = store.getCachedRooms(config.sessionId)
        return config.roomIds.mapNotNull { byId[it] }
    }

    /**
     * Return the avatar from the disk cache only (fast, no network).
     */
    fun cachedAvatar(avatarUrl: String?): Bitmap? {
        if (avatarUrl.isNullOrEmpty()) return null
        val file = File(File(context.cacheDir, "june_widget_avatars"), avatarUrl.sha1())
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private suspend fun fetchRooms(sessionId: String): List<WidgetRoom>? {
        val client = getClient(sessionId) ?: return null
        val summaries = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            client.roomListService.allRooms.summaries.first { it.isNotEmpty() }
        } ?: return null
        val rooms = summaries.map { it.toWidgetRoom() }
        store.saveCachedRooms(sessionId, rooms)
        return rooms
    }

    private suspend fun getClient(sessionId: String): MatrixClient? {
        val id = SessionId(sessionId)
        return matrixClientProvider.getOrNull(id)
            ?: withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                matrixClientProvider.getOrRestore(id).onFailure { Timber.w(it, "Widget: cannot restore session") }.getOrNull()
            }
    }

    /**
     * Return a small avatar bitmap for the room, cached on disk.
     */
    suspend fun loadAvatar(sessionId: String, avatarUrl: String?): Bitmap? {
        if (avatarUrl.isNullOrEmpty()) return null
        val dir = File(context.cacheDir, "june_widget_avatars").apply { mkdirs() }
        val file = File(dir, avatarUrl.sha1())
        if (!file.exists()) {
            val client = getClient(sessionId) ?: return null
            val bytes = withTimeoutOrNull(AVATAR_TIMEOUT_MS) {
                client.matrixMediaLoader.loadMediaThumbnail(MediaSource(avatarUrl), AVATAR_SIZE, AVATAR_SIZE).getOrNull()
            } ?: return null
            val scaled = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { Bitmap.createScaledBitmap(it, AVATAR_SIZE.toInt(), AVATAR_SIZE.toInt(), true) }
            }.getOrNull() ?: return null
            runCatching { file.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            return scaled
        }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    private fun RoomSummary.toWidgetRoom(): WidgetRoom {
        return WidgetRoom(
            roomId = info.id.value,
            name = info.name?.takeIf { it.isNotBlank() } ?: info.id.value,
            preview = latestEvent.previewText(),
            // Element June: count only notifying messages (bot progress m.notice is excluded by push rules)
            unread = info.numUnreadNotifications,
            avatarUrl = info.avatarUrl,
            timestamp = latestEventTimestamp,
        )
    }

    private fun LatestEventValue.previewText(): String {
        val eventContent: EventContent = when (this) {
            is LatestEventValue.Remote -> content
            is LatestEventValue.Local -> content
            else -> return ""
        }
        return when (eventContent) {
            is MessageContent -> eventContent.body
            is StickerContent -> eventContent.body.orEmpty()
            else -> ""
        }.lineSequence().firstOrNull().orEmpty()
    }

    private fun String.sha1(): String {
        return MessageDigest.getInstance("SHA-1").digest(toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val RESTORE_TIMEOUT_MS = 10_000L
        const val FETCH_TIMEOUT_MS = 10_000L
        const val AVATAR_TIMEOUT_MS = 5_000L
        const val AVATAR_SIZE = 96L
    }
}
