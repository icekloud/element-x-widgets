/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Element June: the Korean reading of a line, used by the built-in voice. Rules that are not sure leave the text as it is.
 */
class JuneKoreanSpeechTextTest {
    @Test
    fun `a clock time is read with the native hour and the sino minute`() {
        assertThat(juneKoreanSpeechText("3시 20분에 회의가 있습니다")).isEqualTo("세 시 이십 분에 회의가 있습니다")
        assertThat(juneKoreanSpeechText("1시 5분")).isEqualTo("한 시 오 분")
        assertThat(juneKoreanSpeechText("12시 정각")).isEqualTo("열두 시 정각")
    }

    @Test
    fun `an hour after twelve is read with the sino number`() {
        assertThat(juneKoreanSpeechText("15시 30분")).isEqualTo("십오 시 삼십 분")
        assertThat(juneKoreanSpeechText("20시")).isEqualTo("이십 시")
    }

    @Test
    fun `a duration in minutes or hours is read with the sino number`() {
        assertThat(juneKoreanSpeechText("5분 뒤에 알려드립니다")).isEqualTo("오 분 뒤에 알려드립니다")
        assertThat(juneKoreanSpeechText("3시간 걸립니다")).isEqualTo("세 시간 걸립니다")
    }

    @Test
    fun `a date written with the month and the day is read in Korean`() {
        assertThat(juneKoreanSpeechText("10월 24일 회의")).isEqualTo("시월 이십사 일 회의")
        assertThat(juneKoreanSpeechText("6월 1일")).isEqualTo("유월 일 일")
        assertThat(juneKoreanSpeechText("11월 3일")).isEqualTo("십일월 삼 일")
    }

    @Test
    fun `a date written with a slash is read as a date only in the month and day range`() {
        assertThat(juneKoreanSpeechText("10/24 회의")).isEqualTo("시월 이십사 일 회의")
        // 3/4 can be three quarters: left as it is
        assertThat(juneKoreanSpeechText("3/4 지점")).isEqualTo("3/4 지점")
        assertThat(juneKoreanSpeechText("13/40")).isEqualTo("13/40")
    }

    @Test
    fun `a percentage is read in Korean`() {
        assertThat(juneKoreanSpeechText("비가 올 확률은 20%입니다")).isEqualTo("비가 올 확률은 이십 퍼센트입니다")
        assertThat(juneKoreanSpeechText("배터리 7%")).isEqualTo("배터리 칠 퍼센트")
    }

    @Test
    fun `an amount of won with thousand separators is read in Korean`() {
        assertThat(juneKoreanSpeechText("15,000원입니다")).isEqualTo("만 오천 원입니다")
        assertThat(juneKoreanSpeechText("1,250,000원")).isEqualTo("백이십오만 원")
        assertThat(juneKoreanSpeechText("3000원")).isEqualTo("삼천 원")
    }

    @Test
    fun `a phone number is read digit by digit`() {
        assertThat(juneKoreanSpeechText("010-1234-5678로 연락주세요")).isEqualTo("공일공 일이삼사 오육칠팔로 연락주세요")
        assertThat(juneKoreanSpeechText("02-123-4567")).isEqualTo("공이 일이삼 사오육칠")
    }

    @Test
    fun `a counted noun is read with the native number`() {
        assertThat(juneKoreanSpeechText("제안 3건이 왔습니다")).isEqualTo("제안 세 건이 왔습니다")
        assertThat(juneKoreanSpeechText("사진 2장")).isEqualTo("사진 두 장")
        assertThat(juneKoreanSpeechText("20개")).isEqualTo("스무 개")
    }

    @Test
    fun `a number followed by beon is read with the sino number`() {
        assertThat(juneKoreanSpeechText("GitHub PR 35번")).isEqualTo("GitHub 피 아르 삼십오 번")
        assertThat(juneKoreanSpeechText("1번 항목")).isEqualTo("일 번 항목")
    }

