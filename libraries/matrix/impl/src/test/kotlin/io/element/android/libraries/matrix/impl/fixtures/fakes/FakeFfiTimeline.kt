/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.fixtures.fakes

import io.element.android.tests.testutils.lambda.lambdaError
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.PaginationStatusListener
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.SendHandle
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk.PaginationStatus

class FakeFfiTimeline(
    private val getEventTimelineItemByEventIdLambda: (String) -> EventTimelineItem = { lambdaError() },
    private val sendLambda: (RoomMessageEventContentWithoutRelation) -> SendHandle = { lambdaError() },
) : Timeline(NoHandle) {
    private var listener: TimelineListener? = null
    override suspend fun addListener(listener: TimelineListener): TaskHandle {
        this.listener = listener
        return FakeFfiTaskHandle()
    }

    fun emitDiff(diff: List<TimelineDiff>) {
        listener!!.onUpdate(diff)
    }

    private var paginationStatusListener: PaginationStatusListener? = null
    override suspend fun subscribeToBackPaginationStatus(listener: PaginationStatusListener): TaskHandle {
        this.paginationStatusListener = listener
        return FakeFfiTaskHandle()
    }

    fun emitPaginationStatus(status: PaginationStatus) {
        paginationStatusListener!!.onUpdate(status)
    }

    override suspend fun paginateBackwards(numEvents: UShort): Boolean {
        return true
    }

    override suspend fun fetchMembers() = Unit

    override suspend fun getEventTimelineItemByEventId(eventId: String): EventTimelineItem {
        return getEventTimelineItemByEventIdLambda(eventId)
    }

    override suspend fun send(msg: RoomMessageEventContentWithoutRelation): SendHandle {
        return sendLambda(msg)
    }
}
