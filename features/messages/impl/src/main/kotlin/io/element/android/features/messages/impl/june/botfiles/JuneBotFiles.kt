/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.botfiles

import io.element.android.libraries.androidutils.filesize.FileSizeFormatter
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.api.DateFormatterMode
import io.element.android.libraries.dateformatter.api.toHumanReadableDuration
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.GalleryItemType
import io.element.android.libraries.matrix.api.timeline.item.event.GalleryMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageTypeWithAttachment
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.getAvatarUrl
import io.element.android.libraries.matrix.api.timeline.item.event.getDisambiguatedDisplayName
import io.element.android.libraries.mediaviewer.api.MediaInfo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Element June: what kind of attachment a bot file is (picks the row icon and the viewer). */
enum class JuneBotFileKind { Image, Video, Audio, File }

/** Element June: one attachment a bot sent in this room, as shown in the bot file list. */
data class JuneBotFile(
    /** Unique in the list: the event id, plus the position for pictures sent together. */
    val key: String,
    val eventId: EventId,
    val kind: JuneBotFileKind,
    val timestamp: Long,
    val dateLabel: String,
    val mediaInfo: MediaInfo,
    val mediaSource: MediaSource,
    val thumbnailSource: MediaSource?,
    val blurHash: String?,
)

/**
 * Element June: the attachments in [items] that were not sent by [me], newest first.
 * The room is a chat with one bot, so everything not sent by the user came from the bot.
 */
fun juneBotFiles(
    items: List<MatrixTimelineItem>,
    me: UserId,
    dateFormatter: DateFormatter,
    fileSizeFormatter: FileSizeFormatter,
): ImmutableList<JuneBotFile> {
    val files = mutableListOf<JuneBotFile>()
    for (item in items.asReversed()) {
        val event = (item as? MatrixTimelineItem.Event)?.event ?: continue
        if (event.sender == me) continue
        val eventId = event.eventId ?: continue
        val type = (event.content as? MessageContent)?.type ?: continue
        val parts: List<MessageType> = if (type is GalleryMessageType) {
            type.items.mapNotNull { galleryItem ->
                when (galleryItem) {
                    is GalleryItemType.Image -> galleryItem.content
                    is GalleryItemType.Video -> galleryItem.content
                    is GalleryItemType.Audio -> galleryItem.content
                    is GalleryItemType.File -> galleryItem.content
                    is GalleryItemType.Other -> null
                }
            }
        } else {
            listOf(type)
        }
        parts.forEachIndexed { index, part ->
            val key = if (type is GalleryMessageType) "${eventId.value}#$index" else eventId.value
            event.toBotFile(key, eventId, part, dateFormatter, fileSizeFormatter)?.let(files::add)
        }
    }
    return files.toImmutableList()
}

private fun EventTimelineItem.toBotFile(
    key: String,
    eventId: EventId,
    type: MessageType,
    dateFormatter: DateFormatter,
    fileSizeFormatter: FileSizeFormatter,
): JuneBotFile? {
    val attachment = type as? MessageTypeWithAttachment ?: return null
    val details = when (type) {
        is ImageMessageType -> AttachmentDetails(
            kind = JuneBotFileKind.Image,
            source = type.source,
            mimeType = type.info?.mimetype,
            size = type.info?.size,
            thumbnailSource = type.info?.thumbnailSource,
            blurHash = type.info?.blurhash,
        )
        is VideoMessageType -> AttachmentDetails(
            kind = JuneBotFileKind.Video,
            source = type.source,
            mimeType = type.info?.mimetype,
            size = type.info?.size,
            thumbnailSource = type.info?.thumbnailSource,
            blurHash = type.info?.blurhash,
            duration = type.info?.duration?.inWholeMilliseconds?.toHumanReadableDuration(),
        )
        is AudioMessageType -> AttachmentDetails(
            kind = JuneBotFileKind.Audio,
            source = type.source,
            mimeType = type.info?.mimetype,
            size = type.info?.size,
            duration = type.info?.duration?.inWholeMilliseconds?.toHumanReadableDuration(),
        )
        is VoiceMessageType -> AttachmentDetails(
            kind = JuneBotFileKind.Audio,
            source = type.source,
            mimeType = type.info?.mimetype,
            size = type.info?.size,
            duration = type.info?.duration?.inWholeMilliseconds?.toHumanReadableDuration(),
            waveform = type.details?.waveform,
        )
        is FileMessageType -> AttachmentDetails(
            kind = JuneBotFileKind.File,
            source = type.source,
            mimeType = type.info?.mimetype,
            size = type.info?.size,
        )
        // Stickers are not files
        else -> return null
    }
    return JuneBotFile(
        key = key,
        eventId = eventId,
        kind = details.kind,
        timestamp = timestamp,
        dateLabel = dateFormatter.format(timestamp, DateFormatterMode.TimeOrDate, useRelative = true),
        mediaInfo = MediaInfo(
            filename = attachment.filename,
            caption = attachment.caption,
            formattedCaption = attachment.formattedCaption?.body,
            mimeType = details.mimeType?.takeIf { it.isNotBlank() } ?: MimeTypes.OctetStream,
            fileSize = details.size,
            formattedFileSize = details.size?.let { fileSizeFormatter.format(it) }.orEmpty(),
            fileExtension = attachment.filename.substringAfterLast('.', ""),
            senderId = sender,
            senderName = senderProfile.getDisambiguatedDisplayName(sender),
            senderAvatar = senderProfile.getAvatarUrl(),
            dateSent = dateFormatter.format(timestamp, DateFormatterMode.Day),
            dateSentFull = dateFormatter.format(timestamp, DateFormatterMode.Full),
            waveform = details.waveform,
            duration = details.duration,
        ),
        mediaSource = details.source,
        thumbnailSource = details.thumbnailSource,
        blurHash = details.blurHash,
    )
}

private class AttachmentDetails(
    val kind: JuneBotFileKind,
    val source: MediaSource,
    val mimeType: String?,
    val size: Long?,
    val thumbnailSource: MediaSource? = null,
    val blurHash: String? = null,
    val duration: String? = null,
    val waveform: List<Float>? = null,
)
