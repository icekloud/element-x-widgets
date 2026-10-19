/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog

/*
 * 앱23 (결정기록 1·6): what the memo screen needs so a picked picture can still carry the place it was taken at.
 *
 * The system photo picker hides the EXIF location of what it hands over. Since the 2026-08 Mainline update an app may
 * ask it not to, with the intent extra below; the picker then lets the user allow or refuse that, per app. On a phone
 * whose picker does not know the extra the photo simply goes up without a location — nothing fails and nothing is
 * said on the screen (규약-사진위치 6절).
 */

/** The gallery picker of the memo screen, asking for the pictures' location to be kept (결정기록 1). */
internal class MemoPhotoLocationPicker : ActivityResultContracts.PickMultipleVisualMedia() {
    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent =
        super.createIntent(context, input).putExtra(MEMO_EXTRA_REQUEST_LOCATION_METADATA, true)
}

/** What the user is told before the system asks for [Manifest.permission.ACCESS_MEDIA_LOCATION] (요청문 3). */
internal const val MEMO_MEDIA_LOCATION_RATIONALE = "사진에 담긴 촬영 위치를 읽어 장소를 찾는 데 쓰고, 거절해도 사진은 올라가요"

/**
 * Asks once for the permission that lets the app read a picture's own location, and gives back the function that
 * starts it. Call it when the user picks photos — never on opening a screen. Saying no only means photos go up
 * without a location, so nothing here blocks or repeats: one ask per screen, and the phone itself stops asking once
 * the user has refused for good.
 */
@Composable
internal fun rememberMemoMediaLocationAsk(): () -> Unit {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    var rationale by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* 거절해도 사진은 올라간다 */ }

    if (rationale) {
        ConfirmationDialog(
            content = MEMO_MEDIA_LOCATION_RATIONALE,
            onSubmitClick = {
                rationale = false
                tryOrNull { launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
            },
            onDismiss = { rationale = false },
        )
    }

    return remember(context) {
        {
            if (!asked && memoNeedsMediaLocationAsk(context)) {
                asked = true
                if (memoShouldExplainMediaLocation(context)) {
                    rationale = true
                } else {
                    tryOrNull { launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
                }
            }
        }
    }
}

/** True when the permission exists on this phone and has not been given yet. */
internal fun memoNeedsMediaLocationAsk(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val granted = tryOrNull {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION)
    } == PackageManager.PERMISSION_GRANTED
    return !granted
}

/** True when the phone wants the user told why before being asked again. */
private fun memoShouldExplainMediaLocation(context: Context): Boolean {
    val activity = context.memoActivity() ?: return false
    return tryOrNull {
        activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_MEDIA_LOCATION)
    } == true
}

private fun Context.memoActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
