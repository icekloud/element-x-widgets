/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package io.element.android.features.messages.impl.june.memo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun MemoFoldersTab(controller: MemoController) {
    val folder = controller.nav.value.folder
    if (folder == null) MemoFolderRoot(controller) else MemoFolderView(controller, folder)
}

// ---------------------------------------------------------------- 루트

@Composable
private fun MemoFolderRoot(controller: MemoController) {
    val index by controller.store.index.collectAsState()
    val cells = remember(index) { memoRootGrid(index) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().testTag(MemoTags.FOLDER_ROOT),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 앱9 A: no proposal cards here any more: they are in the proposal area (list icon of the top bar).
        items(cells, key = { c -> if (c is MemoGridCell.Folder) "f-" + c.folder.path else "new" }) { cell ->
            when (cell) {
                is MemoGridCell.Folder -> MemoFolderCell(
                    icon = cell.folder.icon,
                    name = cell.folder.name,
                    count = cell.shownCount,
                    secondary = cell.secondary,
                    dimmed = cell.dimmed,
                    highlighted = cell.folder.path == MEMO_INBOX && cell.folder.unfiledCount > 0,
                    onClick = { controller.go { it.openFolder(cell.folder.path) } },
                )
                MemoGridCell.NewFolder -> MemoFolderCell(
                    icon = "＋",
                    name = "새 폴더",
                    count = null,
                    secondary = null,
                    dimmed = false,
                    highlighted = false,
                    onClick = { controller.sheet = MemoSheet.AskText("새 폴더 이름", "") { name -> controller.run(MemoOp.mkdir(name, null)) } },
                )
            }
        }
    }
}

@Composable
private fun MemoFolderCell(
    icon: String,
    name: String,
    count: Int?,
    secondary: String?,
    dimmed: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.5f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (highlighted) ElementTheme.colors.textActionAccent else ElementTheme.colors.borderInteractiveSecondary, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, style = ElementTheme.typography.fontHeadingMdRegular)
        Text(name, style = ElementTheme.typography.fontBodyMdMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("$count", style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
        if (secondary != null) Text(secondary, style = ElementTheme.typography.fontBodyXsRegular, color = ElementTheme.colors.textSecondary)
    }
}

/** One proposal card (앱9: the proposal area, the note view). */
@Composable
internal fun MemoProposalCard(controller: MemoController, p: MemoProposal) = MemoProposalCard(controller, MemoProposalGroup.of(p))

/**
 * A card of the proposal area: schedule (accept / ignore / other day), folder creation (create / rename / existing /
 * ignore) or the others (accept / ignore). 앱9 C: a press locks the card at once (no second answer, no empty taps) and
 * answers every proposal id of the card once ([memoGroupOps]); it stays "처리 중" until the server confirmed it.
 */
@Composable
internal fun MemoProposalCard(controller: MemoController, group: MemoProposalGroup) {
    val p = group.first
    val locked = group.isSending || group.ids.any { it in controller.answering }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MemoTags.BAND)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, ElementTheme.colors.textActionAccent, RoundedCornerShape(8.dp))
            .alpha(if (locked) 0.6f else 1f)
            .padding(8.dp),
    ) {
        Text(memoProposalLine(p), style = ElementTheme.typography.fontBodySmMedium, color = ElementTheme.colors.textActionAccent)
        if (group.proposals.size > 1) {
            Text("같은 내용 ${group.proposals.size}건", style = ElementTheme.typography.fontBodyXsRegular, color = ElementTheme.colors.textSecondary)
        }
        when {
            locked -> {
                Text(
                    "처리 중 · 메모봇에 전하는 중",
                    modifier = Modifier.testTag(MemoTags.PROPOSAL_SENDING),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                )
                return@Column
            }
            !p.isWaiting -> {
                Text("플랜봇 재검토 중", style = ElementTheme.typography.fontBodyXsRegular, color = ElementTheme.colors.textSecondary)
                return@Column
            }
        }
        FlowRow(modifier = Modifier.padding(top = 4.dp)) {
            if (p.isSchedule) {
                MemoChip("수락", accent = true, onClick = { controller.answer(group, MemoAnswer.Accept) })
                MemoChip("거절", onClick = { controller.answer(group, MemoAnswer.Dismiss) })
                MemoChip("다른 날", onClick = {
                    // The answer is about the suggestion (규약 v2 제안응답 id), not about its note.
                    controller.sheet = MemoSheet.PickDate { date -> controller.answer(group, MemoAnswer.Defer, date = date) }
                })
            } else if (p.isFolderCreate) {
                MemoChip("만들기", accent = true, onClick = { controller.answer(group, MemoAnswer.Accept) })
                MemoChip("이름 바꾸기", onClick = {
                    controller.sheet = MemoSheet.AskText("폴더 이름", p.folder.orEmpty()) { name ->
                        controller.answer(group, MemoAnswer.Accept, folder = name)
                    }
                })
                MemoChip("기존 폴더로", onClick = {
                    controller.sheet = MemoSheet.PickFolder("기존 폴더로") { folder -> controller.answer(group, MemoAnswer.Accept, folder = folder) }
                })
                MemoChip("거절", onClick = { controller.answer(group, MemoAnswer.Dismiss) })
            } else {
                MemoChip("수락", accent = true, onClick = { controller.answer(group, MemoAnswer.Accept) })
                MemoChip("거절", onClick = { controller.answer(group, MemoAnswer.Dismiss) })
            }
            MemoChip("나중에", onClick = { controller.answer(group, MemoAnswer.Defer) })
        }
    }
}

