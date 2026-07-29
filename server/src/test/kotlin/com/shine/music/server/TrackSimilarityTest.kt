package com.shine.music.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackSimilarityTest {
    @Test
    fun `keeps only close tempo and Camelot compatible tracks`() {
        val seed = features(id = "seed", bpm = 120f, key = "Am")
        val sameKey = features(id = "same", bpm = 121f, key = "Am", energy = 0.72f)
        val relative = features(id = "relative", bpm = 122f, key = "C", energy = 0.68f)
        val wrongTempo = features(id = "tempo", bpm = 130f, key = "Am")
        val wrongKey = features(id = "key", bpm = 119f, key = "F#")

        val ranked = TrackSimilarity.rank(seed, listOf(sameKey, relative, wrongTempo, wrongKey), limit = 30)

        assertEquals(listOf("same", "relative"), ranked.map { it.trackId })
        assertTrue(ranked.first().similarityPercent >= ranked.last().similarityPercent)
    }

    @Test
    fun `normalizes legacy percentage metrics`() {
        val seed = features(id = "seed", valence = 80f)
        val candidate = features(id = "candidate", valence = 0.8f)

        assertEquals(100, TrackSimilarity.rank(seed, listOf(candidate), limit = 1).single().similarityPercent)
    }

    @Test
    fun `returns no recommendation without all seven dimensions`() {
        val seed = features(id = "seed").copy(speechiness = null)

        assertTrue(TrackSimilarity.rank(seed, listOf(features(id = "candidate")), limit = 10).isEmpty())
    }

    private fun features(
        id: String,
        bpm: Float = 120f,
        key: String = "Am",
        valence: Float = 0.75f,
        energy: Float = 0.70f,
    ) = TrackFeatureVector(
        trackId = id,
        bpm = bpm,
        keyName = key,
        valence = valence,
        energy = energy,
        danceability = 0.66f,
        acousticness = 0.20f,
        instrumentalness = 0.10f,
        liveness = 0.12f,
        speechiness = 0.08f,
    )
}
