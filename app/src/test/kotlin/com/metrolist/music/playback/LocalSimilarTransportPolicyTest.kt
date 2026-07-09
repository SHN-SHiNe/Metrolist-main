package com.metrolist.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalSimilarTransportPolicyTest {
    @Test
    fun nextCommandUsesRecommendationLogicInsteadOfTimelineNext() {
        val body =
            functionBody(
                signature = "    fun tryPlayNextLocalSimilarFromCurrent(): Boolean",
                nextSignature = "    fun tryPlayPreviousLocalSimilarFromHistory(): Boolean",
            )

        assertTrue(body.contains("playRecommendedLocalSong("))
        assertFalse(body.contains("seekToNextMediaItem"))
    }

    @Test
    fun previousCommandUsesHistoryStackInsteadOfTimelinePrevious() {
        val body =
            functionBody(
                signature = "    fun tryPlayPreviousLocalSimilarFromHistory(): Boolean",
                nextSignature = "    fun prepareLocalSimilarNextFromCurrent()",
            )

        assertTrue(body.contains("playPreviousLocalSimilarFromHistory()"))
        assertFalse(body.contains("seekToPreviousMediaItem"))
    }

    @Test
    fun bufferPreviewNextComesFromPreparedRecommendationNotForwardHistory() {
        val body =
            functionBody(
                signature = "    private suspend fun syncLocalSimilarPlaybackBuffer(currentSongId: String)",
                nextSignature = "    private fun playLocalSimilarItem(",
            )

        assertTrue(body.contains("preparedLocalSimilarSong"))
        assertFalse(body.contains("nextHistorySongId"))
    }

    private fun functionBody(signature: String, nextSignature: String): String {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt")
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