// ---------------------------------------------------------------- 폴더 내부

@Composable
private fun MemoFolderView(controller: MemoController, path: String) {
    val store = controller.store
    val index by store.index.collectAsState()
    val lists by store.lists.collectAsState()
    val tags = controller.folderTags[path].orEmpty()
    val key = MemoListKey.of(path, tags)
    val list = lists[key] ?: MemoListCache()
    var asList by remember(path) { mutableStateOf(false) }
    var pinsFolded by remember(path) { mutableStateOf(false) }

    LaunchedEffect(key) { store.loadFirst(key) }

    val gridState = rememberLazyGridState(list.scrollIndex, list.scrollOffset)
    // Scroll position kept in the store: going back to this folder restores it without asking again (요구 E).
    DisposableEffect(key) {
        onDispose { store.saveScroll(key, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
    }
    LaunchedEffect(key, gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                val s = store.list(key)
                if (memoShouldLoadMore(s, last - headerCount(s, index, path, tags))) store.loadMore(key)
            }
    }

    val folder = index.folder(path)
    val subfolders = memoSubfolders(index, path)
    val folderTags =
        remember(list.items) { list.items.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(20) }
    val pinned = list.items.filter { it.pinned }
    val newCount = store.newCount(key)

    Column(Modifier.fillMaxSize()) {
        // ‹ name · ⊞/≡ · ⋮
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ " + (folder?.name ?: path.substringAfterLast(
                '/'
            )), style = ElementTheme.typography.fontBodyLgMedium, modifier = Modifier.weight(1f).clickable {
                controller.go { s ->
                    (s.back() as? MemoBack.To)?.state
                ?: s
                }
            })
            Text(if (asList) "⊞" else "≡", modifier = Modifier.clickable { asList = !asList }.padding(8.dp))
            Text(
                "⋮",
                modifier = Modifier.clickable {
                    controller.sheet = MemoSheet.Actions(
                        path,
                        listOf(
                            "하위 폴더 만들기" to { controller.sheet = MemoSheet.AskText("하위 폴더 이름", "") { n -> controller.run(MemoOp.mkdir("$path/$n", null)) } },
                            "이름 바꾸기" to
                                { controller.sheet = MemoSheet.AskText("새 이름", folder?.name.orEmpty()) { n -> controller.run(MemoOp.renameDir(path, n)) } },
                            "폴더 지우기 (비어 있을 때)" to {
                                controller.sheet = MemoSheet.Confirm("'$path' 폴더를 지울까요? 비어 있어야 지워집니다.", "지우기") {
                                    controller.run(MemoOp.rmdir(path)) { controller.go { s -> (s.back() as? MemoBack.To)?.state ?: s } }
                                }
                            },
                        ),
                    )
                }.padding(8.dp),
            )
        }
        if (newCount > 0) {
            Text(
                "새 메모 $newCount ↑",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ElementTheme.colors.bgActionPrimaryRest)
                    .clickable {
                        controller.scope.launch {
                        store.reloadTop(key)
                        gridState.scrollToItem(0)
                    }
                    }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                color = ElementTheme.colors.textOnSolidPrimary,
                style = ElementTheme.typography.fontBodySmMedium,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (asList) 1 else 2),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (subfolders.isNotEmpty()) {
                item(key = "subs", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        subfolders.forEach { sf -> MemoChip("${sf.icon} ${sf.name} ${sf.total}", onClick = { controller.go { it.openFolder(sf.path) } }) }
                    }
                }
            }
            if (folderTags.isNotEmpty() || tags.isNotEmpty()) {
                item(key = "tags", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        if (tags.isNotEmpty()) MemoChip("✕", onClick = { controller.folderTags[path] = emptyList() })
                        (tags + folderTags.filter { it !in tags }).forEach { t ->
                            val on = t in tags
                            MemoChip("#$t", selected = on, onClick = { controller.folderTags[path] = if (on) tags - t else tags + t })
                        }
                    }
                }
            }
            if (pinned.isNotEmpty()) {
                item(key = "pins", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ElementTheme.colors.bgSubtleSecondary)
                            .clickable { pinsFolded = !pinsFolded }
                            .padding(8.dp),
                    ) {
                        Text("📌 고정 ${pinned.size}" + if (pinsFolded) " ▸" else " ▾", style = ElementTheme.typography.fontBodySmMedium)
                        if (!pinsFolded) {
                            pinned.take(
                                3
                            ).forEach { item ->
                                Text(
                                "· " + item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { controller.go { it.openNote(item.id) } }
                            )
                            }
                            if (pinned.size > 3) Text("+${pinned.size - 3}", color = ElementTheme.colors.textSecondary)
                        }
                    }
                }
            }
            items(list.items, key = { "n-" + it.id }) { item ->
                if (asList) MemoListRow(controller, item) else MemoTile(controller, item)
            }
            if (list.loading) {
                item(
                    key = "loading",
                    span = { GridItemSpan(maxLineSpan) }
                ) { Text("불러오는 중…", color = ElementTheme.colors.textSecondary, modifier = Modifier.padding(12.dp)) }
            }
            list.error?.let { e ->
                item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.testTag(MemoTags.ERROR).padding(12.dp)) {
                        Text(e, color = ElementTheme.colors.textCriticalPrimary, modifier = Modifier.weight(1f))
                        Text(
                            "다시",
                            color = ElementTheme.colors.textActionAccent,
                            modifier = Modifier.clickable { controller.scope.launch { store.retry(key) } }.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
            if (list.loaded && list.items.isEmpty() && list.error == null) {
                item(
                    key = "empty",
                    span = { GridItemSpan(maxLineSpan) }
                ) { Text("메모가 없습니다", color = ElementTheme.colors.textSecondary, modifier = Modifier.padding(12.dp)) }
            }
        }
    }
}

