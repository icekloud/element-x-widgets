/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.ui.messages.toPlainText
import io.element.android.libraries.push.impl.notifications.fixtures.aNotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.fixtures.aSimpleNotifiableEvent
import org.junit.Test
import java.time.ZoneId

class JuneSpeakLineTest {
    private val line = "지금 야사카 신사 오타비쇼 앞이에요. 가까운 곳에 포케몬센타가 백이십 미터 거리에 있어요."

    @Test
    fun `the first line is read without the speaker emoji`() {
        val body = "🔊 $line\n\n📍 상세 본문\n둘째 줄"
        assertThat(juneExtractSpeakLine(body)).isEqualTo(line)
    }

    @Test
    fun `the emoji with a variation selector or without a space is also removed`() {
        assertThat(juneExtractSpeakLine("🔊\uFE0F $line")).isEqualTo(line)
        assertThat(juneExtractSpeakLine("🔊$line")).isEqualTo(line)
        assertThat(juneExtractSpeakLine("  🔊   $line   ")).isEqualTo(line)
    }

    @Test
    fun `a message without a speaker line is not read`() {
        assertThat(juneExtractSpeakLine("📍 지금 위치\n상세 본문")).isNull()
        assertThat(juneExtractSpeakLine("그냥 메시지")).isNull()
        assertThat(juneExtractSpeakLine(null)).isNull()
        assertThat(juneExtractSpeakLine("")).isNull()
    }

    @Test
    fun `a speaker emoji in the middle of a line is not read`() {
        assertThat(juneExtractSpeakLine("메시지 중간에 🔊 이모지")).isNull()
        assertThat(juneExtractSpeakLine("안녕하세요\n오늘은 🔊 $line")).isNull()
    }

    private val footer = "To stop or manage this job, send me a new message (e.g. \"stop reminder 동선 알림 발송(5분)\")."

    private fun header(name: String = "동선 알림 발송(5분)") = "Cronjob Response: $name\n(job_id: e9d0db134e2d)\n-------------\n\n"

    @Test
    fun `the speaker line after the 3 line header of a scheduled message is read`() {
        val body = header() + "🔊 $line\n\n📍 롬 시어터 남쪽 → 가모강 쪽으로 이동 중\n⏰ 일곱 시 사십 분\n\n$footer"
        assertThat(juneExtractSpeakLine(body)).isEqualTo(line)
    }

    @Test
    fun `the speaker line of a message without header is read`() {
        assertThat(juneExtractSpeakLine("🔊 $line\n\n📍 상세 본문\n\n$footer")).isEqualTo(line)
        assertThat(juneExtractSpeakLine("안녕하세요\n🔊 $line")).isEqualTo(line)
    }

    @Test
    fun `the speaker line is looked for in the first 6 non blank lines only`() {
        // Lines 1 to 5, then the speaker line as 6th non blank line, blank lines do not count
        val sixth = "1\n\n2\n3\n\n\n4\n5\n\n🔊 $line\n본문"
        assertThat(juneExtractSpeakLine(sixth)).isEqualTo(line)
        assertThat(JUNE_SPEAK_MAX_LINES).isEqualTo(6)
    }

    @Test
    fun `a speaker line as 7th non blank line is not read`() {
        val seventh = "1\n2\n3\n\n4\n5\n6\n\n🔊 $line\n본문"
        assertThat(juneExtractSpeakLine(seventh)).isNull()
    }

    @Test
    fun `only the first speaker line is read when several notifications follow each other`() {
        val body = header() + "🔊 첫 알림이에요.\n\n📍 첫 본문\n\n---\n\n🔊 둘째 알림이에요.\n\n📍 둘째 본문\n\n$footer"
        assertThat(juneExtractSpeakLine(body)).isEqualTo("첫 알림이에요.")
    }

    @Test
    fun `a scheduled message with header and footer but no speaker line is not read`() {
        assertThat(juneExtractSpeakLine(header() + "📍 롬 시어터 남쪽\n⏰ 일곱 시 사십 분\n\n$footer")).isNull()
        assertThat(juneExtractSpeakLine(header() + footer)).isNull()
        assertThat(juneExtractSpeakLine(footer)).isNull()
    }

    @Test
    fun `the header is never read, even if it contains a speaker emoji`() {
        // The job name line starts with "Cronjob Response:", not with the emoji: the real speaker line is read
        assertThat(juneExtractSpeakLine(header(name = "🔊 알림") + "🔊 $line\n\n본문")).isEqualTo(line)
        assertThat(juneExtractSpeakLine(header(name = "동선 🔊 알림") + "🔊 $line")).isEqualTo(line)
    }

