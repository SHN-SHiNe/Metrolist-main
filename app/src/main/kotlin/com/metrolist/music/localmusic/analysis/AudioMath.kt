/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object AudioMath {
    fun hannWindow(size: Int): FloatArray {
        require(size > 0) { "Window size must be positive" }
        return FloatArray(size) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / size)).toFloat()
        }
    }

    fun resampleLinear(
        input: FloatArray,
        sourceRate: Int,
        targetRate: Int,
    ): FloatArray {
        require(sourceRate > 0) { "Source rate must be positive" }
        require(targetRate > 0) { "Target rate must be positive" }
        if (input.isEmpty() || sourceRate == targetRate) return input.copyOf()
        val outputSize = max(1, (input.size.toDouble() * targetRate / sourceRate).roundToInt())
        val scale = sourceRate.toDouble() / targetRate
        return FloatArray(outputSize) { index ->
            val source = index * scale
            val left = floor(source).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (source - left).toFloat()
            input[left] * (1f - fraction) + input[right] * fraction
        }
    }

    fun fftPower(
        frame: FloatArray,
        fftSize: Int,
    ): FloatArray {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "FFT size must be a power of two" }
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)
        val copySize = minOf(frame.size, fftSize)
        for (index in 0 until copySize) {
            real[index] = frame[index].toDouble()
        }

        fftInPlace(real, imag)

        return FloatArray(fftSize / 2 + 1) { index ->
            (real[index] * real[index] + imag[index] * imag[index]).toFloat()
        }
    }

    fun melFilterBank(
        sampleRate: Int,
        fftSize: Int,
        melCount: Int,
        fMin: Float,
        fMax: Float,
    ): Array<FloatArray> {
        require(sampleRate > 0) { "Sample rate must be positive" }
        require(fftSize > 0) { "FFT size must be positive" }
        require(melCount > 0) { "Mel count must be positive" }
        val binCount = fftSize / 2 + 1
        val minMel = hzToMel(fMin.toDouble())
        val maxMel = hzToMel(fMax.toDouble())
        val melPoints =
            DoubleArray(melCount + 2) { index ->
                minMel + (maxMel - minMel) * index / (melCount + 1)
            }
        val hzPoints = melPoints.map(::melToHz)
        val bins =
            hzPoints.map { hz ->
                floor((fftSize + 1) * hz / sampleRate).toInt().coerceIn(0, binCount - 1)
            }

        return Array(melCount) { melIndex ->
            val filter = FloatArray(binCount)
            val left = bins[melIndex]
            val center = bins[melIndex + 1]
            val right = bins[melIndex + 2]
            if (center > left) {
                for (bin in left until center) {
                    filter[bin] = (bin - left).toFloat() / (center - left)
                }
            }
            if (right > center) {
                for (bin in center until right) {
                    filter[bin] = (right - bin).toFloat() / (right - center)
                }
            }
            val enorm = 2.0 / (hzPoints[melIndex + 2] - hzPoints[melIndex]).coerceAtLeast(1.0)
            for (bin in filter.indices) {
                filter[bin] = (filter[bin] * enorm).toFloat()
            }
            filter
        }
    }

    fun powerToDb(
        power: FloatArray,
        topDb: Float = 80f,
        amin: Float = 1e-10f,
        ref: Float = 1f,
    ): FloatArray {
        val refDb = 10f * log10(max(amin, ref))
        val converted =
            FloatArray(power.size) { index ->
                10f * log10(max(amin, power[index])) - refDb
            }
        val maxDb = converted.maxOrNull() ?: 0f
        val floorDb = maxDb - topDb
        for (index in converted.indices) {
            if (converted[index] < floorDb) converted[index] = floorDb
        }
        return converted
    }

    private fun fftInPlace(real: DoubleArray, imag: DoubleArray) {
        val size = real.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal
                val tempImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tempImag
            }
        }

        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val wLenReal = cos(angle)
            val wLenImag = sin(angle)
            var start = 0
            while (start < size) {
                var wReal = 1.0
                var wImag = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imag[odd] * wImag
                    val oddImag = real[odd] * wImag + imag[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag
                    val nextReal = wReal * wLenReal - wImag * wLenImag
                    val nextImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextReal
                    wImag = nextImag
                }
                start += length
            }
            length = length shl 1
        }
    }

    private fun hzToMel(hz: Double): Double {
        val minLogHz = 1000.0
        val minLogMel = 15.0
        val logStep = ln(6.4) / 27.0
        return if (hz < minLogHz) {
            3.0 * hz / 200.0
        } else {
            minLogMel + ln(hz / minLogHz) / logStep
        }
    }

    private fun melToHz(mel: Double): Double {
        val minLogHz = 1000.0
        val minLogMel = 15.0
        val logStep = ln(6.4) / 27.0
        return if (mel < minLogMel) {
            200.0 * mel / 3.0
        } else {
            minLogHz * (logStep * (mel - minLogMel)).let { kotlin.math.E.pow(it) }
        }
    }
}
