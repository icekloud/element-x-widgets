/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "SamsungTts"

/**
 * The C entry points used of `libsamsungtts.so`. Only the plain `SMT*`/`ENGINE_*` ones are needed, so the library is called
 * straight from Kotlin with JNA (already used by this app) instead of a JNI wrapper of its own.
 *
 * Every string is passed as a [Pointer] on purpose: the engine keeps the pointer of the data folder it is given
 * (`IEngine::_SetVoice` stores it) instead of copying it, so the memory has to stay alive as long as the engine is open.
 */
private interface SamsungTtsLibrary : Library {
    /**
     * `SetVoice(mode, dataDir, unused, language, country, variant, index)`. Checked on the disassembly: the engine stores the 4th,
     * 5th and 6th arguments as the language, the country and the one letter of the variant, in that order.
     */
    fun SMTSetVoice(mode: Int, dataDir: Pointer, unused: Byte, language: Pointer, country: Pointer, variant: Pointer, index: Int): Int

    fun SMTInitialize(): Int

    fun SMTFinalize(): Int

    fun SMTGetSamplingRate(): Int

    fun SMTInputText(text: Pointer): Int

    fun ENGINE_SetAudioCallback(callback: SamsungAudioCallback?, user: Pointer?): Int

    fun ENGINE_Stop(): Int
}

/** The audio callback of the engine: `void (*)(const char* buffer, int bytes, int, int, const char* label, int resultType, void* user)`. */
private interface SamsungAudioCallback : Callback {
    fun invoke(buffer: Pointer?, bytes: Int, labelStart: Int, labelEnd: Int, label: Pointer?, resultType: Int, user: Pointer?)
}

/** The Samsung engine as the app really uses it. Not thread safe: [SamsungJuneSpeechEngine] serialises everything. */
internal class JnaSamsungTtsNative : SamsungTtsNative {
    private var library: SamsungTtsLibrary? = null

    /** The strings given to the engine: it keeps the pointer of the data folder, so they are only freed once it is closed. */
    private var strings: List<Memory> = emptyList()

    /** Where the audio of the text being read goes. Only read by the callback, which runs on the thread calling [inputText]. */
    @Volatile private var sink: ((ShortArray, Int) -> Unit)? = null

    /** Kept in a field: a callback given to native code must not be collected while the engine may still call it. */
    private val callback = object : SamsungAudioCallback {
        override fun invoke(buffer: Pointer?, bytes: Int, labelStart: Int, labelEnd: Int, label: Pointer?, resultType: Int, user: Pointer?) {
            val target = sink ?: return
            try {
                target(samsungPcmSamples(buffer, bytes), resultType)
            } catch (error: Throwable) {
                // Nothing may be thrown back into native code
                Timber.tag(TAG).e(error, "The audio of the Samsung voice could not be taken")
            }
        }
    }

    override fun load() {
        if (library != null) return
        // Loading it here first gives a plain Java failure (and the name of the library) when the phone cannot use it
        System.loadLibrary(SAMSUNG_TTS_LIBRARY)
        library = Native.load(SAMSUNG_TTS_LIBRARY, SamsungTtsLibrary::class.java)
    }

    override fun setVoice(voice: SamsungVoice, dataDir: String): Int {
        val engine = library ?: return SAMSUNG_TTS_ERROR_NOT_LOADED
        val data = samsungCString(dataDir)
        val language = samsungCString(voice.language)
        val country = samsungCString(voice.country)
        val variant = samsungCString(voice.variant)
        strings = listOf(data, language, country, variant)
        return engine.SMTSetVoice(
            mode = SAMSUNG_TTS_MODE,
            dataDir = data,
            unused = 0,
            language = language,
            country = country,
            variant = variant,
            index = voice.index,
        )
    }

    override fun initialize(): Int {
        val engine = library ?: return SAMSUNG_TTS_ERROR_NOT_LOADED
        val code = engine.SMTInitialize()
        if (code == SAMSUNG_TTS_OK) engine.ENGINE_SetAudioCallback(callback, null)
        return code
    }

    override fun samplingRate(): Int = library?.SMTGetSamplingRate() ?: 0

    override fun inputText(text: String, onAudio: (ShortArray, Int) -> Unit): Int {
        val engine = library ?: return SAMSUNG_TTS_ERROR_NOT_LOADED
        // The engine reads the text while this call lasts and gives the audio to the callback on this thread
        sink = onAudio
        return try {
            engine.SMTInputText(samsungCString(text))
        } finally {
            sink = null
        }
    }

    override fun stop() {
        library?.ENGINE_Stop()
    }

    override fun close() {
        val engine = library ?: return
        library = null
        sink = null
        engine.SMTFinalize()
        // Only once the engine is done with them
        strings = emptyList()
    }
}

/** The engine is asked for the plain reading mode. */
private const val SAMSUNG_TTS_MODE = 0

/** Answered when something is called before the library could be loaded; the engine itself never gives this one. */
internal const val SAMSUNG_TTS_ERROR_NOT_LOADED = -1

/** A UTF-8 string the engine can keep: its memory is freed with the object, not at the end of the call. */
private fun samsungCString(value: String): Memory {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val memory = Memory((bytes.size + 1).toLong())
    memory.write(0, bytes, 0, bytes.size)
    memory.setByte(bytes.size.toLong(), 0)
    return memory
}

/** The [bytes] first bytes of [buffer] as 16 bit mono samples. */
private fun samsungPcmSamples(buffer: Pointer?, bytes: Int): ShortArray {
    if (buffer == null || bytes < Short.SIZE_BYTES) return ShortArray(0)
    val raw = buffer.getByteArray(0, bytes - bytes % Short.SIZE_BYTES)
    val samples = ShortArray(raw.size / Short.SIZE_BYTES)
    ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)
    return samples
}
