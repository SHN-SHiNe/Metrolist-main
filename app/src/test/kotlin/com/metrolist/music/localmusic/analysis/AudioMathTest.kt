/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioMathTest {
    @Test
    fun hannWindowHasExpectedSizeAndEdges() {
        val window = AudioMath.hannWindow(640)

        assertEquals(640, window.size)
        assertEquals(0f, window.first(), 0.0001f)
        assertTrue(window[320] > 0.99f)
    }

    @Test
    fun resampleLinearProducesTargetLength() {
        val resampled = AudioMath.resampleLinear(FloatArray(44100) { 1f }, 44100, 16000)

        assertTrue(abs(resampled.size - 16000) <= 1)
        assertEquals(1f, resampled[8000], 0.0001f)
    }

    @Test
    fun fftPowerReturnsPositiveBinsForSine() {
        val sampleRate = 1024
        val frame =
            FloatArray(1024) { index ->
                sin(2.0 * PI * 64.0 * index / sampleRate).toFloat()
            }

        val power = AudioMath.fftPower(frame, 1024)

        assertEquals(513, power.size)
        assertTrue(power[64] > power[32])
        assertTrue(power[64] > power[128])
    }

    @Test
    fun melFilterBankHasExpectedShape() {
        val bank = AudioMath.melFilterBank(
            sampleRate = 16000,
            fftSize = 1024,
            melCount = 128,
            fMin = 0f,
            fMax = 8000f,
        )

        assertEquals(128, bank.size)
        assertEquals(513, bank.first().size)
        assertTrue(bank.any { filter -> filter.any { it > 0f } })
    }

    @Test
    fun powerToDbKeepsTopDbFloor() {
        val db = AudioMath.powerToDb(floatArrayOf(1f, 0.000000001f), topDb = 80f)

        assertEquals(0f, db[0], 0.0001f)
        assertEquals(-80f, db[1], 0.0001f)
    }
}
