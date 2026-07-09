package com.metrolist.music.localmusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMusicScanFilterTest {
    @Test
    fun skipsSyncthingVersionHistoryDirectory() {
        assertTrue(
            LocalMusicScanFilter.shouldSkip(
                displayName = ".stversions",
                documentId = "primary:Music/SHiNePartyMusicData/.stversions",
            ),
        )
    }

    @Test
    fun skipsFilesInsideHiddenDirectories() {
        assertTrue(
            LocalMusicScanFilter.shouldSkip(
                displayName = "Love Or Die.mp3",
                documentId = "primary:Music/SHiNePartyMusicData/.stversions/Deep Disco Record Mix#219/Love Or Die.mp3",
            ),
        )
    }

    @Test
    fun keepsNormalAudioFiles() {
        assertFalse(
            LocalMusicScanFilter.shouldSkip(
                displayName = "Love Or Die.mp3",
                documentId = "primary:Music/SHiNePartyMusicData/Deep Disco Record Mix#219/Love Or Die.mp3",
            ),
        )
    }

    @Test
    fun rejectsAiffEvenWhenSystemMarksItAsAudio() {
        assertFalse(
            LocalMusicScanFilter.isSupportedPlayableAudio(
                displayName = "Scratch Sample5 .aif",
                mimeType = "audio/x-aiff",
            ),
        )
    }

    @Test
    fun rejectsWmaBecauseBundledPlayerCannotExtractIt() {
        assertFalse(
            LocalMusicScanFilter.isSupportedPlayableAudio(
                displayName = "Old Track.wma",
                mimeType = "audio/x-ms-wma",
            ),
        )
    }

    @Test
    fun acceptsMp3ByExtension() {
        assertTrue(
            LocalMusicScanFilter.isSupportedPlayableAudio(
                displayName = "Love Or Die.mp3",
                mimeType = "application/octet-stream",
            ),
        )
    }

    @Test
    fun acceptsKnownPlayableMimeWithoutExtension() {
        assertTrue(
            LocalMusicScanFilter.isSupportedPlayableAudio(
                displayName = "stream",
                mimeType = "audio/mpeg",
            ),
        )
    }
}
