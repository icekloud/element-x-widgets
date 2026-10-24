/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

/**
 * Element June: writes a line the way it is read in Korean, before giving it to the built-in voice.
 *
 * The model reads Hangul well but is weak on digits and Latin letters, so numbers, times, dates, amounts, phone numbers, units and
 * upper case abbreviations are written out in Hangul. A pattern that is not sure (an ambiguous "3/4", a number too large to read) is
 * left as it is: reading it wrong is worse than leaving it.
 */

/** Emojis, variation selectors and flags: the model cannot read them. */
private val EMOJI = Regex(
    "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{FE0F}\\x{200D}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}]"
)

/** Markdown marks at the start of a line (heading, quote, list). */
private val MARKDOWN_LINE_START = Regex("(?m)^[ \\t]*[#>]+[ \\t]*")

/** Markdown marks inside a line. */
private val MARKDOWN_MARKS = Regex("[*`~]")

private val PHONE = Regex("(?<![\\d-])0\\d{1,2}-\\d{3,4}-\\d{4}(?![\\d-])")
private val DATE = Regex("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")
private val DATE_SLASH = Regex("(?<![\\d/.])(\\d{1,2})/(\\d{1,2})(?![\\d/])")
private val TIME = Regex("(?<![\\d.,])(\\d{1,2})\\s*시\\s*(\\d{1,2})\\s*분")
private val HOUR = Regex("(?<![\\d.,])(\\d{1,2})\\s*시(?!간)")
private val DECIMAL = Regex("(?<![\\d.,])(\\d+)\\.(\\d+)(?![\\d.])\\s*(도|배|초|분|원|퍼센트)?")
private val PERCENT = Regex("(?<![\\d.,])(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*%")
private val MONEY = Regex("(?<![\\d.,])(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*원")
private val MONTH = Regex("(?<![\\d.,])(\\d{1,2})\\s*월(?!일)")
private val UNIT = Regex(
    "(?<![A-Za-z\\d.,])(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*" +
        "(개월|번째|시간|건|장|개|명|살|마리|가지|대|병|잔|권|켤레|벌|번|년|일|분|초|도|층|호|주|km|cm|mm|kg|ml|m|g)(?![A-Za-z])"
)
private val NUMBER = Regex("(?<![A-Za-z\\d.,/])(\\d{1,3}(?:,\\d{3})+|\\d+)(?![A-Za-z\\d/])")
private val ABBREVIATION = Regex("(?<![A-Za-z])([A-Z]{2,5})(?![A-Za-z])")
private val SPACES = Regex("\\s+")

/** Units that are counted with the native Korean numbers ("세 건"), the other ones use the sino ones ("삼 번"). */
private val NATIVE_UNITS = setOf("번째", "시간", "건", "장", "개", "명", "살", "마리", "가지", "대", "병", "잔", "권", "켤레", "벌")

/** Units written with Latin letters, and how they are read. */
private val LATIN_UNITS = mapOf(
    "km" to "킬로미터",
    "cm" to "센티미터",
    "mm" to "밀리미터",
    "kg" to "킬로그램",
    "ml" to "밀리리터",
    "m" to "미터",
    "g" to "그램",
)

private val SINO_DIGITS = listOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
private val PHONE_DIGITS = listOf("공", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
private val MONTH_NAMES = listOf("", "일월", "이월", "삼월", "사월", "오월", "유월", "칠월", "팔월", "구월", "시월", "십일월", "십이월")

/** Native numbers before a counted noun: 1 is "한", not "하나". */
private val NATIVE_ONES = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
private val NATIVE_TENS = listOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")

/** Hours are counted with the native numbers up to twelve ("열두 시"), then with the sino ones ("십오 시"). */
private val NATIVE_HOURS = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "열한", "열두")

private val GROUP_NAMES = listOf("", "만", "억", "조")

/** Longest number that is read aloud: above it the reading would be long and easy to get wrong, so the digits are left as they are. */
private const val JUNE_MAX_NUMBER_DIGITS = 12

/**
 * Element June: [text] written the way it is read in Korean. Pure function, see [JuneKoreanSpeechTextTest] for what is changed.
 */
internal fun juneKoreanSpeechText(text: String): String {
    var result = EMOJI.replace(text, "")
    result = MARKDOWN_LINE_START.replace(result, "")
    result = MARKDOWN_MARKS.replace(result, "")
    result = PHONE.replace(result) { match -> readPhone(match.value) }
    result = DATE.replace(result) { match -> readDate(match, month = match.groupValues[1], day = match.groupValues[2]) }
    result = DATE_SLASH.replace(result) { match -> readSlashDate(match) }
    result = TIME.replace(result) { match -> readTime(match) }
    result = HOUR.replace(result) { match -> readHour(match) }
    result = DECIMAL.replace(result) { match -> readDecimal(match) }
    result = PERCENT.replace(result) { match -> suffixed(match, match.groupValues[1], "퍼센트") }
    result = MONEY.replace(result) { match -> suffixed(match, match.groupValues[1], "원") }
    result = MONTH.replace(result) { match -> readMonth(match) }
    result = UNIT.replace(result) { match -> readUnit(match) }
    result = NUMBER.replace(result) { match -> sinoNumber(match.groupValues[1]) ?: match.value }
    result = ABBREVIATION.replace(result) { match -> readAbbreviation(match.groupValues[1]) }
    return SPACES.replace(result, " ").trim()
}

/** "010-1234-5678" is read digit by digit, with a pause between the groups. */
private fun readPhone(value: String): String = value.split('-')
    .joinToString(" ") { group -> group.map { digit -> PHONE_DIGITS[digit - '0'] }.joinToString("") }

