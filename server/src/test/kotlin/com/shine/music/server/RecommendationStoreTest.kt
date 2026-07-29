package com.shine.music.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RecommendationStoreTest {
    @Test
    fun `analysis survives restart and powers indexed similar lookup`() {
        val root = Files.createTempDirectory("shine-recommendation-store")
        val music = root.resolve("music")
        Files.createDirectories(music)
        listOf("Seed.mp3", "Near.mp3", "Wrong tempo.mp3").forEachIndexed { index, name ->
            music.resolve(name).writeBytes(byteArrayOf(index.toByte()))
        }
        val database = root.resolve("data/library.db")
        MusicStore(database).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "Album", 180_000)
            }.scan()
            val tracks = store.listTracks(null, 0, 10, "title").items.associateBy(Track::title)
            store.saveAnalysis(tracks.getValue("Seed").id, result(bpm = 120f, valence = 0.8f), 100)
            store.saveAnalysis(tracks.getValue("Near").id, result(bpm = 122f, valence = 0.78f), 101)
            store.saveAnalysis(tracks.getValue("Wrong tempo").id, result(bpm = 132f, valence = 0.8f), 102)
        }

        MusicStore(database).use { reopened ->
            val seed = reopened.listTracks("Seed", 0, 1).items.single()
            val similar = reopened.similarTracks(seed.id, limit = 10)!!

            assertEquals("completed", seed.analysis.status)
            assertEquals("8A", seed.analysis.camelot)
            assertEquals(listOf("Near"), similar.items.map { it.track.title })
        }
    }

    @Test
    fun `changed source invalidates stale analysis`() {
        val root = Files.createTempDirectory("shine-analysis-invalidation")
        val music = root.resolve("music")
        Files.createDirectories(music)
        val song = music.resolve("Song.mp3")
        song.writeBytes(byteArrayOf(1))
        MusicStore(root.resolve("data/library.db")).use { store ->
            val scanner = LibraryScanner(music, store, root.resolve("cache")) { AudioMetadata("Song", "Artist", "", 1_000) }
            scanner.scan()
            val id = store.listTracks(null, 0, 1).items.single().id
            store.saveAnalysis(id, result(), 100)

            song.writeBytes(byteArrayOf(1, 2))
            Files.setLastModifiedTime(song, FileTime.fromMillis(System.currentTimeMillis() + 2_000))
            scanner.scan()

            assertEquals("pending", store.track(id)!!.analysis.status)
            assertEquals(null, store.track(id)!!.analysis.bpm)
        }
    }

    @Test
    fun `analysis result is discarded when a scan changes its source fingerprint`() {
        val root = Files.createTempDirectory("shine-analysis-stale-result")
        val music = root.resolve("music")
        Files.createDirectories(music)
        val song = music.resolve("Song.mp3")
        song.writeBytes(byteArrayOf(1))
        MusicStore(root.resolve("data/library.db")).use { store ->
            val scanner = LibraryScanner(music, store, root.resolve("cache")) { AudioMetadata("Song", "Artist", "", 1_000) }
            scanner.scan()
            val id = store.listTracks(null, 0, 1).items.single().id
            val source = store.analysisSource(id)!!
            store.setAnalysisQueued(id)
            store.updateAnalysisState(id, "running", 0.5f, "分析中")

            song.writeBytes(byteArrayOf(1, 2))
            Files.setLastModifiedTime(song, FileTime.fromMillis(source.modifiedAt + 2_000))
            scanner.scan()

            assertFalse(store.saveAnalysis(id, result(), 200, source))
            assertEquals("pending", store.track(id)!!.analysis.status)
        }
    }

    @Test
    fun `analysis progress does not invalidate stable library paging`() {
        val root = Files.createTempDirectory("shine-analysis-revision")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("Song.mp3").writeBytes(byteArrayOf(1))
        MusicStore(root.resolve("data/library.db")).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { AudioMetadata("Song", "Artist", "", 1_000) }.scan()
            val before = store.listTracks(null, 0, 10).revision
            val id = store.listTracks(null, 0, 1).items.single().id

            store.setAnalysisQueued(id)
            store.updateAnalysisState(id, "running", 0.5f, "分析中")
            store.saveAnalysis(id, result(), 200)

            assertEquals(before, store.listTracks(null, 0, 10).revision)
        }
    }

    @Test
    fun `advanced search limits SQL materialization but reports the full match count`() {
        val root = Files.createTempDirectory("shine-advanced-search-store")
        val music = root.resolve("music")
        Files.createDirectories(music)
        listOf("Exact.mp3", "Near.mp3").forEachIndexed { index, name -> music.resolve(name).writeBytes(byteArrayOf(index.toByte())) }
        MusicStore(root.resolve("data/library.db")).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "", 1_000)
            }.scan()
            val tracks = store.listTracks(null, 0, 10, "title").items.associateBy(Track::title)
            store.saveAnalysis(tracks.getValue("Exact").id, result(valence = 0.8f), 100)
            store.saveAnalysis(tracks.getValue("Near").id, result(valence = 0.7f), 101)

            val response = store.advancedSearch(
                AdvancedSearchRequest(valence = 0.8f, emotionTolerance = 0.5f, limit = 1),
            )

            assertEquals(2, response.totalCandidates)
            assertEquals(listOf("Exact"), response.items.map { it.track.title })
        }
    }

    private fun result(bpm: Float = 120f, valence: Float = 0.75f) = AudioAnalysisResult(
        bpm = bpm,
        keyName = "Am",
        valence = valence,
        energy = 0.72f,
        danceability = 0.66f,
        acousticness = 0.2f,
        instrumentalness = 0.1f,
        liveness = 0.12f,
        speechiness = 0.08f,
    )
}
