/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.picker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.element.android.features.widgets.impl.WidgetBindings
import io.element.android.features.widgets.impl.WidgetRoom
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.designsystem.june.JuneSettings
import kotlinx.coroutines.launch

/**
 * Element June: choose and order the rooms shown by the "봇 선택" icon. Stored on the device only.
 */
class BotPickerConfigActivity : ComponentActivity() {
    private val allRooms = mutableStateListOf<WidgetRoom>()
    private val selected = mutableStateListOf<WidgetRoom>()
    private val loading = mutableStateOf(true)
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JuneSettings.ensureLoaded(this)
        val repository = bindings<WidgetBindings>().widgetRoomRepository()
        lifecycleScope.launch {
            val sid = repository.defaultSessionId() ?: return@launch finish()
            sessionId = sid
            val rooms = runCatching { repository.loadAllRooms(sid) }.getOrDefault(emptyList())
            allRooms.addAll(rooms)
            selected.addAll(runCatching { repository.botPickerRooms(sid, cacheOnly = true) }.getOrDefault(emptyList()))
            loading.value = false
        }
        setContent {
            val accent = JuneSettings.color(JuneSettings.ColorSlot.Accent)
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text("봇 선택 아이콘", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "홈 화면의 \"봇 선택\" 아이콘을 누르면 펼쳐질 대화방입니다. 기본은 1:1 대화방(home-ops 제외) 최근 6개입니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.padding(4.dp))
                        if (loading.value) {
                            Text("대화방 목록을 불러오는 중…")
                        } else {
                            Text("펼쳐질 순서 (${selected.size})", fontWeight = FontWeight.Bold)
                            selected.forEachIndexed { index, room ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text("${index + 1}. ${room.name}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton(enabled = index > 0, onClick = { move(index, -1) }) { Text("▲") }
                                    TextButton(enabled = index < selected.size - 1, onClick = { move(index, 1) }) { Text("▼") }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text("전체 대화방", fontWeight = FontWeight.Bold)
                            allRooms.forEach { room ->
                                val checked = selected.any { it.roomId == room.roomId }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Checkbox(checked = checked, onCheckedChange = { on ->
                                        if (on) selected.add(room) else selected.removeAll { it.roomId == room.roomId }
                                    })
                                    Text(room.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            TextButton(onClick = { reset(repository.defaultBotRooms(allRooms)) }) { Text("기본값으로") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { finish() }) { Text("취소") }
                            Button(onClick = {
                                sessionId?.let { repository.store.saveBotPickerRoomIds(it, selected.map { room -> room.roomId }) }
                                finish()
                            }) { Text("저장") }
                        }
                    }
                }
            }
        }
    }

    private fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in selected.indices) return
        val room = selected.removeAt(index)
        selected.add(target, room)
    }

    private fun reset(defaults: List<WidgetRoom>) {
        selected.clear()
        selected.addAll(defaults)
        sessionId?.let { bindings<WidgetBindings>().widgetRoomRepository().store.saveBotPickerRoomIds(it, null) }
    }
}
