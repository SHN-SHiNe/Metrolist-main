package com.shine.music.server

import java.nio.file.Files
import java.nio.file.Path

internal interface TrackAudioAnalyzer : AutoCloseable {
    fun analyze(path: Path, progress: (Float, String) -> Unit): AudioAnalysisResult
}

/**
 * Composes bounded ffmpeg sampling, pure tempo/key analysis, and VibeNet inference.
 * The largest PCM allocation is capped at 90 seconds of mono 16 kHz float samples.
 */
internal class SegmentedAudioAnalyzer(
    private val sampler: AudioSegmentSampler,
    private val emotionAnalyzer: EmotionAnalyzer,
    private val melExtractor: VibenetMelExtractor = VibenetMelExtractor(),
    private val tempoKeyAnalyzer: TempoKeyAnalyzer = TempoKeyAnalyzer(),
) : TrackAudioAnalyzer {
    override fun analyze(path: Path, progress: (Float, String) -> Unit): AudioAnalysisResult {
        require(Files.isRegularFile(path)) { "Audio file no longer exists" }
        checkNotInterrupted()
        progress(0.12f, "正在抽取三个音频片段")
        val segments = sampler.sample(path)
        require(segments.isNotEmpty()) { "No audio samples were decoded" }
        require(segments.all { it.sampleRate == VibenetMelExtractor.SAMPLE_RATE }) { "Decoded samples must be mono 16 kHz PCM" }

        checkNotInterrupted()
        progress(0.48f, "正在估算速度与调性")
        val combined = concatenate(segments)
        val bpm = tempoKeyAnalyzer.estimateBpm(combined, VibenetMelExtractor.SAMPLE_RATE)
        val key = tempoKeyAnalyzer.estimateKey(combined, VibenetMelExtractor.SAMPLE_RATE)

        val emotions = ArrayList<VibenetEmotionResult>(segments.size)
        segments.forEachIndexed { index, segment ->
            checkNotInterrupted()
            progress(0.58f + 0.32f * index / segments.size, "正在分析音色片段 ${index + 1}/${segments.size}")
            emotions += emotionAnalyzer.analyze(melExtractor.extract(segment.samples))
        }
        val average = averageEmotionResults(emotions)
        progress(0.94f, "正在保存分析结果")
        return AudioAnalysisResult(
            bpm = bpm,
            keyName = key,
            valence = average.valence,
            energy = average.energy,
            danceability = average.danceability,
            acousticness = average.acousticness,
            instrumentalness = average.instrumentalness,
            liveness = average.liveness,
            speechiness = average.speechiness,
        )
    }

    private fun concatenate(segments: List<DecodedAudioSegment>): FloatArray {
        val sampleCount = segments.sumOf { it.samples.size.toLong() }
        require(sampleCount in 1..MAX_COMBINED_SAMPLES) { "Decoded PCM exceeds the bounded analysis window" }
        val combined = FloatArray(sampleCount.toInt())
        var destination = 0
        for (segment in segments) {
            segment.samples.copyInto(combined, destinationOffset = destination)
            destination += segment.samples.size
        }
        return combined
    }

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio analysis interrupted")
    }

    override fun close() = emotionAnalyzer.close()

    private companion object {
        const val MAX_COMBINED_SAMPLES = 3L * 30L * VibenetMelExtractor.SAMPLE_RATE
    }
}

internal data class AudioAnalysisRuntime(
    val analyzer: TrackAudioAnalyzer?,
    val available: Boolean,
    val implementation: String,
    val unavailableReason: String?,
) : AutoCloseable {
    override fun close() {
        analyzer?.close()
    }

    companion object {
        private const val IMPLEMENTATION = "ffmpeg-segmented-vibenet-onnx-x86_64"

        fun create(
            modelPath: Path,
            architecture: String,
            sampler: AudioSegmentSampler = FfmpegAudioSampler(),
        ): AudioAnalysisRuntime {
            if (!AnalysisArchitecture.isSupported(architecture)) {
                return unavailable(AnalysisArchitecture.unavailableReason(architecture))
            }
            if (!Files.isRegularFile(modelPath) || !Files.isReadable(modelPath)) {
                return unavailable("VibeNet model is unavailable; configure SHINE_VIBENET_MODEL_PATH")
            }
            return try {
                val inference = VibenetOnnxAnalyzer.open(modelPath)
                AudioAnalysisRuntime(
                    analyzer = SegmentedAudioAnalyzer(sampler, inference),
                    available = true,
                    implementation = IMPLEMENTATION,
                    unavailableReason = null,
                )
            } catch (error: UnsatisfiedLinkError) {
                unavailable("ONNX Runtime native library could not load: ${safeMessage(error)}")
            } catch (error: Exception) {
                unavailable("VibeNet could not initialize: ${safeMessage(error)}")
            }
        }

        private fun unavailable(reason: String) = AudioAnalysisRuntime(
            analyzer = null,
            available = false,
            implementation = IMPLEMENTATION,
            unavailableReason = reason,
        )

        private fun safeMessage(error: Throwable) = (error.message ?: error::class.simpleName ?: "unknown error").take(500)
    }
}
