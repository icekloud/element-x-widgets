/*
 * Copyright (c) 2026 Element June.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TextMessageContent

/**
 * Element June: [content] with the forward [header] ("[전달: …]") as its first line, so that the header and the
 * forwarded message travel as one event.
 *
 * - Text, notice and emote: one text message, the header line then the original text (an HTML body is kept, after
 *   the header). A notice or an emote becomes a text message, which the receiving bot reads.
 * - Picture and voice message: the header goes in front of the caption. A picture named like one picture of a batch
 *   ("june-<id>-<k>of<n>.<ext>") gets a plain name ([juneUnbatchedName]), so the receiving bot does not wait for the
 *   rest of a batch that is not coming.
 * - File, video, other audio, gallery, location and unknown types: null. The receiving bot takes the file name of
 *   a file, a video or an audio from its body, which a caption replaces, and does not read galleries and locations.
 *   These keep the header as a message of its own, sent just before ([juneForwardHeaderContent]).
 */
internal fun juneForwardContent(content: MessageContent, header: String): MessageContent? {
    val type = when (val t = content.msgType) {
        is MessageType.Text -> juneHeaderText(header, t.content.body, t.content.formatted)
        is MessageType.Notice -> juneHeaderText(header, t.content.body, t.content.formatted)
        is MessageType.Emote -> juneHeaderText(header, t.content.body, t.content.formatted)
        is MessageType.Image -> MessageType.Image(
            t.content.copy(
                filename = juneUnbatchedName(t.content.filename),
                caption = juneHeaderLines(header, t.content.caption),
                formattedCaption = juneHeaderHtml(header, t.content.formattedCaption),
            )
        )
        is MessageType.Audio -> t.takeIf { it.content.voice != null }?.let {
            MessageType.Audio(
                it.content.copy(
                    caption = juneHeaderLines(header, it.content.caption),
                    formattedCaption = juneHeaderHtml(header, it.content.formattedCaption),
                )
            )
        }
        is MessageType.File,
        is MessageType.Video,
        is MessageType.Gallery,
        is MessageType.Location,
        is MessageType.Other -> null
    } ?: return null
    return content.copy(msgType = type, body = (type as? MessageType.Text)?.content?.body ?: content.body)
}

/** Element June: the header alone, as a plain text message (for the types [juneForwardContent] leaves alone). */
internal fun juneForwardHeaderContent(header: String): MessageContent = MessageContent(
    msgType = MessageType.Text(TextMessageContent(body = header, formatted = null)),
    body = header,
    isEdited = false,
    mentions = null,
)

/** "june-<id>-<k>of<n>.<ext>", the name of one picture of a batch, becomes "forwarded.<ext>". Other names are kept. */
internal fun juneUnbatchedName(filename: String): String {
    val match = JUNE_BATCH_NAME.matchEntire(filename) ?: return filename
    return "forwarded" + match.groupValues[1]
}

// Same shape as the receiving bot's batch rule
private val JUNE_BATCH_NAME = Regex("^june-[0-9a-f]{6,32}-\\d{1,3}of\\d{1,3}(\\.[A-Za-z0-9]{1,5})$")

private fun juneHeaderText(header: String, body: String, formatted: FormattedBody?): MessageType =
    MessageType.Text(TextMessageContent(body = juneHeaderLines(header, body), formatted = juneHeaderHtml(header, formatted)))

private fun juneHeaderLines(header: String, text: String?): String =
    if (text.isNullOrBlank()) header else "$header\n$text"

/** The HTML body with the header as a plain first line. A body in another format is dropped: the plain body has it all. */
private fun juneHeaderHtml(header: String, formatted: FormattedBody?): FormattedBody? =
    formatted?.takeIf { it.format == MessageFormat.Html }?.let {
        FormattedBody(format = MessageFormat.Html, body = juneEscapeHtml(header) + "<br>" + it.body)
    }

private fun juneEscapeHtml(text: String): String = buildString {
    text.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
