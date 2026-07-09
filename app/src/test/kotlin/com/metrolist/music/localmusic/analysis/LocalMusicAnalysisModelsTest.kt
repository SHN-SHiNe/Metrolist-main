/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import com.metrolist.music.db.entities.LocalMusicEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMusicAnalysisModelsTest {
    @Test
    fun completeAnalysisRequiresBpmKeyAndSevenEmotions() {
        assertTrue(completeLocalMusic().hasCompleteAnalysis())
        assertFalse(completeLocalMusic(bpm = 0f).hasCompleteAnalysis())
        assertFalse(completeLocalMusic(keyName = "").hasCompleteAnalysis())
        assertFalse(completeLocalMusic(valence = null).hasCompleteAnalysis())
        assertFalse(completeLocalMusic(speechiness = null).hasCompleteAnalysis())
    }

    @Test
    fun completeEmotionAnalysisOnlyRequiresSevenEmotions() {
        assertTrue(completeLocalMusic(bpm = null, keyName = null).hasCompleteEmotionAnalysis())
        assertFalse(completeLocalMusic(valence = null).hasCompleteEmotionAnalysis())
        assertFalse(completeLocalMusic(liveness = null).hasCompleteEmotionAnalysis())
    }

    @Test
    fun inlineAnalysisActionDoesNotReplaceRadarWhenEmotionsAreComplete() {
        assertFalse(
            LocalMusicAnalysisState()
                .shouldShowInlineAnalysisAction(hasCompleteEmotionAnalysis = true),
        )
        assertTrue(
            LocalMusicAnalysisState()
                .shouldShowInlineAnalysisAction(hasCompleteEmotionAnalysis = false),
        )
        assertTrue(
            LocalMusicAnalysisState(status = LocalMusicAnalysisStatus.Running)
                .shouldShowInlineAnalysisAction(hasCompleteEmotionAnalysis = true),
        )
        assertFalse(
            LocalMusicAnalysisState(status = LocalMusicAnalysisStatus.Failed)
                .shouldShowInlineAnalysisAction(hasCompleteEmotionAnalysis = true),
        )
    }

    @Test
    fun analysisActionPresentationShowsProgressForActiveStates() {
        assertEquals(
            LocalMusicAnalysisActionPresentation(
                label = "排队中",
                description = "等待分析 5%",
                progress = 0.05f,
                enabled = false,
            ),
            LocalMusicAnalysisState(status = LocalMusicAnalysisStatus.Queued)
                .actionPresentation(hasCompleteAnalysis = false),
        )

        assertEquals(
            LocalMusicAnalysisActionPresentation(
                label = "57%",
                description = "情绪分析 57%",
                progress = 0.57f,
                enabled = false,
            ),
            LocalMusicAnalysisState(
                status = LocalMusicAnalysisStatus.Running,
                progress = 0.57f,
                message = "情绪分析",
            ).actionPresentation(hasCompleteAnalysis = false),
        )
    }

    @Test
    fun analysisActionPresentationNamesAvailableActions() {
        assertEquals(
            LocalMusicAnalysisActionPresentation(label = "待分析", description = "点击立即分析"),
            LocalMusicAnalysisState().actionPresentation(hasCompleteAnalysis = false),
        )
        assertEquals(
            LocalMusicAnalysisActionPresentation(label = "重试", description = "分析失败"),
            LocalMusicAnalysisState(
                status = LocalMusicAnalysisStatus.Failed,
                message = "decoder failed",
            ).actionPresentation(hasCompleteAnalysis = false),
        )
        assertEquals(
            LocalMusicAnalysisActionPresentation(label = "重新分析", description = "重新写入分析结果"),
            LocalMusicAnalysisState().actionPresentation(hasCompleteAnalysis = true),
        )
    }

    @Test
    fun moodSummaryUsesManagedUppercaseNames() {
        val result =
            LocalMusicAnalysisResult(
                bpm = 123.456f,
                keyName = "Am",
                valence = 0.1234f,
                energy = 0.2f,
                danceability = 0.3f,
                acousticness = 0.4f,
                instrumentalness = 0.5f,
                liveness = 0.6f,
                speechiness = 0.7f,
            )

        assertEquals(
            "VALENCE:0.123 | ENERGY:0.200 | DANCEABILITY:0.300 | ACOUSTICNESS:0.400 | INSTRUMENTALNESS:0.500 | LIVENESS:0.600 | SPEECHINESS:0.700",
            result.moodSummary(),
        )
    }

    @Test
    fun normalizedEmotionValuesClampToZeroOne() {
        val result =
            LocalMusicAnalysisResult(
                bpm = 120f,
                keyName = "C",
                valence = -1f,
                energy = 2f,
                danceability = 50f,
                acousticness = 0.25f,
                instrumentalness = 0f,
                liveness = 1f,
                speechiness = 0.5f,
            ).normalized()

        assertEquals(0f, result.valence, 0.0001f)
        assertEquals(1f, result.energy, 0.0001f)
        assertEquals(0.5f, result.danceability, 0.0001f)
    }

    private fun completeLocalMusic(
        bpm: Float? = 120f,
        keyName: String? = "Am",
        valence: Float? = 0.1f,
        energy: Float? = 0.2f,
        danceability: Float? = 0.3f,
        acousticness: Float? = 0.4f,
        instrumentalness: Float? = 0.5f,
        liveness: Float? = 0.6f,
        speechiness: Float? = 0.7f,
    ) = LocalMusicEntity(
        songId = "song",
        contentUri = "content://song",
        treeUri = "content://tree",
        documentId = "document.mp3",
        displayName = "document.mp3",
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
