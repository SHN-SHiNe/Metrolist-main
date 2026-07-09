package com.metrolist.music.playback

import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlaybackUriResolverTest {
    private val isLocalPlaybackUri: (String) -> Boolean = {
        it.startsWith("content://") || it.startsWith("file://")
    }

    @Test
    fun localSongWithoutEmbeddedPlaybackUriUsesDatabaseContentUri() {
        val metadata =
            MediaMetadata(
                id = "local-song",
                title = "Local Song",
                artists = emptyList(),
                duration = 180,
                isLocal = true,
            )

        val resolved =
            LocalPlaybackUriResolver.resolve(
                requestedUri = "local-song",
                metadata = metadata,
                localContentUri = "content://media/external/audio/media/42",
                isLocalPlaybackUri = isLocalPlaybackUri,
            )

        assertEquals("content://media/external/audio/media/42", resolved)
    }

    @Test
    fun databaseContentUriRestoresLocalPlaybackWhenMetadataLostLocalFlag() {
        val metadata =
            MediaMetadata(
                id = "local-song",
                title = "Local Song",
                artists = emptyList(),
                duration = 180,
            )

        val resolved =
            LocalPlaybackUriResolver.resolve(
                requestedUri = "local-song",
                metadata = metadata,
                localContentUri = "content://media/external/audio/media/42",
                isLocalPlaybackUri = isLocalPlaybackUri,
            )

        assertEquals("content://media/external/audio/media/42", resolved)
    }

    @Test
    fun embeddedLocalPlaybackUriIsUsedBeforeDatabaseContentUri() {
        val metadata =
            MediaMetadata(
                id = "local-song",
                title = "Local Song",
                artists = emptyList(),
                duration = 180,
                isLocal = true,
                playbackUri = "content://media/external/audio/media/99",
            )

        val resolved =
            LocalPlaybackUriResolver.resolve(
                requestedUri = "local-song",
                metadata = metadata,
                localContentUri = "content://media/external/audio/media/42",
                isLocalPlaybackUri = isLocalPlaybackUri,
            )

        assertEquals("content://media/external/audio/media/99", resolved)
    }

    @Test
    fun onlineSongWithoutLocalDatabaseEntryReturnsNull() {
        val metadata =
            MediaMetadata(
                id = "yt-song",
                title = "Online Song",
                artists = emptyList(),
                duration = 180,
            )

        val resolved =
            LocalPlaybackUriResolver.resolve(
                requestedUri = "yt-song",
                metadata = metadata,
                localContentUri = null,
                isLocalPlaybackUri = isLocalPlaybackUri,
            )

        assertNull(resolved)
    }
}
