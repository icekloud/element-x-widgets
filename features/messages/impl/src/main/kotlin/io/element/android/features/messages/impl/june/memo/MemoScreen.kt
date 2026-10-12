/*
 * Copyright (c) 2026 Element June contributors.
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package io.element.android.features.messages.impl.june.memo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.dialogs.TextFieldDialog
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val INDEX_POLL_MILLIS = 3_000L

/** Test tags of the memo screen (Compose UI tests). */
internal object MemoTags {
    const val TAB_RECORD = "memo_tab_record"
    const val TAB_FOLDERS = "memo_tab_folders"
    const val TAB_SEARCH = "memo_tab_search"
    const val FOLDER_BADGE = "memo_folder_badge"
    const val BAND = "memo_band"
    const val TILE = "memo_tile"
    const val TILE_PREVIEW = "memo_tile_preview"
    const val TILE_TITLE = "memo_tile_title"
    const val TILE_TAGS = "memo_tile_tags"
    const val RECORD = "memo_record"
    const val FOLDER_ROOT = "memo_folder_root"
    const val SEARCH = "memo_search"
    const val ERROR = "memo_error"
    const val NOTE_ATTACHMENT = "memo_note_attachment"
}

@ContributesTo(AppScope::class)
interface MemoBindings {
    fun memoSessionStore(): SessionStore
}

/** Sheets and dialogs of the memo screen. They close the standard way and never add a back step (요구 F). */
internal sealed interface MemoSheet {
    data class PickFolder(val title: String, val onPick: (String) -> Unit) : MemoSheet
    data class EditTags(val tags: List<String>, val onSave: (List<String>) -> Unit) : MemoSheet
    data class Actions(val title: String, val actions: List<Pair<String, () -> Unit>>) : MemoSheet
    data class AskText(val title: String, val value: String, val onSubmit: (String) -> Unit) : MemoSheet
    data class Confirm(val text: String, val submit: String, val onSubmit: () -> Unit) : MemoSheet
    data class PickDate(val onPick: (String) -> Unit) : MemoSheet
}

/** Everything the memo tabs share: the store, the navigation, the sheets and the error banner. */
internal class MemoController(
    val store: MemoStore,
    val scope: CoroutineScope,
    val nav: MutableState<MemoLocation>,
) {
    var sheet by mutableStateOf<MemoSheet?>(null)
    var error by mutableStateOf<Pair<String, (() -> Unit)?>?>(null)

    /** Tag filters per folder: kept when going back (요구 F "태그 필터는 뒤로가기로 해제 안 함"). */
    val folderTags = mutableStateMapOf<String, List<String>>()

    fun go(f: (MemoLocation) -> MemoLocation) {
        nav.value = f(nav.value)
    }

    /** Sends [op]; on failure shows the reason with a retry; refreshes the index after a change. */
    fun run(op: MemoOp, onOk: (MemoResponse) -> Unit = {}) {
        scope.launch {
            when (val r = store.request(op)) {
                is MemoResult.Ok -> {
                    error = null
                    onOk(r.response)
                    if (op.isChange) store.refreshIndex()
                }
                is MemoResult.Failed -> error = r.reason to { run(op, onOk) }
            }
        }
    }

    fun pickFolderThenMove(target: MemoTarget) {
        sheet = MemoSheet.PickFolder("폴더로 옮기기") { folder -> run(MemoOp.move(target, folder)) }
    }

    fun editTagsOf(target: MemoTarget, current: List<String>) {
        sheet = MemoSheet.EditTags(current) { tags -> run(MemoOp.editTags(target, tags)) }
    }
}

/**
 * Element June memo bot screen (요구 B): the record, folders and search tabs, drawn instead of the timeline of the
 * memo bot room. The composer of the room stays below it, unchanged (queue, photo groups, voice, steering).
 */
