package com.shine.music.server

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class AudioSampleSegment(val startSeconds: Double, val durationSeconds: Double)

/** Builds a fixed-size view of a track, centered at 20%, 50%, and 80% of its duration. */
object AudioSamplePlan {
    private val positions = doubleArrayOf(0.20, 0.50, 0.80)

    fun forDuration(durationSeconds: Double, segmentSeconds: Double = 30.0): List<AudioSampleSegment> {
        require(durationSeconds.isFinite() && durationSeconds > 0.0) { "Audio duration must be finite and positive" }
        require(segmentSeconds.isFinite() && segmentSeconds > 0.0 && segmentSeconds <= MAX_SEGMENT_SECONDS) {
            "Segment duration must be in (0, $MAX_SEGMENT_SECONDS]"
        }
        val length = min(durationSeconds, segmentSeconds)
        val maximumStart = (durationSeconds - length).coerceAtLeast(0.0)
        return positions
            .map { position ->
                val start = (durationSeconds * position - length / 2.0).coerceIn(0.0, maximumStart)
                AudioSampleSegment(startSeconds = start, durationSeconds = length)
            }
            .distinctBy { (it.startSeconds * 1_000).toLong() }
    }

    const val MAX_SEGMENT_SECONDS = 30.0
}

interface AudioSegmentSampler {
    fun sample(path: Path): List<DecodedAudioSegment>
}

/**
 * Uses ffprobe for duration and ffmpeg for bounded mono float PCM decoding.
 *
 * Each invocation can decode at most three 30-second segments, so memory use is independent of
 * the source track's length. Commands are passed directly to ProcessBuilder; filenames never go
 * through a shell.
 */
class FfmpegAudioSampler(
    private val ffmpegExecutable: String = "ffmpeg",
    private val ffprobeExecutable: String = "ffprobe",
    private val processRunner: ExternalProcessRunner = ExternalProcessRunner(),
) : AudioSegmentSampler {
    override fun sample(path: Path): List<DecodedAudioSegment> {
        require(Files.isRegularFile(path) && Files.isReadable(path)) { "Audio file is not readable: $path" }
        val duration = probeDuration(path)
        return AudioSamplePlan.forDuration(duration).map { segment -> decode(path, segment) }
    }

    private fun probeDuration(path: Path): Double {
        val result = processRunner.run(
            command = listOf(
                ffprobeExecutable,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                path.toString(),
            ),
            timeout = Duration.ofSeconds(20),
        )
        result.requireSuccess("ffprobe")
        return parseFfprobeDuration(result.stdout)
    }

    private fun decode(path: Path, segment: AudioSampleSegment): DecodedAudioSegment {
        val pcmPath = Files.createTempFile("shine-analysis-", ".f32le")
        return try {
            val result = processRunner.run(
                command = listOf(
                    ffmpegExecutable,
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-threads", "1",
                    "-ss", formatSeconds(segment.startSeconds),
                    "-i", path.toString(),
                    "-t", formatSeconds(segment.durationSeconds),
                    "-vn",
                    "-ac", "1",
                    "-ar", VibenetMelExtractor.SAMPLE_RATE.toString(),
                    "-acodec", "pcm_f32le",
                    "-f", "f32le",
                    "-y", pcmPath.toString(),
                ),
                timeout = Duration.ofMinutes(2),
            )
            result.requireSuccess("ffmpeg")
            val size = Files.size(pcmPath)
            val maximumBytes = (segment.durationSeconds * VibenetMelExtractor.SAMPLE_RATE * Float.SIZE_BYTES).toLong() + 4_096
            require(size in Float.SIZE_BYTES.toLong()..maximumBytes) { "ffmpeg produced an invalid PCM size: $size bytes" }
            require(size % Float.SIZE_BYTES == 0L) { "ffmpeg produced a partial float sample" }
            val bytes = Files.readAllBytes(pcmPath)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
            DecodedAudioSegment(segment.startSeconds, samples, VibenetMelExtractor.SAMPLE_RATE)
        } finally {
            Files.deleteIfExists(pcmPath)
        }
    }

    private fun formatSeconds(value: Double) = "%.3f".format(java.util.Locale.ROOT, value)
}

internal fun parseFfprobeDuration(output: String): Double {
    val duration = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.toDoubleOrNull()
    require(duration != null && duration.isFinite() && duration > 0.0) { "ffprobe returned an invalid duration" }
    return duration
}

data class ExternalProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    fun requireSuccess(processName: String) {
        check(exitCode == 0) {
            val detail = stderr.trim().ifEmpty { stdout.trim() }.take(1_000)
            "$processName exited with code $exitCode${if (detail.isEmpty()) "" else ": $detail"}"
        }
    }
}

class ExternalProcessRunner {
    fun run(command: List<String>, timeout: Duration): ExternalProcessResult {
        require(command.isNotEmpty()) { "Process command must not be empty" }
        require(!timeout.isNegative && !timeout.isZero) { "Process timeout must be positive" }
        val stdoutPath = Files.createTempFile("shine-process-stdout-", ".log")
        val stderrPath = Files.createTempFile("shine-process-stderr-", ".log")
        var process: Process? = null
        try {
            val startedProcess = ProcessBuilder(command)
                .redirectOutput(stdoutPath.toFile())
                .redirectError(stderrPath.toFile())
                .start()
            process = startedProcess
            val completed = startedProcess.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!completed) {
                startedProcess.destroy()
                if (!startedProcess.waitFor(1, TimeUnit.SECONDS)) startedProcess.destroyForcibly()
                error("${command.first()} timed out after ${timeout.seconds} seconds")
            }
            return ExternalProcessResult(
                exitCode = startedProcess.exitValue(),
                stdout = readBoundedText(stdoutPath),
                stderr = readBoundedText(stderrPath),
            )
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            Files.deleteIfExists(stdoutPath)
            Files.deleteIfExists(stderrPath)
        }
    }

    private fun readBoundedText(path: Path): String = Files.newInputStream(path).use { input ->
        String(input.readNBytes(MAX_LOG_BYTES), StandardCharsets.UTF_8)
    }

    private companion object {
        const val MAX_LOG_BYTES = 64 * 1024
    }
}
