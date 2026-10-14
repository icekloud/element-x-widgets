/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 앱7 항목 2: where the full screen viewer draws a photo from, and its states. Robolectric like the other memo tests
 * (앱8): `org.json` is only the android.jar stub on a plain JVM test, where `JSONObject.put` returns null.
 */
class MemoPhotoViewerTest : RobolectricTest() {
    private val sealed = memoEncryptAttachment(byteArrayOf(1, 2, 3)).fileJson("mxc://hs/a", "image/jpeg").toString()

    @Test
    fun `a phone original is drawn first, then the whole server file, never a thumbnail`() {
        val models = memoViewerModels(MemoAttachment("mxc://hs/a", sealed, "/p/1.jpg"), isFile = { true })
        assertEquals(File("/p/1.jpg"), models[0])
        val server = models[1] as MediaRequestData
        assertTrue(server.kind is MediaRequestData.Kind.Content)
        assertEquals("mxc://hs/a", server.source!!.safeUrl)
        assertTrue(server.source!!.json!!.contains("\"file\""))
    }

    @Test
    fun `only the server copy when the phone file is gone, only the file before the upload`() {
        val serverOnly = memoViewerModels(MemoAttachment("mxc://hs/a", sealed, "/p/gone.jpg"), isFile = { false })
        assertEquals(1, serverOnly.size)
        assertTrue(serverOnly.single() is MediaRequestData)
        val localOnly = memoViewerModels(MemoAttachment("${MEMO_LOCAL_FILE_PREFIX}/p/2.jpg"), isFile = { true })
        assertEquals(listOf<Any>(File("/p/2.jpg")), localOnly)
    }

    @Test
    fun `a failed source moves to the next, both failing shows retry, retry starts again`() {
        var page = MemoViewerPage(listOf(File("/p/1.jpg"), "server"))
        assertTrue(page.loading)
        page = page.error()
        assertEquals("server", page.model)
        assertTrue(page.loading)
        page = page.error()
        assertTrue(page.failed)
        assertFalse(page.loading)
        page = page.retry()
        assertEquals(File("/p/1.jpg"), page.model)
        assertTrue(page.loading)
        assertFalse(page.loaded().loading)
        // Nothing to draw at all (no file, no server copy): failed at once.
        assertTrue(MemoViewerPage(emptyList()).failed)
        assertTrue(MemoViewerPage(memoViewerModels(MemoAttachment("${MEMO_LOCAL_FILE_PREFIX}/p/x.jpg"), isFile = { false })).failed)
    }

    @Test
    fun `the counter shows the page out of the photos`() {
        assertEquals("1/4", memoViewerCounter(0, 4))
        assertEquals("2/4", memoViewerCounter(1, 4))
    }
}
