/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.voice

import io.element.android.features.widgets.impl.WidgetRoomRepository
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.media.AudioInfo
import timber.log.Timber

/**
 * Element June: sends the recording of the shortcut to the commander room, from outside any session screen.
 *
 * This is the path the notification quick reply already uses (restore the session, get the room, use its live
 * timeline), with the voice message call the message composer ends up in, so the upload contract is the same.
 */
class JuneVoiceRoomSender(
    private val repository: WidgetRoomRepository,
) : JuneVoiceSender {
    override suspend fun send(recording: JuneVoiceRecording): Result<Unit> {
        val sessionId = repository.defaultSessionId()
            ?: return Result.failure(JuneVoiceSendException("No session to send the voice message with"))
        val client = repository.restoredClient(sessionId)
            ?: return Result.failure(JuneVoiceSendException("Cannot restore the session"))
        val roomId = resolveRoomId(sessionId)
        val room = client.getJoinedRoom(RoomId(roomId))
            ?: return Result.failure(JuneVoiceSendException("Room $roomId not joined"))
        return try {
            room.liveTimeline.sendVoiceMessage(
                file = recording.file,
                audioInfo = AudioInfo(
                    duration = recording.duration,
                    size = recording.file.length(),
                    mimetype = recording.mimeType,
                ),
                waveform = recording.waveform,
                inReplyToEventId = null,
            ).fold(
                onSuccess = { handler -> handler.await() },
                onFailure = { Result.failure(it) },
            )
        } finally {
            room.destroy()
        }
    }

    /**
     * The configured room, or the room named like the commander when that room is unknown to this session
     * (the account or the room was recreated).
     */
    private fun resolveRoomId(sessionId: String): String {
        val configured = repository.store.getVoiceShortcutRoomId()
        val rooms = repository.store.getCachedRooms(sessionId)
        if (rooms.isEmpty() || rooms.containsKey(configured)) return configured
        val byName = rooms.values.firstOrNull { it.direct && it.name.trim() == COMMANDER_ROOM_NAME }
        if (byName != null) {
            Timber.tag(TAG).i("Voice shortcut room $configured is unknown, using ${byName.roomId}")
            return byName.roomId
        }
        return configured
    }

    private companion object {
        const val TAG = "JuneVoice"
        const val COMMANDER_ROOM_NAME = "커맨더"
    }
}

/** Why a voice message of the shortcut could not be sent; the recording is kept for a later retry. */
class JuneVoiceSendException(message: String) : Exception(message)
