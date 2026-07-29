package com.shine.music.server

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path

interface EmotionAnalyzer : AutoCloseable {
    fun analyze(mel: VibenetMelSpectrogram): VibenetEmotionResult
}

/** Single-session VibeNet inference. The serial analysis manager is its only caller. */
class VibenetOnnxAnalyzer private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : EmotionAnalyzer {
    override fun analyze(mel: VibenetMelSpectrogram): VibenetEmotionResult {
        require(mel.melBands > 0 && mel.frames > 0) { "VibeNet mel input is empty" }
        require(mel.data.size == mel.melBands * mel.frames) { "VibeNet mel shape does not match its data" }
        val shape = longArrayOf(1L, mel.melBands.toLong(), mel.frames.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(mel.data), shape).use { tensor ->
            session.run(mapOf(INPUT_NAME to tensor)).use { results ->
                VibenetOutputParser.parse(results[0].value).normalized()
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private const val INPUT_NAME = "x"

        fun open(modelPath: Path): VibenetOnnxAnalyzer {
            require(Files.isRegularFile(modelPath) && Files.isReadable(modelPath)) { "VibeNet model is not readable: $modelPath" }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            return try {
                VibenetOnnxAnalyzer(environment, environment.createSession(modelPath.toAbsolutePath().toString(), options))
            } finally {
                options.close()
            }
        }
    }
}

internal object VibenetOutputParser {
    fun parse(value: Any): VibenetEmotionResult {
        val logits = when (value) {
            is FloatArray -> value
            is Array<*> -> extractRow(value.firstOrNull())
            else -> error("Unexpected VibeNet output: ${value.javaClass.name}")
        }
        require(logits.size >= OUTPUT_SIZE) { "VibeNet output has ${logits.size} values; expected at least $OUTPUT_SIZE" }
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

    private fun extractRow(row: Any?): FloatArray = when (row) {
        is FloatArray -> row
        is Array<*> -> row.map { value -> (value as? Number)?.toFloat() ?: error("VibeNet output contains a non-number") }.toFloatArray()
        else -> error("Unexpected VibeNet output row: ${row?.javaClass?.name}")
    }

    private fun sigmoid(value: Float): Float = (1.0 / (1.0 + kotlin.math.exp(-value.toDouble()))).toFloat()

    private const val OUTPUT_SIZE = 7
}

object AnalysisArchitecture {
    fun isSupported(architecture: String): Boolean = architecture.trim().lowercase() in setOf("amd64", "x86_64", "x64")

    fun unavailableReason(architecture: String): String =
        "VibeNet ONNX analysis requires x86_64; current architecture is ${architecture.ifBlank { "unknown" }}"
}

object VibenetModelLocator {
    private const val ENVIRONMENT_VARIABLE = "SHINE_VIBENET_MODEL_PATH"
    private const val SYSTEM_PROPERTY = "shine.vibenet.model"

    fun resolve(): Path {
        val configured = System.getProperty(SYSTEM_PROPERTY)?.takeIf(String::isNotBlank)
            ?: System.getenv(ENVIRONMENT_VARIABLE)?.takeIf(String::isNotBlank)
        if (configured != null) return Path.of(configured)

        val candidates = listOf(
            Path.of("models", "vibenet", "efficientnet_model.onnx"),
            Path.of("server", "src", "main", "resources", "vibenet", "efficientnet_model.onnx"),
            Path.of("app", "src", "main", "assets", "vibenet", "efficientnet_model.onnx"),
            Path.of("/app", "models", "vibenet", "efficientnet_model.onnx"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) } ?: candidates.first()
    }
}
