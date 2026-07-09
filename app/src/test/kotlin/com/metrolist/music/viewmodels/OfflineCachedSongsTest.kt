package com.metrolist.music.viewmodels

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineCachedSongsTest {
    @Test
    fun explicitOfflineCacheAndPlayerCacheAreMergedWithoutDuplicates() {
        val old = LocalDateTime.of(2024, 1, 1, 0, 0)
        val newer = LocalDateTime.of(2024, 1, 2, 0, 0)

        val merged =
            OfflineCachedSongs.merge(
                explicitOffline = listOf(
                    OfflineCachedSongRef("song-a", old),
                    OfflineCachedSongRef("song-b", newer),
                ),
                playerCache = listOf(
                    OfflineCachedSongRef("song-a", newer),
                    OfflineCachedSongRef("song-c", null),
                ),
            )

        assertEquals(
            listOf(
                OfflineCachedSongRef("song-a", newer),
                OfflineCachedSongRef("song-b", newer),
                OfflineCachedSongRef("song-c", null),
            ),
            merged,
        )
    }
}
