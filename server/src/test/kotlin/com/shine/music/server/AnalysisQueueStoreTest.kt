package com.shine.music.server

import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisQueueStoreTest {
    @Test
    fun `automatic missing drain excludes failed tracks while manual lookup can include them`() {
        val root = Files.createTempDirectory("shine-analysis-queue")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("Good.mp3").writeBytes(byteArrayOf(1))
        music.resolve("Failed.mp3").writeBytes(byteArrayOf(2))

        MusicStore(root.resolve("data/library.db")).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "", 1_000)
            }.scan()
            val tracks = store.listTracks(null, 0, 10, "title").items.associateBy(Track::title)
            val failedId = tracks.getValue("Failed").id
            val pendingId = tracks.getValue("Good").id
            store.updateAnalysisState(failedId, "failed", 0f, "decoder failed")

            assertEquals(listOf(pendingId), store.pendingAnalysisTrackIds(includeFailed = false))
            assertTrue(store.pendingAnalysisTrackIds().containsAll(listOf(failedId, pendingId)))
            assertEquals(listOf(pendingId), store.claimPendingAnalysisTrackIds(limit = 1))
            assertEquals(emptyList(), store.claimPendingAnalysisTrackIds(limit = 1))
            assertEquals("queued", store.track(pendingId)?.analysis?.status)
            assertEquals("failed", store.track(failedId)?.analysis?.status)
        }
    }
}
