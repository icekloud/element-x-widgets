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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.LifecycleEventObserver
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
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

/** Test tags of the memo screen (Compose UI tests). */
internal object MemoTags {
    const val TAB_RECORD = "memo_tab_record"
    const val TAB_FOLDERS = "memo_tab_folders"
    const val TAB_SEARCH = "memo_tab_search"
    const val FOLDER_BADGE = "memo_folder_badge"
    const val PANEL = "memo_panel"
    const val PANEL_EMPTY = "memo_panel_empty"
    const val PANEL_INPUT = "memo_panel_input"
    const val PANEL_SEND = "memo_panel_send"
    const val PANEL_DIRECTIVE = "memo_panel_directive"
    const val PANEL_RESEND = "memo_panel_resend"
    const val PANEL_LIST = "memo_panel_list"
    const val PANEL_CLEAR = "memo_panel_clear"
    const val PANEL_BADGE = "memo_panel_badge"
    const val PROPOSAL_SENDING = "memo_proposal_sending"
    const val BAND = "memo_band"
    const val TILE = "memo_tile"
    const val TILE_PREVIEW = "memo_tile_preview"
    const val TILE_TITLE = "memo_tile_title"
    const val TILE_TAGS = "memo_tile_tags"
    const val RECORD = "memo_record"
    const val RECORD_ROW = "memo_record_row"
    const val FOLDER_ROOT = "memo_folder_root"
    const val ICON_GRID = "memo_icon_grid"
    const val ICON_CHOICE = "memo_icon_choice"
    const val ICON_RESET = "memo_icon_reset"
    const val SEARCH = "memo_search"
    const val ERROR = "memo_error"
    const val NOTE_ATTACHMENT = "memo_note_attachment"
    const val NOTE_HEAD = "memo_note_head"
    const val NOTE_NUMBER = "memo_note_number"

    /** 앱15 F·G: the small sync mark beside the dates, the 편집 box of the open note and the room kept for it. */
    const val SYNC_MARK = "memo_sync_mark"
    const val NOTE_EDIT = "memo_note_edit"
    const val NOTE_EDIT_ROOM = "memo_note_edit_room"

    /** 앱12 A·B·C: the date lines of the head, the reading area (long press) and the editor's body, 취소 and status. */
    const val NOTE_DATE = "memo_note_date"

    /** 앱13 A·B: the date lines of a record card, the compact title and tag fields of the editor. */
    const val RECORD_DATE = "memo_record_date"
    const val EDIT_TITLE = "memo_edit_title"
    const val EDIT_TAGS = "memo_edit_tags"
    const val EDIT_BODY = "memo_edit_body"
    const val NOTE_READ = "memo_note_read"
    const val EDIT_CANCEL = "memo_edit_cancel"
    const val EDIT_STATUS = "memo_edit_status"

    /** 앱10 D: the photos of the note editor. */
    const val EDIT_PHOTO_ADD = "memo_edit_photo_add"
    const val EDIT_PHOTO = "memo_edit_photo"
    const val EDIT_PHOTO_REMOVE = "memo_edit_photo_remove"
    const val EDIT_PHOTO_UNDO = "memo_edit_photo_undo"
    const val EDIT_PHOTO_PICKED = "memo_edit_photo_picked"

    const val RECORD_HEAD = "memo_record_head"
    const val RECORD_PHOTO = "memo_record_photo"
    const val RECORD_TEXT = "memo_record_text"
    const val PHOTO_RETRY = "memo_photo_retry"
    const val VIEWER = "memo_viewer"
    const val VIEWER_COUNTER = "memo_viewer_counter"
    const val VIEWER_CLOSE = "memo_viewer_close"
    const val VIEWER_RETRY = "memo_viewer_retry"
    const val VIEWER_LOADING = "memo_viewer_loading"

