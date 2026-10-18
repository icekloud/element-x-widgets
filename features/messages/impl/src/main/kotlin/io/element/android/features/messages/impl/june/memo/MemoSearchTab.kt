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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.theme.components.Icon
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
            val folderIndex by controller.store.index.collectAsState()
            val folderChip = ui.query.folder?.let { memoFolderLabel(folderIndex, it) } ?: "$MEMO_FOLDER_ICON_DEFAULT 폴더"
            MemoChip("$folderChip ▾", selected = ui.query.folder != null, onClick = {
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
                    // 앱17 A: a result pushed to the left asks for its review.
                    MemoReviewSwipe(controller, item.id, item.folder) { m ->
                        MemoResultRow(
                            controller,
                            item,
                            onOpen = { controller.openOrSelect(item.id) },
                            onTag = { t ->
                                run(ui.query.withTag(t), submitted = true)
                            },
                            modifier = m,
                        )
                    }
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
private fun MemoResultRow(controller: MemoController, item: MemoItem, onOpen: () -> Unit, onTag: (String) -> Unit, modifier: Modifier = Modifier) {
    val accent = ElementTheme.colors.textActionAccent
    val selected = controller.select.isSelected(item.id)
    Column(
        modifier
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
            val index by controller.store.index.collectAsState()
            Text(
                "${memoFolderLabel(index, item.folder)} · ${item.shortDate}  ",
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
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
        // 앱13 B (관리자: "위에 제목, 일자 나타내는 줄은 메모 편집 중엔 필요없으니 없애서 공간확보"): no head while editing.
        if (!nav.editing) MemoNoteHead(controller, note)
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
            // 앱15 G: the 편집 box floats at the bottom right over it, and the body ends above the box. This area already
            // ends above the tab bar and the composer, and the whole screen shrinks with the keyboard (imePadding up the
            // tree), so the box needs no inset padding of its own (결정기록 G).
            else -> Box(Modifier.fillMaxSize()) {
                MemoNoteReading(controller, n, index, id)
                val edit = memoReadingLongPress(nav, controller.select.active)
                // 앱21 ②: the `장소` of the header left of the 편집 box, in the same fixed line. Without one nothing
                // changes: the 편집 box alone, where and as big as before.
                val place = memoPlaceLine(n.header)
                if (place == null) {
                    if (edit != null) {
                        MemoEditBox(
                            onClick = { controller.go { edit } },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(MEMO_EDIT_BOX_MARGIN_DP.dp),
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .fillMaxWidth()
                            .padding(MEMO_EDIT_BOX_MARGIN_DP.dp),
                    ) {
                        MemoPlaceBox(
                            place,
                            onFailed = { controller.notice = MemoNotice(MEMO_PLACE_OPEN_FAILED) },
                            modifier = Modifier.weight(1f),
                        )
                        if (edit != null) {
                            Spacer(Modifier.width(MEMO_PLACE_BOX_GAP_DP.dp))
                            MemoEditBox(onClick = { controller.go { edit } })
                        }
                    }
                }
            }
        }
    }
}

/** The reading area of an open note (folder, tags, linked proposals, body, photos); it scrolls under the 편집 box. */
@Composable
private fun MemoNoteReading(
    controller: MemoController,
    n: MemoNote,
    index: MemoIndex,
    id: String,
) {
    Column(
        Modifier
            .fillMaxSize()
            .memoLongPressEdit(controller)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .testTag(MemoTags.NOTE_READ)
    ) {
        // 앱16 B (관리자: "메모고정 버튼이 따로 안보임"): the 📌 button at the right end of the folder line, for every note
        // (앱15 H showed it on pinned notes only): a light outline pin pins the note, the filled 📌 takes the pin off.
        // 앱17 C (관리자: "고정버튼 좌측에 검토받게하는 버튼"): the review button of the same size left of it.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                memoFolderLabel(index, n.folder),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (controller.reviewer != null) {
                memoReviewButton(n.trashed, controller.nav.value.editing, controller.select.active)?.let { button ->
                    // 앱21 ①: the directive sheet first; empty, it is the review of before.
                    MemoReviewToggle(button, onClick = { controller.askReviewNote(id) })
                }
            }
            memoPinButton(n.pinned, n.trashed, controller.nav.value.editing, controller.select.active)?.let { button ->
                MemoPinToggle(button, onClick = { controller.setPinWithUndo(id, pin = !button.pinned) })
            }
        }
        // 앱21 추가요청 4 (관리자: "노트 본문에서도 태그편집기능 동일하게 사용 가능하도록"): a press on the tag line opens
        // the tag sheet of 앱19 (the whole line, one press); a note without tags shows "# 태그 추가" there.
        when (val tags = memoReadingTags(n.tags, n.trashed, controller.select.active)) {
            null -> MemoTagLine(n.tags.toImmutableList(), max = 20, modifier = Modifier.padding(vertical = 4.dp))
            else -> FlowRow(
                Modifier
                    .padding(vertical = 4.dp)
                    .clickable(role = Role.Button) { controller.editTagsOf(MemoTarget(noteId = id), n.tags) }
                    .testTag(MemoTags.NOTE_TAGS),
            ) {
                tags.forEach { MemoChip(it) }
            }
        }
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
        // 앱15 G: room for the 편집 box, so the last line is never under it.
        Spacer(Modifier.height(MEMO_EDIT_BOX_ROOM_DP.dp).testTag(MemoTags.NOTE_EDIT_ROOM))
    }
}

/**
 * 앱16 B: the 📌 button of the open note, [MEMO_PIN_TOGGLE_DP] square to be easy to press. Pinned: the 📌 as before;
 * not pinned: the outline pin in a light colour. Disabled (selection mode): drawn dimmed, a press does nothing.
 */
@Composable
private fun MemoPinToggle(button: MemoPinButton, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MEMO_PIN_TOGGLE_DP.dp)
            .clickable(enabled = button.enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = button.description }
            .testTag(MemoA11Tags.PIN_TOGGLE),
    ) {
        val dim = if (button.enabled) 1f else MEMO_PIN_TOGGLE_DISABLED_ALPHA
        if (button.pinned) {
            Text("📌", style = ElementTheme.typography.fontBodyMdRegular, modifier = Modifier.alpha(dim))
        } else {
            Icon(
                imageVector = CompoundIcons.Pin(),
                contentDescription = null,
                tint = ElementTheme.colors.iconTertiary,
                modifier = Modifier.size(MEMO_PIN_ICON_DP.dp).alpha(dim),
            )
        }
    }
}

