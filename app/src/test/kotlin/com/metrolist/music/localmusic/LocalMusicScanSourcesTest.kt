package com.metrolist.music.localmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMusicScanSourcesTest {
    @Test
    fun effectiveSourcesMergeLegacyUserFoldersAndDownloadFolderWithoutDuplicates() {
        val sources =
            LocalMusicScanSources.effectiveSources(
                legacyFolderUri = "content://tree/legacy",
                userFolderUris = "content://tree/custom\ncontent://tree/legacy\n",
                fileDownloadDirectoryUri = "content://tree/download",
            )

        assertEquals(
            listOf(
                LocalMusicScanSource("content://tree/legacy", LocalMusicScanSourceKind.USER),
                LocalMusicScanSource("content://tree/custom", LocalMusicScanSourceKind.USER),
                LocalMusicScanSource("content://tree/download", LocalMusicScanSourceKind.DOWNLOAD),
            ),
            sources,
        )
    }

    @Test
    fun blankDownloadFolderDoesNotCreateUnscannableDefaultTreeSource() {
        val sources =
            LocalMusicScanSources.effectiveSources(
                legacyFolderUri = null,
                userFolderUris = "",
                fileDownloadDirectoryUri = "",
            )

        assertEquals(emptyList<LocalMusicScanSource>(), sources)
    }

    @Test
    fun addingUserFolderAppendsAndDeduplicates() {
        val stored = LocalMusicScanSources.addUserFolder("content://tree/a\n", "content://tree/b")

        assertEquals("content://tree/a\ncontent://tree/b", stored)
    }
}
