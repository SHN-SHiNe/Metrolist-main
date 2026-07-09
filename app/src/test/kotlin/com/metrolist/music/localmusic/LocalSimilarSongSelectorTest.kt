package com.metrolist.music.localmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSimilarSongSelectorTest {
    @Test
    fun selectsHighestSimilarityCandidateOutsideRecentHistory() {
        val current =
            analysis(
                songId = "current",
                bpm = 120f,
                keyName = "Am",
                valence = 0.80f,
                energy = 0.70f,
            )
        val bestButRecent =
            analysis(
                songId = "recent_best",
                bpm = 121f,
                keyName = "Am",
                valence = 0.81f,
                energy = 0.71f,
            )
        val nextBest =
            analysis(
                songId = "next_best",
                bpm = 122f,
                keyName = "C",
                valence = 0.74f,
                energy = 0.69f,
            )
        val far =
            analysis(
                songId = "far",
                bpm = 124f,
                keyName = "F",
                valence = 0.20f,
                energy = 0.10f,
            )

        val selected =
            LocalSimilarSongSelector.selectBest(
                current = current,
                candidates = listOf(bestButRecent, nextBest, far),
                recentSongIds = listOf("recent_best"),
            )

        assertEquals("next_best", selected?.song?.songId)
    }

    @Test
    fun returnsNullWhenEveryCandidateIsRecent() {
        val current = analysis(songId = "current", bpm = 120f, keyName = "Am")
        val onlyCandidate =
            analysis(
                songId = "recent_only",
                bpm = 121f,
                keyName = "Am",
                valence = 0.76f,
                energy = 0.70f,
            )

        val selected =
            LocalSimilarSongSelector.selectBest(
                current = current,
                candidates = listOf(current, onlyCandidate),
                recentSongIds = listOf("current", "recent_only"),
            )

        assertNull(selected)
    }

    @Test
    fun fallsBackToOlderRecentCandidateWhenAllowed() {
        val current = analysis(songId = "current", bpm = 120f, keyName = "Am")
        val immediatePrevious =
            analysis(
                songId = "immediate_previous",
                bpm = 121f,
                keyName = "Am",
                valence = 0.76f,
                energy = 0.70f,
            )
        val olderRecent =
            analysis(
                songId = "older_recent",
                bpm = 122f,
                keyName = "Am",
                valence = 0.70f,
                energy = 0.68f,
            )

        val selected =
            LocalSimilarSongSelector.selectBest(
                current = current,
                candidates = listOf(current, immediatePrevious, olderRecent),
                recentSongIds = listOf("current", "immediate_previous", "older_recent"),
                allowRecentFallback = true,
            )

        assertEquals("older_recent", selected?.song?.songId)
    }

    @Test
    fun doesNotFallBackToImmediatePreviousOnlyCandidate() {
        val current = analysis(songId = "current", bpm = 120f, keyName = "Am")
        val immediatePrevious =
            analysis(
                songId = "immediate_previous",
                bpm = 121f,
                keyName = "Am",
            )

        val selected =
            LocalSimilarSongSelector.selectBest(
                current = current,
                candidates = listOf(current, immediatePrevious),
                recentSongIds = listOf("current", "immediate_previous"),
                allowRecentFallback = true,
            )

        assertNull(selected)
    }

    @Test
    fun returnsNullWhenCurrentSongCannotBeAnalyzed() {
        val selected =
            LocalSimilarSongSelector.selectBest(
                current = analysis(songId = "current", bpm = null, keyName = "Am"),
                candidates = listOf(analysis(songId = "candidate", bpm = 120f, keyName = "Am")),
                recentSongIds = emptyList(),
            )

        assertNull(selected)
    }

    private fun analysis(
        songId: String,
        bpm: Float? = 120f,
        keyName: String? = null,
        valence: Float? = 0.75f,
        energy: Float? = 0.72f,
        danceability: Float? = 0.66f,
        acousticness: Float? = 0.20f,
        instrumentalness: Float? = 0.10f,
        liveness: Float? = 0.12f,
        speechiness: Float? = 0.08f,
    ): LocalSimilarSongAnalysis =
        LocalSimilarSongAnalysis(
            songId = songId,
            bpm = bpm,
            keyName = keyName,
            valence = valence,
            energy = energy,
            danceability = danceability,
            acousticness = acousticness,
            instrumentalness = instrumentalness,
            liveness = liveness,
            speechiness = speechiness,
        )
}
