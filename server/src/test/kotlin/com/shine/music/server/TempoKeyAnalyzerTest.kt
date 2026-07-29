package com.shine.music.server

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TempoKeyAnalyzerTest {
    private val analyzer = TempoKeyAnalyzer()

    @Test
    fun `estimates a steady 120 bpm pulse train`() {
        val sampleRate = 16_000
        val samples = FloatArray(sampleRate * 30)
        val beatSamples = sampleRate / 2
        for (beat in samples.indices step beatSamples) {
            for (offset in 0 until 320) {
                val index = beat + offset
                if (index < samples.size) samples[index] = 1f - offset / 320f
            }
        }

        assertTrue(analyzer.estimateBpm(samples, sampleRate) in 116f..124f)
    }

    @Test
    fun `identifies a synthesized C major triad`() {
        val sampleRate = 16_000
        val frequencies = doubleArrayOf(261.6256, 329.6276, 391.9954)
        val samples = FloatArray(sampleRate * 4) { index ->
            (frequencies.sumOf { frequency -> sin(2.0 * PI * frequency * index / sampleRate) } / frequencies.size).toFloat()
        }

        assertEquals("C", analyzer.estimateKey(samples, sampleRate))
    }

    @Test
    fun `short audio receives stable defaults`() {
        assertEquals(120f, analyzer.estimateBpm(FloatArray(20), 16_000))
        assertEquals("C", analyzer.estimateKey(FloatArray(20), 16_000))
    }
}
