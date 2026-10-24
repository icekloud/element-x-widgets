/*
 * Copyright (c) 2026 Element June contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.june

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetManager
import io.element.android.libraries.core.extensions.runCatchingExceptions
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.util.Random

private const val TAG = "JuneVoiceModel"

/** Folder of the model files, both inside the assets of the app and in its own files when they have to be copied. */
internal const val JUNE_VOICE_ASSETS = "june_voice"

/** Steps of the denoising loop and speed of the speech: the defaults of the reference implementation. */
internal const val JUNE_VOICE_TOTAL_STEPS = 8
internal const val JUNE_VOICE_SPEED = 1.05f

/** Language tag given to the multilingual model. */
private const val LANGUAGE = "ko"

private const val FILE_DURATION_PREDICTOR = "duration_predictor.onnx"
private const val FILE_TEXT_ENCODER = "text_encoder.onnx"
private const val FILE_VECTOR_ESTIMATOR = "vector_estimator.onnx"
private const val FILE_VOCODER = "vocoder.onnx"
private const val FILE_INDEXER = "unicode_indexer.json"
private const val FILE_CONFIG = "tts.json"
private const val FILE_VOICE_STYLE = "F1.json"

/** Every file the built-in voice needs, as they are in the assets. */
internal val JUNE_VOICE_FILES = listOf(
    FILE_DURATION_PREDICTOR,
    FILE_TEXT_ENCODER,
    FILE_VECTOR_ESTIMATOR,
    FILE_VOCODER,
    FILE_INDEXER,
    FILE_CONFIG,
    FILE_VOICE_STYLE,
)

/** The audio made for one chunk, and how long it lasts. */
internal class JuneVoiceAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationMillis: Long get() = if (sampleRate <= 0) 0 else samples.size * 1000L / sampleRate
}

/**
 * Element June: the Supertonic 3 model (voice F1) running on the phone with ONNX Runtime, as in the reference implementation of the
 * model: the text becomes unicode indices, the duration predictor gives the length, the text encoder and the vector estimator make
 * the latent audio in [JUNE_VOICE_TOTAL_STEPS] steps, and the vocoder turns it into a waveform.
 *
 * Opening it loads about 380 MB of model: it is only done while lines are read, and [close] gives the memory back.
 */