    /** 앱19 ②: the chips of the tag sheet, the small ✕ of each, and the input beside them. */
    const val TAG_CHIP = "memo_tag_chip"
    const val TAG_CHIP_REMOVE = "memo_tag_chip_remove"
    const val TAG_INPUT = "memo_tag_input"
}

@ContributesTo(AppScope::class)
interface MemoBindings {
    fun memoSessionStore(): SessionStore
}

/** Sheets and dialogs of the memo screen. They close the standard way and never add a back step (요구 F). */
internal sealed interface MemoSheet {
    data class PickFolder(val title: String, val onPick: (String) -> Unit) : MemoSheet
    data class EditTags(val tags: List<String>, val title: String = "태그 편집", val onSave: (List<String>) -> Unit) : MemoSheet
    data class Actions(val title: String, val actions: List<Pair<String, () -> Unit>>) : MemoSheet
    data class AskText(val title: String, val value: String, val onSubmit: (String) -> Unit) : MemoSheet
    data class Confirm(val text: String, val submit: String, val onSubmit: () -> Unit) : MemoSheet
    data class PickDate(val onPick: (String) -> Unit) : MemoSheet

    /** 앱14 C: the icon grid of one folder ([MEMO_FOLDER_ICON_CHOICES]); a press applies it at once. */
    data class PickIcon(val path: String, val current: String) : MemoSheet
}

