/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.collections.immutable.ImmutableList

/*
 * 앱11 screens: the pinned strip (A·B), the photos under a card's text (D), the selection bar (C) and the tab swipe
 * (추가요청 2). The rules are in MemoSelect.kt.
 */

/**
 * The pinned notes over a list (앱11 A·B): a header "📌 고정 N ▾" and, unfolded, the pinned notes. It stays above the
 * list: only the list scrolls. A tap opens the note (in the selection mode: selects it). Nothing is drawn without
 * pinned notes. 앱15 H (관리자: "고정 메모는 한줄에 하나씩 … 오른쪽 여백 공간 활용해서 메모 제목 … 고유번호는 나오지않게"):
 * one note per line over the whole width (a small photo first for a photo-only note), no number, and a 📌 at the end
 * that takes the pin off. Unfolded it is at most [MEMO_PIN_TRAY_MAX_PART] of the screen and scrolls inside.
 */
@Composable
internal fun MemoPinStrip(controller: MemoController, pinned: ImmutableList<MemoItem>, modifier: Modifier = Modifier) {
    if (pinned.isEmpty()) return
    var folded by rememberSaveable { mutableStateOf(false) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * MEMO_PIN_TRAY_MAX_PART
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ElementTheme.colors.bgSubtleSecondary)
            .testTag(MemoA11Tags.PIN_STRIP),
    ) {
        Text(
            "📌 고정 ${pinned.size}" + if (folded) " ▸" else " ▾",
            modifier = Modifier.fillMaxWidth().clickable { folded = !folded }.padding(horizontal = 8.dp, vertical = 6.dp),
            style = ElementTheme.typography.fontBodySmMedium,
        )
        if (!folded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pinned.forEach { item -> MemoPinRowView(controller, item) }
            }
        }
    }
}

@Composable
private fun MemoPinRowView(controller: MemoController, item: MemoItem) {
    val selected = controller.select.isSelected(item.id)
    val shape = RoundedCornerShape(6.dp)
    val border = if (selected) ElementTheme.colors.textActionAccent else ElementTheme.colors.borderInteractiveSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .background(ElementTheme.colors.bgCanvasDefault)
            .clickable { controller.openOrSelect(item.id) }
            .padding(start = 6.dp)
            .testTag(MemoA11Tags.PIN_CELL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cell = memoPinCell(item)
        if (cell is MemoPinCell.Photo) {
            Box(Modifier.padding(vertical = 4.dp).size(32.dp).clip(RoundedCornerShape(4.dp))) {
                MemoThumbnail(cell.photo.mxc, cell.photo.fileJson, Modifier.fillMaxSize(), cell.photo.localPath)
                if (cell.count > 1) {
                    Text(
                        "${cell.count}",
                        modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 2.dp),
                        color = Color.White,
                        style = ElementTheme.typography.fontBodyXsRegular,
                    )
                }
            }
        }
        Text(
            memoPinRowText(item),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp),
            style = ElementTheme.typography.fontBodySmRegular,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!controller.select.active) {
            Text(
                "📌",
                modifier = Modifier
                    .clickable { controller.unpinWithUndo(item.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag(MemoA11Tags.PIN_OFF),
                style = ElementTheme.typography.fontBodySmRegular,
            )
        }
    }
}

/** The pinned notes of every folder for the record tab strip (앱11 A), read again with the local changes. */
@Composable
internal fun memoPinnedOfAll(controller: MemoController, localVersion: Long): List<MemoItem> {
    val store = controller.store
    val key = remember { MemoListKey.of(MEMO_PINNED_LIST, emptyList()) }
    val lists by store.lists.collectAsState()
    LaunchedEffect(store, localVersion) { store.loadFirst(key, force = true) }
    return lists[key]?.items.orEmpty()
}

/** 앱11 D: the photos of a text note, small, under its text on a card ("+N" on the last when there are more). */
@Composable
internal fun MemoCardPhotos(controller: MemoController, p: MemoPreview.Photo, modifier: Modifier = Modifier, size: Int = 40) {
    val cells = p.cells.take(MEMO_CARD_PHOTOS)
    val hidden = memoHiddenPhotos(p)
    Row(modifier.testTag(MemoA11Tags.CARD_PHOTOS), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        cells.forEachIndexed { i, a ->
            // In the selection mode a tap on a photo selects the card (no viewer).
            val open = if (controller.select.active) Modifier else Modifier.memoOpenPhoto(controller, p.cells, i)
            Box(Modifier.size(size.dp).clip(RoundedCornerShape(4.dp)).then(open)) {
                MemoThumbnail(a.mxc, a.fileJson, Modifier.fillMaxSize(), a.localPath)
                if (i == cells.lastIndex && hidden > 0) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Text("+$hidden", color = Color.White, style = ElementTheme.typography.fontBodyXsRegular)
                    }
                }
            }
        }
    }
}

