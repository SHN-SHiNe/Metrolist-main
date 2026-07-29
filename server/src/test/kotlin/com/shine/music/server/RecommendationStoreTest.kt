package com.shine.music.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `stale analysis worker cannot overwrite a newly queued or running source`() {
        val root = Files.createTempDirectory("shine-analysis-stale-state")
        val music = root.resolve("music")
        Files.createDirectories(music)
        val song = music.resolve("Song.mp3")
        song.writeBytes(byteArrayOf(1))
        MusicStore(root.resolve("data/library.db")).use { store ->
            val scanner = LibraryScanner(music, store, root.resolve("cache")) { AudioMetadata("Song", "Artist", "", 1_000) }
            scanner.scan()
            val id = store.listTracks(null, 0, 1).items.single().id
            assertTrue(store.setAnalysisQueued(id))
            val oldSource = store.startAnalysis(id)!!

            song.writeBytes(byteArrayOf(1, 2))
            Files.setLastModifiedTime(song, FileTime.fromMillis(oldSource.modifiedAt + 2_000))
            scanner.scan()
            assertTrue(store.setAnalysisQueued(id))

            assertFalse(store.updateRunningAnalysisState(id, oldSource, "failed", 0f, "旧任务失败"))
            assertFalse(store.updateRunningAnalysisState(id, oldSource, "pending", 0f, null))
            assertEquals("queued", store.track(id)!!.analysis.status)

            val newSource = store.startAnalysis(id)!!
            assertFalse(store.updateRunningAnalysisState(id, oldSource, "running", 0.8f, "旧任务进度"))
            assertTrue(store.updateRunningAnalysisState(id, newSource, "running", 0.5f, "新任务进度"))
            assertEquals("running", store.track(id)!!.analysis.status)
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

    @Test
    fun `library BPM sorting keeps analyzed tracks ordered before pending tracks`() {
        val root = Files.createTempDirectory("shine-analysis-sort")
        val music = root.resolve("music")
        Files.createDirectories(music)
        listOf("Pending.mp3", "Fast.mp3", "Slow.mp3").forEachIndexed { index, name ->
            music.resolve(name).writeBytes(byteArrayOf(index.toByte()))
        }
        MusicStore(root.resolve("data/library.db")).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "", 1_000)
            }.scan()
            val tracks = store.listTracks(null, 0, 10, "title").items.associateBy(Track::title)
            store.saveAnalysis(tracks.getValue("Fast").id, result(bpm = 132f), 100)
            store.saveAnalysis(tracks.getValue("Slow").id, result(bpm = 92f), 101)

            assertEquals(listOf("Slow", "Fast", "Pending"), store.listTracks(null, 0, 10, "bpm").items.map(Track::title))
        }
    }

    @Test
    fun `library energy sorting supports both directions and keeps pending tracks last`() {
        val root = Files.createTempDirectory("shine-energy-sort")
        val music = root.resolve("music")
        Files.createDirectories(music)
        listOf("Pending.mp3", "Energetic.mp3", "Calm.mp3").forEachIndexed { index, name ->
            music.resolve(name).writeBytes(byteArrayOf(index.toByte()))
        }
        MusicStore(root.resolve("data/library.db")).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "", 1_000)
            }.scan()
            val tracks = store.listTracks(null, 0, 10, "title").items.associateBy(Track::title)
            store.saveAnalysis(tracks.getValue("Energetic").id, result(energy = .91f), 100)
            store.saveAnalysis(tracks.getValue("Calm").id, result(energy = .18f), 101)

            assertEquals(
                listOf("Calm", "Energetic", "Pending"),
                store.listTracks(null, 0, 10, "energy", direction = "asc").items.map(Track::title),
            )
            assertEquals(
                listOf("Energetic", "Calm", "Pending"),
                store.listTracks(null, 0, 10, "energy", direction = "desc").items.map(Track::title),
            )
        }
    }

    private fun result(bpm: Float = 120f, valence: Float = 0.75f, energy: Float = 0.72f) = AudioAnalysisResult(
        bpm = bpm,
        keyName = "Am",
        valence = valence,
        energy = energy,
        danceability = 0.66f,
        acousticness = 0.2f,
        instrumentalness = 0.1f,
        liveness = 0.12f,
        speechiness = 0.08f,
    )
}