    @Test
    fun `a plain integer is read in Korean`() {
        assertThat(juneKoreanSpeechText("1234")).isEqualTo("천이백삼십사")
        assertThat(juneKoreanSpeechText("100")).isEqualTo("백")
        assertThat(juneKoreanSpeechText("0")).isEqualTo("영")
    }

    @Test
    fun `a decimal number is read with jeom`() {
        assertThat(juneKoreanSpeechText("3.5도")).isEqualTo("삼 점 오 도")
        assertThat(juneKoreanSpeechText("0.25")).isEqualTo("영 점 이오")
    }

    @Test
    fun `a number too large to read is left as it is`() {
        assertThat(juneKoreanSpeechText("12345678901234")).isEqualTo("12345678901234")
    }

    @Test
    fun `an upper case abbreviation is spelled out`() {
        assertThat(juneKoreanSpeechText("CI가 통과했습니다")).isEqualTo("씨 아이가 통과했습니다")
        assertThat(juneKoreanSpeechText("APK 빌드")).isEqualTo("에이 피 케이 빌드")
    }

    @Test
    fun `an ordinary english word is left as it is`() {
        assertThat(juneKoreanSpeechText("Element 앱")).isEqualTo("Element 앱")
        assertThat(juneKoreanSpeechText("good morning")).isEqualTo("good morning")
    }

    @Test
    fun `emojis are removed`() {
        assertThat(juneKoreanSpeechText("좋은 아침입니다 🙂")).isEqualTo("좋은 아침입니다")
        assertThat(juneKoreanSpeechText("📍 롬 시어터 남쪽")).isEqualTo("롬 시어터 남쪽")
    }

    @Test
    fun `markdown marks are removed`() {
        assertThat(juneKoreanSpeechText("**중요** 공지")).isEqualTo("중요 공지")
        assertThat(juneKoreanSpeechText("`코드` 확인")).isEqualTo("코드 확인")
        assertThat(juneKoreanSpeechText("# 제목")).isEqualTo("제목")
    }

    @Test
    fun `the spaces are cleaned up`() {
        assertThat(juneKoreanSpeechText("  좋은   아침  ")).isEqualTo("좋은 아침")
        assertThat(juneKoreanSpeechText("한 줄\n다음 줄")).isEqualTo("한 줄 다음 줄")
    }

    @Test
    fun `an empty or blank line gives an empty text`() {
        assertThat(juneKoreanSpeechText("")).isEmpty()
        assertThat(juneKoreanSpeechText("   ")).isEmpty()
        assertThat(juneKoreanSpeechText("🙂")).isEmpty()
    }

    @Test
    fun `a sentence with several kinds of numbers is read in Korean`() {
        assertThat(juneKoreanSpeechText("오후 3시 20분에 15,000원을 20% 할인합니다"))
            .isEqualTo("오후 세 시 이십 분에 만 오천 원을 이십 퍼센트 할인합니다")
    }

    @Test
    fun `a text without any number is left as it is`() {
        val text = "좋은 아침입니다. 오늘도 좋은 하루 되세요."
        assertThat(juneKoreanSpeechText(text)).isEqualTo(text)
    }

    @Test
    fun `a number inside a word is left as it is`() {
        assertThat(juneKoreanSpeechText("abc123def")).isEqualTo("abc123def")
        assertThat(juneKoreanSpeechText("v2 화면")).isEqualTo("v2 화면")
    }

    @Test
    fun `a year is read in Korean`() {
        assertThat(juneKoreanSpeechText("2026년")).isEqualTo("이천이십육 년")
    }

    @Test
    fun `a temperature and a unit are read in Korean`() {
        assertThat(juneKoreanSpeechText("기온 18도")).isEqualTo("기온 십팔 도")
        assertThat(juneKoreanSpeechText("5km 남았습니다")).isEqualTo("오 킬로미터 남았습니다")
    }
}
