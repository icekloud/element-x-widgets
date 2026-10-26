/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import android.content.res.AssetManager
import io.element.android.libraries.designsystem.june.JuneSamsungVoiceChoice
import java.io.File
import java.io.InputStream

/**
 * 앱30: the Korean voices of the Samsung engine shipped inside the app, and where their files go on the phone.
 *
 * The engine looks for its voice under a data folder it is given, in a folder named `<language>_<COUNTRY>_<variant><number>`
 * holding three files with fixed names (`cfg`, `lng` and the voice database named after `_DB_name` of the configuration, here
 * `regular.ivc`). The same files are in the assets of the app with an added suffix, so the build keeps them uncompressed and their
 * length is known before they are copied.
 *
 * 앱32: there are four voices, and they all have the very same language data (`lng`, 20 MB) and engine. That file is therefore
 * shipped **once** ([SAMSUNG_VOICE_SHARED]) and put in place in the folder of each voice as it is installed, as a link to the one
 * copy when the phone allows it ([SamsungVoiceLinker]) and as a copy otherwise.
 */

/** Folder of the Samsung voices, both in the assets of the app and in its own files. */
internal const val SAMSUNG_VOICE_ASSETS = "samsung_tts"

/** Folder of the assets every voice shares. */
internal const val SAMSUNG_VOICE_SHARED = "shared"

/** The one file the four voices share: the language data, the same 20 MB for all of them. */
internal const val SAMSUNG_VOICE_SHARED_FILE = "lng"

/**
 * Suffix of the voice files in the assets. The build keeps everything ending with it uncompressed
 * (`androidResources.noCompress`), so the length of a file is known without reading it and the first copy stays fast.
 */
internal const val SAMSUNG_VOICE_ASSET_SUFFIX = ".smt"

/** The names the engine looks for inside the voice folder, in the order they are copied. */
internal val SAMSUNG_VOICE_FILES = listOf("cfg", SAMSUNG_VOICE_SHARED_FILE, "regular.ivc")

/**
 * A voice of the Samsung engine. The voices shipped with the app are in [SAMSUNG_VOICES]; another one only needs its
 * configuration and its voice database added to the assets under its own [folderName].
 */
internal data class SamsungVoice(
    val language: String,
    val country: String,
    val variant: String,
    val index: Int,
) {
    /** For instance "ko_KR_l08", the folder name the engine builds from the voice it is asked for. */
    val folderName: String = "%s_%s_%s%02d".format(language, country, variant, index)
}

/** The Korean voice the app read with before the voice could be chosen, and still the default one. */
internal val SAMSUNG_VOICE_KO_L08 = SamsungVoice(language = "ko", country = "KR", variant = "l", index = 8)

/** The voice of each [JuneSamsungVoiceChoice], by the name that voice has inside the engine. */
private val SAMSUNG_VOICES_BY_KEY: Map<String, SamsungVoice> = mapOf(
    JuneSamsungVoiceChoice.Voice1.key to SAMSUNG_VOICE_KO_L08,
    JuneSamsungVoiceChoice.Voice2.key to SamsungVoice(language = "ko", country = "KR", variant = "l", index = 1),
    JuneSamsungVoiceChoice.Voice3.key to SamsungVoice(language = "ko", country = "KR", variant = "l", index = 4),
    JuneSamsungVoiceChoice.Voice4.key to SamsungVoice(language = "ko", country = "KR", variant = "l", index = 5),
)

/** The four Korean voices shipped in the app, in the order they are shown. */
internal val SAMSUNG_VOICES: List<SamsungVoice> = JuneSamsungVoiceChoice.entries.map { SAMSUNG_VOICES_BY_KEY.getValue(it.key) }

/** The voice behind the number shown in the settings. */
internal fun samsungVoiceOf(choice: JuneSamsungVoiceChoice): SamsungVoice = SAMSUNG_VOICES_BY_KEY.getValue(choice.key)

