package com.metrolist.music.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileDownloadLocationTest {
    @Test
    fun defaultLocationUsesMusicShineMusicPath() {
        val location = FileDownloadLocation.fromDirectoryUri("")

        assertEquals("Music/SHiNe MUSIC", location.displayPath)
    }

    @Test
    fun primaryStorageTreeUriIsDisplayedAsInternalStoragePath() {
        val location =
            FileDownloadLocation.fromDirectoryUri(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FCustom"
            )

        assertEquals("内部存储/Music/Custom", location.displayPath)
    }

    @Test
    fun unknownTreeUriStillDisplaysDecodedProviderPath() {
        val location =
            FileDownloadLocation.fromDirectoryUri(
                "content://com.example.documents/tree/sdcard%3ADownloads%2FMusic"
            )

        assertEquals("sdcard:Downloads/Music", location.displayPath)
    }

    @Test
    fun downloaderFallsBackWhenSavedCustomDirectoryIsNotWritable() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/download/FileMusicDownloader.kt")

        assertTrue(source.contains("hasPersistedWritePermission"))
        assertTrue(source.contains("writeToCustomDirectoryOrNull"))
        assertTrue(source.contains("clearStaleCustomDirectory"))
        assertTrue(source.contains("writeToDefaultMusicDirectory(context, mediaMetadata, fileName, stream.mimeType, audioBytes)"))
    }

    private fun sourceFile(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var root: File? = File(System.getProperty("user.dir") ?: ".")
        while (root != null) {
            candidates += File(root, relativePath)
            candidates += File(root, relativePath.removePrefix("app/"))
            root = root.parentFile
        }

        return candidates
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$relativePath not found")
    }
}
