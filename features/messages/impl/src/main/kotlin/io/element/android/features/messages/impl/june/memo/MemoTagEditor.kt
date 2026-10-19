/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalLayoutApi::class)

package io.element.android.features.messages.impl.june.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import kotlinx.collections.immutable.ImmutableList

/**
 * 앱19 ② (관리자: "태그편집시 … 하나의 묶음형태로 바꾸고 묶음 왼쪽 위에 작은 x표를 눌러 태그를 클릭한번으로 제거"): the body of
 * the tag sheet. Every tag of [tags] is a chip from the start; a press on the small ✕ at its top left corner takes it
 * away at once (the chip itself does nothing). The input beside the chips turns what was typed into chips when a `#`
 * follows a space ([memoTagChipSplit]); what is still in it when 저장 is pressed is split as before
 * ([memoTagSheetTags]). [known] tags not there yet can be picked below (one press adds a chip).
 */
@Composable
internal fun MemoTagEditor(
    title: String,
    tags: ImmutableList<String>,
    known: ImmutableList<String>,
    onSave: (List<String>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chips by remember(tags) { mutableStateOf(memoTagChipsAdd(emptyList(), tags)) }
    var field by remember(tags) { mutableStateOf(TextFieldValue("")) }
    Column(modifier.padding(16.dp)) {
        Text(title, style = ElementTheme.typography.fontHeadingSmMedium)
        Text("띄어쓰기로 구분합니다. #은 없어도 됩니다.", style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
        FlowRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            chips.forEach { tag -> MemoTagChip(tag, onRemove = { chips = chips - tag }) }
        }
        TextField(
            value = field,
            onValueChange = { value ->
                // A Hangul syllable still being composed before the `#` is left alone (the split waits for it).
                val split = memoTagChipSplit(value.text, value.composition?.start)
                field = if (split.cut == 0) {
                    value
                } else {
                    chips = memoTagChipsAdd(chips, split.tags)
                    val end = split.rest.length
                    TextFieldValue(
                        split.rest,
                        TextRange((value.selection.start - split.cut).coerceIn(0, end), (value.selection.end - split.cut).coerceIn(0, end)),
                        value.composition?.let { TextRange((it.start - split.cut).coerceIn(0, end), (it.end - split.cut).coerceIn(0, end)) },
                    )
                }
            },
            placeholder = "#태그",
            modifier = Modifier.fillMaxWidth().testTag(MemoTags.TAG_INPUT),
        )
        // 앱11 C: the tags there are, to pick (more than one); a tap adds a chip.
        val typed = memoParseTagInput(field.text)
        FlowRow(modifier = Modifier.padding(top = 8.dp)) {
            known.filter { it !in chips && it !in typed }.take(MEMO_PICK_TAGS).forEach { name ->
                MemoChip("#$name", onClick = { chips = memoTagChipsAdd(chips, listOf(name)) })
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Text("취소", modifier = Modifier.clickable(onClick = onCancel).padding(12.dp))
            Text(
                "저장",
                color = ElementTheme.colors.textActionAccent,
                modifier = Modifier.clickable { onSave(memoTagSheetTags(chips, field.text)) }.padding(12.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 앱23 (관리자: 편집 화면의 태그 칸도 시트와 같은 칩으로): the tag line of the note editor. The same chips, the same ✕
 * and the same `#`-after-a-space rule as the sheet above — only the draft keeps one string ([memoEditTagsText],
 * 결정기록 8), so the auto save and 취소 are untouched. Known tags not there yet can be picked beside it.
 */
@Composable
internal fun MemoEditTagChips(
    noteId: String,
    tags: String,
    known: ImmutableList<String>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The note's own tags are chips from the start; what is typed next waits in the input.
    var chips by remember(noteId) { mutableStateOf(memoParseTagInput(tags)) }
    var field by remember(noteId) { mutableStateOf(TextFieldValue("")) }
    fun write(newChips: List<String>, rest: String) {
        chips = newChips
        onChange(memoEditTagsText(newChips, rest))
    }
    Column(modifier) {
        FlowRow(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            chips.forEach { tag -> MemoTagChip(tag, onRemove = { write(chips - tag, field.text) }) }
        }
        TextField(
            value = field,
            onValueChange = { value ->
                val split = memoTagChipSplit(value.text, value.composition?.start)
                field = if (split.cut == 0) {
                    write(chips, value.text)
                    value
                } else {
                    val end = split.rest.length
                    write(memoTagChipsAdd(chips, split.tags), split.rest)
                    TextFieldValue(
                        split.rest,
                        TextRange((value.selection.start - split.cut).coerceIn(0, end), (value.selection.end - split.cut).coerceIn(0, end)),
                        value.composition?.let { TextRange((it.start - split.cut).coerceIn(0, end), (it.end - split.cut).coerceIn(0, end)) },
                    )
                }
            },
            placeholder = "태그 (#교토 #빵집)",
            modifier = Modifier.fillMaxWidth().testTag(MemoTags.EDIT_TAGS),
        )
        val typed = memoParseTagInput(field.text)
        FlowRow {
            known.filter { it !in chips && it !in typed }.take(MEMO_PICK_TAGS).forEach { name ->
                MemoChip("#$name", onClick = { write(memoTagChipsAdd(chips, listOf(name)), field.text) })
            }
        }
    }
}

/** One tag of the sheet: `#이름` in a rounded box, the small ✕ over its top left corner removes it. */
@Composable
private fun MemoTagChip(tag: String, onRemove: () -> Unit) {
    Box(Modifier.padding(end = 4.dp, bottom = 4.dp).testTag(MemoTags.TAG_CHIP)) {
        Text(
            text = "#$tag",
            modifier = Modifier
                .padding(start = MEMO_TAG_X_DP.dp / 2, top = MEMO_TAG_X_DP.dp / 2)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, ElementTheme.colors.textActionAccent, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textActionAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The touch area is larger than the little circle drawn in it.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(MEMO_TAG_X_DP.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = memoTagRemoveLabel(tag) }
                .testTag(MemoTags.TAG_CHIP_REMOVE),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(ElementTheme.colors.iconSecondary), contentAlignment = Alignment.Center) {
                Text("✕", style = ElementTheme.typography.fontBodyXsMedium, color = ElementTheme.colors.textOnSolidPrimary)
            }
        }
    }
}

/** The touch box of the ✕ of a chip; the chip starts at its middle, so the ✕ sits on the chip's corner. */
private const val MEMO_TAG_X_DP = 22

/** Known tags the tag sheet offers to pick (앱11 C). */
internal const val MEMO_PICK_TAGS = 30
