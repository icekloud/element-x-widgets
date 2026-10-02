/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemDaySeparator
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemReactions
import io.element.android.features.messages.impl.timeline.aTimelineItemReadReceipts
import io.element.android.features.messages.impl.timeline.components.event.junePhotoGroupPictureSize
import io.element.android.features.messages.impl.timeline.components.receipt.aReadReceiptData
import io.element.android.features.messages.impl.timeline.model.JunePhotoGroup
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemThreadInfo
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemVideoContent
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.ThreadSummary
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test

class JunePhotoGroupsTest {
    private val alice = UserId("@alice:server")
    private val bob = UserId("@bob:server")

    private fun photo(
        id: String,
        atSeconds: Long,
        sender: UserId = alice,
        caption: String? = null,
        filename: String = "$id.jpg",
    ) = aTimelineItemEvent(
        eventId = EventId("\$$id"),
        content = aTimelineItemImageContent(filename = filename, caption = caption),
        // The helper adds a reaction by default, and reactions end a run
        timelineItemReactions = aTimelineItemReactions(count = 0),
    ).copy(senderId = sender, sentTimeMillis = atSeconds * 1000)

    private fun text(id: String, atSeconds: Long, sender: UserId = alice) =
        aTimelineItemEvent(eventId = EventId("\$$id"), content = aTimelineItemTextContent(), timelineItemReactions = aTimelineItemReactions(count = 0))
            .copy(senderId = sender, sentTimeMillis = atSeconds * 1000)

    /** [oldestFirst] reads like the screen, top to bottom; the timeline keeps the newest first. */
    private fun group(vararg oldestFirst: TimelineItem) = juneGroupPhotos(oldestFirst.reversed().toImmutableList()).reversed()

    private fun List<TimelineItem>.groups() = map { (it as? TimelineItem.Event)?.junePhotoGroup }

    @Test
    fun `consecutive pictures of one sender share a bubble, oldest first`() {
        val result = group(photo("a", 0), photo("b", 10), photo("c", 20))
        assertThat(result.groups()).containsExactly(JunePhotoGroup(0, 3), JunePhotoGroup(1, 3), JunePhotoGroup(2, 3)).inOrder()
        assertThat(JunePhotoGroup(0, 3).isFirst).isTrue()
        assertThat(JunePhotoGroup(2, 3).isLast).isTrue()
        assertThat(JunePhotoGroup(1, 3).isFirst || JunePhotoGroup(1, 3).isLast).isFalse()
    }

    @Test
    fun `a single picture is left as it is`() {
        val items = persistentListOf<TimelineItem>(text("t2", 30), photo("a", 20), text("t1", 0))
        assertThat(juneGroupPhotos(items)).isSameInstanceAs(items)
    }

    @Test
    fun `nothing to group returns the same list`() {
        val items = persistentListOf<TimelineItem>()
        assertThat(juneGroupPhotos(items)).isSameInstanceAs(items)
    }

    @Test
    fun `a text message between pictures breaks the run`() {
        val result = group(photo("a", 0), photo("b", 1), text("t", 2), photo("c", 3), photo("d", 4))
        assertThat(result.groups()).containsExactly(
            JunePhotoGroup(0, 2),
            JunePhotoGroup(1, 2),
            null,
            JunePhotoGroup(0, 2),
            JunePhotoGroup(1, 2),
        ).inOrder()
    }

    @Test
    fun `a video or a day separator between pictures breaks the run`() {
        val video = aTimelineItemEvent(content = aTimelineItemVideoContent()).copy(senderId = alice, sentTimeMillis = 1000)
        assertThat(group(photo("a", 0), video, photo("b", 2)).groups()).containsExactly(null, null, null).inOrder()
        assertThat(group(photo("a", 0), aTimelineItemDaySeparator(), photo("b", 2)).groups()).containsExactly(null, null, null).inOrder()
    }

    @Test
    fun `a different sender breaks the run, and both senders are grouped`() {
        val result = group(photo("a", 0), photo("b", 1), photo("c", 2, sender = bob), photo("d", 3, sender = bob))
        assertThat(result.groups()).containsExactly(
            JunePhotoGroup(0, 2),
            JunePhotoGroup(1, 2),
            JunePhotoGroup(0, 2),
            JunePhotoGroup(1, 2),
        ).inOrder()
        assertThat(group(photo("a", 0), photo("b", 1, sender = bob)).groups()).containsExactly(null, null).inOrder()
    }

    @Test
    fun `pictures at most two minutes apart are grouped, further apart they are not`() {
        assertThat(JUNE_PHOTO_GROUP_WINDOW_MS).isEqualTo(120_000L)
        assertThat(group(photo("a", 0), photo("b", 120)).groups()).containsExactly(JunePhotoGroup(0, 2), JunePhotoGroup(1, 2)).inOrder()
        assertThat(group(photo("a", 0), photo("b", 121)).groups()).containsExactly(null, null).inOrder()
        // The window is between neighbours, not from the first picture
        assertThat(group(photo("a", 0), photo("b", 100), photo("c", 200)).groups())
            .containsExactly(JunePhotoGroup(0, 3), JunePhotoGroup(1, 3), JunePhotoGroup(2, 3))
            .inOrder()
    }

