/*
 * Ported from the SHiNe Music Android audio-analysis implementation.
 * Licensed under GPL-3.0; see the repository history for contributors.
 */
package com.shine.music.server

/** Converts ffmpeg's mono 16 kHz PCM into the exact mel layout expected by VibeNet. */
class VibenetMelExtractor {
    private val window = AudioMath.hannWindow(WINDOW_LENGTH)
    private val fftWorkspace = FftPowerWorkspace(FFT_SIZE)
    private val sparseMelBands = AudioMath.melFilterBank(
        sampleRate = SAMPLE_RATE,
        fftSize = FFT_SIZE,
        melCount = MEL_BANDS,
        fMin = 0f,
        fMax = SAMPLE_RATE / 2f,
    ).map(::toSparseBand)

    fun extract(samples16k: FloatArray): VibenetMelSpectrogram {
        val source = if (samples16k.size < WINDOW_LENGTH) samples16k.copyOf(WINDOW_LENGTH) else samples16k
        val frames = ((source.size - WINDOW_LENGTH) / HOP_LENGTH).coerceAtLeast(0) + 1
        val flattenedPower = FloatArray(MEL_BANDS * frames)
        val frame = FloatArray(FFT_SIZE)

        repeat(frames) { frameIndex ->
            frame.fill(0f)
            val start = frameIndex * HOP_LENGTH
            for (offset in 0 until WINDOW_LENGTH) {
                frame[offset] = (source.getOrNull(start + offset) ?: 0f) * window[offset]
            }
            val power = fftWorkspace.compute(frame)
            for (mel in sparseMelBands.indices) {
                val band = sparseMelBands[mel]
                var sum = 0f
                for (offset in band.weights.indices) {
                    sum += band.weights[offset] * power[band.firstBin + offset]
                }
                flattenedPower[mel * frames + frameIndex] = sum
            }
        }

        return VibenetMelSpectrogram(
            data = AudioMath.powerToDb(flattenedPower, topDb = 80f),
            melBands = MEL_BANDS,
            frames = frames,
        )
    }

    private fun toSparseBand(filter: FloatArray): SparseMelBand {
        val first = filter.indexOfFirst { it != 0f }
        if (first < 0) return SparseMelBand(0, FloatArray(0))
        val last = filter.indexOfLast { it != 0f }
        return SparseMelBand(first, filter.copyOfRange(first, last + 1))
    }

    private data class SparseMelBand(val firstBin: Int, val weights: FloatArray)

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FFT_SIZE = 1024
        private const val HOP_LENGTH = 320
        private const val WINDOW_LENGTH = 640
        private const val MEL_BANDS = 128
    }
}