/** 앱16 B: the touch area of the 📌 button (at least 48dp) and the size of its outline pin. */
internal const val MEMO_PIN_TOGGLE_DP = 48
internal const val MEMO_PIN_ICON_DP = 18
private const val MEMO_PIN_TOGGLE_DISABLED_ALPHA = 0.4f

/**
 * The head of an open note: `#12  제목`; an untitled note without number shows "메모" as before. 앱12 A: the date is
 * no longer part of it — it is drawn right aligned, on its own two lines ([MemoDateLines]).
 */
internal fun memoNoteScreenHead(n: MemoNote): String {
    val title = n.header.optString("제목").ifEmpty { if (n.number > 0) "" else "메모" }
    return memoNoteHead(n.number, title, "")
}

/**
 * The head of the reading screen: `‹ #번호  제목` on the left (앱10 C), the dates right aligned beside it (앱12 A) and
 * `편집`. 앱13 A (관리자: "일자 폰트가 너무 큼", "제목이 잘림"): the dates are half as big, so the title gets the room and
 * wraps onto a second line instead of being cut.
 */
@Composable
private fun MemoNoteHead(controller: MemoController, note: MemoNote?) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val title = note?.let { memoNoteScreenHead(it) } ?: "메모"
        val goBack = {
            when (val b = controller.nav.value.back()) {
                is MemoBack.To -> controller.go { b.state }
                else -> Unit
            }
        }
        // 앱15 E (관리자: "제목 폰트를 지금의 3/4정도로 줄이고 날짜영역 침범하지 않는 선까지 공간활용"): the title takes all the
        // room up to the dates (the dates only their own width); 앱15 G: the 편집 button left the head for the box below.
        Text(
            "‹ $title",
            style = memoNoteHeadTitleStyle(ElementTheme.typography.fontBodyLgMedium),
            maxLines = MEMO_HEAD_TITLE_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = goBack).testTag(MemoTags.NOTE_HEAD),
        )
        if (note != null) {
            val sending by MemoSendingNotes.ids.collectAsState()
            MemoDateArea(
                lines = memoNoteDateLines(note).toImmutableList(),
                mark = memoSyncMark(note.syncWaiting, note.id in sending),
                modifier = Modifier.padding(start = 6.dp).testTag(MemoTags.NOTE_DATE),
            )
        }
    }
}

