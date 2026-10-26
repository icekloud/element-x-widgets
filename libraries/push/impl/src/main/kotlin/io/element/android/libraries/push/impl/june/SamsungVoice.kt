/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream

/**
 * 앱30: the Korean voice of the Samsung engine shipped inside the app, and where its files go on the phone.
 *
 * The engine looks for its voice under a data folder it is given, in a folder named `<language>_<COUNTRY>_<variant><number>`
 * holding three files with fixed names (`cfg`, `lng` and the voice database named after `_DB_name` of the configuration, here
 * `regular.ivc`). The same files are in the assets of the app with an added suffix, so the build keeps them uncompressed and their
 * length is known before they are copied.
 */

/** Folder of the Samsung voice, both in the assets of the app and in its own files. */
internal const val SAMSUNG_VOICE_ASSETS = "samsung_tts"

/**
 * Suffix of the voice files in the assets. The build keeps everything ending with it uncompressed
 * (`androidResources.noCompress`), so the length of a file is known without reading it and the first copy stays fast.
 */
internal const val SAMSUNG_VOICE_ASSET_SUFFIX = ".smt"

/** The names the engine looks for inside the voice folder, in the order they are copied. */
internal val SAMSUNG_VOICE_FILES = listOf("cfg", "lng", "regular.ivc")

/**
 * A voice of the Samsung engine. Only [SAMSUNG_VOICE_KO_L08] is shipped with the app; another one only needs its three files added
 * to the assets under its own [folderName].
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

/** The Korean voice built into the app. */
internal val SAMSUNG_VOICE_KO_L08 = SamsungVoice(language = "ko", country = "KR", variant = "l", index = 8)

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

/** The asset holding [file] of [voice], for instance "samsung_tts/ko_KR_l08/cfg.smt". */
internal fun samsungVoiceAssetPath(voice: SamsungVoice, file: String): String =
    "$SAMSUNG_VOICE_ASSETS/${voice.folderName}/$file$SAMSUNG_VOICE_ASSET_SUFFIX"

/**
 * Copies the files of [voice] from the assets into [root] (the data folder given to the engine) and returns the names of the files
 * that had to be copied, empty when they were all already there. A file is only used once it is complete ([juneVoiceCopy]).
 *
 * Throws [JuneVoiceLoadException] when a file cannot be copied, for instance when there is not enough room left on the phone.
 */
internal fun samsungVoiceInstall(root: File, voice: SamsungVoice, assets: SamsungVoiceAssets): List<String> {
    val folder = File(root, voice.folderName)
    juneVoiceStep(JuneVoiceLoadStep.Environment, voice.folderName) {
        folder.mkdirs()
        if (!folder.isDirectory) throw java.io.IOException("Cannot make the voice folder")
    }
    val copied = mutableListOf<String>()
    SAMSUNG_VOICE_FILES.forEach { name ->
        val target = File(folder, name)
        val asset = samsungVoiceAssetPath(voice, name)
        juneVoiceStep(JuneVoiceLoadStep.Copy, name) {
            val expectedLength = assets.length(asset)
            // The same file as the one [juneVoiceCopy] keeps: anything else (missing, empty, cut in the middle) is copied again
            val alreadyThere = target.isFile && target.length() > 0 && (expectedLength == null || target.length() == expectedLength)
            juneVoiceCopy(
                target = target,
                expectedLength = expectedLength,
                usableSpace = { folder.usableSpace },
                open = { assets.open(asset) },
            )
            if (!alreadyThere) copied += name
        }
    }
    return copied
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
): SamsungVoiceReady {
    val copied = try {
        samsungVoiceInstall(root, voice, assets)
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
