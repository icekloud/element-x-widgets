/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.EventType
import io.element.android.libraries.push.impl.notifications.model.NotifiableEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent

/** The speaker emoji (U+1F50A) that starts the line to read aloud. */
internal const val JUNE_SPEAKER_EMOJI = "\uD83D\uDD0A"
private const val VARIATION_SELECTOR = "\uFE0F"

/** Longest line read aloud. The server writes at most 120 characters, this leaves some room. */
internal const val JUNE_SPEAK_MAX_LENGTH = 150

private const val SENTENCE_ENDS = ".?!。"

/**
 * Element June: the text to read aloud for a message [body], or null if there is none.
 *
 * Only the first non blank line is looked at, and only when it starts with 🔊. The emoji and the spaces around the text are removed.
 * A line longer than [JUNE_SPEAK_MAX_LENGTH] is shortened, see [juneShortenSpeakLine].
 */
internal fun juneExtractSpeakLine(body: String?): String? {
    if (body == null) return null
    val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
    if (!firstLine.startsWith(JUNE_SPEAKER_EMOJI)) return null
    val text = firstLine.removePrefix(JUNE_SPEAKER_EMOJI).removePrefix(VARIATION_SELECTOR).trim()
    if (text.isEmpty()) return null
    return juneShortenSpeakLine(text)
}

/**
 * Element June: [text] cut to at most [maxLength] characters: at the end of the last whole sentence that fits, else at the last space,
 * else at [maxLength] (without splitting an emoji in two).
 */
internal fun juneShortenSpeakLine(text: String, maxLength: Int = JUNE_SPEAK_MAX_LENGTH): String {
    if (text.length <= maxLength) return text
    val head = text.substring(0, maxLength)
    val sentenceEnd = head.indexOfLast { it in SENTENCE_ENDS }
    if (sentenceEnd > 0) return head.substring(0, sentenceEnd + 1)
    val space = head.lastIndexOf(' ')
    if (space > 0) return head.substring(0, space).trimEnd()
    return if (head.last().isHighSurrogate()) head.dropLast(1) else head
}

/**
 * Element June: the line to read aloud for a notification [event], or null if it must not be read: only text messages from someone
 * else are read, never my own messages (including the replies sent from the notification), edits or redacted messages.
 */
internal fun juneSpeakableLine(event: NotifiableEvent): String? {
    if (event !is NotifiableMessageEvent) return null
    if (event.type != EventType.MESSAGE) return null
    if (event.senderId == event.sessionId || event.outGoingMessage) return null
    if (event.isRedacted || event.isUpdated || event.editedEventId != null) return null
    return juneExtractSpeakLine(event.body)
}

// Values of the android.media.AudioDeviceInfo TYPE_ constants, some of them only exist from API 31 on.
private const val TYPE_BLUETOOTH_SCO = 7
private const val TYPE_BLUETOOTH_A2DP = 8
private const val TYPE_HEARING_AID = 23
private const val TYPE_BLE_HEADSET = 26
private const val TYPE_BLE_SPEAKER = 27
private const val TYPE_BLE_BROADCAST = 30

private val BLUETOOTH_OUTPUT_TYPES = setOf(
    TYPE_BLUETOOTH_SCO,
    TYPE_BLUETOOTH_A2DP,
    TYPE_HEARING_AID,
    TYPE_BLE_HEADSET,
    TYPE_BLE_SPEAKER,
    TYPE_BLE_BROADCAST,
)

/** Element June: true if an audio output device of this AudioDeviceInfo [type] is a Bluetooth one. Wired and USB devices are not. */
internal fun juneIsBluetoothOutput(type: Int): Boolean = type in BLUETOOTH_OUTPUT_TYPES

/** Why a line is not read aloud now. */
enum class JuneSpeakBlock {
    Disabled,
    NoBluetooth,
    InCall,
    MediaMuted,
}

/** Element June: the reason not to read aloud now, or null if it can be read. */
internal fun juneSpeakBlock(enabled: Boolean, hasBluetoothOutput: Boolean, isInCall: Boolean, mediaVolume: Int): JuneSpeakBlock? = when {
    !enabled -> JuneSpeakBlock.Disabled
    !hasBluetoothOutput -> JuneSpeakBlock.NoBluetooth
    isInCall -> JuneSpeakBlock.InCall
    mediaVolume <= 0 -> JuneSpeakBlock.MediaMuted
    else -> null
}

internal data class JuneSpeakItem(val eventId: EventId, val text: String)

/**
 * Element June: lines waiting to be read aloud. Keeps at most [maxSize] lines, dropping the oldest one, and accepts each event only once
 * (the last [rememberedIds] event ids are remembered). Not thread safe.
 */
internal class JuneSpeakQueue(
    private val maxSize: Int = 3,
    private val rememberedIds: Int = 200,
) {
    private val items = ArrayDeque<JuneSpeakItem>()
    private val seen = LinkedHashSet<EventId>()

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    /** Adds [item], returns false if this event was already accepted before. */
    fun offer(item: JuneSpeakItem): Boolean {
        if (!seen.add(item.eventId)) return false
        if (seen.size > rememberedIds) seen.remove(seen.first())
        items.addLast(item)
        while (items.size > maxSize) items.removeFirst()
        return true
    }

    fun poll(): JuneSpeakItem? = items.removeFirstOrNull()

    fun clear() = items.clear()
}