/** Number of grid items drawn before the tiles (so the "5 before the end" check counts tiles only). */
private fun headerCount(s: MemoListCache, index: MemoIndex, path: String, tags: List<String>): Int {
    var n = 0
    if (memoSubfolders(index, path).isNotEmpty()) n++
    if (tags.isNotEmpty() || s.items.any { it.tags.isNotEmpty() }) n++
    if (s.items.any { it.pinned }) n++
    return n
}

internal fun memoKindIcon(kind: String): String = when (kind) {
    "사진" -> "📷"
    "링크" -> "🔗"
    "음성" -> "🎤"
    else -> "📝"
}

internal fun memoItemActions(controller: MemoController, item: MemoItem): MemoSheet.Actions {
    val t = MemoTarget(noteId = item.id)
    return MemoSheet.Actions(
        item.title,
        listOf(
            (if (item.pinned) "고정 해제" else "📌 고정") to { controller.run(if (item.pinned) MemoOp.unpin(t) else MemoOp.pin(t)) },
            "📁 폴더 이동" to { controller.pickFolderThenMove(t) },
            "# 태그 편집" to { controller.editTagsOf(t, item.tags) },
            "🗑 휴지통" to { controller.run(MemoOp.trash(t)) },
        ),
    )
}

/** A tile in three rows (요구 B2): preview / title · date · kind / tags (max 4, +N). */
@Composable
internal fun MemoTile(controller: MemoController, item: MemoItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .testTag(MemoTags.TILE)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, ElementTheme.colors.borderInteractiveSecondary, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { controller.go { it.openNote(item.id) } },
                onLongClick = { controller.sheet = memoItemActions(controller, item) },
            ),
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp).testTag(MemoTags.TILE_PREVIEW)) {
            when (val p = item.preview) {
                is MemoPreview.Photo -> {
                    MemoThumbnail(p.mxc, p.fileJson, Modifier.fillMaxSize().memoOpenPhoto(controller, p.cells, 0), p.cells.first().localPath)
                    if (p.count > 1) MemoCorner("${p.count}장", Modifier.align(Alignment.TopEnd))
                }
                is MemoPreview.Link -> if (p.image != null) {
                    MemoThumbnail(p.image, null, Modifier.fillMaxSize())
                } else {
                    Text(
                        p.site ?: p.url,
                        modifier = Modifier.align(Alignment.Center).padding(6.dp),
                        color = ElementTheme.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                is MemoPreview.Text -> Text(
                    p.text.take(90),
                    modifier = Modifier.padding(6.dp),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                    overflow = TextOverflow.Ellipsis
                )
                null -> Unit
            }
            val corner = listOfNotNull(if (item.hasSchedule) "📅" else null, if (item.pinned) {
                "📌"
            } else if (item.pinSuggested) {
                "📌?"
            } else {
                null
            })
            if (corner.isNotEmpty()) MemoCorner(corner.joinToString(" "), Modifier.align(Alignment.TopStart))
            if (item.proposals > 0) {
                // A5 (설계 §d-③): the bot review only proposes; the chip opens them, nothing changes until [적용].
                MemoCorner(
                    "🤖 제안 ${item.proposals}",
                    Modifier.align(Alignment.BottomEnd).clickable { controller.sheet = memoProposalSheet(controller, item.id) },
                )
            }
        }
        // 앱7 항목 1: an untitled text note is its body in the preview box above, so no second title line.
        if (!(item.untitled && item.preview is MemoPreview.Text)) {
            Text(
                memoNumbered(item.number, item.title),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).testTag(MemoTags.TILE_TITLE),
                style = ElementTheme.typography.fontBodySmMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "${item.shortDate} · ${memoKindIcon(item.kind)} ${item.kind}",
            modifier = Modifier.padding(horizontal = 6.dp),
            style = ElementTheme.typography.fontBodyXsRegular,
            color = ElementTheme.colors.textSecondary
        )
        MemoTagLine(item.tags.toImmutableList(), max = 4, modifier = Modifier.padding(6.dp).testTag(MemoTags.TILE_TAGS))
    }
}

