/*
 * Copyright (c) 2026 Element June.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.fixtures.fakes

import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation

/** Keeps the [content] it was made from, so a test can read what would have been sent. */
class FakeFfiRoomMessageEventContentWithoutRelation(
    val content: MessageContent,
) : RoomMessageEventContentWithoutRelation(NoHandle)
