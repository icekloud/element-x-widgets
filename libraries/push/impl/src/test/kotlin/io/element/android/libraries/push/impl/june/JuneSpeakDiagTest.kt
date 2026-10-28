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
 * 앱34: the diagnosis shown under the setting, written without Android so it can be checked here. What the phone answers
 * (the focus code, the importance of the process, the screen, the music) can only be seen on a device.
 */
class JuneSpeakDiagTest {
    private val phone = JunePhoneState(appState = JuneAppState.Background, screenOn = false, musicActive = true)

    @Test
    fun `the diagnosis of a reading without the focus says the code, the tries, the app, the screen, the music and the times`() {
        val diag = JuneSpeakDiag(
            focus = JuneFocusOutcome.Failed,
            focusAttempts = 3,
            phone = phone,
            pushToSpeakMillis = 1_234,
            engineOpenMillis = 600,
        )
        assertThat(juneSpeakDiagLines(diag)).containsExactly(
            "포커스 FAILED 3회 · 앱 뒤 · 화면 꺼짐 · 음악 재생",
            "도착→읽기 1.2초 · 엔진 0.6초",
        ).inOrder()
    }

    @Test
    fun `a reading with the focus, the screen on and no music`() {
        val diag = JuneSpeakDiag(
            focus = JuneFocusOutcome.Granted,
            focusAttempts = 1,
            phone = JunePhoneState(appState = JuneAppState.Top, screenOn = true, musicActive = false),
            pushToSpeakMillis = 900,
            engineOpenMillis = 0,
        )
        assertThat(juneSpeakDiagLines(diag)).containsExactly(
            "포커스 GRANTED 1회 · 앱 앞 · 화면 켜짐 · 음악 없음",
            "도착→읽기 0.9초 · 엔진 0.0초",
        ).inOrder()
    }

    @Test
    fun `what could not be read is left out`() {
        assertThat(juneSpeakDiagLines(null)).isEmpty()
        assertThat(juneSpeakDiagLines(JuneSpeakDiag(focus = JuneFocusOutcome.NotAsked))).isEmpty()
        assertThat(juneSpeakDiagLines(JuneSpeakDiag(focus = JuneFocusOutcome.Unknown, focusAttempts = 2)))
            .containsExactly("포커스 확인 불가 2회")
        assertThat(juneSpeakDiagLines(JuneSpeakDiag(focus = JuneFocusOutcome.NotAsked, engineOpenMillis = 1_500)))
            .containsExactly("엔진 1.5초")
    }

    @Test
    fun `a time is never negative, in case the clock moved`() {
        assertThat(juneSpeakDiagLines(JuneSpeakDiag(focus = JuneFocusOutcome.NotAsked, pushToSpeakMillis = -5_000)))
            .containsExactly("도착→읽기 0.0초")
    }

    @Test
    fun `the whole status keeps the old first line and adds the diagnosis under it`() {
        val diag = JuneSpeakDiag(focus = JuneFocusOutcome.Delayed, focusAttempts = 3, phone = phone, engineOpenMillis = 400)
        val text = juneSpeakStatusText(JuneSpeakResult.ReadNoFocus, epochMillis = 0, diag = diag)
        val lines = text.split("\n")
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).startsWith("읽음(포커스 없이) ")
        assertThat(lines[1]).isEqualTo("포커스 DELAYED 3회 · 앱 뒤 · 화면 꺼짐 · 음악 재생")
        assertThat(lines[2]).isEqualTo("엔진 0.4초")
    }

    @Test
    fun `a status without diagnosis is the one line of the earlier versions`() {
        val text = juneSpeakStatusText(JuneSpeakResult.Read, epochMillis = 0, diag = null)
        assertThat(text).isEqualTo(juneSpeakResultText(JuneSpeakResult.Read, 0))
        assertThat(text).doesNotContain("\n")
    }

    @Test
    fun `reading without the focus and reading with it are told apart`() {
        assertThat(JuneSpeakResult.ReadNoFocus.label).isEqualTo("읽음(포커스 없이)")
        assertThat(JuneSpeakResult.Read.label).isEqualTo("읽음")
        // 앱34 does not record it anymore, but a value saved by an older version still reads the same way
        assertThat(JuneSpeakResult.FocusRefused.label).isEqualTo("포커스 거부")
    }

    @Test
    fun `the importance of the process is turned into a state of the app`() {
        assertThat(juneAppStateOf(0)).isEqualTo(JuneAppState.Unknown)
        assertThat(juneAppStateOf(100)).isEqualTo(JuneAppState.Top)
        assertThat(juneAppStateOf(125)).isEqualTo(JuneAppState.ForegroundService)
        assertThat(juneAppStateOf(200)).isEqualTo(JuneAppState.Visible)
        assertThat(juneAppStateOf(230)).isEqualTo(JuneAppState.Perceptible)
        assertThat(juneAppStateOf(300)).isEqualTo(JuneAppState.Background)
        assertThat(juneAppStateOf(400)).isEqualTo(JuneAppState.Cached)
        assertThat(juneAppStateOf(1_000)).isEqualTo(JuneAppState.Cached)
    }

    @Test
    fun `an arrival is given back once and only to the right event`() {
        val log = JunePushArrivalLog()
        log.mark("a", 1_000)
        log.mark("b", 2_000)
        assertThat(log.take("a")).isEqualTo(1_000)
        assertThat(log.take("a")).isNull()
        assertThat(log.take("c")).isNull()
        assertThat(log.take("b")).isEqualTo(2_000)
        assertThat(log.size).isEqualTo(0)
    }

    @Test
    fun `the arrival log does not grow, the oldest ones are dropped`() {
        val log = JunePushArrivalLog(maxSize = 2)
        log.mark("a", 1)
        log.mark("b", 2)
        log.mark("c", 3)
        assertThat(log.size).isEqualTo(2)
        assertThat(log.take("a")).isNull()
        assertThat(log.take("b")).isEqualTo(2)
        assertThat(log.take("c")).isEqualTo(3)
    }

    @Test
    fun `the last arrival of an event is the one kept`() {
        val log = JunePushArrivalLog()
        log.mark("a", 1)
        log.mark("a", 5)
        assertThat(log.size).isEqualTo(1)
        assertThat(log.take("a")).isEqualTo(5)
    }
}
