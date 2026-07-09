package com.metrolist.music.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicMenuVisibilityTest {
    @Test
    fun localMusicHidesNetworkOnlyMenuItems() {
        val visibility = musicMenuVisibility(isLocalSong = true)

        assertFalse(visibility.showDownloadAction)
        assertFalse(visibility.showOnlineMetadataActions)
        assertFalse(visibility.showSongCommentsAction)
    }

    @Test
    fun onlineMusicKeepsNetworkMenuItems() {
        val visibility = musicMenuVisibility(isLocalSong = false)

        assertTrue(visibility.showDownloadAction)
        assertTrue(visibility.showOnlineMetadataActions)
        assertTrue(visibility.showSongCommentsAction)
    }

    @Test
    fun localSongDetectionUsesMetadataPrefixAndLibraryFlag() {
        assertTrue(isLocalMenuSong(isLocalMetadata = true, mediaId = "abc", libraryIsLocal = false))
        assertTrue(isLocalMenuSong(isLocalMetadata = false, mediaId = "local_abc", libraryIsLocal = false))
        assertTrue(isLocalMenuSong(isLocalMetadata = false, mediaId = "abc", libraryIsLocal = true))
        assertTrue(isLocalMenuSong(isLocalMetadata = false, mediaId = "abc", libraryIsLocal = false, hasLocalFile = true))
        assertFalse(isLocalMenuSong(isLocalMetadata = false, mediaId = "abc", libraryIsLocal = false))
    }
}
