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
    var editingQuick by remember { mutableStateOf<Pair<Int, String>?>(null) }
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

        HorizontalDivider()
        SectionTitle("빠른 명령 (상단 바 목록 버튼)")
        Text("누르면 지금 대화방에 그 문구가 그대로 전송됩니다. 문구를 눌러 고칠 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        val quick = JuneSettings.quickCommands(context)
        quick.forEachIndexed { index, text ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    modifier = Modifier.weight(1f).clickable { editingQuick = index to text }.padding(vertical = 8.dp),
                )
                TextButton(enabled = index > 0, onClick = { JuneSettings.moveQuickCommand(context, index, -1) }) { Text("▲") }
                TextButton(enabled = index < quick.size - 1, onClick = { JuneSettings.moveQuickCommand(context, index, 1) }) { Text("▼") }
                TextButton(onClick = { JuneSettings.removeQuickCommand(context, index) }) { Text("삭제") }
            }
        }
        Row {
            TextButton(onClick = { editingQuick = -1 to "" }) { Text("+ 문구 추가") }
            TextButton(onClick = { JuneSettings.resetQuickCommands(context) }) { Text("기본값으로") }
        }
    }

    editingQuick?.let { (index, initial) ->
        var value by remember(index) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { editingQuick = null },
            title = { Text(if (index < 0) "빠른 명령 추가" else "빠른 명령 수정") },
            text = {
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("보낼 문구") })
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    if (index < 0) JuneSettings.addQuickCommand(context, value) else JuneSettings.updateQuickCommand(context, index, value)
                    editingQuick = null
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { editingQuick = null }) { Text("취소") } },
        )
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