/** Whether the engine has to be closed and opened again because another voice was chosen while one was open. */
internal fun samsungVoiceNeedsReopen(opened: SamsungVoice?, chosen: SamsungVoice): Boolean = opened != null && opened != chosen

/** Why the chosen voice could not be read with, with its number first, for instance "여성 2 · 음성 지정 실패(음성 파일 없음)". */
internal fun samsungVoiceFailure(choice: JuneSamsungVoiceChoice, reason: String): String = "${choice.shortLabel} · $reason"

/** What the reading trace calls the voice, for instance "여성 2 ko_KR_l01": the number as shown, and the folder of the engine. */
internal fun samsungVoiceTraceName(choice: JuneSamsungVoiceChoice, voice: SamsungVoice): String =
    "${choice.shortLabel} ${voice.folderName}"

/** The voice files as they can be read, so the copy can be tested without a phone. */
internal interface SamsungVoiceAssets {
    /** Length of the asset [name], or null when the package does not know it (a compressed asset). */
    fun length(name: String): Long?

    fun open(name: String): InputStream
}

/** The assets of the app. */
internal class SamsungAndroidVoiceAssets(private val assets: AssetManager) : SamsungVoiceAssets {
    override fun length(name: String): Long? = try {
        assets.openFd(name).use { it.length }
    } catch (_: Exception) {
        // A compressed asset has no length of its own; the copy then only checks the room left on the phone
        null
    }

    override fun open(name: String): InputStream = assets.open(name, AssetManager.ACCESS_STREAMING)
}

/**
 * 앱32: puts the shared file of a voice in place without copying it again. The engine opens its files with `fopen`, which follows
 * a link, so one copy is enough for the four voices.
 */
internal interface SamsungVoiceLinker {
    /** Makes [link] lead to [target] and says whether it worked. Must not throw. */
    fun link(target: File, link: File): Boolean
}

/** The asset holding [file] of [voice], for instance "samsung_tts/ko_KR_l08/cfg.smt" or "samsung_tts/shared/lng.smt". */
internal fun samsungVoiceAssetPath(voice: SamsungVoice, file: String): String {
    val folder = if (file == SAMSUNG_VOICE_SHARED_FILE) SAMSUNG_VOICE_SHARED else voice.folderName
    return "$SAMSUNG_VOICE_ASSETS/$folder/$file$SAMSUNG_VOICE_ASSET_SUFFIX"
}

/**
 * Copies the files of [voice] from the assets into [root] (the data folder given to the engine) and returns the names of the files
 * that had to be put in place, empty when they were all already there. A file is only used once it is complete ([juneVoiceCopy]).
 *
 * The file the voices share is copied once into [SAMSUNG_VOICE_SHARED] and [linker] makes the folder of each voice lead to that
 * copy; when the phone does not let it, the file is copied into the folder of the voice like the others.
 *
 * Throws [JuneVoiceLoadException] when a file cannot be put in place, for instance when there is not enough room on the phone.
 */
internal fun samsungVoiceInstall(
    root: File,
    voice: SamsungVoice,
    assets: SamsungVoiceAssets,
    linker: SamsungVoiceLinker,
): List<String> {
    val folder = File(root, voice.folderName)
    juneVoiceStep(JuneVoiceLoadStep.Environment, voice.folderName) {
        folder.mkdirs()
        if (!folder.isDirectory) throw java.io.IOException("Cannot make the voice folder")
    }
    val installed = mutableListOf<String>()
    SAMSUNG_VOICE_FILES.forEach { name ->
        val target = File(folder, name)
        val asset = samsungVoiceAssetPath(voice, name)
        juneVoiceStep(JuneVoiceLoadStep.Copy, name) {
            val expectedLength = assets.length(asset)
            // The same file as the one [juneVoiceCopy] keeps: anything else (missing, empty, cut in the middle) is put there again
            val alreadyThere = target.isFile && target.length() > 0 && (expectedLength == null || target.length() == expectedLength)
            if (!alreadyThere && name == SAMSUNG_VOICE_SHARED_FILE) {
                linker.installShared(root, target, expectedLength) { assets.open(asset) }
            } else {
                juneVoiceCopy(
                    target = target,
                    expectedLength = expectedLength,
                    usableSpace = { folder.usableSpace },
                    open = { assets.open(asset) },
                )
            }
            if (!alreadyThere) installed += name
        }
    }
    return installed
}

