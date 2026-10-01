/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.matrix.api.core.EventId
import org.junit.Test

class JuneReorderPlacedTest {
    private fun mine(id: String) = aTimelineItemEvent(eventId = EventId(id), isMine = true)
    private fun bot(id: String) = aTimelineItemEvent(eventId = EventId(id), isMine = false)
    private fun List<TimelineItem>.ids() = map { (it as TimelineItem.Event).eventId!!.value }

    @Test
    fun `queued message moves right below the answer it took effect after`() {
        // newest first: the answer arrived after 대기1, so Matrix shows 대기1 above the answer
        val items = listOf(bot("\$next"), bot("\$answer"), mine("\$wait1"), bot("\$progress"))
        val out = juneReorderPlaced(items, listOf(JunePlacement("\$wait1", listOf("\$wait1"), "\$answer")))
        assertThat(out.ids()).containsExactly("\$next", "\$wait1", "\$answer", "\$progress").inOrder()
    }

    @Test
    fun `a batch moves together and keeps its order`() {
        val items = listOf(bot("\$a"), mine("\$p2"), mine("\$p1"), bot("\$b"))
        val out = juneReorderPlaced(items, listOf(JunePlacement("\$p2", listOf("\$p1", "\$p2"), "\$a")))
        assertThat(out.ids()).containsExactly("\$p2", "\$p1", "\$a", "\$b").inOrder()
    }

    @Test
    fun `missing anchor, newer message or bot event keep timeline order`() {
        val items = listOf(mine("\$new"), bot("\$a"), bot("\$x"))
        val placed = listOf(
            JunePlacement("\$gone", listOf("\$gone"), "\$a"),
            JunePlacement("\$new", listOf("\$new"), "\$a"),
            JunePlacement("\$x", listOf("\$x"), "\$a"),
            JunePlacement("\$new", listOf("\$new"), "\$unknown"),
        )
        assertThat(juneReorderPlaced(items, placed).ids()).containsExactly("\$new", "\$a", "\$x").inOrder()
    }
}
