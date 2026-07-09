package com.metrolist.music.localmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMusicSourceTrackIdTest {
    @Test
    fun resolveIdentityRebindsGeneratedLocalIdToSourceTrackId() {
        val resolution =
            LocalMusicSourceTrackId.resolveIdentity(
                existingSongId = "local_song_old",
                sourceTrackId = "china_qq_12345",
                stableSongId = "local_song_new",
            )

        assertEquals("china_qq_12345", resolution.songId)
        assertEquals("local_song_old", resolution.rebindFromSongId)
    }

    @Test
    fun resolveIdentityKeepsExistingLocalIdWhenNoSourceTrackIdExists() {
        val resolution =
            LocalMusicSourceTrackId.resolveIdentity(
                existingSongId = "local_song_old",
                sourceTrackId = null,
                stableSongId = "local_song_new",
            )

        assertEquals("local_song_old", resolution.songId)
        assertNull(resolution.rebindFromSongId)
    }

    @Test
    fun normalizeAcceptsNetworkSourceIds() {
        assertEquals(
            "china_qq_12345",
            LocalMusicSourceTrackId.normalize("  china_qq_12345  "),
        )
    }

    @Test
    fun normalizeRemovesControlCharacters() {
        assertEquals(
            "china_kw_abc",
            LocalMusicSourceTrackId.normalize("\u0000china_kw_abc\u0007"),
        )
    }

    @Test
    fun normalizeRejectsBlankAndGeneratedLocalIds() {
        assertNull(LocalMusicSourceTrackId.normalize("   "))
        assertNull(LocalMusicSourceTrackId.normalize("local_song_123"))
    }
}
