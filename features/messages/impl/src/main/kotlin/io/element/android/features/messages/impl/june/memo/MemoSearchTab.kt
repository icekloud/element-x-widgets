/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalLayoutApi::class)

package io.element.android.features.messages.impl.june.memo

import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.noties.markwon.Markwon
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

/** Search tab state, kept across tab switches (the query stays when coming back). */
private class MemoSearchUi {
    var query by mutableStateOf(MemoSearchQuery())
    var results by mutableStateOf<List<MemoItem>>(emptyList())
    var cursor by mutableStateOf<String?>(null)
    var searching by mutableStateOf(false)
    val recent = mutableStateListOf<MemoSearchQuery>()
}

private val searchUis = mutableMapOf<MemoStore, MemoSearchUi>()

@Composable
internal fun MemoSearchTab(controller: MemoController) {
    val ui = remember(controller.store) { searchUis.getOrPut(controller.store) { MemoSearchUi() } }
    val nav = controller.nav.value
    if (nav.tagManager) {
        MemoTagManager(controller)
        return
    }
    val tags by controller.store.tags.collectAsState()
    LaunchedEffect(controller.store) { if (tags.isEmpty()) controller.store.loadTags() }

    // [submitted]: the user sent a search (keyboard, 🔍, voice, a recent line or a tag of the empty state). Only those
    // go to the recent searches; toggling filter chips or removing a tag chip searches again without recording it.
    fun run(q: MemoSearchQuery, submitted: Boolean = false) {
        val normalized = q.normalized()
        ui.query = normalized
        if (normalized.isEmpty) {
            controller.go { it.showSearchResults(false) }
            return
        }
        controller.go { it.showSearchResults(true) }
        ui.searching = true
        controller.scope.launch {
            when (val r = controller.store.search(normalized)) {
                is MemoResult.Ok -> {
                    val page = (r.response as? MemoResponse.Search)?.page
                    ui.results = page?.items.orEmpty()
                    ui.cursor = page?.nextCursor
                    if (submitted) {
                        val next = memoRecentWith(ui.recent, normalized)
                        ui.recent.clear()
                        ui.recent.addAll(next)
                    }
                }
                is MemoResult.Failed -> controller.error = r.reason to { run(normalized, submitted) }
            }
            ui.searching = false
        }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) run(ui.query.copy(text = (ui.query.text + " " + said).trim()), submitted = true)
    }

    Column(Modifier.fillMaxSize().testTag(MemoTags.SEARCH)) {
        // Search box: text + tag chips inside + 🎤
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (ui.query.tags.isNotEmpty()) {
                    FlowRow { ui.query.tags.forEach { t -> MemoChip("#$t ✕", selected = true, onClick = { run(ui.query.withoutTag(t)) }) } }
                }
                TextField(
                    value = ui.query.text,
                    onValueChange = { v -> ui.query = ui.query.copy(text = v).normalized(keepTyping = true) },
                    placeholder = "검색 (#태그)",
                    singleLine = true,
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { run(ui.query, submitted = true) }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("🎤", modifier = Modifier.clickable {
                runCatchingExceptions {
                    voice.launch(
                        Intent(
                            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                        ).putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        ).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    )
                }
            }.padding(8.dp))
            Text("🔍", modifier = Modifier.clickable { run(ui.query, submitted = true) }.padding(8.dp))
        }
        // `#` autocomplete
        val prefix = memoTagPrefix(ui.query.text)
        if (prefix != null) {
            val suggestions = suggestMemoTags(tags, prefix, ui.query.tags)
            Column(Modifier.padding(horizontal = 8.dp)) {
                suggestions.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { ui.query = ui.query.applyTagSuggestion(s.name) }.padding(vertical = 6.dp, horizontal = 8.dp),
                    ) {
                        Text("#" + s.name, modifier = Modifier.weight(1f))
                        Text("${s.count}", color = ElementTheme.colors.textSecondary)
                    }
                }
                if (suggestions.isEmpty()) Text("일치하는 태그 없음", color = ElementTheme.colors.textSecondary, modifier = Modifier.padding(8.dp))
            }
        }
        // Filter chips: kinds 4 · folder ▾ · period ▾ · 📅 · 📌
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            MEMO_KINDS.forEach { k -> MemoChip(memoKindIcon(k) + " " + k, selected = k in ui.query.kinds, onClick = { run(ui.query.toggleKind(k)) }) }
            MemoChip("📁 " + (ui.query.folder ?: "폴더") + " ▾", selected = ui.query.folder != null, onClick = {
                if (ui.query.folder != null) {
                    run(ui.query.copy(folder = null))
                } else {
                    controller.sheet = MemoSheet.PickFolder("폴더로 좁히기") { f -> run(ui.query.copy(folder = f)) }
                }
            })
            MemoChip((ui.query.period?.label ?: "기간") + " ▾", selected = ui.query.period != null, onClick = {
                val next = when (ui.query.period) {
                    null -> MemoPeriod.Today
                    MemoPeriod.Today -> MemoPeriod.Days7
                    MemoPeriod.Days7 -> MemoPeriod.Days30
                    else -> null
                }
                run(ui.query.copy(period = next))
            })
            MemoChip("📅", selected = ui.query.schedule, onClick = { run(ui.query.copy(schedule = !ui.query.schedule)) })
            MemoChip("📌", selected = ui.query.pinned, onClick = { run(ui.query.copy(pinned = !ui.query.pinned)) })
        }
        if (!nav.searchResults) {
            MemoSearchEmpty(
                controller,
                memoRecentWith(ui.recent, MemoSearchQuery()).toImmutableList(),
                tags.toImmutableList(),
                onRun = { q -> run(q, submitted = true) },
            )
        } else {
            Text(
                ui.query.describe(),
                modifier = Modifier.padding(8.dp),
                style = ElementTheme.typography.fontBodySmMedium,
                color = ElementTheme.colors.textSecondary
            )
            if (ui.searching) Text("찾는 중…", modifier = Modifier.padding(8.dp), color = ElementTheme.colors.textSecondary)
            if (!ui.searching && ui.results.isEmpty()) {
                Column(Modifier.padding(12.dp)) {
                    Text("결과 없음", color = ElementTheme.colors.textSecondary)
                    MemoChip(
                        (if (ui.query.includeHidden) "✓ " else "") + "받은편지함·휴지통까지",
                        selected = ui.query.includeHidden,
                        onClick = { run(ui.query.copy(includeHidden = !ui.query.includeHidden)) },
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.results, key = { it.id }) { item ->
                    MemoResultRow(
                        item,
                        onOpen = { controller.go { it.openNote(item.id) } },
                        onTag = { t ->
                            run(ui.query.withTag(t), submitted = true)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoSearchEmpty(
    controller: MemoController,
    recent: ImmutableList<MemoSearchQuery>,
    tags: ImmutableList<MemoTag>,
    onRun: (MemoSearchQuery) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        if (recent.isNotEmpty()) {
            Text("최근 검색", style = ElementTheme.typography.fontBodySmMedium, color = ElementTheme.colors.textSecondary)
            recent.forEach { q -> Text(q.describe(), modifier = Modifier.fillMaxWidth().clickable { onRun(q) }.padding(vertical = 6.dp)) }
        }
        memoTagsByAxis(tags).forEach { (axis, list) ->
            Text(axis, style = ElementTheme.typography.fontBodySmMedium, color = ElementTheme.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
            FlowRow { list.take(12).forEach { t -> MemoChip("#${t.name} ${t.count}", onClick = { onRun(MemoSearchQuery(tags = listOf(t.name))) }) } }
        }
        Text(
            "🏷 태그 관리",
            color = ElementTheme.colors.textActionAccent,
            modifier = Modifier.padding(top = 12.dp).clickable { controller.go { it.openTagManager() } }.padding(8.dp)
        )
    }
}

@Composable
private fun MemoResultRow(item: MemoItem, onOpen: () -> Unit, onTag: (String) -> Unit) {
    val accent = ElementTheme.colors.textActionAccent
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(memoKindIcon(item.kind) + " " + item.title, style = ElementTheme.typography.fontBodyMdMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.snippet?.let { snippet ->
            val text = buildAnnotatedString {
                append(memoMatchPrefix(item.matchField))
                parseMemoSnippet(snippet).forEach { part ->
                    if (part.marked) withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(part.text) } else append(part.text)
                }
            }
            Text(
                text,
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📁 ${item.folder} · ${item.shortDate}  ", style = ElementTheme.typography.fontBodyXsRegular, color = ElementTheme.colors.textSecondary)
            MemoTagLine(item.tags.toImmutableList(), max = 4, onTag = onTag)
        }
    }
}

// ---------------------------------------------------------------- 태그 관리

@Composable
private fun MemoTagManager(controller: MemoController) {
    val tags by controller.store.tags.collectAsState()
    LaunchedEffect(tags.isEmpty()) { if (tags.isEmpty()) controller.store.loadTags() }
    val refresh: (MemoResponse) -> Unit = { controller.scope.launch { controller.store.loadTags() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 태그 관리", style = ElementTheme.typography.fontBodyLgMedium, modifier = Modifier.weight(1f).clickable {
                controller.go { s ->
                    (s.back() as? MemoBack.To)?.state
                ?: s
                }
            })
            Text("＋ 새 태그", color = ElementTheme.colors.textActionAccent, modifier = Modifier.clickable {
                controller.sheet = MemoSheet.AskText("새 태그 이름", "") { n -> controller.run(MemoOp.tagCreate(n.removePrefix("#"), null), refresh) }
            }.padding(8.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(tags, key = { it.name }) { t ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        controller.sheet = MemoSheet.Actions(
                            "#" + t.name,
                            listOf(
                                "이름 바꾸기" to
                                    {
                                        controller.sheet =
                                        MemoSheet.AskText("새 이름", t.name) { n -> controller.run(MemoOp.tagRename(t.name, n.removePrefix("#")), refresh) }
                                    },
                                "다른 태그로 합치기" to
                                    {
                                        controller.sheet =
                                        MemoSheet.AskText("합칠 태그 이름", "") { n -> controller.run(MemoOp.tagMerge(listOf(t.name), n.removePrefix("#")), refresh) }
                                    },
                                "삭제" to
                                    {
                                        controller.sheet =
                                        MemoSheet.Confirm("#${t.name} 태그를 모든 메모에서 뺄까요?", "삭제") { controller.run(MemoOp.tagDelete(t.name), refresh) }
                                    },
                            ),
                        )
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("#" + t.name, style = ElementTheme.typography.fontBodyMdMedium)
                        val meta = listOfNotNull(t.axis, t.source?.let { "출처 $it" }, t.synonyms.takeIf { it.isNotEmpty() }?.joinToString(prefix = "동의어 "))
                        if (meta.isNotEmpty()) {
                            Text(
                                meta.joinToString(" · "),
                                style = ElementTheme.typography.fontBodyXsRegular,
                                color = ElementTheme.colors.textSecondary
                            )
                        }
                    }
                    Text("${t.count}", color = ElementTheme.colors.textSecondary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 노트 뷰어·편집

@Composable
internal fun MemoNoteScreen(
    controller: MemoController,
    id: String,
    canOpenAttachment: (MemoAttachment) -> Boolean = { false },
    onAttachmentClick: (MemoAttachment) -> Unit = {},
) {
    val store = controller.store
    var note by remember(id) { mutableStateOf(store.cachedNote(id)) }
    var failed by remember(id) { mutableStateOf<String?>(null) }
    val load: () -> Unit = {
        failed = null
        controller.scope.launch {
            when (val r = store.loadNote(id)) {
                is MemoResult.Ok -> note = (r.response as? MemoResponse.Note)?.note
                is MemoResult.Failed -> failed = r.reason
            }
        }
    }
    LaunchedEffect(id) { if (note == null) load() }
    val nav = controller.nav.value
    val index by store.index.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val title = note?.header?.optString("제목")?.takeIf { it.isNotEmpty() } ?: "메모"
            val goBack = {
                when (val b = nav.back()) {
                    is MemoBack.To -> controller.go { b.state }
                    else -> Unit
                }
            }
            Text(
                "‹ $title",
                style = ElementTheme.typography.fontBodyLgMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(onClick = goBack),
            )
            if (note != null && !nav.editing) {
                Text(
                    "편집",
                    color = ElementTheme.colors.textActionAccent,
                    modifier = Modifier.clickable { controller.go { it.startEditing() } }.padding(8.dp)
                )
            }
        }
        val n = note
        when {
            failed != null -> Row(Modifier.padding(12.dp).testTag(MemoTags.ERROR)) {
                Text(failed.orEmpty(), color = ElementTheme.colors.textCriticalPrimary, modifier = Modifier.weight(1f))
                Text("다시", color = ElementTheme.colors.textActionAccent, modifier = Modifier.clickable(onClick = load).padding(horizontal = 8.dp))
            }
            n == null -> Text("불러오는 중…", modifier = Modifier.padding(12.dp), color = ElementTheme.colors.textSecondary)
            nav.editing -> MemoNoteEditor(controller, n, onSave = { saved -> note = saved })
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                Text(
                    "📁 ${n.folder} · ${n.created.take(10)}" + if (n.pinned) " · 📌" else "",
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary
                )
                MemoTagLine(n.tags.toImmutableList(), max = 20, modifier = Modifier.padding(vertical = 4.dp))
                // 📅 proposal linked to this note
                index.proposals.filter { it.noteId == id }.forEach { p -> MemoProposalCard(controller, p) }
                MemoMarkdown(n.body)
                if (n.truncated) Text("(본문이 길어 일부만 받았습니다)", color = ElementTheme.colors.textSecondary)
                if (n.attachments.isNotEmpty()) {
                    FlowRow(Modifier.padding(vertical = 8.dp)) {
                        n.attachments.forEach { a ->
                            // Opens the media viewer of the room when the photo is in the loaded room timeline.
                            val open = if (canOpenAttachment(a)) Modifier.clickable { onAttachmentClick(a) } else Modifier
                            MemoThumbnail(
                                a.mxc,
                                a.fileJson,
                                Modifier
                                    .padding(end = 6.dp, bottom = 6.dp)
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(open)
                                    .testTag(MemoTags.NOTE_ATTACHMENT),
                                a.localPath,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Markdown body, drawn with Markwon like the June file viewer (viewfolder). */
@Composable
private fun MemoMarkdown(body: String) {
    val color = ElementTheme.colors.textPrimary.toArgb()
    AndroidView(
        factory = { ctx -> TextView(ctx).apply { setTextIsSelectable(true) } },
        update = { tv ->
            tv.setTextColor(color)
            Markwon.create(tv.context).setMarkdown(tv, body)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MemoNoteEditor(controller: MemoController, note: MemoNote, onSave: (MemoNote) -> Unit) {
    val title0 = note.header.optString("제목")
    val tags0 = note.tags.joinToString(" ") { "#$it" }
    var title by remember(note.id) { mutableStateOf(title0) }
    var tags by remember(note.id) { mutableStateOf(tags0) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    val dirty = title != title0 || tags != tags0 || body != note.body
    LaunchedEffect(dirty) { controller.go { it.edited(dirty) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        TextField(value = title, onValueChange = { title = it }, label = "제목", singleLine = true, modifier = Modifier.fillMaxWidth())
        TextField(value = tags, onValueChange = { tags = it }, label = "태그", singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        TextField(value = body, onValueChange = { body = it }, label = "본문", minLines = 8, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("취소", modifier = Modifier.clickable { controller.go { it.stopEditing() } }.padding(12.dp))
            Text("저장", color = ElementTheme.colors.textActionAccent, modifier = Modifier.clickable {
                val newTags = memoParseTagInput(tags)
                controller.run(
                    MemoOp.edit(
                        MemoTarget(noteId = note.id),
                        body = body.takeIf { it != note.body },
                        title = title.takeIf { it != title0 },
                        tags = newTags.takeIf { tags != tags0 },
                    ),
                ) {
                    onSave(
                        note.copy(body = body, header = org.json.JSONObject(note.header.toString()).put("제목", title).put("태그", org.json.JSONArray(newTags)))
                    )
                    controller.go { it.stopEditing() }
                }
            }.padding(12.dp))
        }
    }
}
