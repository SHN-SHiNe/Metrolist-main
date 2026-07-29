package com.shine.music.server

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class RoomAutofillTest {
    @Test
    fun `restart restores queue and position but requires a user to resume sound`() = runBlocking {
        val root = Files.createTempDirectory("shine-room-safe-restore")
        val database = root.resolve("data/library.db")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("Song.mp3").writeBytes(byteArrayOf(1))
        lateinit var roomId: String
        lateinit var trackId: String
        var playingVersion = 0L

        MusicStore(database).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "Album", 180_000)
            }.scan()
            trackId = store.listTracks(null, 0, 1).items.single().id
            val hub = RoomHub(store, OnlineCatalog(store), InMemorySendspinBridge())
            roomId = hub.create("客厅").id
            playingVersion = checkNotNull(
                hub.update(roomId, UpdateRoomStateRequest(listOf(trackId), trackId, 54_321, true)),
            ).summary.version
        }

        MusicStore(database).use { reopened ->
            val bridge = InMemorySendspinBridge()
            val restoredHub = RoomHub(reopened, OnlineCatalog(reopened), bridge, clock = { 99_000 })

            restoredHub.restore()

            val detail = checkNotNull(restoredHub.detail(roomId))
            assertEquals(listOf(trackId), detail.state.queue)
            assertEquals(trackId, detail.state.currentTrackId)
            assertEquals(54_321, detail.state.positionMs)
            assertEquals(false, detail.state.playing)
            assertEquals(playingVersion + 1, detail.summary.version)
            val stored = reopened.rooms().single { it.id == roomId }
            assertEquals(detail.state, Json.decodeFromString<RoomPlaybackState>(stored.stateJson))
            assertEquals(detail.summary.version, stored.version)
            val bridged = checkNotNull(bridge.status(roomId))
            assertEquals(54_321, bridged.positionMs)
            assertEquals(false, bridged.playing)
        }
    }

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

    @Test
    fun `failed room persistence leaves runtime database and Sendspin queue unchanged`() = runBlocking {
        val root = Files.createTempDirectory("shine-room-autofill-failure")
        val database = root.resolve("data/library.db")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("Seed.mp3").writeBytes(byteArrayOf(1))
        music.resolve("Near.mp3").writeBytes(byteArrayOf(2))

        MusicStore(database).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "Album", 180_000)
            }.scan()
            val tracks = store.listTracks(null, 0, 10, "title").items.associateBy(Track::title)
            store.saveAnalysis(tracks.getValue("Seed").id, analysis(120f, .80f), 100)
            store.saveAnalysis(tracks.getValue("Near").id, analysis(122f, .78f), 101)

            val bridge = InMemorySendspinBridge()
            val hub = RoomHub(store, OnlineCatalog(store), bridge)
            val room = hub.create("客厅")
            val seedId = tracks.getValue("Seed").id
            hub.update(room.id, UpdateRoomStateRequest(listOf(seedId), seedId, 160_000, true))
            val beforeRuntime = checkNotNull(hub.detail(room.id))
            val beforeStored = store.rooms().single { it.id == room.id }
            val beforeBridge = checkNotNull(bridge.status(room.id))

            rejectRoomUpdates(database)

            assertFails { hub.autofill(room.id, emptyList(), 12) }

            val afterRuntime = checkNotNull(hub.detail(room.id))
            val afterStored = store.rooms().single { it.id == room.id }
            val afterBridge = checkNotNull(bridge.status(room.id))
            assertEquals(beforeRuntime.state, afterRuntime.state)
            assertEquals(beforeRuntime.summary.version, afterRuntime.summary.version)
            assertEquals(beforeStored.stateJson, afterStored.stateJson)
            assertEquals(beforeStored.version, afterStored.version)
            assertEquals(beforeBridge, afterBridge)
            assertEquals(beforeRuntime.state, Json.decodeFromString<RoomPlaybackState>(afterStored.stateJson))
        }
    }

    @Test
    fun `failed room control persistence does not reach runtime or Sendspin`() = runBlocking {
        val root = Files.createTempDirectory("shine-room-update-failure")
        val database = root.resolve("data/library.db")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("Song.mp3").writeBytes(byteArrayOf(1))

        MusicStore(database).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "Album", 180_000)
            }.scan()
            val trackId = store.listTracks(null, 0, 1).items.single().id
            val bridge = InMemorySendspinBridge()
            val hub = RoomHub(store, OnlineCatalog(store), bridge)
            val room = hub.create("书房")
            hub.update(room.id, UpdateRoomStateRequest(listOf(trackId), trackId, 20_000, true))
            val beforeRuntime = checkNotNull(hub.detail(room.id))
            val beforeStored = store.rooms().single { it.id == room.id }
            val beforeBridge = checkNotNull(bridge.status(room.id))
            rejectRoomUpdates(database)

            assertFails {
                hub.update(room.id, UpdateRoomStateRequest(positionMs = 42_000, playing = false))
            }

            val afterRuntime = checkNotNull(hub.detail(room.id))
            val afterStored = store.rooms().single { it.id == room.id }
            val afterBridge = checkNotNull(bridge.status(room.id))
            assertEquals(beforeRuntime.state, afterRuntime.state)
            assertEquals(beforeRuntime.summary.version, afterRuntime.summary.version)
            assertEquals(beforeStored.stateJson, afterStored.stateJson)
            assertEquals(beforeStored.version, afterStored.version)
            assertEquals(beforeBridge, afterBridge)
        }
    }

    @Test
    fun `failed reconcile persistence leaves the bridge revision retryable`() = runBlocking {
        val root = Files.createTempDirectory("shine-room-reconcile-failure")
        val database = root.resolve("data/library.db")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("Song.mp3").writeBytes(byteArrayOf(1))

        MusicStore(database).use { store ->
            LibraryScanner(music, store, root.resolve("cache")) { path ->
                AudioMetadata(path.fileName.toString().removeSuffix(".mp3"), "Artist", "Album", 180_000)
            }.scan()
            val trackId = store.listTracks(null, 0, 1).items.single().id
            val bridge = InMemorySendspinBridge()
            val hub = RoomHub(store, OnlineCatalog(store), bridge)
            val room = hub.create("卧室")
            hub.update(room.id, UpdateRoomStateRequest(listOf(trackId), trackId, 20_000, true))
            val beforeStored = store.rooms().single { it.id == room.id }
            val beforeBridge = checkNotNull(bridge.status(room.id))
            val event = beforeBridge.copy(positionMs = 42_000, playing = false, revision = beforeBridge.revision + 1)
            rejectRoomUpdates(database)

            assertFails { hub.reconcile(event) }
            assertEquals(beforeStored, store.rooms().single { it.id == room.id })

            allowRoomUpdates(database)
            hub.reconcile(event)
            val retried = store.rooms().single { it.id == room.id }
            val retriedState = Json.decodeFromString<RoomPlaybackState>(retried.stateJson)
            assertEquals(42_000, retriedState.positionMs)
            assertEquals(false, retriedState.playing)
            assertEquals(beforeStored.version + 1, retried.version)
        }
    }

    private fun rejectRoomUpdates(database: java.nio.file.Path) {
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { db ->
            db.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TRIGGER reject_room_updates BEFORE UPDATE ON rooms
                    BEGIN
                        SELECT RAISE(ABORT, 'forced_room_save_failure');
                    END
                    """.trimIndent(),
                )
            }
        }
    }

    private fun allowRoomUpdates(database: java.nio.file.Path) {
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { db ->
            db.createStatement().use { statement -> statement.execute("DROP TRIGGER reject_room_updates") }
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