@Composable
internal fun MemoScreen(
    roomId: String,
    useFakeData: Boolean,
    timelineItems: ImmutableList<TimelineItem>,
    onLoadOlder: () -> Unit,
    onPhotoClick: (TimelineItem.Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val backend: MemoBackend = remember(roomId, useFakeData) {
        if (useFakeData) FakeMemoBackend() else MemoRoomBackend(context.bindings<MemoBindings>().memoSessionStore(), roomId)
    }
    val store = remember(backend) { MemoStore(backend) }
    val scope = rememberCoroutineScope()
    val nav = remember(roomId) { mutableStateOf(MemoLocation()) }
    val controller = remember(store) { MemoController(store, scope, nav) }
    val timeline = remember(timelineItems) { memoTimeline(timelineItems) }

    // Answers seen in the room timeline (decrypted by the SDK) reach the waiting requests first.
    LaunchedEffect(timeline.answers, backend) {
        (backend as? MemoRoomBackend)?.let { b -> timeline.answers.forEach(b::deliver) }
    }
    // The index is read while the screen is shown (like the June background panel).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(store) {
        while (true) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) store.refreshIndex()
            delay(INDEX_POLL_MILLIS)
        }
    }

    val state = nav.value
    var discardAsked by remember { mutableStateOf(false) }
    BackHandler(enabled = state.handlesBack) {
        when (val b = state.back()) {
            is MemoBack.To -> nav.value = b.state
            MemoBack.ConfirmDiscard -> discardAsked = true
            MemoBack.Exit -> Unit
        }
    }
    if (discardAsked) {
        ConfirmationDialog(
            content = "편집한 내용을 버리고 나갈까요?",
            submitText = "버리기",
            cancelText = "계속 편집",
            onSubmitClick = {
                discardAsked = false
                nav.value = nav.value.afterDiscardConfirmed()
            },
            onDismiss = { discardAsked = false },
        )
    }

    val index by store.index.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        controller.error?.let { (reason, retry) -> MemoErrorBanner(reason, retry, onClose = { controller.error = null }) }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val note = state.note
            when {
                note != null -> MemoNoteScreen(
                    controller,
                    note,
                    canOpenAttachment = { a -> a.mxc in timeline.photosByMxc },
                    onAttachmentClick = { a -> timeline.photosByMxc[a.mxc]?.let(onPhotoClick) },
                )
                state.tab == MemoTab.Record -> MemoRecordTab(controller, timeline, onLoadOlder, onPhotoClick)
                state.tab == MemoTab.Folders -> MemoFoldersTab(controller)
                else -> MemoSearchTab(controller)
            }
        }
        MemoTabBar(
            selected = state.tab,
            pendingProposals = index.pendingProposals,
            onSelect = { tab -> controller.go { it.closeNote().selectTab(tab) } },
        )
    }
    MemoSheetHost(controller)
}

@Composable
internal fun MemoTabBar(selected: MemoTab, pendingProposals: Int, onSelect: (MemoTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgCanvasDefault)
            .border(width = 0.5.dp, color = ElementTheme.colors.borderInteractiveSecondary),
    ) {
        listOf(
            Triple(MemoTab.Record, R.string.june_memo_tab_record, MemoTags.TAB_RECORD),
            Triple(MemoTab.Folders, R.string.june_memo_tab_folders, MemoTags.TAB_FOLDERS),
            Triple(MemoTab.Search, R.string.june_memo_tab_search, MemoTags.TAB_SEARCH),
        ).forEach { (tab, label, tag) ->
            val on = tab == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .testTag(tag)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(label),
                    style = if (on) ElementTheme.typography.fontBodyMdMedium else ElementTheme.typography.fontBodyMdRegular,
                    color = if (on) ElementTheme.colors.textActionAccent else ElementTheme.colors.textSecondary,
                )
                if (tab == MemoTab.Folders && pendingProposals > 0) {
                    Spacer(Modifier.width(4.dp))
                    MemoBadge(pendingProposals.toString(), Modifier.testTag(MemoTags.FOLDER_BADGE))
                }
            }
        }
    }
}

@Composable
internal fun MemoBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ElementTheme.colors.bgActionPrimaryRest)
            .padding(horizontal = 6.dp),
        style = ElementTheme.typography.fontBodyXsMedium,
        color = ElementTheme.colors.textOnSolidPrimary,
    )
}

@Composable
private fun MemoErrorBanner(reason: String, retry: (() -> Unit)?, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MemoTags.ERROR)
            .background(ElementTheme.colors.bgSubtleSecondary)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(reason, modifier = Modifier.weight(1f), color = ElementTheme.colors.textCriticalPrimary, style = ElementTheme.typography.fontBodySmRegular)
        if (retry != null) {
            Text("다시", modifier = Modifier.clickable {
                onClose()
                retry()
            }.padding(8.dp), color = ElementTheme.colors.textActionAccent)
        }
        Text("✕", modifier = Modifier.clickable(onClick = onClose).padding(8.dp), color = ElementTheme.colors.textSecondary)
    }
}