internal class JuneVoiceModel private constructor(
    private val environment: OrtEnvironment,
    private val sessions: List<OrtSession>,
    private val closeables: List<Closeable>,
    private val indexer: IntArray,
    private val styleTtl: OnnxTensor,
    private val styleDp: OnnxTensor,
    val sampleRate: Int,
    private val baseChunkSize: Int,
    private val chunkCompressFactor: Int,
    private val latentDim: Int,
) {
    private val durationPredictor get() = sessions[0]
    private val textEncoder get() = sessions[1]
    private val vectorEstimator get() = sessions[2]
    private val vocoder get() = sessions[3]

    private val random = Random()

    /** Makes the audio of one [chunk]. The chunk must be short (see [juneVoiceChunks]). */
    fun synthesize(chunk: String): JuneVoiceAudio {
        val text = "<$LANGUAGE>${chunk.trim()}</$LANGUAGE>"
        val codePoints = text.codePoints().toArray()
        val textIds = LongArray(codePoints.size) { index ->
            val codePoint = codePoints[index]
            if (codePoint in indexer.indices) indexer[codePoint].toLong() else 0L
        }
        val textMask = FloatArray(codePoints.size) { 1f }

        OnnxTensor.createTensor(environment, LongBuffer.wrap(textIds), longArrayOf(1, textIds.size.toLong())).use { textIdsTensor ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(textMask), longArrayOf(1, 1, textMask.size.toLong())).use { maskTensor ->
                val duration = predictDuration(textIdsTensor, maskTensor) / JUNE_VOICE_SPEED
                val textEmbedding = textEncoder.run(mapOf("text_ids" to textIdsTensor, "style_ttl" to styleTtl, "text_mask" to maskTensor))
                textEmbedding.use { embedding ->
                    val samples = denoiseAndVocode(embedding.get(0) as OnnxTensor, maskTensor, duration)
                    return JuneVoiceAudio(samples, sampleRate)
                }
            }
        }
    }

    private fun predictDuration(textIds: OnnxTensor, textMask: OnnxTensor): Float {
        durationPredictor.run(mapOf("text_ids" to textIds, "style_dp" to styleDp, "text_mask" to textMask)).use { result ->
            return when (val value = result.get(0).value) {
                is Array<*> -> (value.first() as FloatArray).first()
                is FloatArray -> value.first()
                else -> error("Unexpected duration type ${value?.javaClass}")
            }
        }
    }

    private fun denoiseAndVocode(textEmbedding: OnnxTensor, textMask: OnnxTensor, duration: Float): FloatArray {
        val chunkSize = baseChunkSize * chunkCompressFactor
        val waveLength = (duration * sampleRate).toLong()
        val latentLength = ((waveLength + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
        val dimensions = latentDim * chunkCompressFactor

        val latentMask = FloatArray(latentLength) { 1f }
        var latent = FloatArray(dimensions * latentLength) { random.nextGaussian().toFloat() }
        val latentShape = longArrayOf(1, dimensions.toLong(), latentLength.toLong())

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(latentMask), longArrayOf(1, 1, latentLength.toLong())).use { maskTensor ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(JUNE_VOICE_TOTAL_STEPS.toFloat()))).use { totalStep ->
                repeat(JUNE_VOICE_TOTAL_STEPS) { step ->
                    latent = denoiseStep(latent, latentShape, textEmbedding, textMask, maskTensor, totalStep, step)
                }
            }
        }

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(latent), latentShape).use { latentTensor ->
            vocoder.run(mapOf("latent" to latentTensor)).use { result ->
                val wave = result.get(0).value as Array<*>
                val samples = wave.first() as FloatArray
                // The model makes a little more audio than the predicted duration: only the predicted part is kept, as in the reference
                val kept = (sampleRate * duration).toInt().coerceIn(0, samples.size)
                return samples.copyOf(kept)
            }
        }
    }

    private fun denoiseStep(
        latent: FloatArray,
        latentShape: LongArray,
        textEmbedding: OnnxTensor,
        textMask: OnnxTensor,
        latentMask: OnnxTensor,
        totalStep: OnnxTensor,
        step: Int,
    ): FloatArray {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(latent), latentShape).use { noisyLatent ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(step.toFloat()))).use { currentStep ->
                vectorEstimator.run(
                    mapOf(
                        "noisy_latent" to noisyLatent,
                        "text_emb" to textEmbedding,
                        "style_ttl" to styleTtl,
                        "latent_mask" to latentMask,
                        "text_mask" to textMask,
                        "current_step" to currentStep,
                        "total_step" to totalStep,
                    )
                ).use { result ->
                    return flatten(result.get(0).value)
                }
            }
        }
    }

    /** The model gives a (1, dimensions, length) array: it is flattened to be given back as the next noisy latent. */
    private fun flatten(value: Any?): FloatArray {
        val batch = (value as Array<*>).first() as Array<*>
        val length = (batch.first() as FloatArray).size
        val result = FloatArray(batch.size * length)
        batch.forEachIndexed { index, row ->
            System.arraycopy(row as FloatArray, 0, result, index * length, length)
        }
        return result
    }

    fun close() {
        runCatchingExceptions {
            styleTtl.close()
            styleDp.close()
            sessions.forEach { it.close() }
            closeables.forEach { it.close() }
        }.onFailure { Timber.tag(TAG).e(it, "Cannot close the built-in voice model") }
    }

    companion object {
        /**
         * Loads the model of the built-in voice, or null when it cannot be loaded (files missing, not enough memory).
         * The model files are read straight from the assets of the app when they are stored there uncompressed, so nothing is copied
         * and nothing is kept in the Java heap; if that is not possible they are copied once into the files of the app.
         */
        fun load(context: Context): JuneVoiceModel? = runCatchingExceptions { loadOrThrow(context) }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Cannot load the built-in voice model")
            null
        }

        private fun loadOrThrow(context: Context): JuneVoiceModel {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // Half of the cores, so the reading does not take the whole phone while the app is in the background
                setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))
            }
            val sources = JUNE_VOICE_FILES.associateWith { name -> JuneVoiceSource.of(context, name) }
            val closeables = mutableListOf<Closeable>()
            val sessions = mutableListOf<OrtSession>()
            try {
                listOf(FILE_DURATION_PREDICTOR, FILE_TEXT_ENCODER, FILE_VECTOR_ESTIMATOR, FILE_VOCODER).forEach { name ->
                    val source = sources.getValue(name)
                    sessions += source.createSession(environment, options)
                    source.keepAlive()?.let { closeables += it }
                }
                val config = JSONObject(sources.getValue(FILE_CONFIG).readText())
                val autoEncoder = config.getJSONObject("ae")
                val latentConfig = config.getJSONObject("ttl")
                val style = JSONObject(sources.getValue(FILE_VOICE_STYLE).readText())
                return JuneVoiceModel(
                    environment = environment,
                    sessions = sessions,
                    closeables = closeables,
                    indexer = readIndexer(sources.getValue(FILE_INDEXER).readText()),
                    styleTtl = readStyle(environment, style.getJSONObject("style_ttl")),
                    styleDp = readStyle(environment, style.getJSONObject("style_dp")),
                    sampleRate = autoEncoder.getInt("sample_rate"),
                    baseChunkSize = autoEncoder.getInt("base_chunk_size"),
                    chunkCompressFactor = latentConfig.getInt("chunk_compress_factor"),
                    latentDim = latentConfig.getInt("latent_dim"),
                )
            } catch (error: Throwable) {
                sessions.forEach { runCatchingExceptions { it.close() } }
                closeables.forEach { runCatchingExceptions { it.close() } }
                throw error
            }
        }

        private fun readIndexer(text: String): IntArray {
            val array = JSONArray(text)
            return IntArray(array.length()) { index -> array.getInt(index) }
        }

        /** The voice style is a (1, rows, columns) array of numbers in a JSON file. */
        private fun readStyle(environment: OrtEnvironment, style: JSONObject): OnnxTensor {
            val dimensions = style.getJSONArray("dims")
            val rows = dimensions.getInt(1)
            val columns = dimensions.getInt(2)
            val data = style.getJSONArray("data").getJSONArray(0)
            val values = FloatArray(rows * columns)
            for (row in 0 until rows) {
                val line = data.getJSONArray(row)
                for (column in 0 until columns) {
                    values[row * columns + column] = line.getDouble(column).toFloat()
                }
            }
            return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, rows.toLong(), columns.toLong()))
        }
    }
}

