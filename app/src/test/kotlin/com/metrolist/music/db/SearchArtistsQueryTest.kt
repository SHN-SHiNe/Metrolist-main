package com.metrolist.music.db

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchArtistsQueryTest {
    @Test
    fun searchArtistsUsesLibrarySongCoverWhenArtistThumbnailIsMissing() {
        assertTrue(
            SEARCH_ARTISTS_WITH_THUMBNAIL_FALLBACK_QUERY.contains(
                "COALESCE(NULLIF(artist.thumbnailUrl, ''),",
            ),
        )
        assertTrue(SEARCH_ARTISTS_WITH_THUMBNAIL_FALLBACK_QUERY.contains("fallback_song.thumbnailUrl"))
        assertTrue(SEARCH_ARTISTS_WITH_THUMBNAIL_FALLBACK_QUERY.contains("fallback_song.inLibrary IS NOT NULL"))
    }

    @Test
    fun searchArtistsStillOnlyReturnsArtistsWithLibrarySongs() {
        assertTrue(SEARCH_ARTISTS_WITH_THUMBNAIL_FALLBACK_QUERY.contains("songCount > 0"))
    }
}
