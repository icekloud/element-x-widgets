/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.model.aRoomListRoomSummary
import io.element.android.features.home.impl.roomlist.RoomListContentState
import io.element.android.features.home.impl.roomlist.aRoomsContentState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

/** Element June: the memo room is left out of the home room list only when it is set (추가요청 1~3). */
class JuneHomeMemoRoomTest {
    private val memo = aRoomListRoomSummary(id = "!memo:domain")
    private val other = aRoomListRoomSummary(id = "!other:domain")
    private val rooms = aRoomsContentState(summaries = persistentListOf(other, memo))

    @Test
    fun `without a memo room the room list is the same`() {
        assertThat(rooms.withoutJuneMemoRoom(null)).isSameInstanceAs(rooms)
    }

    @Test
    fun `the memo room is hidden and the other rooms stay in order`() {
        val shown = rooms.withoutJuneMemoRoom("!memo:domain") as RoomListContentState.Rooms
        assertThat(shown.summaries.map { it.roomId.value }).containsExactly("!other:domain")
    }

    @Test
    fun `a memo room that is not in the list changes nothing`() {
        assertThat(rooms.withoutJuneMemoRoom("!absent:domain")).isSameInstanceAs(rooms)
    }

    @Test
    fun `other states are returned as they are`() {
        val skeleton = RoomListContentState.Skeleton(count = 3)
        assertThat(skeleton.withoutJuneMemoRoom("!memo:domain")).isSameInstanceAs(skeleton)
    }
}
