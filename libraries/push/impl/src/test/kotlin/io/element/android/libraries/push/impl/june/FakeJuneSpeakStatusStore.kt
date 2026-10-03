/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

class FakeJuneSpeakStatusStore : JuneSpeakStatusStore {
    val results = mutableListOf<JuneSpeakResult>()

    override fun record(result: JuneSpeakResult) {
        results += result
    }
}
