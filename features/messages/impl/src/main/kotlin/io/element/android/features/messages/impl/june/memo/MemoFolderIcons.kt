/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.june.memo

/**
 * 앱14 A·B·C: the folder icons, in one place. Every screen draws the icon [memoFolderIcon] works out:
 * ① the icon picked on this phone or ② a non empty icon from the server (both are the stored value), then
 * ③ the built-in folder table, ④ the name rules, ⑤ the plain folder. ③ to ⑤ are worked out when shown and never
 * written to the database, so a change of the table shows at once.
 */
internal const val MEMO_FOLDER_ICON_DEFAULT = "📁"

/** The local op that sets the icon of one folder (empty = back to the default). It never goes to the server. */
internal const val MEMO_OP_FOLDER_ICON = "folder_icon"

/** The built-in folders (server `BUILTIN_FOLDERS` + archive and trash), created with the database. */
internal val MEMO_BUILTIN_FOLDERS = listOf(
    MEMO_INBOX to "📥",
    "여행" to "✈️",
    "쇼핑·구매" to "🛒",
    "아이디어" to "💡",
    "집·생활" to "🏠",
    "일·공부" to "📚",
    "사람" to "👤",
    "링크보관" to "🔗",
    MEMO_ARCHIVE to "📦",
    MEMO_TRASH to "🗑",
)

private val MEMO_BUILTIN_ICONS = MEMO_BUILTIN_FOLDERS.toMap()

/**
 * 앱14 B: words of a folder name → icon. The first line with a matching word wins (so 아이디어 comes before 아이).
 * A longer word matches anywhere in the name (lower case); a short word — one Korean letter (책, 돈, 차, 약, 점) or
 * three latin letters at most (cat, car, gym) — only matches a whole part of the name (split on spaces and ·), so
 * 점심, 약속 or education do not get 🔮, 💊 or 🐾.
 */
internal val MEMO_FOLDER_ICON_RULES: List<Pair<String, List<String>>> = listOf(
    "💡" to listOf("아이디어", "영감", "idea"),
    "💪" to listOf("운동", "헬스", "피트니스", "요가", "필라테스", "러닝", "gym", "workout", "fitness"),
    "🍽" to listOf("식단", "음식", "맛집", "요리", "레시피", "식당", "카페", "디저트", "아이스크림", "food", "recipe"),
    "🏥" to listOf("건강", "병원", "약국", "처방", "진료", "health"),
    "💊" to listOf("약", "영양제"),
    "💰" to listOf("가계", "재테크", "투자", "주식", "금융", "예산", "지출", "저축", "월급", "돈", "money", "finance"),
    "📖" to listOf("독서", "읽을거리", "책", "book"),
    "🎬" to listOf("영화", "드라마", "넷플릭스", "movie"),
    "🎵" to listOf("음악", "노래", "playlist", "music"),
    "🚗" to listOf("자동차", "차량", "주차", "차", "car"),
    "💼" to listOf("업무", "회의", "회사", "직장", "프로젝트", "work", "meeting"),
    "🧒" to listOf("육아", "아이", "아기", "유치원", "학교", "kid"),
    "🐾" to listOf("반려", "강아지", "고양이", "펫", "pet", "dog", "cat"),
    "🎨" to listOf("취미", "그림", "공예", "drawing", "hobby"),
    "🔮" to listOf("운세", "사주", "타로", "점"),
    "✈️" to listOf("여행", "출장", "trip", "travel"),
    "🛒" to listOf("쇼핑", "구매", "장보기", "살것", "살 것", "shopping"),
    "🏠" to listOf("집", "생활", "살림", "인테리어", "home"),
    "📚" to listOf("공부", "학습", "강의", "시험", "자격증", "study"),
    "👤" to listOf("사람", "인물", "연락처", "지인", "people"),
    "🔗" to listOf("링크", "북마크", "link"),
    "📅" to listOf("일정", "약속", "계획", "캘린더", "schedule"),
    "✅" to listOf("할일", "할 일", "todo", "체크리스트"),
    "📝" to listOf("메모", "기록", "일기", "노트", "journal"),
    "📸" to listOf("사진", "앨범", "photo"),
    "🎮" to listOf("게임", "game"),
    "⚽" to listOf("축구", "야구", "농구", "스포츠", "sport"),
    "🌱" to listOf("식물", "정원", "화분"),
    "🎁" to listOf("선물", "기념일", "생일", "gift"),
    "💻" to listOf("개발", "코딩", "프로그래밍", "컴퓨터", "code"),
    "📰" to listOf("뉴스", "기사", "news"),
    "🧾" to listOf("영수증", "세금", "보험", "서류", "계약"),
    "🏃" to listOf("산책", "등산", "걷기", "하이킹"),
    "☕" to listOf("커피", "coffee"),
)