    @Test
    fun `full width spaces, variation selector and invisible characters around the emoji are handled`() {
        assertThat(juneExtractSpeakLine(header() + "🔊\uFE0F $line")).isEqualTo(line)
        assertThat(juneExtractSpeakLine(header() + "\u3000🔊\u3000$line\u3000")).isEqualTo(line)
        assertThat(juneExtractSpeakLine(header() + "\u00A0🔊\uFE0F\u00A0$line")).isEqualTo(line)
        assertThat(juneExtractSpeakLine("\uFEFF🔊 $line")).isEqualTo(line)
        assertThat(juneExtractSpeakLine("\u200B🔊 $line")).isEqualTo(line)
        // A line made of the emoji, the selector and spaces only gives nothing
        assertThat(juneExtractSpeakLine(header() + "🔊\uFE0F\u3000\n본문")).isNull()
    }

    @Test
    fun `the speaker line of a scheduled message sent as html by the server is read`() {
        // The server converts the markdown of the message to html: the "-------------" line becomes a rule, which is dropped
        val html = "<p>Cronjob Response: 동선 알림 발송(5분)<br>\n(job_id: e9d0db134e2d)</p>\n<hr>\n" +
            "<p>🔊 $line</p>\n<p>📍 롬 시어터 남쪽 → 가모강<br>\n⏰ 7:40</p>\n<hr>\n<p>🔊 둘째 알림</p>\n<p>📍 x</p>\n" +
            "<p>To stop or manage this job, send me a new message (e.g. &quot;stop reminder 동선 알림 발송(5분)&quot;).</p>"
        val body = TextMessageType(body = "", formatted = FormattedBody(MessageFormat.HTML, html)).toPlainText(FakePermalinkParser())
        assertThat(juneExtractSpeakLine(body)).isEqualTo(line)
    }

    @Test
    fun `the result line shows the outcome and the time, never the text`() {
        val zone = ZoneId.of("Asia/Seoul")
        // 2026-10-03 10:36:00 UTC = 19:36 KST
        val millis = 1_791_023_760_000L
        assertThat(juneSpeakResultText(JuneSpeakResult.Read, millis, zone)).isEqualTo("읽음 19:36")
        assertThat(juneSpeakResultText(JuneSpeakResult.NoSpeakerLine, millis, zone)).isEqualTo("🔊 줄 없음 19:36")
        assertThat(juneSpeakResultText(JuneSpeakResult.FocusRefused, millis, zone)).isEqualTo("포커스 거부 19:36")
        assertThat(JuneSpeakResult.entries.map { it.label }).containsExactly(
            "읽음",
            "🔊 줄 없음",
            "설정 꺼짐",
            "블루투스 아님",
            "통화 중",
            "음량 0",
            "포커스 거부",
            "한국어 음성 없음",
            "음성 엔진 준비 실패",
            "읽기 실패",
        )
    }

    @Test
    fun `blank lines before the first line are skipped`() {
        assertThat(juneExtractSpeakLine("\n\n  \n🔊 $line\n\n본문")).isEqualTo(line)
        assertThat(juneExtractSpeakLine("\r\n🔊 $line\r\n\r\n본문")).isEqualTo(line)
    }

    @Test
    fun `a speaker emoji alone or blank lines only give nothing`() {
        assertThat(juneExtractSpeakLine("🔊")).isNull()
        assertThat(juneExtractSpeakLine("🔊   \n\n본문")).isNull()
        assertThat(juneExtractSpeakLine("\n\n   \n")).isNull()
    }

    @Test
    fun `a line up to the limit is read whole`() {
        val text = "가".repeat(JUNE_SPEAK_MAX_LENGTH)
        assertThat(juneExtractSpeakLine("🔊 $text")).isEqualTo(text)
        assertThat(juneShortenSpeakLine(line)).isEqualTo(line)
    }

    @Test
    fun `a too long line is cut at the end of the last whole sentence`() {
        val first = "첫 문장이에요."
        val text = first + " " + "나".repeat(JUNE_SPEAK_MAX_LENGTH)
        val result = juneExtractSpeakLine("🔊 $text")
        assertThat(result).isEqualTo(first)
    }

    @Test
    fun `a too long line without a sentence end is cut at the last space`() {
        val text = "다".repeat(100) + " " + "라".repeat(100)
        assertThat(juneShortenSpeakLine(text)).isEqualTo("다".repeat(100))
    }