    @Test
    fun `pictures sent together by the app are grouped even when the upload took long`() {
        val result = group(
            photo("a", 0, filename = "june-1a2b3c4d-1of2.jpg"),
            photo("b", 600, filename = "june-1a2b3c4d-2of2.jpg"),
        )
        assertThat(result.groups()).containsExactly(JunePhotoGroup(0, 2), JunePhotoGroup(1, 2)).inOrder()
        val otherBatch = group(
            photo("a", 0, filename = "june-1a2b3c4d-2of2.jpg"),
            photo("b", 600, filename = "june-9f8e7d6c-1of2.jpg"),
        )
        assertThat(otherBatch.groups()).containsExactly(null, null).inOrder()
    }

    @Test
    fun `a picture with a caption ends its run, the next picture starts a new one`() {
        val result = group(photo("a", 0), photo("b", 1, caption = "look"), photo("c", 2), photo("d", 3))
        assertThat(result.groups()).containsExactly(
            JunePhotoGroup(0, 2),
            JunePhotoGroup(1, 2),
            JunePhotoGroup(0, 2),
            JunePhotoGroup(1, 2),
        ).inOrder()
        // A captioned picture followed by a lone picture: both alone
        assertThat(group(photo("a", 0, caption = "look"), photo("b", 1)).groups()).containsExactly(null, null).inOrder()
    }

    @Test
    fun `reactions end a run, a reply starts one`() {
        val withReaction = photo("b", 1).copy(reactionsState = aTimelineItemReactions(count = 1))
        assertThat(group(photo("a", 0), withReaction, photo("c", 2)).groups())
            .containsExactly(JunePhotoGroup(0, 2), JunePhotoGroup(1, 2), null)
            .inOrder()
        // A reply's quote is drawn at the top of its bubble: it starts a new run, and can be followed by pictures
        val reply = photo("b", 1).copy(inReplyTo = InReplyToDetails.Loading(EventId("\$quoted")))
        assertThat(group(photo("a", 0), reply, photo("c", 2)).groups())
            .containsExactly(null, JunePhotoGroup(0, 2), JunePhotoGroup(1, 2))
            .inOrder()
    }

    @Test
    fun `threads keep their pictures apart`() {
        val summary = ThreadSummary(latestEvent = AsyncData.Uninitialized, numberOfReplies = 3)
        val root = photo("a", 0).copy(threadInfo = TimelineItemThreadInfo.ThreadRoot(summary = summary, latestEventText = null))
        assertThat(group(root, photo("b", 1)).groups()).containsExactly(null, null).inOrder()
        val inThread = photo("c", 1).copy(threadInfo = TimelineItemThreadInfo.ThreadResponse(ThreadId("\$root")))
        assertThat(group(photo("b", 0), inThread).groups()).containsExactly(null, null).inOrder()
        val inThreadToo = photo("d", 2).copy(threadInfo = TimelineItemThreadInfo.ThreadResponse(ThreadId("\$root")))
        assertThat(group(inThread, inThreadToo).groups()).containsExactly(JunePhotoGroup(0, 2), JunePhotoGroup(1, 2)).inOrder()
    }

    @Test
    fun `a picture that failed to send stays alone`() {
        val failed = photo("b", 1).copy(localSendState = LocalEventSendState.Failed.Unknown("error"))
        assertThat(group(photo("a", 0), failed).groups()).containsExactly(null, null).inOrder()
    }

    @Test
    fun `the last picture shows the read receipts of the whole run, once per user`() {
        val r1 = aReadReceiptData(1)
        val r2 = aReadReceiptData(2)
        val result = group(
            photo("a", 0).copy(readReceiptState = aTimelineItemReadReceipts(listOf(r1))),
            photo("b", 1).copy(readReceiptState = aTimelineItemReadReceipts(listOf(r2, r1))),
            photo("c", 2),
        ).map { it as TimelineItem.Event }
        assertThat(result[2].readReceiptState.receipts).containsExactly(r2, r1).inOrder()
        // The other pictures keep theirs (not shown), so that per picture data is not lost
        assertThat(result[0].readReceiptState.receipts).containsExactly(r1)
    }

    @Test
    fun `a picture that no longer has neighbours loses its group`() {
        val wasGrouped = photo("a", 0).copy(junePhotoGroup = JunePhotoGroup(0, 2))
        assertThat(group(wasGrouped, text("t", 1)).groups()).containsExactly(null, null).inOrder()
    }

    @Test
    fun `grouping twice gives the same items`() {
        val once = juneGroupPhotos(listOf<TimelineItem>(photo("c", 2), photo("b", 1), photo("a", 0)).toImmutableList())
        assertThat(juneGroupPhotos(once)).isSameInstanceAs(once)
    }

    @Test
    fun `pictures of a run all get the same width, height bounded`() {
        val tall = junePhotoGroupPictureSize(0.5f)
        val wide = junePhotoGroupPictureSize(2f)
        val unknown = junePhotoGroupPictureSize(null)
        assertThat(tall.width).isEqualTo(wide.width)
        assertThat(unknown.width).isEqualTo(wide.width)
        assertThat(tall.height.value).isAtMost(180f)
        assertThat(wide.height.value).isAtLeast(100f)
        assertThat(junePhotoGroupPictureSize(Float.NaN).width).isEqualTo(wide.width)
    }
}