/**
 * Where one model file is read from: straight from the assets of the app when they hold it uncompressed (nothing is copied), else from
 * a copy made once in the files of the app.
 */
internal sealed interface JuneVoiceSource {
    fun createSession(environment: OrtEnvironment, options: OrtSession.SessionOptions): OrtSession
    fun readText(): String

    /** Something that has to stay open while the session uses it, or null. */
    fun keepAlive(): Closeable?

    /** The file is mapped from the assets of the app: ONNX Runtime reads it without copying it to the Java heap. */
    class Mapped(private val channel: FileChannel, private val offset: Long, private val length: Long) : JuneVoiceSource {
        override fun createSession(environment: OrtEnvironment, options: OrtSession.SessionOptions): OrtSession {
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset, length)
            return environment.createSession(buffer, options)
        }

        override fun readText(): String {
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset, length)
            val bytes = ByteArray(length.toInt())
            buffer.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        override fun keepAlive(): Closeable = channel
    }

    /** The file was copied into the files of the app and is read by its path. */
    class Copied(private val file: File) : JuneVoiceSource {
        override fun createSession(environment: OrtEnvironment, options: OrtSession.SessionOptions): OrtSession =
            environment.createSession(file.absolutePath, options)

        override fun readText(): String = file.readText()

        override fun keepAlive(): Closeable? = null
    }

    companion object {
        fun of(context: Context, name: String): JuneVoiceSource {
            val assetPath = "$JUNE_VOICE_ASSETS/$name"
            val mapped = runCatchingExceptions {
                val descriptor = context.assets.openFd(assetPath)
                // openFd only works on an asset stored uncompressed, which is what the build makes sure of
                descriptor.use { Mapped(it.createInputStream().channel, it.startOffset, it.length) }
            }.getOrNull()
            if (mapped != null) return mapped
            Timber.tag(TAG).w("The model file %s is not stored uncompressed, copying it", name)
            return Copied(copyAsset(context, assetPath, name))
        }

        private fun copyAsset(context: Context, assetPath: String, name: String): File {
            val folder = File(context.filesDir, JUNE_VOICE_ASSETS).apply { mkdirs() }
            val file = File(folder, name)
            val expected = context.assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { input -> input.available().toLong() }
            if (file.length() > 0 && (expected <= 0 || file.length() == expected)) return file
            context.assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            return file
        }
    }
}
