/*
 * Copyright (c) 2026 Element June.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.ForwardEventException
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustAudioMessageContent
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustEventTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustFileMessageContent
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustImageMessageContent
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustMessageTypeGallery
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustVideoMessageContent
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoom
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoomListService
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoomMessageEventContentWithoutRelation
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiSendHandle
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimeline
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_ROOM_ID_3
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.AssetType
import org.matrix.rustcomponents.sdk.EmoteMessageContent
import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.LocationContent
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.NoticeMessageContent
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.UnstableVoiceContent

/** Element June: a forward with the "[전달: …]" header is one event per room when the message type allows it. */
class RoomContentForwarderTest {
    @Test
    fun `text - one event per room, the header line then the text`() = runTest {
        val result = forward(aText("안녕하세요\n둘째 줄"), listOf(A_ROOM_ID))
        assertThat(result.error).isNull()
        val sent = result.sentTo(A_ROOM_ID)
        assertThat(sent).hasSize(1)
        assertThat(sent[0].text().body).isEqualTo("$HEADER\n안녕하세요\n둘째 줄")
        assertThat(sent[0].text().formatted).isNull()
        assertThat(sent[0].body).isEqualTo("$HEADER\n안녕하세요\n둘째 줄")
    }

    @Test
    fun `text - the header without a comment is the first line`() = runTest {
        val result = forward(aText("본문"), listOf(A_ROOM_ID), header = "[전달: 원래 방]")
        assertThat(result.sentTo(A_ROOM_ID).single().text().body).isEqualTo("[전달: 원래 방]\n본문")
    }

    @Test
    fun `text - the HTML body is kept after a plain header line`() = runTest {
        val html = "<b>굵게</b> <a href=\"https://example.org\">링크</a><ul><li>하나</li></ul><code>x &lt; y</code>"
        val result = forward(aText("**굵게** 링크", html = html), listOf(A_ROOM_ID), header = "[전달: A&B <방>]")
        val text = result.sentTo(A_ROOM_ID).single().text()
        assertThat(text.body).isEqualTo("[전달: A&B <방>]\n**굵게** 링크")
        assertThat(text.formatted?.format).isEqualTo(MessageFormat.Html)
        assertThat(text.formatted?.body).isEqualTo("[전달: A&amp;B &lt;방&gt;]<br>$html")
    }

    @Test
    fun `text - a body in an unknown format is dropped, the plain body keeps everything`() = runTest {
        val content = aMessage(MessageType.Text(TextMessageContent("본문", FormattedBody(MessageFormat.Unknown("x"), "?"))), "본문")
        val text = forward(content, listOf(A_ROOM_ID)).sentTo(A_ROOM_ID).single().text()
        assertThat(text.body).isEqualTo("$HEADER\n본문")
        assertThat(text.formatted).isNull()
    }

    @Test
    fun `text - an emote and a notice become a text message with the header`() = runTest {
        val emote = aMessage(MessageType.Emote(EmoteMessageContent("손을 흔든다", null)), "손을 흔든다")
        val notice = aMessage(MessageType.Notice(NoticeMessageContent("알림", null)), "알림")
        assertThat(forward(emote, listOf(A_ROOM_ID)).sentTo(A_ROOM_ID).single().text().body).isEqualTo("$HEADER\n손을 흔든다")
        assertThat(forward(notice, listOf(A_ROOM_ID)).sentTo(A_ROOM_ID).single().text().body).isEqualTo("$HEADER\n알림")
    }

    @Test
    fun `text - a very long text is one event with the header once at the start`() = runTest {
        val long = "가나다라마바사 ".repeat(700)
        val sent = forward(aText(long), listOf(A_ROOM_ID)).sentTo(A_ROOM_ID)
        assertThat(sent).hasSize(1)
        val body = sent[0].text().body
        assertThat(body).isEqualTo("$HEADER\n$long")
        assertThat(body.split("[전달:")).hasSize(2)
    }

    @Test
    fun `text - no mention is added, the event carries no mentions`() = runTest {
        val content = aText("@someone:example.org 봐 주세요").copy(mentions = null)
        val sent = forward(content, listOf(A_ROOM_ID)).sentTo(A_ROOM_ID).single()
        assertThat(sent.mentions).isNull()
    }

    @Test
    fun `three rooms - one event in each room`() = runTest {
        val result = forward(aText("본문"), listOf(A_ROOM_ID, A_ROOM_ID_2, A_ROOM_ID_3))
        assertThat(result.error).isNull()
        assertThat(result.sent.keys).containsExactly(A_ROOM_ID.value, A_ROOM_ID_2.value, A_ROOM_ID_3.value)
        result.sent.values.forEach { events ->
            assertThat(events).hasSize(1)
            assertThat(events[0].text().body).isEqualTo("$HEADER\n본문")
        }
    }

