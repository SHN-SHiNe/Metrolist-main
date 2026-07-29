package com.shine.music.server

import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class RoomAutofillTest {
    @Test
    fun `concurrent room autofill requests append NAS recommendations only once`() = runBlocking {
        val root = Files.createTempDirectory("shine-room-autofill")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("Seed.mp3").writeBytes(byteArrayOf(1))
        music.resolve("Near.mp3").writeBytes(byteArrayOf(2))

        MusicStore(root.resolve("data/library.db")).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "Album", 180_000)
            }.scan()
            val tracks = store.listTracks(null, 0, 10, "title").items.associateBy(Track::title)
            store.saveAnalysis(tracks.getValue("Seed").id, analysis(120f, .80f), 100)
            store.saveAnalysis(tracks.getValue("Near").id, analysis(122f, .78f), 101)

            val hub = RoomHub(store, OnlineCatalog(store), InMemorySendspinBridge())
            val room = hub.create("客厅")
            val seedId = tracks.getValue("Seed").id
            val nearId = tracks.getValue("Near").id
            hub.update(room.id, UpdateRoomStateRequest(listOf(seedId), seedId, 160_000, true))

            val first = checkNotNull(hub.autofill(room.id, emptyList(), 12))
            val second = checkNotNull(hub.autofill(room.id, emptyList(), 12))

            assertEquals(listOf(seedId, nearId), first.state.queue)
            assertEquals(first.state.queue, second.state.queue)
            assertEquals(first.summary.version, second.summary.version)
        }
    }

    private fun analysis(bpm: Float, valence: Float) = AudioAnalysisResult(
        bpm = bpm,
        keyName = "Am",
        valence = valence,
        energy = .72f,
        danceability = .66f,
        acousticness = .2f,
        instrumentalness = .1f,
        liveness = .12f,
        speechiness = .08f,
    )
}
