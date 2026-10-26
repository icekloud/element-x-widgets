/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import android.system.Os
import timber.log.Timber
import java.io.File

/**
 * 앱32: makes the folder of a voice lead to the one copy of the file the four voices share, with a symbolic link
 * (`android.system.Os.symlink`, there since API 21 while this app needs 24).
 *
 * The engine opens its files with `fopen`, which follows a link, so the 20 MB of language data are only kept once. A phone that
 * does not let the link be made or read answers false, and the file is copied instead ([samsungVoiceInstall]).
 */
internal class SamsungOsVoiceLinker : SamsungVoiceLinker {
    override fun link(target: File, link: File): Boolean = try {
        link.delete()
        Os.symlink(target.absolutePath, link.absolutePath)
        // A link the file system refuses to follow (SELinux, another mount point) is of no use: it is checked before being kept
        link.isFile && link.length() == target.length()
    } catch (error: Throwable) {
        Timber.tag("SamsungVoice").w("The shared voice file could not be linked: %s", error.javaClass.simpleName)
        false
    }
}
