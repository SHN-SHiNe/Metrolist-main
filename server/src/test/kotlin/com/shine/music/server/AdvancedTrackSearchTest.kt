package com.shine.music.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancedTrackSearchTest {
    @Test
    fun `combines tempo Camelot and emotion filters`() {
        val criteria = AdvancedSearchRequest(
            bpm = 120f,
            bpmTolerance = 5f,
            keyName = "C",
            keyTolerance = 1,
            energy = 0.8f,
            emotionTolerance = 0.1f,
        )

        val results = AdvancedTrackSearch.rank(
            listOf(
                vector("exact", bpm = 120f, key = "C", energy = 0.8f),
                vector("relative", bpm = 122f, key = "Am", energy = 0.75f),
                vector("wrong-key", bpm = 120f, key = "F#", energy = 0.8f),
                vector("wrong-energy", bpm = 120f, key = "C", energy = 0.4f),
            ),
            criteria,
        )

        assertEquals(listOf("exact", "relative"), results.map { it.trackId })
        assertEquals(100, results.first().similarityPercent)
        assertEquals(2f, results.last().bpmDelta)
        assertEquals(0, results.last().camelotDelta)
        assertTrue(results.last().camelotModeChanged == true)
    }

    @Test
    fun `Camelot tolerance includes same-number relative and ring neighbors`() {
        assertEquals(
            setOf(7 to "B", 8 to "B", 9 to "B", 8 to "A"),
            AdvancedTrackSearch.matchingCamelot(8 to "B", 1),
        )
    }

    @Test
    fun `advanced BPM target is strictly limited to product range`() {
        assertFailsWith<IllegalArgumentException> {
            AdvancedTrackSearch.rank(listOf(vector("low", 40f, "C", .5f)), AdvancedSearchRequest(bpm = 39f))
        }
        assertFailsWith<IllegalArgumentException> {
            AdvancedTrackSearch.rank(listOf(vector("high", 220f, "C", .5f)), AdvancedSearchRequest(bpm = 221f))
        }
        assertEquals(
            listOf("edge-low", "edge-high"),
            AdvancedTrackSearch.rank(
                listOf(vector("edge-low", 40f, "C", .5f), vector("edge-high", 220f, "C", .5f)),
                AdvancedSearchRequest(bpmTolerance = 60f),
            ).map { it.trackId },
        )
    }

    @Test
    fun `ranked explanations preserve signed tempo and Camelot deltas`() {
        val match = AdvancedTrackSearch.rank(
            listOf(vector("neighbor", 118f, "F", .7f)),
            AdvancedSearchRequest(bpm = 120f, bpmTolerance = 5f, keyName = "8B", keyTolerance = 1),
        ).single()

        assertEquals(-2f, match.bpmDelta)
        assertEquals(-1, match.camelotDelta)
        assertFalse(match.camelotModeChanged ?: true)
    }

    private fun vector(id: String, bpm: Float, key: String, energy: Float) = TrackFeatureVector(
        trackId = id,
        bpm = bpm,
        keyName = key,
        valence = 0.5f,
        energy = energy,
        danceability = 0.5f,
        acousticness = 0.5f,
        instrumentalness = 0.5f,
        liveness = 0.5f,
        speechiness = 0.5f,
    )
}
