/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.widgets.impl.config

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.element.android.features.widgets.impl.WidgetBindings
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.june.JuneSettings

/**
 * Element June: "June 꾸미기" screen — colours of the app and the widgets, and the composer "+" menu.
 * Everything is stored on the device only.
 */
class JuneSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JuneSettings.ensureLoaded(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val accent = JuneSettings.color(JuneSettings.ColorSlot.Accent)
            val scheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JuneSettingsScreen(onColorsChanged = ::refreshWidgets, onClose = ::finish)
                }
            }
        }
    }

    private fun refreshWidgets() {
        runCatching { bindings<WidgetBindings>().widgetUpdater().requestUpdate() }
    }
}

private val PRESETS = listOf(
    0xFF7E57C2, 0xFF9575CD, 0xFFB39DDB, 0xFFD9CCF2, 0xFFF1EBFB, 0xFFFAF7FF,
    0xFF5C6BC0, 0xFF42A5F5, 0xFF26A69A, 0xFF66BB6A, 0xFFFFCA28, 0xFFFF7043,
    0xFFEC407A, 0xFF8D6E63, 0xFF78909C, 0xFF2E2440, 0xFFFFFFFF, 0xFF000000,
).map { Color(it) }

@Composable
private fun JuneSettingsScreen(onColorsChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<JuneSettings.ColorSlot?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("June 꾸미기", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("닫기") }
        }
        Text("바꾼 내용은 바로 적용됩니다. 일부 화면은 다시 열면 반영됩니다.", style = MaterialTheme.typography.bodySmall)

        SectionTitle("색상")
        JuneSettings.ColorSlot.entries.forEach { slot ->
            val color = JuneSettings.color(slot)
            Row(
                modifier = Modifier.fillMaxWidth().clickable { editing = slot }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(color, 32)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(slot.label)
                    Text(
                        (if (JuneSettings.isCustomised(slot)) "직접 지정 · " else "기본값 · ") + color.toHex() +
                            if (slot.lightOnly && !JuneSettings.isCustomised(slot)) " (밝은 테마만)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        TextButton(onClick = {
            JuneSettings.resetAllColors(context)
            onColorsChanged()
        }) { Text("색상 전체 기본값(연보라)으로") }

        HorizontalDivider()
        SectionTitle("+ 메뉴 (항목 켜기·끄기, 순서)")
        val items = JuneSettings.menuItems(context)
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = !JuneSettings.isHidden(item),
                    onCheckedChange = { JuneSettings.setMenuItemVisible(context, item, it) },
                )
                Text(item.label, modifier = Modifier.weight(1f))
                TextButton(enabled = index > 0, onClick = { JuneSettings.moveMenuItem(context, item, -1) }) { Text("▲") }
                TextButton(enabled = index < items.size - 1, onClick = { JuneSettings.moveMenuItem(context, item, 1) }) { Text("▼") }
            }
        }
        TextButton(onClick = { JuneSettings.resetMenu(context) }) { Text("+ 메뉴 기본값으로") }

        HorizontalDivider()
        SectionTitle("봇 선택 아이콘")
        Text("홈 화면 \"봇 선택\" 아이콘을 누르면 펼쳐질 대화방과 순서를 고릅니다.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            context.startActivity(android.content.Intent(context, io.element.android.features.widgets.impl.picker.BotPickerConfigActivity::class.java))
        }) { Text("펼쳐질 봇 고르기") }

        HorizontalDivider()
        SectionTitle("커맨더 음성 아이콘")
        Text(
            "홈 화면 \"커맨더 음성\" 아이콘을 한 번 누르면 녹음이 시작되고, 다시 누르면 커맨더에게 바로 보냅니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        val voiceStore = remember { runCatching { context.bindings<WidgetBindings>().widgetRoomRepository().store }.getOrNull() }
        if (voiceStore != null) {
            var toneEnabled by remember { mutableStateOf(voiceStore.isVoiceShortcutToneEnabled()) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = toneEnabled,
                    onCheckedChange = {
                        toneEnabled = it
                        voiceStore.saveVoiceShortcutToneEnabled(it)
                    },
                )
                Text("녹음 시작할 때 짧은 알림음 (진동은 항상)", modifier = Modifier.weight(1f))
            }
            var maxMinutes by remember { mutableStateOf((voiceStore.getVoiceShortcutMaxSeconds() / 60).coerceAtLeast(1).toString()) }
            OutlinedTextField(
                value = maxMinutes,
                onValueChange = { value ->
                    maxMinutes = value.filter { it.isDigit() }.take(2)
                    maxMinutes.toIntOrNull()?.let { voiceStore.saveVoiceShortcutMaxSeconds(it * 60) }
                },
                label = { Text("최대 녹음 길이 (분)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "최대 길이에 닿으면 녹음을 멈추고 보내지 않습니다. 알림에서 보내거나 지울 수 있습니다.\n보낼 방: " +
                    voiceStore.getVoiceShortcutRoomId(),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()
        MemoRoomSection()

        HorizontalDivider()
                SectionTitle("방별 테마 (Aperture · GLaDOS)")
        Text("방 이름에 아래 단어가 들어가면 그 방은 어두운 Aperture 테마(검정 배경·주황 강조)로 보입니다. 쉼표로 구분합니다.", style = MaterialTheme.typography.bodySmall)
        var apertureText by remember { mutableStateOf(JuneSettings.apertureRooms(context)) }
        OutlinedTextField(value = apertureText, onValueChange = { apertureText = it }, label = { Text("방 이름 단어") }, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(onClick = { JuneSettings.setApertureRooms(context, apertureText) }) { Text("저장") }
            TextButton(onClick = {
                apertureText = JuneSettings.DEFAULT_APERTURE_ROOMS
                JuneSettings.setApertureRooms(context, apertureText)
            }) { Text("기본값으로") }
        }

    }

    editing?.let { slot ->
        ColorDialog(
            slot = slot,
            onPick = {
                JuneSettings.setColor(context, slot, it)
                onColorsChanged()
                editing = null
            },
            onReset = {
                JuneSettings.resetColor(context, slot)
                onColorsChanged()
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * Element June memo bot: the room opened as the memo screen (기록 · 폴더 · 검색) instead of the chat.
 * The room is the one the admin joined by accepting the bot invite; it is picked from the room list (no id typing).
 */
@Composable
private fun MemoRoomSection() {
    val context = LocalContext.current
    Column {
        SectionTitle("메모봇 방")
        Text(
            "고른 방은 대화 대신 메모 화면(기록 · 폴더 · 검색)으로 열립니다. 입력창은 그대로입니다. 다른 방은 바뀌지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        val repository = remember { runCatchingExceptions { context.bindings<WidgetBindings>().widgetRoomRepository() }.getOrNull() }
        var rooms by remember { mutableStateOf<List<io.element.android.features.widgets.impl.WidgetRoom>?>(null) }
        var picking by remember { mutableStateOf(false) }
        LaunchedEffect(repository) {
            if (repository == null) {
                rooms = emptyList()
                return@LaunchedEffect
            }
            val sid = runCatchingExceptions { repository.defaultSessionId() }.getOrNull()
            rooms = if (sid == null) emptyList() else runCatchingExceptions { repository.loadAllRooms(sid) }.getOrDefault(emptyList())
        }
        val current = JuneSettings.memoRoomId(context)
        val currentName = current?.let { id -> rooms?.firstOrNull { it.roomId == id }?.name ?: id }
        Text(if (currentName == null) "지정 안 됨" else "지금: $currentName")
        Row {
            TextButton(enabled = rooms != null, onClick = { picking = !picking }) { Text(if (rooms == null) "방 목록을 불러오는 중…" else "방 고르기") }
            TextButton(enabled = current != null, onClick = { JuneSettings.setMemoRoomId(context, null) }) { Text("지정 해제") }
        }
        if (picking) {
            rooms.orEmpty().forEach { room ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            JuneSettings.setMemoRoomId(context, room.roomId)
                            picking = false
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text((if (room.roomId == current) "● " else "○ ") + room.name, modifier = Modifier.weight(1f), maxLines = 1)
                }
            }
            if (rooms.isNullOrEmpty()) Text("대화방이 없습니다. 메모봇 초대를 먼저 수락하세요.", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = JuneSettings.memoFakeData(context), onCheckedChange = { JuneSettings.setMemoFakeData(context, it) })
            Text("가짜 데이터로 미리 보기 (서버 없이 화면만 확인, 조작은 저장되지 않음)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(
    slot: JuneSettings.ColorSlot,
    onPick: (Color) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var hex by remember(slot) { mutableStateOf(JuneSettings.colorOf(context, slot).toHex()) }
    val parsed = parseHex(hex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(slot.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { c ->
                        Box(modifier = Modifier.clickable { hex = c.toHex() }) { Swatch(c, 36) }
                    }
                    // Transparent (no background)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .border(1.dp, Color.Gray, CircleShape)
                            .clickable { hex = "#00000000" },
                        contentAlignment = Alignment.Center,
                    ) { Text("없음", style = MaterialTheme.typography.labelSmall) }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it.trim() },
                    label = { Text("색상 코드 (#RRGGBB 또는 #AARRGGBB)") },
                    singleLine = true,
                    isError = parsed == null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("미리보기  ")
                    Swatch(parsed ?: Color.Transparent, 28)
                }
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onPick) }) { Text("적용") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("기본값") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Swatch(color: Color, sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(50))
            .background(color, RoundedCornerShape(50)),
    )
}

private fun Color.toHex(): String {
    val argb = toArgb()
    return if ((argb ushr 24) == 0xFF) {
        "#%06X".format(argb and 0xFFFFFF)
    } else {
        "#%08X".format(argb)
    }
}

private fun parseHex(text: String): Color? {
    val t = text.removePrefix("#")
    if (!t.matches(Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}"))) return null
    val v = t.toLong(16)
    return if (t.length == 6) Color(0xFF000000 or v) else Color(v)
}
