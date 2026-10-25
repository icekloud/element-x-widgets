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
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import io.element.android.libraries.core.extensions.runCatchingExceptions
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
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

/** The step of the denoising loop is given to the model as one number for the one line being read. */
private val STEP_SHAPE = longArrayOf(1)

private const val FILE_DURATION_PREDICTOR = "duration_predictor.onnx"
private const val FILE_TEXT_ENCODER = "text_encoder.onnx"
private const val FILE_VECTOR_ESTIMATOR = "vector_estimator.onnx"
private const val FILE_VOCODER = "vocoder.onnx"
private const val FILE_INDEXER = "unicode_indexer.json"
private const val FILE_CONFIG = "tts.json"
private const val FILE_VOICE_STYLE = "F1.json"

/** The model files of the built-in voice, in the order of the sessions, as they are in the assets. */
internal val JUNE_VOICE_SESSION_FILES = listOf(
    FILE_DURATION_PREDICTOR,
    FILE_TEXT_ENCODER,
    FILE_VECTOR_ESTIMATOR,
    FILE_VOCODER,
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
        val text = juneVoiceModelText(chunk)
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
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(JUNE_VOICE_TOTAL_STEPS.toFloat())), STEP_SHAPE).use { totalStep ->
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
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatArrayOf(step.toFloat())), STEP_SHAPE).use { currentStep ->
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

    /** Gives the memory back: the sessions first, then the files they read, which had to stay open as long as the sessions. */
    fun close() {
        runCatchingExceptions {
            styleTtl.close()
            styleDp.close()
            sessions.forEach { it.close() }
        }.onFailure { Timber.tag(TAG).e(it, "Cannot close the built-in voice model") }
        closeables.forEach { juneVoiceCloseQuietly(it) }
    }

    companion object {
        /**
         * Loads the model of the built-in voice, or says where it stopped (files missing, not enough memory or storage, ONNX Runtime
         * that cannot be used). The model files are read straight from the package of the app when they are stored there
         * uncompressed, so nothing is copied and nothing is kept in the Java heap; a file for which that does not work is copied once
         * into the files of the app and read from there.
         */
        fun load(context: Context): JuneVoiceModelLoad {
            val failure = try {
                return loadOrThrow(context)
            } catch (reported: JuneVoiceLoadException) {
                reported
            } catch (error: Throwable) {
                // Every step says where it stopped; this is only for what would happen in between
                juneVoiceLoadException(JuneVoiceLoadStep.Environment, null, error) as? JuneVoiceLoadException ?: throw error
            }
            Timber.tag(TAG).e(failure, "Cannot load the built-in voice model: %s", failure.failure.reason)
            return JuneVoiceModelLoad.Failed(failure.failure.reason)
        }

        private fun loadOrThrow(context: Context): JuneVoiceModelLoad.Loaded {
            val (environment, options) = juneVoiceStep(JuneVoiceLoadStep.Environment, null) {
                OrtEnvironment.getEnvironment() to OrtSession.SessionOptions().apply {
                    // Half of the cores, so the reading does not take the whole phone while the app is in the background
                    setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))
                }
            }
            val sources = mutableListOf<Closeable>()
            val sessions = mutableListOf<OrtSession>()
            val copied = mutableListOf<String>()
            try {
                JUNE_VOICE_SESSION_FILES.forEach { name ->
                    val opened = juneVoiceOpenSession(
                        file = name,
                        openMapped = { JuneVoiceSource.mapped(context, name) },
                        openCopied = { JuneVoiceSource.copied(context, name) },
                        createSession = { source -> source.createSession(environment, options) },
                    )
                    sources += opened.source
                    sessions += opened.session
                    if (opened.copied) copied += name
                }
                val config = juneVoiceStep(JuneVoiceLoadStep.ReadConfig, FILE_CONFIG) { JSONObject(readAssetText(context, FILE_CONFIG)) }
                val (sampleRate, baseChunkSize) = juneVoiceStep(JuneVoiceLoadStep.ReadConfig, FILE_CONFIG) {
                    val autoEncoder = config.getJSONObject("ae")
                    autoEncoder.getInt("sample_rate") to autoEncoder.getInt("base_chunk_size")
                }
                val (chunkCompressFactor, latentDim) = juneVoiceStep(JuneVoiceLoadStep.ReadConfig, FILE_CONFIG) {
                    val latentConfig = config.getJSONObject("ttl")
                    latentConfig.getInt("chunk_compress_factor") to latentConfig.getInt("latent_dim")
                }
                val indexer = juneVoiceStep(JuneVoiceLoadStep.ReadConfig, FILE_INDEXER) { readIndexer(readAssetText(context, FILE_INDEXER)) }
                val styles = juneVoiceStep(JuneVoiceLoadStep.ReadConfig, FILE_VOICE_STYLE) {
                    val style = JSONObject(readAssetText(context, FILE_VOICE_STYLE))
                    val styleTtl = readStyle(environment, style.getJSONObject("style_ttl"))
                    try {
                        styleTtl to readStyle(environment, style.getJSONObject("style_dp"))
                    } catch (error: Throwable) {
                        styleTtl.close()
                        throw error
                    }
                }
                if (copied.isNotEmpty()) Timber.tag(TAG).w("The model files %s were copied to be read", copied)
                val model = JuneVoiceModel(
                    environment = environment,
                    sessions = sessions,
                    closeables = sources,
                    indexer = indexer,
                    styleTtl = styles.first,
                    styleDp = styles.second,
                    sampleRate = sampleRate,
                    baseChunkSize = baseChunkSize,
                    chunkCompressFactor = chunkCompressFactor,
                    latentDim = latentDim,
                )
                return JuneVoiceModelLoad.Loaded(model, copied)
            } catch (error: Throwable) {
                sessions.forEach { runCatchingExceptions { it.close() } }
                sources.forEach { juneVoiceCloseQuietly(it) }
                throw error
            }
        }

        /** The small text files are read from the package as they are, compressed or not: they are a few hundred KB. */
        private fun readAssetText(context: Context, name: String): String =
            context.assets.open("$JUNE_VOICE_ASSETS/$name").use { input -> String(input.readBytes(), Charsets.UTF_8) }

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

