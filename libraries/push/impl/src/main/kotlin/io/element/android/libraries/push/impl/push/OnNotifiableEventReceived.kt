/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.push

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.widgets.api.WidgetUpdater
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.push.impl.june.JuneSpeaker
import io.element.android.libraries.push.impl.notifications.DefaultNotificationDrawerManager
import io.element.android.libraries.push.impl.notifications.model.NotifiableEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableRingingCallEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface OnNotifiableEventReceived {
    fun onNotifiableEventsReceived(notifiableEvents: List<NotifiableEvent>)
}

@ContributesBinding(AppScope::class)
class DefaultOnNotifiableEventReceived(
    private val defaultNotificationDrawerManager: DefaultNotificationDrawerManager,
    @AppCoroutineScope
    private val coroutineScope: CoroutineScope,
    private val widgetUpdater: WidgetUpdater,
    private val juneSpeaker: JuneSpeaker,
) : OnNotifiableEventReceived {
    override fun onNotifiableEventsReceived(notifiableEvents: List<NotifiableEvent>) {
        // Element June: read the 🔊 line aloud when a Bluetooth audio output is connected (on top of the notification, never instead of it)
        juneSpeaker.onNotifiableEvents(notifiableEvents)
        coroutineScope.launch {
            defaultNotificationDrawerManager.onNotifiableEventsReceived(notifiableEvents.filter { it !is NotifiableRingingCallEvent })
            // Element June: refresh the home screen widgets on new messages
            widgetUpdater.requestUpdate()
        }
    }
}
