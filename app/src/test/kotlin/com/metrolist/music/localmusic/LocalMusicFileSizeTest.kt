package com.metrolist.music.localmusic

import com.metrolist.music.db.entities.LocalMusicEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMusicFileSizeTest {
    @Test
    fun totalBytesSumsDifferentLocalFiles() {
        val total =
            LocalMusicFileSize.totalBytes(
                listOf(
                    localFile(songId = "a", documentId = "primary:Music/A.mp3", displayName = "A.mp3", fileSize = 100),
                    localFile(songId = "b", documentId = "primary:Music/B.mp3", displayName = "B.mp3", fileSize = 250),
                ),
            )

        assertEquals(350, total)
    }

    @Test
    fun totalBytesDeduplicatesSameSafDocumentFromNestedScanSources() {
        val total =
            LocalMusicFileSize.totalBytes(
                listOf(
                    localFile(songId = "parent", documentId = "primary:Music/Album/Song.mp3", fileSize = 120),
                    localFile(
                        songId = "child",
                        documentId = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAlbum/document/primary%3AMusic%2FAlbum%2FSong.mp3",
                        fileSize = 120,
                    ),
                ),
            )

        assertEquals(120, total)
    }

    @Test
    fun totalBytesDeduplicatesDownloadedMediaStoreFileWhenScannedAgain() {
        val total =
            LocalMusicFileSize.totalBytes(
                listOf(
                    localFile(
                        songId = "download",
                        documentId = "content://media/external/audio/media/42",
                        contentUri = "content://media/external/audio/media/42",
                        displayName = "Artist - Song.mp3",
                        fileSize = 500,
                    ),
                    localFile(
                        songId = "scan",
                        documentId = "primary:Music/SHiNe MUSIC/Artist - Song.mp3",
                        displayName = "Artist - Song.mp3",
                        fileSize = 500,
                    ),
                ),
            )

        assertEquals(500, total)
    }

    private fun localFile(
        songId: String,
        documentId: String,
        contentUri: String = documentId,
        displayName: String = "Song.mp3",
        fileSize: Long,
        missingSince: Long? = null,
    ): LocalMusicEntity =
        LocalMusicEntity(
            songId = songId,
            contentUri = contentUri,
            treeUri = "content://tree",
            documentId = documentId,
            displayName = displayName,
            fileSize = fileSize,
            lastScannedAt = 1L,
            scanGeneration = 1L,
            missingSince = missingSince,
        )
}