/** 앱14 C: the icons offered when the user picks one (no 📁: that is the plain default). */
internal val MEMO_FOLDER_ICON_CHOICES = listOf(
    "📥", "📦", "🗑", "✈️", "🛒", "💡", "🏠", "📚", "👤", "🔗",
    "💪", "🍽", "🏥", "💰", "📖", "🎬", "🎵", "🚗", "💼", "🧒",
    "🐾", "🎨", "🔮", "📅", "✅", "📝", "📸", "🎮", "⚽", "🌱",
    "🎁", "💻", "📰", "🧾", "💊", "🏃", "☕", "📂", "🗂", "⭐",
    "❤️", "🔥", "🌈", "🎯", "🧳", "🏖", "🍳", "🍰", "🍷", "🎓",
    "🔧", "🧘", "🚴", "🎤", "🧸", "🌙", "☀️", "🌸", "🏡", "🔒",
)

/** ④ The icon the name rules give one folder name (the last part of a path), null when no rule matches. */
internal fun memoRuleFolderIcon(name: String): String? {
    val text = name.trim().lowercase()
    if (text.isEmpty()) return null
    val parts = text.split(' ', '·', '_', '-', ',').filter { it.isNotEmpty() }.toSet()
    val hit = MEMO_FOLDER_ICON_RULES.firstOrNull { (_, words) -> words.any { w -> if (memoIsShortWord(w)) w in parts else w in text } }
    return hit?.first
}

private fun memoIsShortWord(word: String): Boolean {
    if (word.length == 1) return true
    return word.length <= MEMO_SHORT_LATIN_WORD && word.all { it in 'a'..'z' }
}

/** Latin words of this many letters or fewer (cat, car, gym) only match a whole part of a folder name. */
private const val MEMO_SHORT_LATIN_WORD = 3

/**
 * ③ to ⑤: the icon of [path] when nothing is stored. A sub folder goes by its last name; the folders the system
 * makes under the trash or the archive (`휴지통/태그복구`) take the icon of that top folder.
 */
internal fun memoAutoFolderIcon(path: String): String {
    val clean = path.trim().trim('/')
    MEMO_BUILTIN_ICONS[clean]?.let { return it }
    val top = clean.substringBefore('/')
    if ('/' in clean && (top == MEMO_TRASH || top == MEMO_ARCHIVE)) return MEMO_BUILTIN_ICONS.getValue(top)
    return memoRuleFolderIcon(clean.substringAfterLast('/')) ?: MEMO_FOLDER_ICON_DEFAULT
}

/**
 * The icon drawn for [path]. [stored] is what the database or the server holds: kept when it is not empty. A stored
 * 📁 counts as empty, because up to 앱13 a folder made on the phone stored it as its default.
 */
internal fun memoFolderIcon(path: String, stored: String?): String =
    stored?.trim()?.takeIf { it.isNotEmpty() && it != MEMO_FOLDER_ICON_DEFAULT } ?: memoAutoFolderIcon(path)

/**
 * 앱14 C: the icon kept when the server's folder list comes in. ① one picked here and not taken by the server yet,
 * ② the server's own icon when it sent one, ③ what the phone held (an empty server icon wipes nothing).
 */
internal fun memoMergedFolderIcon(server: String?, picked: String?, stored: String?): String {
    val fromServer = server?.trim().orEmpty()
    return picked ?: fromServer.ifEmpty { stored.orEmpty() }
}

/** The icon of [path] from the folder list (resolved there), or worked out when the list does not have it. */
internal fun memoFolderIconIn(index: MemoIndex, path: String): String = index.folder(path)?.icon ?: memoAutoFolderIcon(path)

/** `💪 운동`: a folder with its icon, for the chips and lines that show a note's folder. */
internal fun memoFolderLabel(index: MemoIndex, path: String): String = memoFolderIconIn(index, path) + " " + path

/** 앱14 C: sets the icon of [path]; an empty [icon] goes back to the default. Answered by the local database only. */
internal fun memoFolderIconOp(path: String, icon: String) = MemoOp(MEMO_OP_FOLDER_ICON, linkedMapOf("path" to path, "icon" to icon))

/**
 * The icon an op may store: one of [MEMO_FOLDER_ICON_CHOICES]; nothing or blank = empty (back to the default); null for
 * anything else (the op is refused, nothing is wiped).
 */
internal fun memoChosenFolderIcon(value: Any?): String? {
    val icon = (value as? String)?.trim().orEmpty()
    return when {
        icon.isEmpty() -> ""
        icon in MEMO_FOLDER_ICON_CHOICES -> icon
        else -> null
    }
}
