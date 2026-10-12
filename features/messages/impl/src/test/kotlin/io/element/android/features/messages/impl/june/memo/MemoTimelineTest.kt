/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.libraries.matrix.api.media.MediaSource
import org.junit.Test

class MemoTimelineTest {
    @Test
    fun `photo events are found by their mxc url, so a note attachment opens the room media viewer`() {
        val photo = aTimelineItemEvent(content = aTimelineItemImageContent().copy(mediaSource = MediaSource("mxc://hs/abc#auto")))
        val text = aTimelineItemEvent(content = aTimelineItemTextContent(body = "빵집 메모"))
        // v2: the record tab reads the local notes; only this lookup is left of the timeline (설계 §g)
        val photos = memoPhotosByMxc(listOf(text, photo))
        assertThat(photos.keys).containsExactly("mxc://hs/abc")
        assertThat(photos["mxc://hs/abc"]).isSameInstanceAs(photo)
    }
}