/** 앱13 A: a long title wraps onto this many lines (the two date lines beside it are about as high). */
internal const val MEMO_HEAD_TITLE_LINES = 2

/** 앱15 E: the title of the open note is this much of the 앱14 size (the record card keeps its own size, 추가요청 6). */
internal const val MEMO_NOTE_HEAD_TITLE_SCALE = 0.75f

/** 앱15 E: [base] (the 앱14 head style) at [MEMO_NOTE_HEAD_TITLE_SCALE], the line height with it. */
internal fun memoNoteHeadTitleStyle(base: TextStyle): TextStyle = base.copy(
    fontSize = base.fontSize * MEMO_NOTE_HEAD_TITLE_SCALE,
    lineHeight = base.lineHeight * MEMO_NOTE_HEAD_TITLE_SCALE,
)

/**
 * 앱15 F: the dates of a note with the small sync mark on their left (no text, only its content description). The
 * open note and the record card both draw it. No mark when [mark] is null (not a note of the phone database).
 */
@Composable
internal fun MemoDateArea(
    lines: ImmutableList<String>,
    mark: MemoSyncMark?,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (mark != null) MemoSyncMarkIcon(mark, Modifier.padding(end = 4.dp))
        MemoDateLines(lines)
    }
}

/**
 * 앱15 F: 대기 = a clock, 동기화 중 = a turning spinner (it turns only while it is drawn, and a push takes a moment),
 * 동기화됨 = a light check. The size of one date line.
 */
@Composable
private fun MemoSyncMarkIcon(mark: MemoSyncMark, modifier: Modifier = Modifier) {
    val size = Modifier.size(MEMO_SYNC_MARK_DP.dp)
    val tagged = modifier.then(size).testTag(memoSyncMarkTag(mark))
    when (mark) {
        MemoSyncMark.Waiting -> Icon(
            imageVector = CompoundIcons.Time(),
            contentDescription = mark.description,
            tint = ElementTheme.colors.iconSecondary,
            modifier = tagged,
        )
        MemoSyncMark.Sending -> {
            val turn = rememberInfiniteTransition(label = "memoSyncTurn")
            val angle by turn.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(durationMillis = MEMO_SYNC_TURN_MS, easing = LinearEasing)),
                label = "memoSyncAngle",
            )
            Icon(
                imageVector = CompoundIcons.Spinner(),
                contentDescription = mark.description,
                tint = ElementTheme.colors.iconAccentPrimary,
                modifier = tagged.rotate(angle),
            )
        }
        MemoSyncMark.Synced -> Icon(
            imageVector = CompoundIcons.Check(),
            contentDescription = mark.description,
            tint = ElementTheme.colors.iconQuaternary,
            modifier = tagged,
        )
    }
}

/** 앱15 F: the test tag of each mark (the screen shows no text for it). */
internal fun memoSyncMarkTag(mark: MemoSyncMark): String = "${MemoTags.SYNC_MARK}_${mark.name}"

