/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Element June: background process panel.
 *
 * The bot's gateway publishes its running background processes as the room state event
 * `app.june.background`. This panel shows them above the composer and can ask the gateway to
 * force-stop one by sending the room event `app.june.bg_kill` ({"id": "<process id>"}).
 * Nothing is shown when the room has no background process (or the bot does not support it).
 */
@ContributesTo(AppScope::class)
interface JuneBackgroundBindings {
    fun juneSessionStore(): SessionStore
}

private const val STATE_TYPE = "app.june.background"
private const val KILL_TYPE = "app.june.bg_kill"
private const val POLL_MILLIS = 3_000L

internal data class JuneBackgroundItem(
    val id: String,
    val title: String,
    val command: String,
    val startedAtSeconds: Long,
)

private class JuneBackgroundClient(private val sessionStore: SessionStore) {
    private suspend fun session() = sessionStore.getLatestSession()

    private fun open(base: String, path: String, token: String, method: String): HttpURLConnection {
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    /** Returns the current process list, an empty list when there is none, or null on a transient error. */
    suspend fun fetch(roomId: String): List<JuneBackgroundItem>? = withContext(Dispatchers.IO) {
        try {
            val s = session() ?: return@withContext null
            val room = URLEncoder.encode(roomId, "UTF-8")
            val conn = open(s.homeserverUrl, "/_matrix/client/v3/rooms/$room/state/$STATE_TYPE/", s.accessToken, "GET")
            try {
                when (conn.responseCode) {
                    200 -> {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val arr = json.optJSONArray("processes") ?: return@withContext emptyList()
                        List(arr.length()) { i ->
                            val o = arr.getJSONObject(i)
                            JuneBackgroundItem(
                                id = o.optString("id"),
                                title = o.optString("title"),
                                command = o.optString("command"),
                                startedAtSeconds = o.optLong("started_at"),
                            )
                        }.filter { it.id.isNotEmpty() }
                    }
                    404 -> emptyList()
                    else -> null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Timber.d("June background: fetch failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    suspend fun kill(roomId: String, processId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val s = session() ?: return@withContext false
            val room = URLEncoder.encode(roomId, "UTF-8")
            val txn = UUID.randomUUID().toString().replace("-", "")
            val conn = open(s.homeserverUrl, "/_matrix/client/v3/rooms/$room/send/$KILL_TYPE/$txn", s.accessToken, "PUT")
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("id", processId).toString().toByteArray()) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Timber.d("June background: kill request failed (%s)", e.javaClass.simpleName)
            false
        }
    }
}

private fun elapsedText(startedAtSeconds: Long, nowMillis: Long): String {
    if (startedAtSeconds <= 0) return ""
    val sec = ((nowMillis / 1000) - startedAtSeconds).coerceAtLeast(0)
    return when {
        sec < 60 -> "${sec}초"
        sec < 3600 -> "${sec / 60}분 ${sec % 60}초"
        else -> "${sec / 3600}시간 ${(sec % 3600) / 60}분"
    }
}

@Composable
internal fun JuneBackgroundPanel(
    roomId: String,
    modifier: Modifier = Modifier,
) {
    val context: Context = LocalContext.current
    val client = remember(context) { JuneBackgroundClient(context.bindings<JuneBackgroundBindings>().juneSessionStore()) }
    var items by remember(roomId) { mutableStateOf<List<JuneBackgroundItem>>(emptyList()) }
    var expanded by remember(roomId) { mutableStateOf(false) }
    var openId by remember(roomId) { mutableStateOf<String?>(null) }
    var confirm by remember(roomId) { mutableStateOf<JuneBackgroundItem?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Ids the user asked to kill: hidden until the server stops listing them (or 60s), so a poll that
    // still sees the dying process does not bring the row back.
    val killed = remember(roomId) { mutableStateMapOf<String, Long>() }
    fun visible(list: List<JuneBackgroundItem>): List<JuneBackgroundItem> {
        val nowMs = System.currentTimeMillis()
        val ids = list.map { it.id }.toSet()
        killed.keys.toList().forEach { k ->
            if (k !in ids || nowMs - (killed[k] ?: 0L) > 60_000L) killed.remove(k)
        }
        return list.filterNot { it.id in killed }
    }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(roomId) {
        while (true) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                client.fetch(roomId)?.let { items = visible(it) }
            }
            now = System.currentTimeMillis()
            delay(POLL_MILLIS)
        }
    }
    LaunchedEffect(items.isNotEmpty()) {
        while (items.isNotEmpty()) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    if (items.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ElementTheme.colors.bgSubtleSecondary),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                modifier = Modifier.size(18.dp),
                imageVector = if (expanded) CompoundIcons.ChevronDown() else CompoundIcons.ChevronRight(),
                contentDescription = null,
                tint = ElementTheme.colors.iconSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${items.size} Background",
                style = ElementTheme.typography.fontBodySmMedium,
                color = ElementTheme.colors.textSecondary,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openId = if (openId == item.id) null else item.id }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { confirm = item }
                                    .padding(5.dp),
                                imageVector = CompoundIcons.Close(),
                                contentDescription = "강제종료",
                                tint = ElementTheme.colors.textCriticalPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                modifier = Modifier.weight(1f),
                                text = item.title,
                                style = ElementTheme.typography.fontBodyMdRegular,
                                color = ElementTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = elapsedText(item.startedAtSeconds, now),
                                style = ElementTheme.typography.fontBodySmRegular,
                                color = ElementTheme.colors.textSecondary,
                            )
                        }
                        if (openId == item.id && item.command.isNotEmpty()) {
                            Text(
                                modifier = Modifier.padding(start = 36.dp, top = 2.dp),
                                text = item.command,
                                style = ElementTheme.typography.fontBodySmRegular,
                                color = ElementTheme.colors.textSecondary,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { target ->
        ConfirmationDialog(
            title = "백그라운드 작업 강제종료",
            content = "'${target.title}' 작업을 강제로 종료할까요?\n진행 중인 작업이 중단되며 되돌릴 수 없습니다.",
            submitText = "강제종료",
            destructiveSubmit = true,
            onSubmitClick = {
                confirm = null
                killed[target.id] = System.currentTimeMillis()
                items = items.filterNot { it.id == target.id }
                scope.launch {
                    if (!client.kill(roomId, target.id)) {
                        // request failed: show it again
                        killed.remove(target.id)
                        client.fetch(roomId)?.let { items = visible(it) }
                    }
                }
            },
            onDismiss = { confirm = null },
        )
    }
}
