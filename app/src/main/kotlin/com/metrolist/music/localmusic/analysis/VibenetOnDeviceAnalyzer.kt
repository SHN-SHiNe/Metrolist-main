/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VibenetOnDeviceAnalyzer
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val session: OrtSession by lazy {
        val options =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
        context.assets.open(MODEL_ASSET_PATH).use { input ->
            environment.createSession(input.readBytes(), options)
        }
    }

    suspend fun analyze(mel: VibenetMelSpectrogram): VibenetEmotionResult =
        withContext(Dispatchers.Default) {
            val shape = longArrayOf(1L, mel.melBands.toLong(), mel.frames.toLong())
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(mel.data), shape).use { tensor ->
                session.run(mapOf(INPUT_NAME to tensor)).use { results ->
                    VibenetOutputParser.parse(results[0].value).normalized()
                }
            }
        }

    private companion object {
        const val MODEL_ASSET_PATH = "vibenet/efficientnet_model.onnx"
        const val INPUT_NAME = "x"
    }
}

internal object VibenetOutputParser {
    fun parse(value: Any): VibenetEmotionResult {
        val logits =
            when (value) {
                is Array<*> -> {
                    val first = value.firstOrNull()
                    when (first) {
                        is FloatArray -> first
                        is Array<*> -> first.filterIsInstance<Float>().toFloatArray()
                        else -> error("Unexpected vibenet output row: ${first?.javaClass?.name}")
                    }
                }
                is FloatArray -> value
                else -> error("Unexpected vibenet output: ${value.javaClass.name}")
            }
        require(logits.size >= 7) { "vibenet output has ${logits.size} values, expected 7" }
        return VibenetEmotionResult(
            acousticness = sigmoid(logits[0]),
            danceability = logits[1].coerceIn(0f, 1f),
            energy = logits[2].coerceIn(0f, 1f),
            instrumentalness = sigmoid(logits[3]),
            liveness = sigmoid(logits[4]),
            speechiness = logits[5].coerceIn(0f, 1f),
            valence = logits[6].coerceIn(0f, 1f),
        )
    }

    private fun sigmoid(value: Float): Float =
        (1.0 / (1.0 + kotlin.math.exp(-value.toDouble()))).toFloat()
}