/** Everything the memo tabs share: the store, the navigation, the sheets and the error banner. */
internal class MemoController(
    val store: MemoStore,
    val scope: CoroutineScope,
    val nav: MutableState<MemoLocation>,
) {
    var sheet by mutableStateOf<MemoSheet?>(null)

    /** 앱7 항목 2: the photos open full screen (not a sheet: back closes it first). */
    var viewer by mutableStateOf<MemoViewerRequest?>(null)
    var error by mutableStateOf<Pair<String, (() -> Unit)?>?>(null)

    /** 앱9 C: proposal ids whose answer is being written right now (the card is locked from the press on). */
    val answering = mutableStateSetOf<String>()

    /**
     * Answers a proposal card once: locks it at once, then one op per proposal id ([memoGroupOps]). A second press
     * on a locked card does nothing; the local backend also answers an id only once, whatever screen asks.
     */
    fun answer(group: MemoProposalGroup, answer: MemoAnswer, date: String? = null, folder: String? = null) {
        if (group.isSending || group.ids.any { it in answering }) return
        val ops = memoGroupOps(group, answer, date, folder)
        if (ops.isEmpty()) return
        answering += group.ids
        scope.launch {
            try {
                for (op in ops) {
                    val r = store.request(op)
                    if (r is MemoResult.Failed) {
                        error = r.reason to null
                        break
                    }
                }
                store.refreshIndex()
            } finally {
                answering -= group.ids.toSet()
            }
        }
    }

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

    /** 앱11 C: the selection mode (the top bar icon). Ending it drops the selection. */
    var select by mutableStateOf(MemoSelect())

    /** The notes the current tab shows, for "전체 선택" (each tab sets it while shown). */
    var visibleIds by mutableStateOf<List<String>>(emptyList())

    /** A tap on a card or a pinned note: opens it, or in the selection mode selects / unselects it (never opens). */
    fun openOrSelect(id: String) {
        if (select.active) select = select.toggle(id) else go { it.openNote(id) }
    }

    fun endSelect() {
        select = select.end()
    }

    fun bulkTrash() = runBulk(memoBulkTrash(select.ids))

    /** 앱17 B: every selected note goes to the review (in turns of [MEMO_REVIEW_MAX]); the selection mode ends. */
    fun bulkReview() {
        val ids = select.ids
        if (ids.isEmpty() || reviewer == null) return
        review(ids)
        endSelect()
    }

    fun bulkMove() {
        sheet = MemoSheet.PickFolder("${select.count}개를 폴더로 옮기기") { folder -> runBulk(memoBulkMove(select.ids, folder)) }
    }

    /** Tags typed or picked are added to every selected note (the tags they have stay). */
    fun bulkTags() {
        sheet = MemoSheet.EditTags(emptyList(), title = "${select.count}개에 태그 더하기") { tags ->
            if (tags.isNotEmpty()) runBulk(memoBulkTag(select.ids, tags))
        }
    }

    private fun runBulk(op: MemoOp) {
        if (select.ids.isEmpty()) return
        run(op) { endSelect() }
    }

    /** 앱15 H: the banner with one undo action, shown a moment after a change made outside the editor. */
    var undo by mutableStateOf<MemoUndo?>(null)

    /**
     * 앱15 H (관리자: "편집 화면 아니더라도 고정 해제 가능하게끔"): a press on 📌 takes the pin off at once (the same
     * `unpin` op as the menus, 앱3) and offers 되돌리기 (`pin`) for a moment. Nothing in the selection mode.
     */
    fun unpinWithUndo(id: String) = setPinWithUndo(id, pin = false)

    /**
     * 앱16 B: the 📌 button of the open note pins a note that is not pinned (`pin`, the same op as the menus) and offers
     * 되돌리기 (`unpin`) in the same banner; on a pinned note it is [unpinWithUndo].
     */
    fun setPinWithUndo(id: String, pin: Boolean) {
        if (select.active) return
        val target = MemoTarget(id)
        run(if (pin) MemoOp.pin(target) else MemoOp.unpin(target)) {
            undo = MemoUndo(memoPinUndoText(pinnedNow = pin)) { run(if (pin) MemoOp.unpin(target) else MemoOp.pin(target)) }
        }
    }

    /** 앱17: the review requests of the room (null: no server, e.g. the fake data; the review controls are not shown). */
    var reviewer: MemoReviewer? by mutableStateOf(null)

    /** 앱17: the small notice of a review request and of its end. */
    var notice by mutableStateOf<MemoNotice?>(null)

    /**
     * 앱19 ①: brings the cards of the reviewed notes to the local table (the screen: [MemoSyncRunner.syncAfterReview], then
     * the index). Null: nothing to sync (the fake data). Called at most once per review end, only with proposals.
     */
    var reviewSync: (suspend (List<String>) -> Unit)? = null

    private val following = mutableListOf<Job>()

    /**
     * 앱17 A·B·C: asks for the review of [ids] at once. A note asked for less than [MEMO_REVIEW_REPEAT_MS] ago is not sent
     * again. [before] runs first (C: the save of the editor ends). Then the notice says what was taken, and
     * `review_status` is asked until the reviews are done ("검토 완료: 제안 N건"); it stops quietly on a failed look-up,
     * after two minutes, or when the screen goes away.
     */
    fun review(ids: List<String>, before: suspend () -> Unit = {}) {
        val r = reviewer ?: return
        val fresh = r.repeat.admit(ids)
        if (fresh.isEmpty()) return
        scope.launch {
            before()
            val sent = r.request(fresh)
            notice = MemoNotice(memoReviewSentText(sent), memoReviewSentShowMs(sent))
            if (sent.accepted.isEmpty()) return@launch
            // 앱19 ①: "검토 완료: 제안 N건" brings the new cards at once (one sync); 제안 없음 / 실패 sync nothing.
            val job = scope.launch {
                memoReviewFinish(r.follow(sent.accepted), sent.accepted, show = { notice = MemoNotice(it, MEMO_NOTICE_RESULT_SHOW_MS) }, sync = reviewSync)
            }
            following += job
            job.invokeOnCompletion { following -= job }
        }
    }

    /** 앱17 C: the review of the open note, after the save of its editor (running, or still to come) has ended. */
    fun reviewNote(id: String) {
        if (select.active) return
        review(listOf(id)) { MemoEditHub.settled(id) }
    }

    /** 앱17: leaving the screen or the app stops the look-ups (the proposals still come to the proposal area). */
    fun stopFollowing() {
        following.toList().forEach { it.cancel() }
    }
}