/** What [JuneVoiceModel.load] gives: the model and the files that had to be copied, or the reason it could not be loaded. */
internal sealed interface JuneVoiceModelLoad {
    class Loaded(val model: JuneVoiceModel, val copiedFiles: List<String>) : JuneVoiceModelLoad
    class Failed(val reason: String) : JuneVoiceModelLoad
}

/**
 * Where one model file is read from: straight from the package of the app when it is stored there uncompressed (nothing is copied),
 * else from a copy made once in the files of the app. It stays open while its session is used, and is closed with the model.
 */
internal sealed interface JuneVoiceSource : Closeable {
    fun createSession(environment: OrtEnvironment, options: OrtSession.SessionOptions): OrtSession

    /**
     * The file is mapped from the package: ONNX Runtime reads it without copying it to the Java heap. The descriptor of the asset and
     * its stream share one file descriptor, so both stay open until [close]: closing the descriptor first leaves the channel with a
     * closed file descriptor, and mapping it then fails.
     */
    class Mapped(private val descriptor: AssetFileDescriptor) : JuneVoiceSource {
        private val stream: FileInputStream = descriptor.createInputStream()

        override fun createSession(environment: OrtEnvironment, options: OrtSession.SessionOptions): OrtSession {
            val buffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
            return environment.createSession(buffer, options)
        }

        override fun close() {
            juneVoiceCloseQuietly(stream)
            juneVoiceCloseQuietly(descriptor)
        }
    }

    /** The file was copied into the files of the app and is read by its path. */
    class Copied(private val file: File) : JuneVoiceSource {
        override fun createSession(environment: OrtEnvironment, options: OrtSession.SessionOptions): OrtSession =
            environment.createSession(file.absolutePath, options)

        override fun close() = Unit
    }

    companion object {
        fun mapped(context: Context, name: String): Mapped {
            // openFd only works on an asset stored uncompressed, which is what the build makes sure of for the model files
            val descriptor = context.assets.openFd("$JUNE_VOICE_ASSETS/$name")
            return try {
                Mapped(descriptor)
            } catch (error: Throwable) {
                juneVoiceCloseQuietly(descriptor)
                throw error
            }
        }

        /** Copies the file once into the files of the app; the length of the asset is checked when the package gives it. */
        fun copied(context: Context, name: String): Copied {
            val assetPath = "$JUNE_VOICE_ASSETS/$name"
            val expectedLength = runCatchingExceptions { context.assets.openFd(assetPath).use { it.length } }.getOrNull()
            val folder = File(context.filesDir, JUNE_VOICE_ASSETS).apply { mkdirs() }
            val file = juneVoiceCopy(File(folder, name), expectedLength, usableSpace = { folder.usableSpace }) {
                context.assets.open(assetPath, AssetManager.ACCESS_STREAMING)
            }
            return Copied(file)
        }
    }
}
