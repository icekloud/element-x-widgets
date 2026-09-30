/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.credentials.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun statusText(s: RemoteState): String = when (s.status) {
    RemoteStatus.PENDING -> "보내기 전"
    RemoteStatus.SENDING -> "전송 중…"
    RemoteStatus.SAVED -> "저장됨"
    RemoteStatus.DELETE_PENDING -> "삭제 대기" + (s.error?.let { " ($it)" } ?: "")
    RemoteStatus.DELETING -> "삭제 중…"
    RemoteStatus.FAILED -> "실패: " + when (s.error) {
        "no_key" -> "봇 키 없음(봇이 이 기능을 지원하는지 확인)"
        "key_changed" -> "봇 키가 바뀜(확인 필요)"
        "send_failed" -> "전송 실패"
        "timeout" -> "응답 없음"
        "decrypt_failed" -> "봇이 복호화 실패"
        "invalid_item" -> "금고가 항목을 거부"
        "stale" -> "요청 시간 만료"
        else -> s.error ?: "알 수 없음"
    }
}

@Composable
internal fun CredentialsScreen(controller: CredentialsController, isDeviceSecure: () -> Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CredentialEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<CredentialEntry?>(null) }

    LaunchedEffect(Unit) { controller.refreshKeys() }
    LaunchedEffect(Unit) {
        while (true) {
            if (controller.hasInFlight || controller.entries.any { e -> e.remote.values.any { it.error == "timeout" } }) controller.poll()
            delay(3_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("자격증명 관리", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("닫기") }
        }
        Text(
            "봇이 대신 로그인할 때 쓸 계정입니다. 비밀번호는 이 기기에서 암호화해 저장하고(키 위치: ${controller.keyBackend}), " +
                "고른 봇만 풀 수 있게 봉인해 보냅니다. 채팅 기록·모델에는 남지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        controller.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }) { Text("추가") }
            OutlinedButton(enabled = !controller.busy, onClick = { scope.launch { controller.send() } }) {
                Text(if (controller.busy) "보내는 중…" else "대기 중인 것 모두 보내기")
            }
        }

        KeySection(controller)
        HorizontalDivider()

        val visible = controller.entries.filter { !it.deleted }
        if (visible.isEmpty()) Text("저장된 자격증명이 없습니다.", style = MaterialTheme.typography.bodyMedium)
        visible.forEach { e ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e.label, fontWeight = FontWeight.SemiBold)
                    Text(e.origin, style = MaterialTheme.typography.bodySmall)
                    Text("ID: ${e.identifier}", style = MaterialTheme.typography.bodyMedium)
                    Text("비밀번호: ${e.masked}", style = MaterialTheme.typography.bodyMedium)
                    e.bots.forEach { bot ->
                        Text("· $bot — ${statusText(e.remote[bot] ?: RemoteState())}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editing = e }) { Text("수정") }
                        TextButton(enabled = !controller.busy, onClick = { scope.launch { controller.send(onlyId = e.id) } }) { Text("서버로 보내기") }
                        TextButton(onClick = { confirmDelete = e }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        val deleting = controller.entries.filter { it.deleted }
        if (deleting.isNotEmpty()) {
            Text("봇 금고에서 삭제 처리 중", style = MaterialTheme.typography.titleSmall)
            deleting.forEach { e ->
                Text(
                    "${e.label} — " + e.remote.entries.joinToString { (bot, s) -> "$bot: ${statusText(s)}" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (adding || editing != null) {
        EditDialog(
            controller = controller,
            existing = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { origin, label, identifier, password, bots ->
                scope.launch {
                    if (controller.save(editing, origin, label, identifier, password, bots, isDeviceSecure())) {
                        adding = false
                        editing = null
                    }
                }
            },
        )
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("자격증명 삭제") },
            text = { Text("'${target.label}'을(를) 이 기기와 대상 봇 금고(${target.bots.joinToString()})에서 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { controller.delete(target) }
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun KeySection(controller: CredentialsController) {
    val keys = controller.keys
    Text("봇 키", style = MaterialTheme.typography.titleSmall)
    if (keys.isEmpty()) {
        Text("아직 키를 게시한 봇이 없습니다. 봇 게이트웨이에 금고 전송 기능이 켜져 있어야 합니다.", style = MaterialTheme.typography.bodySmall)
    }
    keys.values.sortedBy { it.bot }.forEach { k ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${k.bot} · 키 ${k.key.kid.take(8)} · ${k.key.sender}" + if (k.state == KeyState.CHANGED) " · 키가 바뀌었습니다" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (k.state == KeyState.CHANGED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (k.state == KeyState.CHANGED) {
                TextButton(onClick = { controller.trustNewKey(k.bot) }) { Text("새 키 신뢰") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(
    controller: CredentialsController,
    existing: CredentialEntry?,
    onDismiss: () -> Unit,
    onSave: (origin: String, label: String, identifier: String, password: String, bots: List<String>) -> Unit,
) {
    var origin by remember { mutableStateOf(existing?.origin.orEmpty()) }
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var identifier by remember { mutableStateOf(existing?.identifier.orEmpty()) }
    // Plain remember (not rememberSaveable): the password never goes into the saved-instance Bundle.
    var password by remember { mutableStateOf("") }
    var bots by remember { mutableStateOf(existing?.bots?.toSet() ?: setOf("commander")) }
    var extraBot by remember { mutableStateOf("") }
    val choices = controller.knownBots(existing?.bots.orEmpty() + bots)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "자격증명 추가" else "자격증명 수정") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = origin, onValueChange = { origin = it }, singleLine = true,
                    label = { Text("사이트 주소") }, placeholder = { Text("https://example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true, label = { Text("표시 이름(선택)") })
                OutlinedTextField(
                    value = identifier, onValueChange = { identifier = it }, singleLine = true, label = { Text("ID(이메일·아이디)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text(if (existing == null) "비밀번호" else "새 비밀번호(비우면 유지: ${existing.masked})") },
                    // PasswordVisualTransformation also disables copy and cut in Compose text fields.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                Text("대상 봇(여러 개 선택 가능)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { bot ->
                        FilterChip(
                            selected = bot in bots,
                            onClick = { bots = if (bot in bots) bots - bot else bots + bot },
                            label = { Text(bot) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = extraBot, onValueChange = { extraBot = it.trim() }, singleLine = true,
                        label = { Text("다른 봇 이름") }, modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = extraBot.matches(Regex("[a-z0-9_-]{1,32}")), onClick = {
                        bots = bots + extraBot
                        extraBot = ""
                    }) { Text("추가") }
                }
                if (existing != null && existing.bots.any { it !in bots }) {
                    Text("선택에서 뺀 봇의 금고 항목은 삭제됩니다.", style = MaterialTheme.typography.bodySmall)
                }
                controller.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(origin, label, identifier, password, bots.toList())
            }) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = {
                password = ""
                onDismiss()
            }) { Text("취소") }
        },
    )
}