/**
 * Puts the one copy of the shared file in place for this voice: the copy itself is made under [SAMSUNG_VOICE_SHARED] of [root] (and
 * reused by the other voices), then [target] is made to lead to it, or copied from it when the phone does not allow a link.
 */
private fun SamsungVoiceLinker.installShared(root: File, target: File, expectedLength: Long?, open: () -> InputStream) {
    val sharedFolder = File(root, SAMSUNG_VOICE_SHARED)
    sharedFolder.mkdirs()
    val shared = juneVoiceCopy(
        target = File(sharedFolder, SAMSUNG_VOICE_SHARED_FILE),
        expectedLength = expectedLength,
        usableSpace = { sharedFolder.usableSpace },
        open = open,
    )
    if (link(shared, target)) return
    // The phone refuses links: the file is copied into the folder of the voice, which only costs room
    target.delete()
    juneVoiceCopy(
        target = target,
        expectedLength = expectedLength,
        usableSpace = { target.parentFile?.usableSpace ?: 0L },
        open = { shared.inputStream() },
    )
}

/** What the voice is ready to read with, or why it is not. */
internal sealed interface SamsungVoiceReady {
    class Ready(val sampleRate: Int, val copiedFiles: List<String>) : SamsungVoiceReady
    class Failed(val reason: String) : SamsungVoiceReady
}

/**
 * Gets the voice ready: its files are put where the engine looks for them, the library is loaded, the voice is chosen and opened.
 * Gives back the reason of the first step that did not work, short enough for one line of the settings and without any path.
 */
internal fun samsungVoicePrepare(
    native: SamsungTtsNative,
    root: File,
    voice: SamsungVoice,
    assets: SamsungVoiceAssets,
    linker: SamsungVoiceLinker,
): SamsungVoiceReady {
    val copied = try {
        samsungVoiceInstall(root, voice, assets, linker)
    } catch (error: JuneVoiceLoadException) {
        return SamsungVoiceReady.Failed("음성 파일 ${error.failure.reason}")
    } catch (error: Throwable) {
        if (!juneVoiceIsRecoverable(error)) throw error
        return SamsungVoiceReady.Failed("음성 파일 · ${juneVoiceErrorText(error)}")
    }
    try {
        native.load()
    } catch (error: Throwable) {
        if (!juneVoiceIsRecoverable(error)) throw error
        // What a phone that cannot use the library answers, for instance one with 16 KB memory pages or not arm64
        return SamsungVoiceReady.Failed("라이브러리 적재 실패 · ${juneVoiceErrorText(error)}")
    }
    // The engine is given the folder holding the voice folder, without a trailing slash, and keeps that very string
    val voiceCode = native.setVoice(voice, root.absolutePath)
    if (voiceCode != SAMSUNG_TTS_OK) return SamsungVoiceReady.Failed("음성 지정 실패(${samsungTtsErrorText(voiceCode)})")
    val openCode = native.initialize()
    if (openCode != SAMSUNG_TTS_OK) return SamsungVoiceReady.Failed("엔진 열기 실패(${samsungTtsErrorText(openCode)})")
    val sampleRate = native.samplingRate().takeIf { it > 0 } ?: SAMSUNG_TTS_DEFAULT_SAMPLE_RATE
    return SamsungVoiceReady.Ready(sampleRate, copied)
}

/** What the settings say about how the voice was made ready, or null when nothing had to be copied. */
internal fun samsungVoiceOpenNote(copiedFiles: List<String>): String? =
    if (copiedFiles.isEmpty()) null else "음성 파일 ${copiedFiles.size}개 설치"
