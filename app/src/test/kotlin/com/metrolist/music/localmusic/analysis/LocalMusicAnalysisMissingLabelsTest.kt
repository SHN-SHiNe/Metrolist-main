package com.metrolist.music.localmusic.analysis

import com.metrolist.music.db.entities.LocalMusicEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMusicAnalysisMissingLabelsTest {
    @Test
    fun completeAnalysisHasNoMissingLabels() {
        assertTrue(completeLocalMusic().missingAnalysisLabels().isEmpty())
    }

    @Test
    fun availableAnalysisLabelsUseCompactDisplayValues() {
        assertEquals(
            listOf("128 BPM", "C"),
            completeLocalMusic().availableAnalysisLabels(),
        )
    }

    @Test
    fun unavailableAnalysisValuesAreNotDisplayed() {
        assertEquals(
            emptyList<String>(),
            completeLocalMusic(bpm = 0f, keyName = " ").availableAnalysisLabels(),
        )
    }

    @Test
    fun analysisLabelsRemoveReplacementCharactersFromKey() {
        assertEquals(
            listOf("128 BPM", "C"),
            completeLocalMusic(keyName = "C\uFFFD").availableAnalysisLabels(),
        )
    }

    @Test
    fun analysisLabelsHideKeyWhenItOnlyContainsReplacementCharacters() {
        assertEquals(
            listOf("128 BPM"),
            completeLocalMusic(keyName = "\uFFFD").availableAnalysisLabels(),
        )
    }

    @Test
    fun missingBpmKeyAndEmotionsAreListedInDisplayOrder() {
        assertEquals(
            listOf("BPM", "KEY", "VAL", "ENG", "DAN", "ACO", "INS", "LIV", "SP"),
            completeLocalMusic(
                bpm = null,
                keyName = null,
                valence = null,
                energy = null,
                danceability = null,
                acousticness = null,
                instrumentalness = null,
                liveness = null,
                speechiness = null,
            ).missingAnalysisLabels(),
        )
    }

    @Test
    fun zeroBpmAndBlankKeyAreMissing() {
        assertEquals(
            listOf("BPM", "KEY"),
            completeLocalMusic(bpm = 0f, keyName = " ").missingAnalysisLabels(),
        )
    }

    @Test
    fun partialEmotionAnalysisListsOnlyMissingEmotionValues() {
        assertEquals(
            listOf("ENG", "LIV"),
            completeLocalMusic(energy = null, liveness = null).missingAnalysisLabels(),
        )
    }

    private fun completeLocalMusic(
        bpm: Float? = 128f,
        keyName: String? = "C",
        valence: Float? = 0.5f,
        energy: Float? = 0.6f,
        danceability: Float? = 0.7f,
        acousticness: Float? = 0.2f,
        instrumentalness: Float? = 0.1f,
        liveness: Float? = 0.3f,
        speechiness: Float? = 0.4f,
    ): LocalMusicEntity =
        LocalMusicEntity(
            songId = "local_test",
            contentUri = "content://music/local_test",
            treeUri = "content://tree",
            documentId = "local_test",
            displayName = "local_test.mp3",
            lastScannedAt = 1L,
            scanGeneration = 1L,
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
