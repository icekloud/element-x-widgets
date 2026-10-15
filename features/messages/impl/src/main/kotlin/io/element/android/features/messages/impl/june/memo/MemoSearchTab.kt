/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package io.element.android.features.messages.impl.june.memo

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay
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
            // 앱11 C: "전체 선택" takes the results shown.
            LaunchedEffect(ui.results) { controller.visibleIds = ui.results.map { it.id } }
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.results, key = { it.id }) { item ->
                    MemoResultRow(
                        controller,
                        item,
                        onOpen = { controller.openOrSelect(item.id) },
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
private fun MemoResultRow(controller: MemoController, item: MemoItem, onOpen: () -> Unit, onTag: (String) -> Unit) {
    val accent = ElementTheme.colors.textActionAccent
    val selected = controller.select.isSelected(item.id)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) ElementTheme.colors.bgSubtleSecondary else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (controller.select.active) MemoSelectMark(selected)
        Text(
            memoKindIcon(item.kind) + " " + memoItemLine(item),
            style = ElementTheme.typography.fontBodyMdMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
        // 앱11 D: the note's photos, small (search results come from the same local list).
        (item.photos ?: item.preview as? MemoPreview.Photo)?.let { MemoCardPhotos(controller, it, Modifier.padding(vertical = 2.dp), size = 36) }
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
    val nav = controller.nav.value
    // 앱12 D: while the editor is open the note is never read again — a change coming from the server must not take
    // the place of what is being typed. Back in the reading screen every local change (the auto save included) shows.
    val localVersion by MemoLocalChanges.version.collectAsState()
    LaunchedEffect(id, nav.editing, localVersion) { if (!nav.editing) load() }
    val index by store.index.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 앱10 C / 앱12 A: `‹ #번호  제목` on the left, the dates right aligned beside it.
            val title = note?.let { memoNoteScreenHead(it) } ?: "메모"
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
                modifier = Modifier.weight(1f).clickable(onClick = goBack).testTag(MemoTags.NOTE_HEAD),
            )
            note?.let { MemoNoteDates(it) }
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
            nav.editing -> MemoNoteEditor(controller, n)
            // 앱12 C (관리자: "메모 화면에서 꾹 누르면 편집모드로"): a long press anywhere on the reading area starts the
            // editor. Over the body text itself the Android text selection takes the press first (결정기록 3).
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .memoLongPressEdit(controller)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .testTag(MemoTags.NOTE_READ)
            ) {
                Text(
                    "📁 ${n.folder}" + if (n.pinned) " · 📌" else "",
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary
                )
                MemoTagLine(n.tags.toImmutableList(), max = 20, modifier = Modifier.padding(vertical = 4.dp))
                // 📅 proposal linked to this note (앱9: grouped and locked like the proposal area)
                memoPanelGroups(index).filter { it.first.noteId == id }.forEach { g -> MemoProposalCard(controller, g) }
                MemoMarkdown(n.body)
                if (n.truncated) Text("(본문이 길어 일부만 받았습니다)", color = ElementTheme.colors.textSecondary)
                if (n.attachments.isNotEmpty()) {
                    FlowRow(Modifier.padding(vertical = 8.dp)) {
                        n.attachments.forEachIndexed { i, a ->
                            // 앱7 항목 2: the memo viewer, from the phone file or the server copy (no room timeline).
                            val open = Modifier.memoOpenPhoto(controller, n.attachments, i)
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

/**
 * The head of an open note: `#12  제목`; an untitled note without number shows "메모" as before. 앱12 A: the date is
 * no longer part of it — it is drawn right aligned, on its own two lines ([MemoNoteDates]).
 */
internal fun memoNoteScreenHead(n: MemoNote): String {
    val title = n.header.optString("제목").ifEmpty { if (n.number > 0) "" else "메모" }
    return memoNoteHead(n.number, title, "")
}

/**
 * 앱12 A (관리자: "날짜표기를 우측 정렬하고 폰트크기를 한단계 낮추고 진한회색으로"): `작성 …` and, when the user changed
 * the note later, `수정 …` under it. One step below the head text, in the grey halfway between the body and the
 * secondary colour of the theme (dark theme follows).
 */
@Composable
private fun MemoNoteDates(note: MemoNote) {
    val lines = memoNoteDateLines(note)
    if (lines.isEmpty()) return
    val color = lerp(ElementTheme.colors.textSecondary, ElementTheme.colors.textPrimary, MEMO_DATE_TONE)
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 6.dp).testTag(MemoTags.NOTE_DATE)) {
        lines.forEach { line ->
            Text(line, style = ElementTheme.typography.fontBodyMdRegular, color = color, maxLines = 1)
        }
    }
}

/** How far the date colour sits between the secondary and the primary text colour. */
private const val MEMO_DATE_TONE = 0.5f

/** 앱12 C: a long press on the reading area opens the editor (nothing on a short press, no ripple). */
@Composable
private fun Modifier.memoLongPressEdit(controller: MemoController): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return combinedClickable(
        interactionSource = interaction,
        indication = null,
        onLongClick = { memoReadingLongPress(controller.nav.value, controller.select.active)?.let { next -> controller.go { next } } },
        onClick = {},
    )
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

/**
 * 앱12 B·D·E: the note editor without a [저장] button. What is typed is kept outside the screen ([MemoEditHub]) and
 * written into the local note [MEMO_EDIT_SAVE_DELAY_MS] after the typing stops, and once more when the editor leaves
 * the screen (back, another tab, the app going to the background). [취소], bottom right, goes back to the state the
 * editor was opened with. The photos go with the last save only (결정기록 4).
 */
@Composable
private fun MemoNoteEditor(controller: MemoController, note: MemoNote) {
    val context = LocalContext.current.applicationContext
    val sessions by MemoEditHub.sessions.collectAsState()
    LaunchedEffect(note.id) { MemoEditHub.open(note.id, memoEditDraftOf(note)) }
    val state = sessions[note.id] ?: return
    val persist: suspend (MemoEditState, Boolean) -> MemoSaveResult = { current, finish ->
        // New picks are copied next to the database first, like the photos of a new note (앱8 order).
        val picked = if (finish) current.draft.photos.picked.map(Uri::parse) else emptyList()
        val added = memoCopyPictures(context, picked).map(::memoPhotoArg)
        val op = memoEditOp(note.id, current.saved, current.draft, added, includePhotos = finish)
        val saved = memoEditSaved(current.saved, current.draft, includePhotos = finish)
        when (val r = op?.let { controller.store.request(it) }) {
            null -> MemoSaveResult(saved)
            is MemoResult.Ok -> MemoSaveResult(saved)
            is MemoResult.Failed -> MemoSaveResult(null, r.reason)
        }
    }
    // The typing stopped for a moment: write the text. A new keystroke starts the wait again.
    LaunchedEffect(note.id, state.draft) {
        if (!state.textDirty) return@LaunchedEffect
        delay(MEMO_EDIT_SAVE_DELAY_MS)
        MemoEditHub.save(note.id) { current -> persist(current, false) }
    }
    // Leaving the screen: the last save, photos included. The screen's scope is gone by then, so the hub runs it.
    DisposableEffect(note.id) {
        onDispose { MemoEditHub.saveLater(note.id) { current -> persist(current, true) } }
    }
    val typed: (MemoEditDraft) -> Unit = { draft -> MemoEditHub.typed(note.id, draft) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        // 앱10 C: the number is the server's, shown and never edited here.
        Text(
            if (note.number > 0) "#${note.number}" else "번호 없음 · 동기화 때 서버가 매깁니다",
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = 4.dp).testTag(MemoTags.NOTE_NUMBER),
        )
        TextField(
            value = state.draft.title,
            onValueChange = { v -> typed(state.draft.copy(title = v)) },
            label = "제목",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = state.draft.tags,
            onValueChange = { v -> typed(state.draft.copy(tags = v)) },
            label = "태그",
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        TextField(
            value = state.draft.body,
            onValueChange = { v -> typed(state.draft.copy(body = v)) },
            label = "본문",
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        MemoEditorPhotos(state.draft.photos, onChange = { p -> typed(state.draft.copy(photos = p)) })
        val status = memoEditStatus(state)
        if (status.isNotEmpty()) {
            Text(
                status,
                style = ElementTheme.typography.fontBodyXsRegular,
                color = if (state.failure != null) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp).testTag(MemoTags.EDIT_STATUS),
            )
        }
        // 앱12 B (관리자: "취소 저장 버튼을 우측 하단에"): only 취소 is left, at the bottom right.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            Text(
                "취소",
                color = ElementTheme.colors.textActionAccent,
                modifier = Modifier.clickable { askMemoEditCancel(controller, state) }.padding(12.dp).testTag(MemoTags.EDIT_CANCEL),
            )
        }
    }
}

/**
 * [취소]: one question, then the note goes back to the state the editor was opened with. The auto save may already
 * have written part of it, so the undo is sent as a normal change (it syncs like any other); the photo marks are
 * simply dropped, nothing was written for them.
 */
private fun askMemoEditCancel(controller: MemoController, state: MemoEditState) {
    val leave = {
        MemoEditHub.drop(state.noteId)
        controller.go { it.stopEditing() }
    }
    if (!state.revertable) {
        leave()
        return
    }
    controller.sheet = MemoSheet.Confirm("편집한 내용을 되돌리고 나갈까요?", "되돌리기") {
        val op = memoEditCancelOp(state.noteId, state.saved, state.entry)
        leave()
        if (op != null) controller.run(op)
    }
}