/** A small rounded chip. [accent] = action chip, [dotted] look for tags is drawn with the secondary border. */
@Composable
internal fun MemoChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val color = when {
        selected -> ElementTheme.colors.textOnSolidPrimary
        accent -> ElementTheme.colors.textActionAccent
        else -> ElementTheme.colors.textSecondary
    }
    val border = if (accent || selected) ElementTheme.colors.textActionAccent else ElementTheme.colors.borderInteractiveSecondary
    Text(
        text = text,
        modifier = modifier
            .padding(end = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ElementTheme.colors.bgActionPrimaryRest else Color.Transparent)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = ElementTheme.typography.fontBodySmRegular,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun MemoThumbnail(mxc: String, fileJson: String?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = MediaRequestData(MediaSource(mxc, fileJson), MediaRequestData.Kind.Thumbnail(320)),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(ElementTheme.colors.bgSubtleSecondary),
    )
}

// ---------------------------------------------------------------- 기록 탭

@Composable
private fun MemoRecordTab(
    controller: MemoController,
    timeline: MemoTimeline,
    onLoadOlder: () -> Unit,
    onPhotoClick: (TimelineItem.Event) -> Unit,
) {
    // Newest card at the bottom, right above the composer (reverse layout: index 0 = newest).
    val cards = remember(timeline.entries) { buildMemoCards(timeline.entries).asReversed() }
    val listState = rememberLazyListState()
    val loadOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(listState, cards.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (cards.isNotEmpty() && last >= cards.size - 3) loadOlder() }
    }
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxSize().testTag(MemoTags.RECORD), contentAlignment = Alignment.Center) {
            Text("아래 입력창에 붙여넣기 / 📷 / 🎤 로 메모를 남기세요", color = ElementTheme.colors.textSecondary)
        }
        return
    }
    LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize().testTag(MemoTags.RECORD)) {
        items(cards, key = { it.key }) { card ->
            MemoCardView(controller, card, timeline, onPhotoClick)
        }
    }
}

