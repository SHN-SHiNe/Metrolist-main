package com.metrolist.music.localmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedLocalMusicSearchTest {
    @Test
    fun emotionMetricLabelsAreChinese() {
        assertEquals(
            listOf("愉悦度", "能量", "律动", "原声", "器乐", "现场感", "人声"),
            AdvancedEmotionMetric.entries.map { it.label },
        )
    }

    @Test
    fun emotionToleranceIsAppliedGloballyWithoutChangingTargets() {
        val filters =
            mapOf(
                AdvancedEmotionMetric.VALENCE to AdvancedNumericFilter(enabled = true, target = 0.25f, tolerance = 0.02f),
                AdvancedEmotionMetric.ENERGY to AdvancedNumericFilter(enabled = false, target = 0.75f, tolerance = 0.08f),
            )

        val normalized = AdvancedLocalMusicSearch.withEmotionTolerance(filters, 0.12f)

        assertEquals(true, normalized.getValue(AdvancedEmotionMetric.VALENCE).enabled)
        assertEquals(0.25f, normalized.getValue(AdvancedEmotionMetric.VALENCE).target)
        assertEquals(0.12f, normalized.getValue(AdvancedEmotionMetric.VALENCE).tolerance)
        assertEquals(false, normalized.getValue(AdvancedEmotionMetric.ENERGY).enabled)
        assertEquals(0.75f, normalized.getValue(AdvancedEmotionMetric.ENERGY).target)
        assertEquals(0.12f, normalized.getValue(AdvancedEmotionMetric.ENERGY).tolerance)
        assertEquals(0.5f, normalized.getValue(AdvancedEmotionMetric.DANCEABILITY).target)
        assertEquals(0.12f, normalized.getValue(AdvancedEmotionMetric.DANCEABILITY).tolerance)
    }

    @Test
    fun camelotLabelsMatchStandardWheel() {
        assertEquals("A Minor", advancedCamelotDisplayName(8, "A"))
        assertEquals("D-Flat Minor", advancedCamelotDisplayName(12, "A"))
        assertEquals("E Major", advancedCamelotDisplayName(12, "B"))
        assertEquals("8A / Am", AdvancedLocalMusicSearch.keyLabel(8, "A"))
        assertEquals("12A / Dbm", AdvancedLocalMusicSearch.keyLabel(12, "A"))
        assertEquals("12B / E", AdvancedLocalMusicSearch.keyLabel(12, "B"))
        assertEquals("8A\nAm", AdvancedLocalMusicSearch.keyShortLabel(8, "A"))
        assertEquals("12A\nDbm", AdvancedLocalMusicSearch.keyShortLabel(12, "A"))
        assertEquals("12B\nE", AdvancedLocalMusicSearch.keyShortLabel(12, "B"))
        assertEquals("Am", advancedKeyDisplayName("Am"))
        assertEquals("Dbm", advancedKeyDisplayName("Dbm"))
        assertEquals("E", advancedKeyDisplayName("E"))
        assertEquals(12, advancedNormalizeCamelotNumber(0))
        assertEquals("8A", AdvancedLocalMusicSearch.camelotLabel("Am"))
        assertEquals("12A", AdvancedLocalMusicSearch.camelotLabel("Dbm"))
        assertEquals("12B", AdvancedLocalMusicSearch.camelotLabel("E"))
    }

    @Test
    fun bpmFilterUsesTargetAndTolerance() {
        val results =
            AdvancedLocalMusicSearch.rank(
                listOf(
                    candidate("inside", bpm = 124f),
                    candidate("outside", bpm = 126f),
                ),
                AdvancedLocalMusicSearchCriteria(
                    bpm = AdvancedNumericFilter(enabled = true, target = 120f, tolerance = 5f),
                ),
            )

        assertEquals(listOf("inside"), results.map { it.songId })
    }

    @Test
    fun keyFilterIncludesNeighborAndRelativeKeysWithToleranceOne() {
        val results =
            AdvancedLocalMusicSearch.rank(
                listOf(
                    candidate("exact", keyName = "C"),
                    candidate("left", keyName = "F"),
                    candidate("right", keyName = "G"),
                    candidate("relative", keyName = "Am"),
                    candidate("far", keyName = "D"),
                ),
                AdvancedLocalMusicSearchCriteria(
                    key = AdvancedKeyFilter(enabled = true, number = 8, mode = "B", tolerance = 1),
                ),
            )

        assertEquals(listOf("exact", "relative", "left", "right"), results.map { it.songId })
    }

    @Test
    fun textAndEmotionFiltersAreCombinedWithAnd() {
        val results =
            AdvancedLocalMusicSearch.rank(
                listOf(
                    candidate("match", title = "Night Drive", energy = 0.82f),
                    candidate("wrongText", title = "Morning Drive", energy = 0.82f),
                    candidate("wrongEnergy", title = "Night Drive", energy = 0.5f),
                ),
                AdvancedLocalMusicSearchCriteria(
                    text = "night",
                    emotions =
                        AdvancedEmotionMetric.entries.associateWith {
                            AdvancedNumericFilter(target = 0.5f, tolerance = 0.05f)
                        } + (AdvancedEmotionMetric.ENERGY to AdvancedNumericFilter(enabled = true, target = 0.8f, tolerance = 0.05f)),
                ),
            )

        assertEquals(listOf("match"), results.map { it.songId })
    }

    @Test
    fun resultsAreSortedByClosestMatch() {
        val results =
            AdvancedLocalMusicSearch.rank(
                listOf(
                    candidate("far", bpm = 123f),
                    candidate("near", bpm = 121f),
                ),
                AdvancedLocalMusicSearchCriteria(
                    bpm = AdvancedNumericFilter(enabled = true, target = 120f, tolerance = 5f),
                ),
            )

        assertEquals(listOf("near", "far"), results.map { it.songId })
    }

    @Test
    fun resultsExposeSimilarityPercentFromExactTargetDistance() {
        val results =
            AdvancedLocalMusicSearch.rank(
                listOf(
                    candidate("exact", bpm = 120f, energy = 0.8f),
                    candidate("near", bpm = 124f, energy = 0.75f),
                ),
                AdvancedLocalMusicSearchCriteria(
                    bpm = AdvancedNumericFilter(enabled = true, target = 120f, tolerance = 5f),
                    emotions =
                        AdvancedEmotionMetric.entries.associateWith {
                            AdvancedNumericFilter(target = 0.5f, tolerance = 0.05f)
                        } + (AdvancedEmotionMetric.ENERGY to AdvancedNumericFilter(enabled = true, target = 0.8f, tolerance = 0.1f)),
                ),
            )

        assertEquals("exact", results.first().songId)
        assertEquals(100, results.first().similarityPercent)
        assertEquals(true, results.first().similarityPercent > results.last().similarityPercent)
    }

    @Test
    fun similarityPercentClampsDistance() {
        assertEquals(100, AdvancedLocalMusicSearch.similarityPercent(-1f))
        assertEquals(75, AdvancedLocalMusicSearch.similarityPercent(0.25f))
        assertEquals(0, AdvancedLocalMusicSearch.similarityPercent(2f))
    }

    private fun candidate(
        songId: String,
        title: String = songId,
        bpm: Float? = 120f,
        keyName: String? = "C",
        energy: Float? = 0.5f,
    ): AdvancedLocalMusicSearchCandidate =
        AdvancedLocalMusicSearchCandidate(
            songId = songId,
            title = title,
            artists = "Artist",
            album = "Album",
            displayName = "$title.mp3",
            bpm = bpm,
            keyName = keyName,
            valence = 0.5f,
            energy = energy,
            danceability = 0.5f,
            acousticness = 0.5f,
            instrumentalness = 0.5f,
            liveness = 0.5f,
            speechiness = 0.5f,
        )
}