private fun readDate(match: MatchResult, month: String, day: String): String {
    val monthValue = month.toIntOrNull() ?: return match.value
    val dayValue = day.toIntOrNull() ?: return match.value
    if (monthValue !in 1..12 || dayValue !in 1..31) return match.value
    val dayText = sinoNumber(day) ?: return match.value
    return "${MONTH_NAMES[monthValue]} $dayText 일"
}

/**
 * "10/24" is a date, but "3/4" can be three quarters: only a day that cannot be a month (13 and above) is read as a date, the rest is
 * left as it is.
 */
private fun readSlashDate(match: MatchResult): String {
    val day = match.groupValues[2].toIntOrNull() ?: return match.value
    if (day < 13) return match.value
    return readDate(match, month = match.groupValues[1], day = match.groupValues[2])
}

private fun readTime(match: MatchResult): String {
    val hour = hourText(match.groupValues[1]) ?: return match.value
    val minute = sinoNumber(match.groupValues[2]) ?: return match.value
    return "$hour 시 $minute 분"
}

private fun readHour(match: MatchResult): String {
    val hour = hourText(match.groupValues[1]) ?: return match.value
    return "$hour 시"
}

private fun hourText(value: String): String? {
    val hour = value.toIntOrNull() ?: return null
    return if (hour in 1..12) NATIVE_HOURS[hour] else sinoNumber(value)
}

private fun readDecimal(match: MatchResult): String {
    val whole = sinoNumber(match.groupValues[1]) ?: return match.value
    val fraction = match.groupValues[2].map { digit -> SINO_DIGITS[digit - '0'] }.joinToString("")
    val unit = match.groupValues[3]
    return "$whole 점 $fraction" + if (unit.isEmpty()) "" else " $unit"
}

private fun readMonth(match: MatchResult): String {
    val month = match.groupValues[1].toIntOrNull() ?: return match.value
    if (month !in 1..12) return match.value
    return MONTH_NAMES[month]
}

private fun readUnit(match: MatchResult): String {
    val unit = match.groupValues[2]
    val unitText = LATIN_UNITS[unit] ?: unit
    val number = if (unit in NATIVE_UNITS) {
        nativeNumber(match.groupValues[1]) ?: sinoNumber(match.groupValues[1])
    } else {
        sinoNumber(match.groupValues[1])
    } ?: return match.value
    return "$number $unitText"
}

private fun suffixed(match: MatchResult, digits: String, unit: String): String {
    val number = sinoNumber(digits) ?: return match.value
    return "$number $unit"
}

/** "PR" is read letter by letter: the model reads Hangul, not the names of the Latin letters. */
private fun readAbbreviation(value: String): String = value.map { letter -> LETTER_NAMES[letter] ?: letter.toString() }.joinToString(" ")

private val LETTER_NAMES = mapOf(
    'A' to "에이", 'B' to "비", 'C' to "씨", 'D' to "디", 'E' to "이", 'F' to "에프", 'G' to "지",
    'H' to "에이치", 'I' to "아이", 'J' to "제이", 'K' to "케이", 'L' to "엘", 'M' to "엠",
    'N' to "엔", 'O' to "오", 'P' to "피", 'Q' to "큐", 'R' to "아르", 'S' to "에스", 'T' to "티",
    'U' to "유", 'V' to "브이", 'W' to "더블유", 'X' to "엑스", 'Y' to "와이", 'Z' to "지",
)

/**
 * The sino Korean reading of [digits] (the thousand separators are ignored), for instance "천이백삼십사" or "만 오천". Null when the
 * number is too large to be read: the digits are then left as they are.
 */
internal fun sinoNumber(digits: String): String? {
    val plain = digits.replace(",", "").trimStart('0').ifEmpty { "0" }
    if (plain.length > JUNE_MAX_NUMBER_DIGITS) return null
    val value = plain.toLongOrNull() ?: return null
    if (value == 0L) return "영"
    val groups = mutableListOf<String>()
    var rest = value
    var groupIndex = 0
    while (rest > 0) {
        val group = (rest % 10_000).toInt()
        if (group > 0) {
            val name = GROUP_NAMES[groupIndex]
            // 10000 is read "만", not "일만", while 100000000 stays "일억"
            val head = if (group == 1 && name == "만") "" else sinoGroup(group)
            groups += head + name
        }
        rest /= 10_000
        groupIndex++
    }
    return groups.reversed().joinToString(" ")
}

/** The reading of a group of at most four digits: 1234 is "천이백삼십사" (the leading "일" of 천, 백 and 십 is dropped). */
private fun sinoGroup(group: Int): String {
    val builder = StringBuilder()
    listOf(1000 to "천", 100 to "백", 10 to "십").forEach { (unit, name) ->
        val digit = group / unit % 10
        if (digit > 0) {
            if (digit > 1) builder.append(SINO_DIGITS[digit])
            builder.append(name)
        }
    }
    val ones = group % 10
    if (ones > 0) builder.append(SINO_DIGITS[ones])
    return builder.toString()
}

/** The native Korean reading of [digits] before a counted noun ("스무 개"), or null above 99 where the sino reading is used. */
internal fun nativeNumber(digits: String): String? {
    val value = digits.replace(",", "").toIntOrNull() ?: return null
    if (value !in 1..99) return null
    val tens = value / 10
    val ones = value % 10
    // Twenty alone is "스무" before a noun, twenty five is "스물다섯"
    val tensText = if (tens == 2 && ones == 0) "스무" else NATIVE_TENS[tens]
    return tensText + NATIVE_ONES[ones]
}