    @Test
    fun `a too long line without a space is cut at the limit`() {
        val text = "마".repeat(200)
        assertThat(juneShortenSpeakLine(text)).isEqualTo("마".repeat(JUNE_SPEAK_MAX_LENGTH))
        assertThat(juneShortenSpeakLine("abcdef", maxLength = 4)).isEqualTo("abcd")
    }

    @Test
    fun `a cut never splits an emoji in two`() {
        // The limit falls between both halves of the emoji
        assertThat(juneShortenSpeakLine("abc😀def", maxLength = 4)).isEqualTo("abc")
    }

    @Test
    fun `a text message from someone else with a speaker line is read`() {
        val event = aNotifiableMessageEvent(body = "🔊 $line\n\n본문")
        assertThat(juneSpeakableLine(event)).isEqualTo(line)
    }

    @Test
    fun `my own messages are not read`() {
        assertThat(juneSpeakableLine(aNotifiableMessageEvent(body = "🔊 $line", senderId = A_SESSION_ID))).isNull()
        assertThat(juneSpeakableLine(aNotifiableMessageEvent(body = "🔊 $line").copy(outGoingMessage = true))).isNull()
    }

    @Test
    fun `edits, redacted messages and other event types are not read`() {
        val event = aNotifiableMessageEvent(body = "🔊 $line")
        assertThat(juneSpeakableLine(event.copy(editedEventId = EventId("\$edited")))).isNull()
        assertThat(juneSpeakableLine(event.copy(isUpdated = true))).isNull()
        assertThat(juneSpeakableLine(event.copy(isRedacted = true))).isNull()
        assertThat(juneSpeakableLine(aNotifiableMessageEvent(body = "🔊 $line", type = "m.reaction"))).isNull()
        assertThat(juneSpeakableLine(aSimpleNotifiableEvent(eventId = AN_EVENT_ID))).isNull()
    }

    @Test
    fun `bluetooth audio outputs are recognised`() {
        listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
        ).forEach { type ->
            assertThat(juneIsBluetoothOutput(type)).isTrue()
        }
    }

    @Test
    fun `the phone speaker, wired and usb outputs are not bluetooth`() {
        listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_UNKNOWN,
        ).forEach { type ->
            assertThat(juneIsBluetoothOutput(type)).isFalse()
        }
    }

    @Test
    fun `reading is blocked when disabled, without bluetooth, during a call or with the media volume at zero`() {
        assertThat(juneSpeakBlock(enabled = true, hasBluetoothOutput = true, isInCall = false, mediaVolume = 5)).isNull()
        assertThat(juneSpeakBlock(enabled = false, hasBluetoothOutput = true, isInCall = false, mediaVolume = 5)).isEqualTo(JuneSpeakBlock.Disabled)
        assertThat(juneSpeakBlock(enabled = true, hasBluetoothOutput = false, isInCall = false, mediaVolume = 5)).isEqualTo(JuneSpeakBlock.NoBluetooth)
        assertThat(juneSpeakBlock(enabled = true, hasBluetoothOutput = true, isInCall = true, mediaVolume = 5)).isEqualTo(JuneSpeakBlock.InCall)
        assertThat(juneSpeakBlock(enabled = true, hasBluetoothOutput = true, isInCall = false, mediaVolume = 0)).isEqualTo(JuneSpeakBlock.MediaMuted)
    }

    @Test
    fun `the queue keeps the 3 newest lines`() {
        val queue = JuneSpeakQueue()
        (1..5).forEach { queue.offer(JuneSpeakItem(EventId("\$event$it"), "줄 $it")) }
        assertThat(queue.size).isEqualTo(3)
        assertThat(generateSequence { queue.poll() }.map { it.text }.toList()).containsExactly("줄 3", "줄 4", "줄 5").inOrder()
        assertThat(queue.isEmpty()).isTrue()
    }

    @Test
    fun `the same event is accepted only once, even after it was read`() {
        val queue = JuneSpeakQueue()
        val item = JuneSpeakItem(EventId("\$event1"), "줄")
        assertThat(queue.offer(item)).isTrue()
        assertThat(queue.offer(item)).isFalse()
        assertThat(queue.poll()).isEqualTo(item)
        assertThat(queue.offer(item)).isFalse()
        assertThat(queue.isEmpty()).isTrue()
    }

    @Test
    fun `only the last event ids are remembered`() {
        val queue = JuneSpeakQueue(maxSize = 3, rememberedIds = 2)
        val first = JuneSpeakItem(EventId("\$event1"), "1")
        queue.offer(first)
        queue.offer(JuneSpeakItem(EventId("\$event2"), "2"))
        queue.offer(JuneSpeakItem(EventId("\$event3"), "3"))
        assertThat(queue.offer(first)).isTrue()
    }
}
