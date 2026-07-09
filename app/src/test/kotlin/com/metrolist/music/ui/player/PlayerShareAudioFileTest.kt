package com.metrolist.music.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerShareAudioFileTest {
    @Test
    fun playerShareButtonPassesCurrentLocalMusicContentUri() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        assertTrue(source.contains("val currentShareLocalMusic = currentLocalMusic?.takeIf { it.missingSince == null }"))
        assertTrue(source.contains("shareAudioFile(context, mediaMetadata, currentShareLocalMusic)"))
    }

    @Test
    fun shareAudioFilePrefersExistingLocalFileBeforeDownloadingNetworkMp3() {
        val body =
            functionBody(
                signature = "private suspend fun shareAudioFile(",
                nextSignature = "private suspend fun shareChinaSongMp3(",
            )

        assertTrue(body.contains("localMusic != null"))
        assertTrue(body.contains("shareExistingAudioFile("))
        assertTrue(body.indexOf("shareExistingAudioFile(") < body.indexOf("shareChinaSongMp3("))
        assertFalse(body.substringBefore("shareExistingAudioFile(").contains("ChinaMusicUtils.isChinaMediaId"))
    }

    @Test
    fun cachedNetworkShareFilesAreStoredUnderSharedAudioCacheDirectory() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        assertTrue(source.contains("File(context.cacheDir, \"shared_audio\")"))
        assertTrue(source.contains("shareDir.listFiles()?.forEach { it.delete() }"))
    }

    private fun functionBody(signature: String, nextSignature: String): String {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")
        val start = source.indexOf(signature)
        require(start >= 0) { "$signature not found" }
        val end = source.indexOf(nextSignature, start + signature.length)
        require(end > start) { "$nextSignature not found after $signature" }
        return source.substring(start, end)
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
