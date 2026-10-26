/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.june.JuneSamsungVoiceChoice
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 앱32: the four Korean voices of the Samsung engine shipped in the app. They are only ever shown by their number
 * ("삼성 여성 1" to "삼성 여성 4"); nothing else about them is written anywhere the user can see.
 *
 * The four voices share the very same language data and engine, so only the voice database and its configuration are shipped per
 * voice and the shared file is put in place of each voice folder when it is installed.
 */
class SamsungVoiceChoiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val assets = FakeSamsungVoiceAssets()

    // What is shown

    @Test
    fun `every voice is shown by its number only`() {
        val labels = JuneSamsungVoiceChoice.entries.map { it.label }
        assertThat(labels).containsExactly("삼성 여성 1", "삼성 여성 2", "삼성 여성 3", "삼성 여성 4").inOrder()
        // Nothing but the number may be shown: no name, no model, no file
        JuneSamsungVoiceChoice.entries.forEach { choice ->
            assertThat(choice.label).isEqualTo("삼성 여성 ${choice.number}")
            assertThat(choice.shortLabel).isEqualTo("여성 ${choice.number}")
        }
    }

    @Test
    fun `the numbers go from one to four in order`() {
        assertThat(JuneSamsungVoiceChoice.entries.map { it.number }).containsExactly(1, 2, 3, 4).inOrder()
    }

    @Test
    fun `the first voice is the one the app already read with`() {
        assertThat(JuneSamsungVoiceChoice.entries.first()).isEqualTo(JuneSamsungVoiceChoice.Voice1)
        assertThat(JuneSamsungVoiceChoice.Voice1.key).isEqualTo("l08")
        assertThat(samsungVoiceOf(JuneSamsungVoiceChoice.Voice1)).isEqualTo(SAMSUNG_VOICE_KO_L08)
    }

    // What is saved

    @Test
    fun `the saved value is the key of the voice, and a broken one reads as the first voice`() {
        assertThat(JuneSamsungVoiceChoice.entries.map { it.key }).containsExactly("l08", "l01", "l04", "l05").inOrder()
        assertThat(JuneSamsungVoiceChoice.parse("l04")).isEqualTo(JuneSamsungVoiceChoice.Voice3)
        assertThat(JuneSamsungVoiceChoice.parse(null)).isEqualTo(JuneSamsungVoiceChoice.Voice1)
        assertThat(JuneSamsungVoiceChoice.parse("")).isEqualTo(JuneSamsungVoiceChoice.Voice1)
        assertThat(JuneSamsungVoiceChoice.parse("ko_KR_g01")).isEqualTo(JuneSamsungVoiceChoice.Voice1)
    }

    @Test
    fun `pressing the setting goes through the four voices and comes back`() {
        var choice = JuneSamsungVoiceChoice.Voice1
        val seen = mutableListOf(choice)
        repeat(4) {
            choice = choice.next()
            seen += choice
        }
        assertThat(seen).containsExactly(
            JuneSamsungVoiceChoice.Voice1,
            JuneSamsungVoiceChoice.Voice2,
            JuneSamsungVoiceChoice.Voice3,
            JuneSamsungVoiceChoice.Voice4,
            JuneSamsungVoiceChoice.Voice1,
        ).inOrder()
    }

    // The folders the engine looks for

    @Test
    fun `each voice has its own folder, all of them Korean`() {
        val folders = JuneSamsungVoiceChoice.entries.map { samsungVoiceOf(it).folderName }
        assertThat(folders).containsExactly("ko_KR_l08", "ko_KR_l01", "ko_KR_l04", "ko_KR_l05").inOrder()
        JuneSamsungVoiceChoice.entries.forEach { choice ->
            val voice = samsungVoiceOf(choice)
            assertThat(voice.language).isEqualTo("ko")
            assertThat(voice.country).isEqualTo("KR")
            // The engine refuses a variant that is not exactly one character
            assertThat(voice.variant).hasLength(1)
        }
    }

    @Test
    fun `the voices shipped in the app are the four of the setting`() {
        assertThat(SAMSUNG_VOICES.map { it.folderName })
            .containsExactlyElementsIn(JuneSamsungVoiceChoice.entries.map { samsungVoiceOf(it).folderName })
    }

    // The assets: what is per voice and what is shared

    @Test
    fun `the configuration and the voice database are read per voice`() {
        assertThat(samsungVoiceAssetPath(samsungVoiceOf(JuneSamsungVoiceChoice.Voice2), "cfg"))
            .isEqualTo("samsung_tts/ko_KR_l01/cfg.smt")
        assertThat(samsungVoiceAssetPath(samsungVoiceOf(JuneSamsungVoiceChoice.Voice4), "regular.ivc"))
            .isEqualTo("samsung_tts/ko_KR_l05/regular.ivc.smt")
    }

    @Test
    fun `the language data is shipped once and read from the shared folder by every voice`() {
        // The four voices have the very same language data, so it is only in the app once
        SAMSUNG_VOICES.forEach { voice ->
            assertThat(samsungVoiceAssetPath(voice, SAMSUNG_VOICE_SHARED_FILE)).isEqualTo("samsung_tts/shared/lng.smt")
        }
    }

    // Installing

    @Test
    fun `installing a voice puts its own files and the shared one in its folder`() {
        val root = temporaryFolder.newFolder("files")
        val copied = samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice2), assets, FakeSamsungVoiceLinker())
        assertThat(copied).containsExactly("cfg", "lng", "regular.ivc").inOrder()
        val folder = File(root, "ko_KR_l01")
        assertThat(folder.list()?.toList()).containsExactly("cfg", "lng", "regular.ivc")
        assertThat(File(folder, "cfg").readBytes()).isEqualTo(assets.content("ko_KR_l01/cfg"))
        assertThat(File(folder, "regular.ivc").readBytes()).isEqualTo(assets.content("ko_KR_l01/regular.ivc"))
    }

    @Test
    fun `the shared language data is only copied once, the other voices link to it`() {
        val root = temporaryFolder.newFolder("files")
        val linker = FakeSamsungVoiceLinker()
        samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice1), assets, linker)
        samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice2), assets, linker)
        samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice3), assets, linker)
        // Read from the assets once, then linked
        assertThat(assets.opened.count { it == "shared/lng" }).isEqualTo(1)
        assertThat(linker.links).hasSize(3)
        // Every voice folder still holds the three files the engine opens
        listOf("ko_KR_l08", "ko_KR_l01", "ko_KR_l04").forEach { folder ->
            assertThat(File(root, folder).list()?.toList()).containsExactly("cfg", "lng", "regular.ivc")
            assertThat(File(root, "$folder/lng").readBytes()).isEqualTo(assets.content("shared/lng"))
        }
    }

    @Test
    fun `a phone that cannot make links copies the language data instead`() {
        val root = temporaryFolder.newFolder("files")
        val linker = FakeSamsungVoiceLinker(works = false)
        val copied = samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice3), assets, linker)
        assertThat(copied).contains("lng")
        assertThat(File(root, "ko_KR_l04/lng").readBytes()).isEqualTo(assets.content("shared/lng"))
        // A link that is made but cannot be read is not left behind
        assertThat(File(root, "ko_KR_l04/lng").isFile).isTrue()
    }

    @Test
    fun `a link that leads nowhere is made again`() {
        val root = temporaryFolder.newFolder("files")
        val linker = FakeSamsungVoiceLinker()
        samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice2), assets, linker)
        File(root, "ko_KR_l01/lng").delete()
        assertThat(samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice2), assets, linker)).contains("lng")
        assertThat(File(root, "ko_KR_l01/lng").readBytes()).isEqualTo(assets.content("shared/lng"))
    }

    @Test
    fun `installing a second voice does not touch the files of the first one`() {
        val root = temporaryFolder.newFolder("files")
        val linker = FakeSamsungVoiceLinker()
        samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice1), assets, linker)
        samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice2), assets, linker)
        assertThat(File(root, "ko_KR_l08/regular.ivc").readBytes()).isEqualTo(assets.content("ko_KR_l08/regular.ivc"))
        assertThat(File(root, "ko_KR_l01/regular.ivc").readBytes()).isEqualTo(assets.content("ko_KR_l01/regular.ivc"))
        // Each voice is only installed when it is used: nothing was read for the two voices that were not asked for
        assertThat(assets.opened.none { it.startsWith("ko_KR_l04") || it.startsWith("ko_KR_l05") }).isTrue()
    }

    @Test
    fun `installing the same voice again copies nothing`() {
        val root = temporaryFolder.newFolder("files")
        val linker = FakeSamsungVoiceLinker()
        samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice4), assets, linker)
        assertThat(samsungVoiceInstall(root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice4), assets, linker)).isEmpty()
    }

    // Getting ready with the voice that was chosen

    @Test
    fun `the engine is asked for the chosen voice`() {
        val root = temporaryFolder.newFolder("files")
        val native = FakeSamsungTtsNative()
        val ready = samsungVoicePrepare(native, root, samsungVoiceOf(JuneSamsungVoiceChoice.Voice3), assets, FakeSamsungVoiceLinker())
        assertThat(ready).isInstanceOf(SamsungVoiceReady.Ready::class.java)
        assertThat(native.voice?.folderName).isEqualTo("ko_KR_l04")
        assertThat(native.dataDir).isEqualTo(root.absolutePath)
    }

    // Changing the voice

    @Test
    fun `the engine is opened again when another voice is chosen`() {
        val first = samsungVoiceOf(JuneSamsungVoiceChoice.Voice1)
        val second = samsungVoiceOf(JuneSamsungVoiceChoice.Voice2)
        assertThat(samsungVoiceNeedsReopen(opened = first, chosen = second)).isTrue()
        assertThat(samsungVoiceNeedsReopen(opened = first, chosen = first)).isFalse()
        // Nothing is open yet: it is opened, not opened again
        assertThat(samsungVoiceNeedsReopen(opened = null, chosen = second)).isFalse()
    }

    // What the settings are told when the chosen voice fails

    @Test
    fun `a failure says which voice number it was`() {
        assertThat(samsungVoiceFailure(JuneSamsungVoiceChoice.Voice2, "음성 파일 복사 regular.ivc · IOException"))
            .isEqualTo("여성 2 · 음성 파일 복사 regular.ivc · IOException")
    }

    @Test
    fun `the reading trace says which voice number read the line`() {
        val trace = SamsungSpeakTrace(
            voice = samsungVoiceTraceName(JuneSamsungVoiceChoice.Voice3, samsungVoiceOf(JuneSamsungVoiceChoice.Voice3)),
            sampleRate = 24_000,
            chunks = 1,
        )
        assertThat(trace.line()).contains("여성 3 ko_KR_l04")
    }
}