    @Test
    fun `a room picked twice gets one event`() = runTest {
        val result = forward(aText("본문"), listOf(A_ROOM_ID, A_ROOM_ID))
        assertThat(result.sentTo(A_ROOM_ID)).hasSize(1)
    }

    @Test
    fun `one room failing does not stop the others, and is reported`() = runTest {
        val result = forward(aText("본문"), listOf(A_ROOM_ID, A_ROOM_ID_2, A_ROOM_ID_3), failIn = setOf(A_ROOM_ID_2))
        assertThat(result.sentTo(A_ROOM_ID)).hasSize(1)
        assertThat(result.sentTo(A_ROOM_ID_3)).hasSize(1)
        assertThat(result.sent[A_ROOM_ID_2.value]).isNull()
        assertThat(result.error).isInstanceOf(ForwardEventException::class.java)
        assertThat((result.error as ForwardEventException).roomIds).containsExactly(A_ROOM_ID_2)
    }

    @Test
    fun `picture without a caption - one event, the header is the caption and the file name stays`() = runTest {
        val picture = aMessage(MessageType.Image(aRustImageMessageContent(filename = "IMG_0001.jpg", caption = null)), "IMG_0001.jpg")
        val sent = forward(picture, listOf(A_ROOM_ID)).sentTo(A_ROOM_ID)
        assertThat(sent).hasSize(1)
        val image = (sent[0].msgType as MessageType.Image).content
        assertThat(image.caption).isEqualTo(HEADER)
        assertThat(image.filename).isEqualTo("IMG_0001.jpg")
        assertThat(image.source.url()).isEqualTo("mxc://server/image")
    }

    @Test
    fun `picture with a caption - the header line then the caption`() = runTest {
        val content = aRustImageMessageContent(
            filename = "IMG_0002.png",
            caption = "영수증",
            formattedCaption = FormattedBody(MessageFormat.Html, "<b>영수증</b>"),
        )
        val sent = forward(aMessage(MessageType.Image(content), "영수증"), listOf(A_ROOM_ID)).sentTo(A_ROOM_ID)
        val image = (sent.single().msgType as MessageType.Image).content
        assertThat(image.caption).isEqualTo("$HEADER\n영수증")
        assertThat(image.formattedCaption?.body).isEqualTo("$HEADER<br><b>영수증</b>")
    }

    @Test
    fun `picture of a batch - the batch name is dropped so the bot does not wait for the rest of the batch`() = runTest {
        val content = aRustImageMessageContent(filename = "june-0a1b2c3d4e-2of3.jpg", caption = null)
        val sent = forward(aMessage(MessageType.Image(content), "june-0a1b2c3d4e-2of3.jpg"), listOf(A_ROOM_ID)).sentTo(A_ROOM_ID)
        val image = (sent.single().msgType as MessageType.Image).content
        assertThat(image.filename).isEqualTo("forwarded.jpg")
        assertThat(image.caption).isEqualTo(HEADER)
    }

    @Test
    fun `pictures of one batch forwarded to several rooms - one event per picture and room, each with the header`() = runTest {
        listOf(1, 2, 3).forEach { k ->
            val content = aRustImageMessageContent(filename = "june-0a1b2c3d4e-${k}of3.jpg", caption = null)
            val result = forward(aMessage(MessageType.Image(content), content.filename), listOf(A_ROOM_ID, A_ROOM_ID_2))
            listOf(A_ROOM_ID, A_ROOM_ID_2).forEach { room ->
                val image = (result.sentTo(room).single().msgType as MessageType.Image).content
                assertThat(image.caption).isEqualTo(HEADER)
                assertThat(image.filename).isEqualTo("forwarded.jpg")
            }
        }
    }

    @Test
    fun `voice message - one event, the header is the caption`() = runTest {
        val voice = aRustAudioMessageContent(filename = "voice.ogg", voice = UnstableVoiceContent())
        val sent = forward(aMessage(MessageType.Audio(voice), "voice.ogg"), listOf(A_ROOM_ID)).sentTo(A_ROOM_ID)
        val audio = (sent.single().msgType as MessageType.Audio).content
        assertThat(audio.caption).isEqualTo(HEADER)
        assertThat(audio.voice).isNotNull()
        assertThat(audio.filename).isEqualTo("voice.ogg")
    }