/** 앱15 F: the mark is as high as one date line, a little more to be seen. */
internal const val MEMO_SYNC_MARK_DP = 11
private const val MEMO_SYNC_TURN_MS = 1_000

/**
 * 앱15 G (관리자: "우측상단 편집 아이콘 우측 하단에 고정시키고 박스형태의 버튼으로"): the 편집 box at the bottom right of the
 * open note, over the reading area (it stays while the body scrolls). Rounded corners, the accent colour, 48 dp high.
 */
@Composable
private fun MemoEditBox(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = MEMO_EDIT_BOX_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(MEMO_EDIT_BOX_CORNER_DP.dp))
            .background(ElementTheme.colors.bgActionPrimaryRest)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag(MemoTags.NOTE_EDIT),
    ) {
        Icon(
            imageVector = CompoundIcons.Edit(),
            contentDescription = null,
            tint = ElementTheme.colors.iconOnSolidPrimary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "편집",
            color = ElementTheme.colors.textOnSolidPrimary,
            style = ElementTheme.typography.fontBodyMdMedium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** 앱15 G: the box is at least this high (a comfortable touch) and this far from the edges; the body ends above it. */
internal const val MEMO_EDIT_BOX_HEIGHT_DP = 48
internal const val MEMO_EDIT_BOX_MARGIN_DP = 16
internal const val MEMO_EDIT_BOX_ROOM_DP = MEMO_EDIT_BOX_HEIGHT_DP + MEMO_EDIT_BOX_MARGIN_DP * 2
private const val MEMO_EDIT_BOX_CORNER_DP = 12

/**
 * 앱12 A / 앱13 A: `작성 …` and, when the user changed the note later, `수정 …` under it, right aligned, in the grey
 * halfway between the body and the secondary colour of the theme (dark theme follows). The open note and the record
 * card both draw them here, from the same lines ([memoNoteDateLines]). Half of the 앱12 size, never below
 * [MEMO_DATE_TEXT_SP] (결정기록 A).
 */
@Composable
internal fun MemoDateLines(lines: ImmutableList<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val color = lerp(ElementTheme.colors.textSecondary, ElementTheme.colors.textPrimary, MEMO_DATE_TONE)
    val style = ElementTheme.typography.fontBodyXsRegular.copy(fontSize = MEMO_DATE_TEXT_SP.sp, lineHeight = MEMO_DATE_LINE_SP.sp)
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        lines.forEach { line ->
            Text(line, style = style, color = color, maxLines = 1)
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
        // 앱13 D: a save that wrote the photo marks says so, and the hub takes them out of the draft.
        when (val r = op?.let { controller.store.request(it) }) {
            is MemoResult.Failed -> MemoSaveResult(null, r.reason)
            else -> MemoSaveResult(saved, photosWritten = finish)
        }
    }
    // The typing stopped for a moment: write the text. A new keystroke starts the wait again.
    LaunchedEffect(note.id, state.draft) {
        if (!state.textDirty) return@LaunchedEffect
        delay(MEMO_EDIT_SAVE_DELAY_MS)
        MemoEditHub.save(note.id) { current -> persist(current, false) }
    }
    // Leaving the screen: the last save, photos included. The screen's scope is gone by then, so the hub runs it.
    // The app going to the background keeps the composition, so the text is written on ON_STOP too (the photo marks
    // wait for the end of the editing, 결정기록 4).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, note.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                MemoEditHub.saveLater(note.id, dropWhenDone = false) { current -> persist(current, false) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            MemoEditHub.saveLater(note.id) { current -> persist(current, true) }
        }
    }
    val title = rememberMemoField(note.id, state.draft.title) { d, v -> d.copy(title = v) }
    val tags = rememberMemoField(note.id, state.draft.tags) { d, v -> d.copy(tags = v) }
    val body = rememberMemoField(note.id, state.draft.body) { d, v -> d.copy(body = v) }
    // 앱13 B·C (관리자: "수정영역이 너무 작음", 삼성 노트처럼): no head, no number line, no tab bar and no room composer while
    // editing (MemoScreen, MessagesView); title and tags are one small line each, the body takes every pixel that is left
    // and scrolls inside itself. The screen shrinks with the keyboard (imePadding up the tree), and the body field
    // brings the line with the cursor back into sight when its own height changes (TextFieldState, 결정기록 C).
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
        MemoEditField(title, "제목", Modifier.fillMaxWidth().testTag(MemoTags.EDIT_TITLE))
        MemoEditField(tags, "태그 (#교토 #빵집)", Modifier.fillMaxWidth().padding(top = 4.dp).testTag(MemoTags.EDIT_TAGS))
        MemoEditField(
            body,
            "본문",
            Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp).testTag(MemoTags.EDIT_BODY),
            singleLine = false,
        )
        // The photos: one row under the body that scrolls sideways, so many photos never push the body away.
        MemoEditorPhotos(
            state.draft.photos,
            onChange = { p -> MemoEditHub.change(note.id) { it.copy(photos = p) } },
            modifier = Modifier.padding(top = 4.dp),
        )
        // 앱12 B: 취소 at the bottom right; the auto save status and the photo count share its line.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                memoEditStatus(state),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = if (state.failure != null) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(MemoTags.EDIT_STATUS),
            )
            Text(
                "사진 ${state.draft.photos.count}장",
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
            Text(
                "취소",
                color = ElementTheme.colors.textActionAccent,
                modifier = Modifier
                    .clickable { askMemoEditCancel(controller, state) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(MemoTags.EDIT_CANCEL),
            )
        }
    }
}