/** The files of the four voices, small enough for a test, as they are in the assets (per voice and shared). */
internal class FakeSamsungVoiceAssets : SamsungVoiceAssets {
    /** The file whose reading throws, by its path under the assets folder (for instance "ko_KR_l01/cfg"). */
    var failing: String? = null

    val opened = mutableListOf<String>()

    private val contents: Map<String, ByteArray> = buildMap {
        put("shared/lng", ByteArray(48) { (it + 7).toByte() })
        SAMSUNG_VOICES.forEachIndexed { index, voice ->
            put("${voice.folderName}/cfg", ByteArray(16 * (index + 1)) { (it + index).toByte() })
            put("${voice.folderName}/regular.ivc", ByteArray(32 * (index + 1)) { (it + index + 1).toByte() })
        }
    }

    fun content(name: String): ByteArray = contents.getValue(name)

    private fun nameOf(asset: String): String =
        asset.removePrefix("$SAMSUNG_VOICE_ASSETS/").removeSuffix(SAMSUNG_VOICE_ASSET_SUFFIX)

    override fun length(name: String): Long = content(nameOf(name)).size.toLong()

    override fun open(name: String): InputStream {
        val file = nameOf(name)
        opened += file
        if (file == failing) throw IOException("broken asset")
        return ByteArrayInputStream(content(file))
    }
}

/** A phone that can (or cannot) make a link to the shared file. A link is a plain copy here, so reading it is the same. */
internal class FakeSamsungVoiceLinker(private val works: Boolean = true) : SamsungVoiceLinker {
    val links = mutableListOf<String>()

    override fun link(target: File, link: File): Boolean {
        if (!works) return false
        links += link.name
        target.copyTo(link, overwrite = true)
        return true
    }
}