    @Test
    fun `file, video, other audio, location, gallery and unknown types - the header alone first, then the message as it was`() = runTest {
        val location = LocationContent(body = "위치", geoUri = "geo:37.5,127.0", description = null, zoomLevel = null, asset = AssetType.SENDER)
        val kept = listOf(
            aMessage(MessageType.File(aRustFileMessageContent()), "document.pdf"),
            aMessage(MessageType.Video(aRustVideoMessageContent()), "video.mp4"),
            aMessage(MessageType.Audio(aRustAudioMessageContent()), "audio.mp3"),
            aMessage(MessageType.Location(location), "위치"),
            aMessage(aRustMessageTypeGallery(), "A gallery"),
            aMessage(MessageType.Other(msgtype = "m.custom", body = "무엇"), "무엇"),
        )
        kept.forEach { content ->
            val sent = forward(content, listOf(A_ROOM_ID)).sentTo(A_ROOM_ID)
            assertThat(sent).hasSize(2)
            assertThat(sent[0].text().body).isEqualTo(HEADER)
            assertThat(sent[0].text().formatted).isNull()
            assertThat(sent[1]).isEqualTo(content)
        }
    }

    @Test
    fun `a file to three rooms - in every room the header first, then the file`() = runTest {
        val file = aMessage(MessageType.File(aRustFileMessageContent()), "document.pdf")
        val result = forward(file, listOf(A_ROOM_ID, A_ROOM_ID_2, A_ROOM_ID_3), failIn = setOf(A_ROOM_ID_3))
        listOf(A_ROOM_ID, A_ROOM_ID_2).forEach { room ->
            val sent = result.sentTo(room)
            assertThat(sent).hasSize(2)
            assertThat(sent[0].text().body).isEqualTo(HEADER)
            assertThat(sent[1]).isEqualTo(file)
        }
        assertThat((result.error as ForwardEventException).roomIds).containsExactly(A_ROOM_ID_3)
    }

    @Test
    fun `without a header the message is forwarded as it was`() = runTest {
        val content = aText("본문")
        val sent = forward(content, listOf(A_ROOM_ID), header = null).sentTo(A_ROOM_ID)
        assertThat(sent).containsExactly(content)
    }

    @Test
    fun `batch name - only the batch shape is renamed`() {
        assertThat(juneUnbatchedName("june-0a1b2c3d4e-1of2.png")).isEqualTo("forwarded.png")
        assertThat(juneUnbatchedName("IMG_0001.jpg")).isEqualTo("IMG_0001.jpg")
        assertThat(juneUnbatchedName("june-to-abcdefgh12.1.jpg")).isEqualTo("june-to-abcdefgh12.1.jpg")
        assertThat(juneUnbatchedName("june-0a1b2c3d4e-1of2")).isEqualTo("june-0a1b2c3d4e-1of2")
    }

    private class Forwarded(val sent: Map<String, List<MessageContent>>, val error: Throwable?) {
        fun sentTo(roomId: RoomId): List<MessageContent> = sent[roomId.value].orEmpty()
    }

    private suspend fun forward(
        content: MessageContent,
        roomIds: List<RoomId>,
        failIn: Set<RoomId> = emptySet(),
        header: String? = HEADER,
    ): Forwarded {
        val sent = mutableMapOf<String, MutableList<MessageContent>>()
        val source = FakeFfiTimeline(getEventTimelineItemByEventIdLambda = { aRustEventTimelineItem(content = aMsgLike(content)) })
        val roomListService = FakeFfiRoomListService(
            roomLambda = { id ->
                FakeFfiRoom(
                    roomId = RoomId(id),
                    timelineLambda = {
                        FakeFfiTimeline(
                            sendLambda = { msg ->
                                check(RoomId(id) !in failIn) { "not sent" }
                                sent.getOrPut(id) { mutableListOf() }.add((msg as FakeFfiRoomMessageEventContentWithoutRelation).content)
                                FakeFfiSendHandle()
                            }
                        )
                    },
                )
            }
        )
        val forwarder = RoomContentForwarder(roomListService, toEventContent = { FakeFfiRoomMessageEventContentWithoutRelation(it) })
        val error = try {
            forwarder.forward(fromTimeline = source, eventId = AN_EVENT_ID, toRoomIds = roomIds, header = header)
            null
        } catch (e: ForwardEventException) {
            e
        }
        return Forwarded(sent, error)
    }

    private fun MessageContent.text(): TextMessageContent = (msgType as MessageType.Text).content

    private fun aMessage(type: MessageType, body: String) = MessageContent(msgType = type, body = body, isEdited = false, mentions = null)

    private fun aText(body: String, html: String? = null) =
        aMessage(MessageType.Text(TextMessageContent(body = body, formatted = html?.let { FormattedBody(MessageFormat.Html, it) })), body)

    private fun aMsgLike(content: MessageContent) = TimelineItemContent.MsgLike(
        content = MsgLikeContent(
            kind = MsgLikeKind.Message(content = content),
            threadRoot = null,
            inReplyTo = null,
            threadSummary = null,
        ),
    )

    private companion object {
        const val HEADER = "[전달: 원래 방, 맥락:이것 좀 봐]"
    }
}
