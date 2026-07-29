package com.shine.music.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioAnalysisSupportTest {
    @Test
    fun `plans centered bounded samples at 20 50 and 80 percent`() {
        val segments = AudioSamplePlan.forDuration(durationSeconds = 180.0)

        assertEquals(listOf(21.0, 75.0, 129.0), segments.map { it.startSeconds })
        assertTrue(segments.all { it.durationSeconds == 30.0 })
    }

    @Test
    fun `short tracks are decoded once instead of three duplicate times`() {
        val segments = AudioSamplePlan.forDuration(durationSeconds = 12.5)

        assertEquals(listOf(AudioSampleSegment(0.0, 12.5)), segments)
    }

    @Test
    fun `parses finite positive ffprobe duration only`() {
        assertEquals(183.42, parseFfprobeDuration("183.420000\n"))
        assertFailsWith<IllegalArgumentException> { parseFfprobeDuration("N/A") }
        assertFailsWith<IllegalArgumentException> { parseFfprobeDuration("0") }
    }

    @Test
    fun `architecture gate is explicit`() {
        assertTrue(AnalysisArchitecture.isSupported("amd64"))
        assertTrue(AnalysisArchitecture.isSupported("x86_64"))
        assertFalse(AnalysisArchitecture.isSupported("aarch64"))
        assertTrue(AnalysisArchitecture.unavailableReason("arm64").contains("x86_64"))
    }

    @Test
    fun `vibenet parser applies the legacy output activations`() {
        val result = VibenetOutputParser.parse(arrayOf(floatArrayOf(0f, -1f, 2f, 0f, 2f, 0.25f, 0.75f)))

        assertEquals(0.5f, result.acousticness, absoluteTolerance = 0.0001f)
        assertEquals(0f, result.danceability)
        assertEquals(1f, result.energy)
        assertEquals(0.5f, result.instrumentalness, absoluteTolerance = 0.0001f)
        assertTrue(result.liveness in 0.88f..0.89f)
        assertEquals(0.25f, result.speechiness)
        assertEquals(0.75f, result.valence)
    }

    @Test
    fun `emotion aggregation averages all decoded segments`() {
        val average = averageEmotionResults(
            listOf(
                VibenetEmotionResult(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f, 0.5f),
                VibenetEmotionResult(1f, 0.4f, 0.6f, 0.8f, 0f, 0f, 0.7f),
            ),
        )

        assertEquals(0.5f, average.acousticness)
        assertEquals(0.3f, average.danceability, absoluteTolerance = 0.0001f)
        assertEquals(0.5f, average.energy)
        assertEquals(0.7f, average.instrumentalness, absoluteTolerance = 0.0001f)
        assertEquals(0.4f, average.liveness)
        assertEquals(0.5f, average.speechiness)
        assertEquals(0.6f, average.valence, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `mel extraction keeps the VibeNet shape finite for silence`() {
        val mel = VibenetMelExtractor().extract(FloatArray(VibenetMelExtractor.SAMPLE_RATE))

        assertEquals(128, mel.melBands)
        assertEquals(49, mel.frames)
        assertEquals(mel.melBands * mel.frames, mel.data.size)
        assertTrue(mel.data.all(Float::isFinite))
    }
}