/** 앱15 H: the text of the undo banner and what 되돌리기 does. */
internal data class MemoUndo(val text: String, val onUndo: () -> Unit)

/**
 * Element June memo bot screen (요구 B): the record, folders and search tabs, drawn instead of the timeline of the
 * memo bot room. The composer of the room stays below it, unchanged (queue, photo groups, voice, steering).
 */
@Composable
internal fun MemoScreen(
    roomId: String,
    useFakeData: Boolean,
    timelineItems: ImmutableList<TimelineItem>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // v2 (설계 A2): the screens read and write the local database; nothing goes to the room until the sync (A4).
    // The fake backend stays for the developer setting and the sync tests.
    val local = remember { MemoLocalBackend({ MemoDatabase.get(context) }) }
    val backend: MemoBackend = remember(roomId, useFakeData) { if (useFakeData) FakeMemoBackend() else local }
    // The composer below writes local notes while this screen is shown (설계 §f, A3): nothing goes to the room.
    DisposableEffect(roomId, local) {
        val detach = MemoComposerInput.attach(roomId, MemoLocalInputSink(context.applicationContext, local))
        onDispose { detach() }
    }
    val store = remember(backend) { MemoStore(backend) }
    val scope = rememberCoroutineScope()
    val nav = remember(roomId) { mutableStateOf(MemoLocation()) }
    val controller = remember(store) { MemoController(store, scope, nav) }
    // 앱17: the review requests go to the memo bot room (not with the fake data of the developer setting).
    if (!useFakeData) {
        val reviewer = remember(roomId) {
            MemoSyncHub.reviewer(context.applicationContext, context.applicationContext.bindings<MemoSyncBindings>().memoSyncSessionStore(), roomId)
        }
        LaunchedEffect(controller, reviewer) { controller.reviewer = reviewer }
        // 앱19 ①: the cards a review made come to the local table (and the open proposal area) right after its notice.
        val reviewRunner = remember(roomId) {
            MemoSyncHub.runner(context.applicationContext, context.applicationContext.bindings<MemoSyncBindings>().memoSyncSessionStore(), roomId)
        }
        LaunchedEffect(controller, reviewRunner) {
            controller.reviewSync = { ids ->
                reviewRunner.syncAfterReview(ids)
                store.refreshIndex()
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stopFollowing() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.stopFollowing()
        }
    }

    // The index is read again when the local notes change (no polling, 설계 §k).
    val localVersion by MemoLocalChanges.version.collectAsState()
    LaunchedEffect(store, localVersion) {
        store.refreshIndex()
    }
    val state = nav.value
    // 추가요청 2: while a note is being edited nothing goes up to the server (the auto save stays local).
    if (!useFakeData) MemoSyncEffects(roomId, timelineItems, localVersion, editing = state.editing)
    // 앱13 B·C: the editor takes the whole screen; the tab bar and the room composer below come back when it ends.
    val fullEditor = memoEditorFullScreen(state)
    LaunchedEffect(roomId, fullEditor) { MemoEditingRooms.set(roomId, fullEditor) }
    DisposableEffect(roomId) { onDispose { MemoEditingRooms.set(roomId, false) } }

    // 앱11 C: the top bar icon turns the selection mode on and off; ending it here (종료, back, a bulk action) turns
    // the icon off too.
    val selectRooms by MemoSelectHub.rooms.collectAsState()
    val selectOn = roomId in selectRooms
    LaunchedEffect(selectOn) { controller.select = if (selectOn) controller.select.start() else controller.select.end() }
    LaunchedEffect(controller.select.active) { if (!controller.select.active) MemoSelectHub.set(roomId, false) }
    DisposableEffect(roomId) { onDispose { MemoSelectHub.set(roomId, false) } }
    // Back ends the selection mode first (registered after the screen's own handler: it is asked first).
    BackHandler(enabled = state.handlesBack) {
        when (val b = state.back()) {
            is MemoBack.To -> nav.value = b.state
            MemoBack.Exit -> Unit
        }
    }
    BackHandler(enabled = controller.select.active) { controller.endSelect() }

    val index by store.index.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        controller.error?.let { (reason, retry) -> MemoErrorBanner(reason, retry, onClose = { controller.error = null }) }
        controller.undo?.let { MemoUndoBanner(controller, it) }
        controller.notice?.let { MemoNoticeBanner(controller, it) }
        if (!useFakeData) MemoAttachmentBanners(local, localVersion)
        // 앱17 D (관리자: "슬라이드로 탭 간 이동하는 기능은 폐기"): the tab swipe of 앱11 추가요청 2 is gone; a card pushed to
        // the left asks for its review now (MemoReviewSwipe). The tab buttons stay.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val note = state.note
            when {
                // 앱7 항목 2: a photo of the note opens the memo viewer from the phone file or the server copy, so the
                // room timeline (photosByMxc) is no longer needed here.
                note != null -> MemoNoteScreen(controller, note)
                state.tab == MemoTab.Record -> MemoRecordTab(controller, localVersion)
                state.tab == MemoTab.Folders -> MemoFoldersTab(controller)
                else -> MemoSearchTab(controller)
            }
        }
        if (controller.select.active && state.note == null) MemoSelectBar(controller, controller.visibleIds.toImmutableList())
        if (!fullEditor) {
            MemoTabBar(
                selected = state.tab,
                onSelect = { tab -> controller.go { it.closeNote().selectTab(tab) } },
            )
        }
    }
    // 앱9 A: the proposal area opens from the list icon of the top bar; the badge there counts the waiting cards.
    val waitingCards = remember(index) { memoPanelWaitingCount(index) }
    LaunchedEffect(roomId, waitingCards) { MemoPanelHub.setCount(roomId, waitingCards) }
    DisposableEffect(roomId) { onDispose { MemoPanelHub.leave(roomId) } }
    val panelRoom by MemoPanelHub.openRoom.collectAsState()
    if (panelRoom == roomId) {
        val directives = if (useFakeData) {
            null
        } else {
            remember(roomId) {
                MemoSyncHub.directives(context.applicationContext, context.applicationContext.bindings<MemoSyncBindings>().memoSyncSessionStore(), roomId)
            }
        }
        val runner = if (useFakeData) {
            null
        } else {
            remember(roomId) {
                MemoSyncHub.runner(context.applicationContext, context.applicationContext.bindings<MemoSyncBindings>().memoSyncSessionStore(), roomId)
            }
        }
        MemoPanel(
            controller = controller,
            directives = directives,
            // A finished instruction changed notes or tags on the server: one sync brings them down.
            onSync = {
                runner?.syncNow()
                store.refreshIndex()
            },
            onClose = { MemoPanelHub.close() },
        )
    }
    MemoSheetHost(controller)
    controller.viewer?.let { MemoPhotoViewer(it, onClose = { controller.viewer = null }) }
}

