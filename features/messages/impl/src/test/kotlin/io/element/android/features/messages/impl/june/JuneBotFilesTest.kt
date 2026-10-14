/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.june.botfiles.JuneBotFileKind
import io.element.android.features.messages.impl.june.botfiles.juneBotFiles
import io.element.android.libraries.androidutils.filesize.FakeFileSizeFormatter
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.GalleryItemType
import io.element.android.libraries.matrix.api.timeline.item.event.GalleryMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.test.media.aMediaSource
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import org.junit.Test

class JuneBotFilesTest {
    private val me = UserId("@mmtrx2:server.org")
    private val bot = UserId("@coder:server.org")

    private fun item(id: String, sender: UserId, type: MessageType, timestamp: Long = 0L) = MatrixTimelineItem.Event(
        uniqueId = UniqueId(id),
        event = anEventTimelineItem(
            eventId = EventId("\$event$id"),
            sender = sender,
            timestamp = timestamp,
            content = aMessageContent(body = "body", messageType = type),
        ),
    )

    private fun file(name: String, mime: String? = "application/pdf", size: Long? = 10L) = FileMessageType(
        filename = name,
        caption = null,
        formattedCaption = null,
        source = aMediaSource("mxc://server/$name"),
        info = FileInfo(mimetype = mime, size = size, thumbnailInfo = null, thumbnailSource = null),
    )

    private fun image(name: String) = ImageMessageType(
        filename = name,
        caption = null,
        formattedCaption = null,
        source = aMediaSource("mxc://server/$name"),
        info = ImageInfo(height = 1, width = 1, mimetype = MimeTypes.Png, size = 5L, thumbnailInfo = null, thumbnailSource = null, blurhash = null),
    )

    private fun run(items: List<MatrixTimelineItem>) = juneBotFiles(items, me, FakeDateFormatter(), FakeFileSizeFormatter())

    @Test
    fun `only attachments the bot sent are listed, newest first`() {
        val result = run(
            listOf(
                item("a", bot, file("old.pdf"), timestamp = 1),
                item("b", me, file("mine.pdf"), timestamp = 2),
                item("c", bot, TextMessageType(body = "hello", formatted = null), timestamp = 3),
                item("d", bot, image("new.png"), timestamp = 4),
            )
        )
        assertThat(result.map { it.mediaInfo.filename }).containsExactly("new.png", "old.pdf").inOrder()
        assertThat(result.map { it.kind }).containsExactly(JuneBotFileKind.Image, JuneBotFileKind.File).inOrder()
        assertThat(result.first().eventId).isEqualTo(EventId("\$eventd"))
    }

    @Test
    fun `every picture of a gallery message is its own row with a unique key`() {
        val gallery = GalleryMessageType(
            body = "two",
            formatted = null,
            items = listOf(
                GalleryItemType.Image(image("one.png")),
                GalleryItemType.Other(itemType = "x", body = "skip"),
                GalleryItemType.File(file("two.pdf")),
            ),
        )
        val result = run(listOf(item("g", bot, gallery)))
        assertThat(result.map { it.mediaInfo.filename }).containsExactly("one.png", "two.pdf").inOrder()
        assertThat(result.map { it.key }.toSet()).hasSize(2)
        assertThat(result.map { it.eventId }.toSet()).containsExactly(EventId("\$eventg"))
    }

    @Test
    fun `a file without a mime type is saved as a plain binary file`() {
        val result = run(listOf(item("a", bot, file("data.bin", mime = null, size = null))))
        assertThat(result.single().mediaInfo.mimeType).isEqualTo(MimeTypes.OctetStream)
        assertThat(result.single().mediaInfo.formattedFileSize).isEmpty()
        assertThat(result.single().mediaInfo.fileExtension).isEqualTo("bin")
    }
}
