/*
 * Ported from the SHiNe Music Android audio-analysis implementation.
 * Licensed under GPL-3.0; see the repository history for contributors.
 */
package com.shine.music.server

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Estimates tempo and musical key without Android or native dependencies. */
class TempoKeyAnalyzer {
    fun estimateBpm(samples: FloatArray, sampleRate: Int): Float {
        if (samples.size < ENERGY_FRAME_SIZE || sampleRate <= 0) return DEFAULT_BPM
        val energies = frameEnergies(samples)
        if (energies.size < 4) return DEFAULT_BPM
        val onset = FloatArray(energies.size - 1)
        for (index in onset.indices) onset[index] = max(0f, energies[index + 1] - energies[index])
        val frameRate = sampleRate.toFloat() / ENERGY_HOP_SIZE
        var bestBpm = DEFAULT_BPM
        var bestScore = Float.NEGATIVE_INFINITY
        for (bpm in MIN_BPM..MAX_BPM) {
            val lag = (60f * frameRate / bpm).roundToInt()
            if (lag <= 0 || lag >= onset.size) continue
            var score = 0f
            for (index in lag until onset.size) score += onset[index] * onset[index - lag]
            val halfLag = lag / 2
            if (halfLag > 0) {
                for (index in halfLag until onset.size) score += onset[index] * onset[index - halfLag] * 0.35f
            }
            if (score > bestScore) {
                bestScore = score
                bestBpm = bpm.toFloat()
            }
        }
        return if (bestBpm < 80f && bestBpm * 2f <= MAX_BPM) bestBpm * 2f else bestBpm
    }

    fun estimateKey(samples: FloatArray, sampleRate: Int): String {
        if (samples.size < KEY_FRAME_SIZE || sampleRate <= 0) return "C"
        val chroma = pitchClassEnergy(samples, sampleRate)
        val major = FloatArray(12)
        val minor = FloatArray(12)
        for (key in 0 until 12) {
            major[key] = correlation(chroma, MAJOR_PROFILE, key)
            minor[key] = correlation(chroma, MINOR_PROFILE, key)
        }
        val majorIndex = major.indices.maxBy { major[it] }
        val minorIndex = minor.indices.maxBy { minor[it] }
        return if (major[majorIndex] > minor[minorIndex]) KEY_NAMES[majorIndex] else "${KEY_NAMES[minorIndex]}m"
    }

    private fun frameEnergies(samples: FloatArray): FloatArray {
        val count = ((samples.size - ENERGY_FRAME_SIZE) / ENERGY_HOP_SIZE).coerceAtLeast(0) + 1
        return FloatArray(count) { frameIndex ->
            val start = frameIndex * ENERGY_HOP_SIZE
            var sum = 0f
            for (offset in 0 until ENERGY_FRAME_SIZE) sum += abs(samples.getOrElse(start + offset) { 0f })
            sum / ENERGY_FRAME_SIZE
        }
    }

    private fun pitchClassEnergy(samples: FloatArray, sampleRate: Int): FloatArray {
        val window = AudioMath.hannWindow(KEY_FRAME_SIZE)
        val chroma = FloatArray(12)
        val frame = FloatArray(KEY_FFT_SIZE)
        val fftWorkspace = FftPowerWorkspace(KEY_FFT_SIZE)
        var frameCount = 0
        var start = 0
        while (start + KEY_FRAME_SIZE <= samples.size) {
            for (offset in 0 until KEY_FRAME_SIZE) frame[offset] = samples[start + offset] * window[offset]
            val power = fftWorkspace.compute(frame)
            for (bin in 1 until power.size) {
                val frequency = bin.toDouble() * sampleRate / KEY_FFT_SIZE
                if (frequency < MIN_KEY_FREQUENCY || frequency > MAX_KEY_FREQUENCY) continue
                val midi = (69 + 12 * (ln(frequency / 440.0) / LN_2)).roundToInt()
                val pitchClass = ((midi % 12) + 12) % 12
                chroma[pitchClass] += sqrt(power[bin])
            }
            frameCount++
            start += KEY_HOP_SIZE
        }
        if (frameCount == 0) return chroma
        val total = chroma.sum().takeIf { it > 0f } ?: return chroma
        for (index in chroma.indices) chroma[index] /= total
        return chroma
    }

    private fun correlation(chroma: FloatArray, profile: FloatArray, key: Int): Float {
        val chromaMean = chroma.average().toFloat()
        val profileMean = profile.average().toFloat()
        var numerator = 0f
        var chromaDenominator = 0f
        var profileDenominator = 0f
        for (index in 0 until 12) {
            val chromaValue = chroma[(index + key) % 12] - chromaMean
            val profileValue = profile[index] - profileMean
            numerator += chromaValue * profileValue
            chromaDenominator += chromaValue * chromaValue
            profileDenominator += profileValue * profileValue
        }
        val denominator = sqrt(chromaDenominator * profileDenominator)
        return if (denominator == 0f) 0f else numerator / denominator
    }

    private companion object {
        const val DEFAULT_BPM = 120f
        const val MIN_BPM = 40
        const val MAX_BPM = 220
        const val ENERGY_FRAME_SIZE = 1024
        const val ENERGY_HOP_SIZE = 512
        const val KEY_FRAME_SIZE = 4096
        const val KEY_FFT_SIZE = 4096
        const val KEY_HOP_SIZE = 2048
        const val MIN_KEY_FREQUENCY = 55.0
        const val MAX_KEY_FREQUENCY = 4186.0
        val LN_2 = ln(2.0)
        val KEY_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val MAJOR_PROFILE = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
        val MINOR_PROFILE = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)
    }
}
