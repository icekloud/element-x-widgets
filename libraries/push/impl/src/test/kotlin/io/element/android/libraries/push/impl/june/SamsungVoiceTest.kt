/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 앱30: the files of the Samsung voice, put where the engine looks for them, and the order of the calls getting it ready.
 *
 * The names below are the ones the engine builds itself (checked on its code): the folder is `<language>_<COUNTRY>_<variant><NN>`
 * and holds `cfg`, `lng` and the voice database. A wrong name here means the engine answers "음성 파일 없음" on the phone.
 */
class SamsungVoiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val assets = FakeSamsungVoiceAssets()

    @Test
    fun `the folder name of a voice is the one the engine looks for`() {
        assertThat(SAMSUNG_VOICE_KO_L08.folderName).isEqualTo("ko_KR_l08")
        assertThat(SamsungVoice("ko", "KR", "g", 1).folderName).isEqualTo("ko_KR_g01")
        assertThat(SamsungVoice("ko", "KR", "l", 10).folderName).isEqualTo("ko_KR_l10")
    }

    @Test
    fun `the voice is asked for with one letter of variant`() {
        // The engine refuses a variant that is not exactly one character (it answers 10)
        assertThat(SAMSUNG_VOICE_KO_L08.variant).hasLength(1)
        assertThat(SAMSUNG_VOICE_KO_L08.language).isEqualTo("ko")
        assertThat(SAMSUNG_VOICE_KO_L08.country).isEqualTo("KR")
    }

    @Test
    fun `the assets of a voice keep the names the engine needs`() {
        assertThat(samsungVoiceAssetPath(SAMSUNG_VOICE_KO_L08, "cfg")).isEqualTo("samsung_tts/ko_KR_l08/cfg.smt")
        assertThat(samsungVoiceAssetPath(SAMSUNG_VOICE_KO_L08, "regular.ivc")).isEqualTo("samsung_tts/ko_KR_l08/regular.ivc.smt")
    }

    @Test
    fun `installing puts the three files in the folder of the voice`() {
        val root = temporaryFolder.newFolder("files")
        val copied = samsungVoiceInstall(root, SAMSUNG_VOICE_KO_L08, assets)
        assertThat(copied).containsExactly("cfg", "lng", "regular.ivc").inOrder()
        val folder = File(root, "ko_KR_l08")
        assertThat(folder.list()?.toList()).containsExactly("cfg", "lng", "regular.ivc")
        assertThat(File(folder, "cfg").readBytes()).isEqualTo(assets.content("cfg"))
    }

    @Test
    fun `installing again copies nothing`() {
        val root = temporaryFolder.newFolder("files")
        samsungVoiceInstall(root, SAMSUNG_VOICE_KO_L08, assets)
        assertThat(samsungVoiceInstall(root, SAMSUNG_VOICE_KO_L08, assets)).isEmpty()
        assertThat(assets.opened).containsExactly("cfg", "lng", "regular.ivc")
    }

    @Test
    fun `a file cut in the middle is copied again`() {
        val root = temporaryFolder.newFolder("files")
        samsungVoiceInstall(root, SAMSUNG_VOICE_KO_L08, assets)
        File(root, "ko_KR_l08/lng").writeBytes(ByteArray(3))
        assertThat(samsungVoiceInstall(root, SAMSUNG_VOICE_KO_L08, assets)).containsExactly("lng")
        assertThat(File(root, "ko_KR_l08/lng").readBytes()).isEqualTo(assets.content("lng"))
    }

    @Test
    fun `a file that cannot be read says where it stopped`() {
        val root = temporaryFolder.newFolder("files")
        assets.failing = "lng"
        val error = runCatching { samsungVoiceInstall(root, SAMSUNG_VOICE_KO_L08, assets) }.exceptionOrNull()
        assertThat(error).isInstanceOf(JuneVoiceLoadException::class.java)
        assertThat((error as JuneVoiceLoadException).failure.reason).isEqualTo("복사 lng · IOException: broken asset")
        // The file is not left behind half written
        assertThat(File(root, "ko_KR_l08").list()?.toList()).containsExactly("cfg")
    }

    @Test
    fun `the voice is ready once its files are there and the engine opened it`() {
        val root = temporaryFolder.newFolder("files")
        val native = FakeSamsungTtsNative(sampleRate = 24_000)
        val ready = samsungVoicePrepare(native, root, SAMSUNG_VOICE_KO_L08, assets)
        assertThat(ready).isInstanceOf(SamsungVoiceReady.Ready::class.java)
        assertThat((ready as SamsungVoiceReady.Ready).sampleRate).isEqualTo(24_000)
        assertThat(ready.copiedFiles).hasSize(3)
        // The engine is given the folder holding the voice folder, without a trailing slash, and that voice
        assertThat(native.dataDir).isEqualTo(root.absolutePath)
        assertThat(native.dataDir).doesNotMatch(".*/$")
        assertThat(native.voice).isEqualTo(SAMSUNG_VOICE_KO_L08)
        assertThat(native.calls).containsExactly("load", "setVoice", "initialize", "samplingRate").inOrder()
    }

    @Test
    fun `the engine is only asked for the voice after its files have been copied`() {
        val root = temporaryFolder.newFolder("files")
        val native = object : SamsungTtsNative by FakeSamsungTtsNative() {
            var filesWhenAsked: List<String>? = null
            override fun setVoice(voice: SamsungVoice, dataDir: String): Int {
                filesWhenAsked = File(dataDir, voice.folderName).list()?.sorted()
                return SAMSUNG_TTS_OK
            }
        }
        samsungVoicePrepare(native, root, SAMSUNG_VOICE_KO_L08, assets)
        assertThat(native.filesWhenAsked).containsExactly("cfg", "lng", "regular.ivc")
    }

    @Test
    fun `a library that cannot be loaded says so without any path`() {
        val root = temporaryFolder.newFolder("files")
        val native = FakeSamsungTtsNative(loadFailure = UnsatisfiedLinkError("dlopen failed: /data/app/lib.so has bad alignment"))
        val ready = samsungVoicePrepare(native, root, SAMSUNG_VOICE_KO_L08, assets)
        assertThat(ready).isInstanceOf(SamsungVoiceReady.Failed::class.java)
        val reason = (ready as SamsungVoiceReady.Failed).reason
        assertThat(reason).startsWith("라이브러리 적재 실패 · UnsatisfiedLinkError: dlopen failed:")
        assertThat(reason).doesNotContain("/data/app")
    }

    @Test
    fun `a voice the engine refuses says what it answered`() {
        val root = temporaryFolder.newFolder("files")
        val native = FakeSamsungTtsNative(setVoiceCode = 11)
        val ready = samsungVoicePrepare(native, root, SAMSUNG_VOICE_KO_L08, assets)
        assertThat((ready as SamsungVoiceReady.Failed).reason).isEqualTo("음성 지정 실패(음성 파일 없음)")
        assertThat(native.calls).doesNotContain("initialize")
    }

    @Test
    fun `an engine that cannot be opened says what it answered`() {
        val root = temporaryFolder.newFolder("files")
        val native = FakeSamsungTtsNative(initializeCode = 227)
        val ready = samsungVoicePrepare(native, root, SAMSUNG_VOICE_KO_L08, assets)
        assertThat((ready as SamsungVoiceReady.Failed).reason).isEqualTo("엔진 열기 실패(cfg 열기 실패)")
    }

    @Test
    fun `files that cannot be copied stop before the library is loaded`() {
        val root = temporaryFolder.newFolder("files")
        assets.failing = "cfg"
        val native = FakeSamsungTtsNative()
        val ready = samsungVoicePrepare(native, root, SAMSUNG_VOICE_KO_L08, assets)
        assertThat((ready as SamsungVoiceReady.Failed).reason).isEqualTo("음성 파일 복사 cfg · IOException: broken asset")
        assertThat(native.loadCount).isEqualTo(0)
    }

    @Test
    fun `an engine that does not say its sample rate is read at the rate of the voice`() {
        val root = temporaryFolder.newFolder("files")
        val native = FakeSamsungTtsNative(sampleRate = 0)
        val ready = samsungVoicePrepare(native, root, SAMSUNG_VOICE_KO_L08, assets)
        assertThat((ready as SamsungVoiceReady.Ready).sampleRate).isEqualTo(SAMSUNG_TTS_DEFAULT_SAMPLE_RATE)
    }

    @Test
    fun `the note says how many files were installed, and nothing when they were already there`() {
        assertThat(samsungVoiceOpenNote(emptyList())).isNull()
        assertThat(samsungVoiceOpenNote(listOf("cfg", "lng", "regular.ivc"))).isEqualTo("음성 파일 3개 설치")
    }

    @Test
    fun `what the engine answers is said in Korean`() {
        assertThat(samsungTtsErrorText(SAMSUNG_TTS_OK)).isEqualTo("성공")
        assertThat(samsungTtsErrorText(10)).isEqualTo("인자 이상(음성 이름)")
        assertThat(samsungTtsErrorText(1_000_493)).isEqualTo("신경망 엔진 초기화 실패")
        assertThat(samsungTtsErrorText(12_345)).isEqualTo("오류 12345")
    }
}

/** The three files of a voice, small enough for a test, as they are in the assets. */
private class FakeSamsungVoiceAssets : SamsungVoiceAssets {
    /** The file whose reading throws, by its name in the voice folder. */
    var failing: String? = null

    val opened = mutableListOf<String>()

    private val contents = SAMSUNG_VOICE_FILES.mapIndexed { index, name ->
        name to ByteArray(16 * (index + 1)) { (it + index).toByte() }
    }.toMap()

    fun content(name: String): ByteArray = contents.getValue(name)

    private fun nameOf(asset: String): String = asset.substringAfterLast('/').removeSuffix(SAMSUNG_VOICE_ASSET_SUFFIX)

    override fun length(name: String): Long = content(nameOf(name)).size.toLong()

    override fun open(name: String): InputStream {
        val file = nameOf(name)
        opened += file
        if (file == failing) throw IOException("broken asset")
        return ByteArrayInputStream(content(file))
    }
}
