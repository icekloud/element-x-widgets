/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.designsystem.june.JuneMemoButtonSpot
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱32 (추가요청 3, 관리자: "플로팅 버튼(꾹 누르면 이동 가능한형태)로 제안탭 접근 가능한 방식으로 수정"): the parts of the
 * floating proposal button that can be told without a screen — when it is drawn, what it says, where it sits.
 */
class MemoPanelFabTest : RobolectricTest() {
    @Test
    fun `the floating button is drawn over the tabs and gone while something else has the screen`() {
        assertTrue(memoPanelFabVisible(fullEditor = false, sheetOpen = false, viewerOpen = false, panelOpen = false))
        // 추가요청 3 ⑤: the editor fills the screen — the button would sit over what is being written.
        assertFalse(memoPanelFabVisible(fullEditor = true, sheetOpen = false, viewerOpen = false, panelOpen = false))
        assertFalse(memoPanelFabVisible(fullEditor = false, sheetOpen = true, viewerOpen = false, panelOpen = false))
        assertFalse(memoPanelFabVisible(fullEditor = false, sheetOpen = false, viewerOpen = true, panelOpen = false))
        // The proposal screen it opens is already open.
        assertFalse(memoPanelFabVisible(fullEditor = false, sheetOpen = false, viewerOpen = false, panelOpen = true))
    }

    @Test
    fun `the badge and the spoken name are the same as the list icon of the top bar`() {
        assertNull(memoPanelFabBadge(0))
        assertNull(memoPanelFabBadge(-1))
        assertEquals("1", memoPanelFabBadge(1))
        assertEquals("99", memoPanelFabBadge(99))
        assertEquals("99+", memoPanelFabBadge(100))
        assertEquals("제안", memoPanelFabDescription(0))
        assertEquals("제안 3건", memoPanelFabDescription(3))
    }

    @Test
    fun `it starts at the right edge a little below the middle`() {
        assertEquals(1f, MEMO_FAB_DEFAULT.x, 0f)
        assertEquals(0.65f, MEMO_FAB_DEFAULT.y, 0f)
        // Not the place of the home screen button (bottom centre), which would sit on the composer.
        assertTrue(MEMO_FAB_DEFAULT != JuneMemoButtonSpot.DEFAULT)
    }

    @Test
    fun `where it was let go is kept as parts of the screen, so it stays on a screen of another size`() {
        // Let go in the middle of a 1000 x 2000 area: the middle of a 500 x 1000 one too (a turn of the phone).
        val spot = JuneMemoButtonSpot.dropped(left = 500f, top = 1000f, freeWidth = 1000f, freeHeight = 2000f, snap = 24f)
        assertEquals(0.5f, spot.x, 0.001f)
        assertEquals(0.5f, spot.y, 0.001f)
        assertEquals(250f, spot.left(500f), 0.001f)
        assertEquals(500f, spot.top(1000f), 0.001f)
        // 결정기록 D13: let go near an edge it is put against it, and it never leaves the area.
        assertEquals(1f, JuneMemoButtonSpot.dropped(990f, 100f, 1000f, 2000f, 24f).x, 0.001f)
        assertEquals(0f, JuneMemoButtonSpot.dropped(-50f, 10f, 1000f, 2000f, 24f).x, 0.001f)
        assertEquals(1f, JuneMemoButtonSpot.dropped(5000f, 9000f, 1000f, 2000f, 24f).y, 0.001f)
    }
}
