/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TempoKeyAnalyzerTest {
    private val analyzer = TempoKeyAnalyzer()

    @Test
    fun estimatesBpmFromPulseTrain() {
        val sampleRate = 16000
        val samples = FloatArray(sampleRate * 8)
        val beatInterval = sampleRate / 2
        for (beat in samples.indices step beatInterval) {
            for (offset in 0 until 256) {
                val index = beat + offset
                if (index < samples.size) samples[index] = 1f
            }
        }

        assertEquals(120f, analyzer.estimateBpm(samples, sampleRate), 4f)
    }

    @Test
    fun estimatesMinorKeyFromTriadEnergy() {
        val sampleRate = 16000
        val frequencies = listOf(220.0, 261.6256, 329.6276)
        val samples =
            FloatArray(sampleRate * 3) { index ->
                frequencies.sumOf { freq ->
                    sin(2.0 * PI * freq * index / sampleRate)
                }.toFloat() / frequencies.size
            }

        assertEquals("Am", analyzer.estimateKey(samples, sampleRate))
    }
}
