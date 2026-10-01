/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.libraries.matrix.api.core.EventId
import org.junit.Test

class JuneSteeringRowsTest {
    private fun queued(id: String) = JuneQueueItem(id, listOf(id), "text", editable = true, edited = false)

    @Test
    fun `steered messages get a row, unless the server still lists them as queued`() {
        val rows = juneSteeringRows(
            items = listOf(queued("\$q")),
            steering = listOf(JuneHeldItem("\$s", listOf("\$s")), JuneHeldItem("\$q", listOf("\$q"))),
        )
        assertThat(rows.map { it.id }).containsExactly("\$s")
    }

    @Test
    fun `header counts queued and steered messages, and is null when both are empty`() {
        assertThat(juneQueueHeader(2, 0)).isEqualTo("대기 2")
        assertThat(juneQueueHeader(0, 1)).isEqualTo("반영 대기 1")
        assertThat(juneQueueHeader(3, 1)).isEqualTo("대기 3 · 반영 대기 1")
        assertThat(juneQueueHeader(25, 0)).isEqualTo("대기 25 · 곧 가득 참(최대 32)")
        assertThat(juneQueueHeader(0, 0)).isNull()
    }

    @Test
    fun `steering row shows the first line of the held message`() {
        val event = aTimelineItemEvent(eventId = EventId("\$s"), isMine = true, content = aTimelineItemTextContent(body = "\n이거 먼저 봐\n둘째 줄"))
        assertThat(juneSteeringLine(JuneHeldItem("\$s", listOf("\$s")), event)).isEqualTo("⏩ 반영 대기 · 이거 먼저 봐")
    }

    @Test
    fun `steering row without the event falls back to a picture count or a plain label`() {
        assertThat(juneSteeringLine(JuneHeldItem("\$p2", listOf("\$p1", "\$p2")), null)).isEqualTo("⏩ 반영 대기 · 📷 사진 2장")
        assertThat(juneSteeringLine(JuneHeldItem("\$s", listOf("\$s")), null)).isEqualTo("⏩ 반영 대기 · 대기 중인 메시지")
    }
}
