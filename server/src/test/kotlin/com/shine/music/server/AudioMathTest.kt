package com.shine.music.server

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioMathTest {
    @Test
    fun `hann window matches the periodic window used by VibeNet`() {
        val window = AudioMath.hannWindow(4)

        assertTrue(window.zip(floatArrayOf(0f, 0.5f, 1f, 0.5f)).all { (actual, expected) -> abs(actual - expected) < 0.0001f })
    }

    @Test
    fun `fft of an impulse has flat power`() {
        val power = AudioMath.fftPower(floatArrayOf(1f), fftSize = 8)

        assertEquals(5, power.size)
        assertTrue(power.all { abs(it - 1f) < 0.0001f })
    }

    @Test
    fun `fft workspace clears buffers between frames`() {
        val workspace = FftPowerWorkspace(8)
        assertTrue(workspace.compute(floatArrayOf(1f)).all { abs(it - 1f) < 0.0001f })

        assertTrue(workspace.compute(FloatArray(8)).all { abs(it) < 0.0001f })
    }

    @Test
    fun `linear resampling never returns an empty signal`() {
        assertContentEquals(floatArrayOf(0.25f), AudioMath.resampleLinear(floatArrayOf(0.25f), 48_000, 1))
        assertFailsWith<IllegalArgumentException> { AudioMath.resampleLinear(floatArrayOf(1f), 0, 16_000) }
    }
}
