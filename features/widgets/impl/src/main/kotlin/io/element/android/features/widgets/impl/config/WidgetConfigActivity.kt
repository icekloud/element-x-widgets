/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.element.android.features.widgets.impl.WidgetBindings
import io.element.android.features.widgets.impl.WidgetConfig
import io.element.android.features.widgets.impl.WidgetRoom
import io.element.android.features.widgets.impl.WidgetRoomRepository
import io.element.android.libraries.architecture.bindings
import kotlinx.coroutines.launch

/**
 * Configuration screen of a widget: choose the rooms and their order (long press and drag to reorder).
 * For the 1x1 button widget, only one room can be selected.
 */
class WidgetConfigActivity : ComponentActivity() {
    private lateinit var repository: WidgetRoomRepository
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        repository = bindings<WidgetBindings>().widgetRoomRepository()
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className.orEmpty()
        val singleRoom = provider.endsWith("RoomButtonWidgetReceiver")

        val state = ConfigState()
        lifecycleScope.launch {
            val sessionId = repository.defaultSessionId()
            if (sessionId == null) {
                state.error = "로그인한 계정이 없습니다. 먼저 앱에서 로그인하세요."
                state.loading = false
                return@launch
            }
            state.sessionId = sessionId
            val existing = repository.store.getConfig(appWidgetId)
            val rooms = runCatching { repository.loadAllRooms(sessionId) }.getOrDefault(emptyList())
            state.allRooms.addAll(rooms)
            existing?.roomIds?.forEach { id -> rooms.firstOrNull { it.roomId == id }?.let { state.selected.add(it) } }
            if (rooms.isEmpty()) state.error = "대화방 목록을 불러오지 못했습니다. 앱을 한 번 연 뒤 다시 시도하세요."
            state.loading = false
        }

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConfigScreen(
                        state = state,
                        singleRoom = singleRoom,
                        onSave = { save(state) },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun save(state: ConfigState) {
        val sessionId = state.sessionId ?: return
        repository.store.saveConfig(appWidgetId, WidgetConfig(sessionId, state.selected.map { it.roomId }))
        bindings<WidgetBindings>().widgetUpdater().requestUpdate()
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

internal class ConfigState {
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var sessionId: String? = null
    val allRooms = mutableStateListOf<WidgetRoom>()
    val selected = mutableStateListOf<WidgetRoom>()
}

@Composable
private fun ConfigScreen(
    state: ConfigState,
    singleRoom: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text(
            text = if (singleRoom) "바로 열 대화방 선택" else "위젯에 보일 대화방 선택",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (singleRoom) "대화방 하나를 고르세요." else "선택한 순서대로 표시됩니다. 위쪽 목록에서 길게 눌러 끌면 순서를 바꿀 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> Text("대화방 목록을 불러오는 중…")
            state.error != null && state.allRooms.isEmpty() -> Text(state.error.orEmpty())
            else -> {
                if (!singleRoom && state.selected.isNotEmpty()) {
                    Text("선택됨 (${state.selected.size})", fontWeight = FontWeight.Bold)
                    ReorderableSelected(state)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("대화방 검색") },
                )
                Spacer(Modifier.height(8.dp))
                val filtered = state.allRooms.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(filtered, key = { _, room -> room.roomId }) { _, room ->
                        val checked = state.selected.any { it.roomId == room.roomId }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    toggle(state, room, singleRoom)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle(state, room, singleRoom) })
                            Column(Modifier.weight(1f)) {
                                Text(room.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (room.preview.isNotEmpty()) {
                                    Text(room.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("취소") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSave, enabled = state.selected.isNotEmpty()) { Text("저장") }
        }
    }
}

private fun toggle(state: ConfigState, room: WidgetRoom, singleRoom: Boolean) {
    val index = state.selected.indexOfFirst { it.roomId == room.roomId }
    if (index >= 0) {
        state.selected.removeAt(index)
    } else {
        if (singleRoom) state.selected.clear()
        state.selected.add(room)
    }
}

@Composable
private fun ReorderableSelected(state: ConfigState) {
    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowHeightPx = 56f * androidx.compose.ui.platform.LocalDensity.current.density
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().height((minOf(state.selected.size, 5) * 56).dp),
    ) {
        itemsIndexed(state.selected, key = { _, room -> room.roomId }) { index, room ->
            val isDragging = index == draggingIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .background(
                        if (isDragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(8.dp),
                    )
                    .pointerInput(room.roomId) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = state.selected.indexOfFirst { it.roomId == room.roomId }
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val from = draggingIndex
                                if (from < 0) return@detectDragGesturesAfterLongPress
                                val to = when {
                                    dragOffset > rowHeightPx / 2 && from < state.selected.lastIndex -> from + 1
                                    dragOffset < -rowHeightPx / 2 && from > 0 -> from - 1
                                    else -> from
                                }
                                if (to != from) {
                                    state.selected.add(to, state.selected.removeAt(from))
                                    draggingIndex = to
                                    dragOffset += if (to > from) -rowHeightPx else rowHeightPx
                                }
                            },
                            onDragEnd = { draggingIndex = -1; dragOffset = 0f },
                            onDragCancel = { draggingIndex = -1; dragOffset = 0f },
                        )
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("≡", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(12.dp))
                Text("${index + 1}. ${room.name}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { state.selected.removeAt(index) }) { Text("빼기") }
            }
        }
    }
}