@Composable
internal fun MemoTabBar(selected: MemoTab, onSelect: (MemoTab) -> Unit) {
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
                // 앱9 A: no badge on the folder tab: the proposals moved to the list icon of the top bar.
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

/** 앱15 H: "고정을 해제했습니다 · 되돌리기" for [MEMO_UNDO_SHOW_MS], then it goes by itself (a new one starts again). */
@Composable
private fun MemoUndoBanner(controller: MemoController, undo: MemoUndo) {
    LaunchedEffect(undo) {
        delay(MEMO_UNDO_SHOW_MS)
        if (controller.undo === undo) controller.undo = null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MemoA11Tags.UNDO)
            .background(ElementTheme.colors.bgSubtleSecondary)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(undo.text, modifier = Modifier.weight(1f), style = ElementTheme.typography.fontBodySmRegular)
        Text(
            "되돌리기",
            modifier = Modifier
                .clickable {
                    controller.undo = null
                    undo.onUndo()
                }
                .padding(8.dp),
            color = ElementTheme.colors.textActionAccent,
            style = ElementTheme.typography.fontBodySmMedium,
        )
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

/**
 * One memo photo (앱6 원인분석_사진.md). The phone original [localPath] first while it is there, else the server copy:
 * [fileJson] wrapped as `{"file": …}` so the SDK decrypts it ([memoMediaSourceJson]). A photo that cannot be drawn
 * shows "↻ 다시" instead of an empty grey box; the local original falls back to the server copy first.
 */
@Composable
internal fun MemoThumbnail(mxc: String, fileJson: String?, modifier: Modifier = Modifier, localPath: String? = null) {
    val local = localPath ?: mxc.takeIf { it.startsWith(MEMO_LOCAL_FILE_PREFIX) }?.removePrefix(MEMO_LOCAL_FILE_PREFIX)
    val hasServer = !mxc.startsWith(MEMO_LOCAL_FILE_PREFIX)
    var useLocal by remember(local) { mutableStateOf(local != null && File(local).isFile) }
    var failed by remember(mxc, local) { mutableStateOf(!useLocal && !hasServer) }
    var attempt by remember { mutableIntStateOf(0) }
    Box(modifier.background(ElementTheme.colors.bgSubtleSecondary), contentAlignment = Alignment.Center) {
        if (failed) {
            Text(
                "↻ 다시",
                modifier = Modifier
                    .clickable {
                        useLocal = local != null && File(local).isFile
                        failed = !useLocal && !hasServer
                        attempt++
                    }
                    .padding(4.dp)
                    .testTag(MemoTags.PHOTO_RETRY),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
        } else {
            key(attempt, useLocal) {
                AsyncImage(
                    model = memoPhotoModel(mxc, fileJson, local.takeIf { useLocal }),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (useLocal && hasServer) {
                            useLocal = false
                        } else {
                            failed = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** What Coil loads for a memo photo: the local file, or the SDK request with the json the SDK can decrypt. */
internal fun memoPhotoModel(mxc: String, fileJson: String?, localPath: String?): Any =
    localPath?.let(::File) ?: MediaRequestData(MediaSource(mxc, memoMediaSourceJson(fileJson)), MediaRequestData.Kind.Thumbnail(320))

/** The photos of a record card in one row (앱6 결정기록 3): up to [MEMO_CARD_PHOTOS], the rest as "+N" on the last one. */
@Composable
private fun MemoPhotoRow(p: MemoPreview.Photo, modifier: Modifier = Modifier, onOpen: (Int) -> Unit = {}) {
    val cells = p.cells.take(MEMO_CARD_PHOTOS)
    val hidden = memoHiddenPhotos(p)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.forEachIndexed { i, a ->
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)).clickable { onOpen(i) }.testTag(MemoTags.RECORD_PHOTO)) {
                MemoThumbnail(a.mxc, a.fileJson, Modifier.fillMaxSize(), a.localPath)
                if (i == cells.lastIndex && hidden > 0) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Text("+$hidden", color = Color.White, style = ElementTheme.typography.fontBodyLgMedium)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 기록 탭

@Composable
internal fun MemoRecordTab(controller: MemoController, localVersion: Long) {
    val store = controller.store
    val key = remember { MemoListKey.of(MEMO_ALL_FOLDERS, emptyList()) }
    val lists by store.lists.collectAsState()
    val list = lists[key] ?: MemoListCache()
    // v2 (A3): the record tab is the newest local notes. A note typed below is saved at once and shows here
    // (local change signal), with no answer to wait for.
    LaunchedEffect(store, localVersion) { store.loadFirst(key, force = true) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, list.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (memoShouldLoadMore(store.list(key), last)) store.loadMore(key) }
    }
    // 앱11 A: the pinned notes of every folder stay on top; only the list below scrolls.
    val pinned = memoPinnedOfAll(controller, localVersion)
    LaunchedEffect(list.items) { controller.visibleIds = list.items.map { it.id } }
    Column(Modifier.fillMaxSize()) {
        MemoPinStrip(controller, pinned.toImmutableList())
        if (list.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().testTag(MemoTags.RECORD), contentAlignment = Alignment.Center) {
                Text("아래 입력창에 붙여넣기 / 📷 / 🎤 로 메모를 남기세요", color = ElementTheme.colors.textSecondary)
            }
        } else {
            // Newest note at the bottom, right above the composer (reverse layout: index 0 = newest).
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth().testTag(MemoTags.RECORD)) {
                items(list.items, key = { it.id }) { item -> MemoReviewSwipe(controller, item.id, item.folder) { m -> MemoRecordRow(controller, item, m) } }
            }
        }
    }
}

/** One local note of the record tab: what was saved, where it is filed, its tags. A tap opens it. */
@Composable
internal fun MemoRecordRow(controller: MemoController, item: MemoItem, modifier: Modifier = Modifier) {
    val target = MemoTarget(item.id)
    val (borderWidth, borderColor) = memoCardBorder(controller, item.id)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .combinedClickable(
                // 앱11 C: in the selection mode a tap selects, it does not open.
                onClick = { controller.openOrSelect(item.id) },
                onLongClick = { if (controller.select.active) controller.openOrSelect(item.id) else controller.sheet = memoItemActions(controller, item) },
            )
            .padding(8.dp)
            .testTag(MemoTags.RECORD_ROW),
    ) {
        if (controller.select.active) MemoSelectMark(controller.select.isSelected(item.id))
        val text = memoCardText(item)
        // 앱10 C: `#번호  제목`, no author ("나"). 앱13 A (관리자: "작성, 수정일자 표시방식이 이 화면에서는 반영안됨. 절반정도로
        // 폰트크기 줄이고 두줄로 나누고 우측정렬"): the dates right aligned on two small lines, the same ones as the open note.
        // 앱15 E·F (추가요청 6: the card keeps its title size): the title takes the room up to the dates, the small sync
        // mark sits left of the dates.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                memoRecordTitle(item, text),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(MemoTags.RECORD_HEAD),
            )
            val sending by MemoSendingNotes.ids.collectAsState()
            MemoDateArea(
                lines = memoItemDateLines(item).toImmutableList(),
                mark = memoSyncMark(item.syncWaiting, item.id in sending),
                modifier = Modifier.padding(start = 6.dp).testTag(MemoTags.RECORD_DATE),
            )
        }
        val uploadMark = memoPhotoUploadMark(item)
        if (uploadMark.isNotEmpty()) {
            Text(
                uploadMark,
                style = ElementTheme.typography.fontBodyXsRegular,
                color = if (item.photosFailed > 0) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.testTag(MEMO_PHOTO_UPLOAD_MARK_TAG),
            )
        }
        when (val p = item.preview) {
            is MemoPreview.Photo -> MemoPhotoRow(p, Modifier.padding(vertical = 4.dp)) { i -> controller.viewer = MemoViewerRequest(p.cells, i) }
            is MemoPreview.Link -> Text(p.url, style = ElementTheme.typography.fontBodySmRegular, maxLines = 1, overflow = TextOverflow.Ellipsis)
            is MemoPreview.Text, null -> Unit
        }
        // 앱6 항목 4·5: the body right above the folder and tag chips, a bit bigger (14 sp), up to 7 lines; the card
        // grows with it (no fixed height).
        text?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 2.dp).testTag(MemoTags.RECORD_TEXT),
                style = ElementTheme.typography.fontBodyMdRegular,
                maxLines = MEMO_CARD_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 앱11 D: a text note's photos, small, under its text.
        item.photos?.let { MemoCardPhotos(controller, it, Modifier.padding(top = 4.dp), size = 56) }
        FlowRow(modifier = Modifier.padding(top = 4.dp)) {
            // 앱14 A: the folder's own icon ([memoFolderIcon] through the folder list).
            val index by controller.store.index.collectAsState()
            MemoChip(
                memoFolderLabel(index, item.folder),
                accent = item.folder != MEMO_INBOX,
                onClick = { controller.pickFolderThenMove(target) },
            )
            item.tags.forEach { tag -> MemoChip("#$tag", onClick = { controller.editTagsOf(target, item.tags) }) }
            // 앱15 H: a press on 📌 takes the pin off (with 되돌리기).
            if (item.pinned) {
                MemoChip(
                    "📌",
                    modifier = Modifier.testTag(MemoA11Tags.PIN_OFF),
                    onClick = { controller.unpinWithUndo(item.id) },
                )
            }
        }
    }
}

/** The head line of a record card: `#12  제목  10-15 11:31` ([memoNoteHead]). */
internal fun memoRecordHead(item: MemoItem, text: String?): String = memoNoteHead(item.number, memoCardHeadTitle(item, text), memoRecordTime(item.date))

/** 앱13 A: the left part of the record card head, `#12  제목` (the dates are drawn beside it, [memoItemDateLines]). */
internal fun memoRecordTitle(item: MemoItem, text: String?): String = memoNoteHead(item.number, memoCardHeadTitle(item, text), "")

/** "10-12 11:31" from "2026-10-12T11:31:00+09:00". */
internal fun memoRecordTime(date: String): String = if (date.length >= 16) date.substring(5, 16).replace('T', ' ') else date

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
            // 앱19 ②: the tags as chips with a small ✕, the input beside them (MemoTagEditor), for every use of this sheet.
            val known by controller.store.tags.collectAsState()
            LaunchedEffect(Unit) { controller.store.loadTags() }
            ModalBottomSheet(onDismissRequest = close, scrollable = false) {
                MemoTagEditor(
                    title = sheet.title,
                    tags = sheet.tags.toImmutableList(),
                    known = known.map { it.name }.toImmutableList(),
                    onSave = { tags ->
                        close()
                        sheet.onSave(tags)
                    },
                    onCancel = close,
                )
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
        is MemoSheet.PickIcon -> ModalBottomSheet(onDismissRequest = close, scrollable = true) {
            MemoIconPicker(sheet.path, sheet.current) { icon ->
                close()
                controller.run(memoFolderIconOp(sheet.path, icon))
            }
        }
    }
}

/** 앱14 C: the icon choices of a folder, then 기본으로 되돌리기 (empty icon). No free text field. */
@Composable
private fun MemoIconPicker(path: String, current: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("'$path' 아이콘", style = ElementTheme.typography.fontHeadingSmMedium, modifier = Modifier.padding(16.dp))
        FlowRow(Modifier.padding(horizontal = 12.dp).testTag(MemoTags.ICON_GRID)) {
            MEMO_FOLDER_ICON_CHOICES.forEach { icon ->
                val selected = icon == current
                Text(
                    icon,
                    style = ElementTheme.typography.fontHeadingMdRegular,
                    modifier = Modifier
                        .padding(2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) ElementTheme.colors.bgActionSecondaryPressed else ElementTheme.colors.bgCanvasDefault)
                        .clickable { onPick(icon) }
                        .padding(8.dp)
                        .testTag(MemoTags.ICON_CHOICE),
                )
            }
        }
        Text(
            "기본으로 되돌리기 (" + memoAutoFolderIcon(path) + ")",
            color = ElementTheme.colors.textActionAccent,
            modifier = Modifier.fillMaxWidth().clickable { onPick("") }.padding(16.dp).testTag(MemoTags.ICON_RESET),
        )
        Spacer(Modifier.height(24.dp))
    }
}

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