@Composable
internal fun MemoCardView(
    controller: MemoController,
    card: MemoCard,
    timeline: MemoTimeline,
    onPhotoClick: (TimelineItem.Event) -> Unit,
) {
    var expanded by remember(card.key) { mutableStateOf(false) }
    val info = card.info
    val target = card.target
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (info?.review == MemoReviewState.Proposed) ElementTheme.colors.textActionAccent else ElementTheme.colors.borderInteractiveSecondary,
                RoundedCornerShape(10.dp),
            )
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    if (target.isKnown) {
                        controller.sheet = MemoSheet.Actions(
                            card.headline(),
                            listOf(
                                "📌 고정" to cardAction(controller, card, MemoCardAction.Pin),
                                "고정 해제" to cardAction(controller, card, MemoCardAction.Unpin),
                                "🗑 휴지통" to cardAction(controller, card, MemoCardAction.Trash),
                            ),
                        )
                    }
                },
            )
            .padding(8.dp),
    ) {
        if (!card.isBotOnly) {
            Text(
                "나 · " + card.headline(),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val photos = card.inputs.filter { it.kind == MemoEntryKind.Photo }
            if (photos.isNotEmpty()) {
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    photos.take(4).forEach { p ->
                        val thumb = timeline.thumbnails[p.key]
                        AsyncImage(
                            model = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(ElementTheme.colors.bgSubtleSecondary)
                                .clickable { timeline.events[p.key]?.let(onPhotoClick) },
                        )
                    }
                    if (photos.size > 4) Text("+${photos.size - 4}", color = ElementTheme.colors.textSecondary)
                }
            }
        }
        if (info != null) {
            FlowRow(modifier = Modifier.padding(top = 4.dp)) {
                if (info.inbox) {
                    MemoChip("📥 받은편지함" + (info.confidence?.let { " · 확신 $it" } ?: ""))
                } else {
                    info.folder?.let { MemoChip("📁 $it", accent = true, onClick = { if (target.isKnown) controller.pickFolderThenMove(target) }) }
                }
                info.tags.forEach { tag -> MemoChip("#$tag", onClick = { if (target.isKnown) controller.editTagsOf(target, info.tags) }) }
                info.review?.let { MemoChip(it.label, accent = it == MemoReviewState.Proposed) }
                if (info.pinSuggested) MemoChip("📌?", accent = true, onClick = { memoCardOp(card, MemoCardAction.Pin)?.let(controller::run) })
            }
            if (info.inbox && info.candidates.isNotEmpty()) {
                FlowRow {
                    Text("후보: ", style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
                    info.candidates.forEach { c -> MemoChip(c, accent = true, onClick = { memoCardOp(card, MemoCardAction.MoveTo(c))?.let(controller::run) }) }
                }
            }
        } else if (card.replies.isEmpty()) {
            Text("⏳ 메모봇 처리 중…", style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
        }
        // The bot answer itself stays folded inside the card; a tap on the card unfolds it.
        if (card.replies.isNotEmpty()) {
            if (expanded || card.isBotOnly) {
                card.replies.forEach { r -> Text(r.text, style = ElementTheme.typography.fontBodySmRegular, modifier = Modifier.padding(top = 4.dp)) }
            } else {
                Text(
                    "봇 답 ▸",
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private fun cardAction(controller: MemoController, card: MemoCard, action: MemoCardAction): () -> Unit = {
    memoCardOp(card, action)?.let { op -> controller.run(op) }
}

// ---------------------------------------------------------------- 시트·대화상자

@Composable
private fun MemoSheetHost(controller: MemoController) {
    val sheet = controller.sheet ?: return
    val close = { controller.sheet = null }
    when (sheet) {
        is MemoSheet.PickFolder -> {
            val index by controller.store.index.collectAsState()
            ModalBottomSheet(onDismissRequest = close, scrollable = true) {
                Text(sheet.title, style = ElementTheme.typography.fontHeadingSmMedium, modifier = Modifier.padding(16.dp))
                memoMoveTargets(index).forEach { f ->
                    Text(
                        "${f.icon} ${f.path}",
                        modifier = Modifier.fillMaxWidth().clickable {
                            close()
                            sheet.onPick(f.path)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        is MemoSheet.EditTags -> {
            var text by remember(sheet) { mutableStateOf(sheet.tags.joinToString(" ") { "#$it" }) }
            ModalBottomSheet(onDismissRequest = close, scrollable = false) {
                Column(Modifier.padding(16.dp)) {
                    Text("태그 편집", style = ElementTheme.typography.fontHeadingSmMedium)
                    Text("띄어쓰기로 구분합니다. #은 없어도 됩니다.", style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
                    TextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text("취소", modifier = Modifier.clickable(onClick = close).padding(12.dp))
                        Text(
                            "저장",
                            color = ElementTheme.colors.textActionAccent,
                            modifier = Modifier.clickable {
                                close()
                                sheet.onSave(memoParseTagInput(text))
                            }.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        is MemoSheet.Actions -> ModalBottomSheet(onDismissRequest = close, scrollable = false) {
            Text(
                sheet.title,
                style = ElementTheme.typography.fontBodyMdMedium,
                modifier = Modifier.padding(16.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            sheet.actions.forEach { (label, action) ->
                Text(label, modifier = Modifier.fillMaxWidth().clickable {
                    close()
                    action()
                }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
        is MemoSheet.AskText -> TextFieldDialog(
            title = sheet.title,
            value = sheet.value,
            placeholder = null,
            onSubmit = { v ->
                close()
                if (v.isNotBlank()) sheet.onSubmit(v.trim())
            },
            onDismissRequest = close,
        )
        is MemoSheet.Confirm -> ConfirmationDialog(
            content = sheet.text,
            submitText = sheet.submit,
            onSubmitClick = {
                close()
                sheet.onSubmit()
            },
            onDismiss = close,
        )
        is MemoSheet.PickDate -> MemoDatePicker(onPick = { d ->
            close()
            sheet.onPick(d)
        }, onDismiss = close)
    }
}

/** "#교토 이동, 빵집" → [교토, 이동, 빵집]. */
internal fun memoParseTagInput(text: String): List<String> =
    text.split(' ', ',', '\n').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()

@Composable
private fun MemoDatePicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val state = androidx.compose.material3.rememberDatePickerState()
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Text(
                "확인",
                color = ElementTheme.colors.textActionAccent,
                modifier = Modifier.clickable {
                    state.selectedDateMillis?.let { onPick(memoDateOf(it)) } ?: onDismiss()
                }.padding(12.dp),
            )
        },
        dismissButton = { Text("취소", modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp)) },
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}

/** UTC millis of the date picker → "2026-10-06". */
internal fun memoDateOf(utcMillis: Long): String {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return f.format(java.util.Date(utcMillis))
}