/** The suggestions of one note as a bottom sheet: [적용] / [아니오] each, and [모두 적용] (A5, 설계 §d-③). */
internal fun memoProposalSheet(controller: MemoController, noteId: String): MemoSheet {
    // 앱9 C: the same cards as the proposal area (copies grouped, one answer per id, locked while sent).
    val groups = memoPanelGroups(controller.store.index.value).filter { it.first.noteId == noteId && it.isWaiting }
    val actions = groups.flatMap { g ->
        val label = memoProposalLine(g.first)
        listOf<Pair<String, () -> Unit>>(
            "적용 · $label" to { controller.answer(g, MemoAnswer.Accept) },
            "아니오 · $label" to { controller.answer(g, MemoAnswer.Dismiss) },
        )
    } + if (groups.size > 1) {
        listOf<Pair<String, () -> Unit>>("모두 적용 (${groups.size})" to { groups.forEach { controller.answer(it, MemoAnswer.Accept) } })
    } else {
        emptyList()
    }
    return MemoSheet.Actions("🤖 ${groups.size}건", actions)
}

@Composable
private fun MemoCorner(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ElementTheme.colors.bgCanvasDefault)
            .padding(horizontal = 4.dp),
        style = ElementTheme.typography.fontBodyXsRegular,
    )
}

@Composable
internal fun MemoTagLine(tags: ImmutableList<String>, max: Int, modifier: Modifier = Modifier, onTag: ((String) -> Unit)? = null) {
    val shown = memoVisibleTags(tags, max)
    Row(modifier) {
        shown.first.forEach { t -> MemoChip("#$t", onClick = onTag?.let { { it(t) } }) }
        if (shown.second > 0) MemoChip("+${shown.second}")
    }
}

/** The tags drawn on a tile and the "+N" rest. */
internal fun memoVisibleTags(tags: List<String>, max: Int): Pair<List<String>, Int> =
    tags.take(max) to (tags.size - max).coerceAtLeast(0)

@Composable
private fun MemoListRow(controller: MemoController, item: MemoItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MemoTags.TILE)
            .combinedClickable(
                onClick = { controller.go { it.openNote(item.id) } },
                onLongClick = { controller.sheet = memoItemActions(controller, item) },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(memoKindIcon(item.kind), modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (item.pinned) "📌 " else "") + memoNumbered(item.number, item.title),
                style = ElementTheme.typography.fontBodyMdMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            MemoTagLine(item.tags.toImmutableList(), max = 4)
        }
        Text(item.shortDate, style = ElementTheme.typography.fontBodyXsRegular, color = ElementTheme.colors.textSecondary)
    }
}