/** 앱11 C: the check mark in the corner of a selected card. */
@Composable
internal fun MemoSelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    Text(
        if (selected) "✔" else "○",
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ElementTheme.colors.bgActionPrimaryRest else ElementTheme.colors.bgCanvasDefault)
            .padding(horizontal = 6.dp)
            .testTag(MemoA11Tags.SELECT_MARK),
        color = if (selected) ElementTheme.colors.textOnSolidPrimary else ElementTheme.colors.textSecondary,
        style = ElementTheme.typography.fontBodySmMedium,
    )
}

/** The border of a card in the selection mode: thick and in the accent colour when selected. */
@Composable
internal fun memoCardBorder(controller: MemoController, id: String): Pair<Dp, Color> =
    if (controller.select.isSelected(id)) 2.dp to ElementTheme.colors.textActionAccent else 1.dp to ElementTheme.colors.borderInteractiveSecondary

/**
 * 앱11 C: the bar of the selection mode, above the tabs: the count, select all / none, end, and the three bulk
 * actions. [visible] = the ids of the notes the current screen shows ("전체 선택").
 */
@Composable
internal fun MemoSelectBar(controller: MemoController, visible: ImmutableList<String>) {
    val s = controller.select
    var askTrash by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtleSecondary).testTag(MemoA11Tags.SELECT_BAR)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${s.count}개 선택", modifier = Modifier.weight(1f).padding(4.dp), style = ElementTheme.typography.fontBodyMdMedium)
            MemoChip("전체 선택", accent = true, onClick = { controller.select = s.all(visible) })
            MemoChip("선택 해제", onClick = { controller.select = s.none() })
            MemoChip("종료", onClick = { controller.endSelect() })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val on = s.count > 0
            listOf<Pair<String, () -> Unit>>(
                "🗑 휴지통" to { askTrash = true },
                "# 태그" to { controller.bulkTags() },
                "📁 폴더 이동" to { controller.bulkMove() },
            ).forEach { (label, action) ->
                Text(
                    label,
                    modifier = Modifier.clickable(enabled = on, onClick = action).padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (on) ElementTheme.colors.textActionAccent else ElementTheme.colors.textDisabled,
                    style = ElementTheme.typography.fontBodyMdMedium,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
    if (askTrash) {
        ConfirmationDialog(
            content = "선택한 ${s.count}개를 휴지통으로 옮길까요? 휴지통에서 되살릴 수 있어요.",
            submitText = "휴지통으로",
            cancelText = "취소",
            onSubmitClick = {
                askTrash = false
                controller.bulkTrash()
            },
            onDismiss = { askTrash = false },
        )
    }
}

/**
 * 추가요청 2: a horizontal swipe anywhere on the tab goes to the next / previous tab. It looks at the gesture after
 * the children ([PointerEventPass.Main] runs child first): a list scrolling or a row scrolling sideways takes the move
 * first and the tab stays. Once the move is clearly sideways and long enough, it is taken here (consumed) so the
 * card under the finger does not open on release.
 */
internal fun Modifier.memoTabSwipe(enabled: Boolean, onSwipe: (Int) -> Unit): Modifier = if (!enabled) {
    this
} else {
    pointerInput(Unit) {
        val edge = 24.dp.toPx()
        val min = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            var byChild = false
            var claimed = false
            var direction = 0
            var pressed = true
            while (pressed) {
                val c = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
                pressed = c?.pressed == true
                if (c != null) {
                    if (c.isConsumed && !claimed) byChild = true
                    val dx = c.position.x - down.position.x
                    val dy = c.position.y - down.position.y
                    direction = memoSwipeDirection(down.position.x, dx, dy, size.width.toFloat(), edge, min, byChild)
                    if (direction != 0) claimed = true
                    if (claimed) c.consume()
                }
            }
            if (claimed && direction != 0) onSwipe(direction)
        }
    }
}
