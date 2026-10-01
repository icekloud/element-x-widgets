/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.libraries.matrix.api.core.EventId
import org.junit.Test

class JuneQueuePhotosTest {
    private fun item(id: String, kind: String, editable: Boolean = true, photos: List<JuneQueuePhoto> = emptyList()) =
        JuneQueueItem(id, listOf(id) + photos.map { it.id }, kind, editable = editable, edited = false, photos = photos)

    private val twoPhotos = listOf(JuneQueuePhoto("\$p1", "img_a.jpg"), JuneQueuePhoto("\$p2", "img_b.jpg"))

    @Test
    fun `photo line shows the count and the first line of the text`() {
        assertThat(junePhotoLine(2, "\n이거 봐줘\n둘째 줄")).isEqualTo("📷 2장 · 이거 봐줘")
        assertThat(junePhotoLine(1, null)).isEqualTo("📷 사진 1장")
        assertThat(junePhotoLine(3, "  ")).isEqualTo("📷 사진 3장")
        assertThat(junePhotoLine(0, "글")).isEqualTo("📷 1장 · 글")
    }

    @Test
    fun `queue line uses the server picture list with the caption or the text`() {
        val caption = aTimelineItemEvent(eventId = EventId("\$p2"), isMine = true, content = aTimelineItemImageContent(caption = "캡션"))
        assertThat(juneQueueLine(item("\$p2", "photo", photos = twoPhotos), caption)).isEqualTo("📷 2장 · 캡션")
        val noCaption = aTimelineItemEvent(eventId = EventId("\$p2"), isMine = true, content = aTimelineItemImageContent(caption = null))
        assertThat(juneQueueLine(item("\$p2", "photo", photos = twoPhotos), noCaption)).isEqualTo("📷 사진 2장")
        // a text message with a picture added while editing it
        val text = aTimelineItemEvent(eventId = EventId("\$t"), isMine = true, content = aTimelineItemTextContent(body = "질문"))
        assertThat(juneQueueLine(item("\$t", "photo", photos = twoPhotos.take(1)), text)).isEqualTo("📷 1장 · 질문")
        // not loaded yet
        assertThat(juneQueueLine(item("\$p2", "photo", photos = twoPhotos), null)).isEqualTo("📷 사진 2장")
    }

    @Test
    fun `queue line without a picture list keeps the old wording (older gateway)`() {
        val text = aTimelineItemEvent(eventId = EventId("\$t"), isMine = true, content = aTimelineItemTextContent(body = "안녕\n둘째"))
        assertThat(juneQueueLine(item("\$t", "text"), text)).isEqualTo("안녕")
        val photo = aTimelineItemEvent(eventId = EventId("\$p"), isMine = true, content = aTimelineItemImageContent(caption = "캡션"))
        assertThat(juneQueueLine(item("\$p", "photo"), photo)).isEqualTo("📷 사진 1장 + 캡션")
        assertThat(juneQueueLine(item("\$x", "text"), null)).isEqualTo("대기 중인 메시지")
    }

    @Test
    fun `attach file name carries the queued event id without the dollar sign`() {
        assertThat(juneAttachFileName("\$AbCdEf12_-xyz", 1, "jpeg")).isEqualTo("june-to-AbCdEf12_-xyz.1.jpeg")
        assertThat(juneAttachFileName("\$AbCdEf12345", 12, "png")).isEqualTo("june-to-AbCdEf12345.12.png")
        // the gateway accepts 1 to 5 letters or digits as extension
        assertThat(juneAttachFileName("\$AbCdEf12345", 1, "")).isEqualTo("june-to-AbCdEf12345.1.jpg")
        assertThat(juneAttachFileName("\$AbCdEf12345", 1, "jp.e!g")).isEqualTo("june-to-AbCdEf12345.1.jpeg")
    }

    @Test
    fun `attach file name matches the gateway rule, or is null when the id cannot be carried`() {
        val gatewayRule = Regex("^june-to-([A-Za-z0-9_\\-]{8,128})\\.(\\d{1,3})\\.[A-Za-z0-9]{1,5}$")
        val name = juneAttachFileName("\$Ab1-_cdEFgh9", 999, "webp")
        assertThat(name).isNotNull()
        assertThat(gatewayRule.matches(name!!)).isTrue()
        assertThat(gatewayRule.find(name)!!.groupValues[1]).isEqualTo("Ab1-_cdEFgh9")
        assertThat(juneAttachFileName("\$short", 1, "jpg")).isNull()
        assertThat(juneAttachFileName("\$has:colon.server", 1, "jpg")).isNull()
        assertThat(juneAttachFileName("\$AbCdEf12345", 0, "jpg")).isNull()
        assertThat(juneAttachFileName("\$AbCdEf12345", 1000, "jpg")).isNull()
    }

    @Test
    fun `a picture message is edited through its caption, a text through its body`() {
        val text = aTimelineItemEvent(eventId = EventId("\$t"), isMine = true, content = aTimelineItemTextContent(body = "글"))
        val photo = aTimelineItemEvent(eventId = EventId("\$p"), isMine = true, content = aTimelineItemImageContent())
        assertThat(juneEditKind(item("\$t", "text"), text)).isEqualTo(JuneEditKind.Text)
        assertThat(juneEditKind(item("\$p", "photo", photos = twoPhotos), photo)).isEqualTo(JuneEditKind.Caption)
        assertThat(juneEditKind(item("\$p", "photo", editable = false), photo)).isNull()
        assertThat(juneEditKind(item("\$p", "photo"), null)).isNull()
    }

    @Test
    fun `an emptied picture message points to cancel`() {
        assertThat(juneQueueErrorText("detach_photo", "empty")).contains("메시지 취소")
        assertThat(juneQueueErrorText("detach_photo", "oops")).isEqualTo("사진을 빼지 못했습니다")
        assertThat(juneQueueErrorText("cancel", null)).isEqualTo("취소하지 못했습니다")
    }
}
