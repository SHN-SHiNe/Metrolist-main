/*
 * Ported from the SHiNe Music Android audio-analysis implementation.
 * Licensed under GPL-3.0; see the repository history for contributors.
 */
package com.shine.music.server

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal class FftPowerWorkspace(private val fftSize: Int) {
    private val real = DoubleArray(fftSize)
    private val imaginary = DoubleArray(fftSize)
    private val power = FloatArray(fftSize / 2 + 1)

    init {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "FFT size must be a power of two" }
    }

    fun compute(frame: FloatArray): FloatArray = AudioMath.fftPowerInto(frame, fftSize, real, imaginary, power)
}

/** Pure JVM signal-processing helpers shared by tempo, key, and VibeNet analysis. */
object AudioMath {
    fun hannWindow(size: Int): FloatArray {
        require(size > 0) { "Window size must be positive" }
        return FloatArray(size) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / size)).toFloat()
        }
    }

    fun resampleLinear(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
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

    fun fftPower(frame: FloatArray, fftSize: Int): FloatArray = FftPowerWorkspace(fftSize).compute(frame)

    internal fun fftPowerInto(
        frame: FloatArray,
        fftSize: Int,
        real: DoubleArray,
        imaginary: DoubleArray,
        power: FloatArray,
    ): FloatArray {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "FFT size must be a power of two" }
        require(real.size == fftSize && imaginary.size == fftSize && power.size == fftSize / 2 + 1) { "FFT workspace size mismatch" }
        real.fill(0.0)
        imaginary.fill(0.0)
        for (index in 0 until minOf(frame.size, fftSize)) real[index] = frame[index].toDouble()

        fftInPlace(real, imaginary)
        for (index in power.indices) {
            power[index] = (real[index] * real[index] + imaginary[index] * imaginary[index]).toFloat()
        }
        return power
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
        require(fMin >= 0f && fMax > fMin && fMax <= sampleRate / 2f) { "Invalid mel frequency range" }
        val binCount = fftSize / 2 + 1
        val minimumMel = hzToMel(fMin.toDouble())
        val maximumMel = hzToMel(fMax.toDouble())
        val melPoints = DoubleArray(melCount + 2) { index ->
            minimumMel + (maximumMel - minimumMel) * index / (melCount + 1)
        }
        val frequencyPoints = melPoints.map(::melToHz)
        val bins = frequencyPoints.map { frequency ->
            floor((fftSize + 1) * frequency / sampleRate).toInt().coerceIn(0, binCount - 1)
        }

        return Array(melCount) { melIndex ->
            val filter = FloatArray(binCount)
            val left = bins[melIndex]
            val center = bins[melIndex + 1]
            val right = bins[melIndex + 2]
            if (center > left) {
                for (bin in left until center) filter[bin] = (bin - left).toFloat() / (center - left)
            }
            if (right > center) {
                for (bin in center until right) filter[bin] = (right - bin).toFloat() / (right - center)
            }
            val normalization = 2.0 / (frequencyPoints[melIndex + 2] - frequencyPoints[melIndex]).coerceAtLeast(1.0)
            for (bin in filter.indices) filter[bin] = (filter[bin] * normalization).toFloat()
            filter
        }
    }

    fun powerToDb(power: FloatArray, topDb: Float = 80f, minimumAmplitude: Float = 1e-10f, reference: Float = 1f): FloatArray {
        require(topDb >= 0f) { "Top dB must not be negative" }
        val referenceDb = 10f * log10(max(minimumAmplitude, reference))
        val converted = FloatArray(power.size) { index ->
            10f * log10(max(minimumAmplitude, power[index])) - referenceDb
        }
        val floorDb = (converted.maxOrNull() ?: 0f) - topDb
        for (index in converted.indices) if (converted[index] < floorDb) converted[index] = floorDb
        return converted
    }

    private fun fftInPlace(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var reordered = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reordered and bit != 0) {
                reordered = reordered xor bit
                bit = bit shr 1
            }
            reordered = reordered xor bit
            if (index < reordered) {
                val realValue = real[index]
                real[index] = real[reordered]
                real[reordered] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reordered]
                imaginary[reordered] = imaginaryValue
            }
        }

        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val lengthReal = cos(angle)
            val lengthImaginary = sin(angle)
            var start = 0
            while (start < size) {
                var weightReal = 1.0
                var weightImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * weightReal - imaginary[odd] * weightImaginary
                    val oddImaginary = real[odd] * weightImaginary + imaginary[odd] * weightReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = weightReal * lengthReal - weightImaginary * lengthImaginary
                    val nextImaginary = weightReal * lengthImaginary + weightImaginary * lengthReal
                    weightReal = nextReal
                    weightImaginary = nextImaginary
                }
                start += length
            }
            length = length shl 1
        }
    }

    private fun hzToMel(frequency: Double): Double {
        val minimumLogFrequency = 1000.0
        val minimumLogMel = 15.0
        val logStep = ln(6.4) / 27.0
        return if (frequency < minimumLogFrequency) {
            3.0 * frequency / 200.0
        } else {
            minimumLogMel + ln(frequency / minimumLogFrequency) / logStep
        }
    }

    private fun melToHz(mel: Double): Double {
        val minimumLogFrequency = 1000.0
        val minimumLogMel = 15.0
        val logStep = ln(6.4) / 27.0
        return if (mel < minimumLogMel) {
            200.0 * mel / 3.0
        } else {
            minimumLogFrequency * kotlin.math.E.pow(logStep * (mel - minimumLogMel))
        }
    }
}
