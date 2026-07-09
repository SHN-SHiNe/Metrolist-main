package com.metrolist.music.ui.player

import com.metrolist.music.db.entities.LocalMusicEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLocalRecommendationSourceTest {
    @Test
    fun activeDownloadedNetworkSongCountsAsLocalRecommendationSource() {
        assertTrue(
            isCurrentLocalRecommendationSource(
                isLocalMetadata = false,
                currentLocalMusic = localMusic(songId = "china_qq_12345"),
            ),
        )
    }

    @Test
    fun missingDownloadedFileDoesNotCountAsLocalRecommendationSource() {
        assertFalse(
            isCurrentLocalRecommendationSource(
                isLocalMetadata = false,
                currentLocalMusic = localMusic(songId = "china_qq_12345", missingSince = 123L),
            ),
        )
    }

    @Test
    fun networkSongWithoutLocalFileDoesNotCountAsLocalRecommendationSource() {
        assertFalse(
            isCurrentLocalRecommendationSource(
                isLocalMetadata = false,
                currentLocalMusic = null,
            ),
        )
    }

    @Test
    fun localMetadataStillCountsWithoutDatabaseRow() {
        assertTrue(
            isCurrentLocalRecommendationSource(
                isLocalMetadata = true,
                currentLocalMusic = null,
            ),
        )
    }

    private fun localMusic(
        songId: String,
        missingSince: Long? = null,
    ): LocalMusicEntity =
        LocalMusicEntity(
            songId = songId,
            contentUri = "content://music/$songId",
            treeUri = "content://tree",
            documentId = songId,
            displayName = "$songId.mp3",
            lastScannedAt = 1L,
            scanGeneration = 1L,
            missingSince = missingSince,
        )
}