/**
 * One field of the editor as a [TextFieldState] (앱13 C): the field keeps its own text, cursor and scroll, and every
 * change goes to [MemoEditHub] at once ([MemoEditHub.change]), so the auto save and the leave save see it.
 */
@Composable
private fun rememberMemoField(noteId: String, initial: String, update: (MemoEditDraft, String) -> MemoEditDraft): TextFieldState {
    val field = remember(noteId) { TextFieldState(initial) }
    val currentUpdate by rememberUpdatedState(update)
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { v -> MemoEditHub.change(noteId) { currentUpdate(it, v) } }
    }
    return field
}

/**
 * 앱13 B (관리자: "제목,태그 입력란의 높이를 절반정도로 줄이고 폰트도 그에 맞도록"): a field without the label line of the
 * design system field and with less padding. The hint sits inside it; [singleLine] false is the body (scrolls inside).
 */
@Composable
private fun MemoEditField(field: TextFieldState, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    val style = if (singleLine) {
        ElementTheme.typography.fontBodyMdRegular.copy(fontSize = MEMO_EDIT_FIELD_TEXT_SP.sp, lineHeight = MEMO_EDIT_FIELD_LINE_SP.sp)
    } else {
        ElementTheme.typography.fontBodyLgRegular
    }
    BasicTextField(
        state = field,
        modifier = modifier,
        textStyle = style.copy(color = ElementTheme.colors.textPrimary),
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(),
        cursorBrush = SolidColor(ElementTheme.colors.textPrimary),
        decorator = { inner ->
            // 앱14 D: a one line field has a set height ([memoEditFieldHeight]) with its line in the middle.
            val fontScale = LocalDensity.current.fontScale
            val size = if (singleLine) {
                Modifier.fillMaxWidth().height(memoEditFieldHeight(fontScale).dp).padding(horizontal = 8.dp)
            } else {
                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = MEMO_EDIT_FIELD_PAD_DP.dp)
            }
            Box(
                Modifier
                    .border(1.dp, ElementTheme.colors.borderInteractiveSecondary, RoundedCornerShape(4.dp))
                    .then(size),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (field.text.isEmpty()) Text(hint, style = style, color = ElementTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
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
