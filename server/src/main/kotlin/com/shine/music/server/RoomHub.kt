package com.shine.music.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class RoomHub(
    private val store: MusicStore,
    private val catalog: OnlineCatalog,
    private val bridge: SendspinBridge,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rooms = ConcurrentHashMap<String, RuntimeRoom>()

    init {
        store.rooms().forEach { stored ->
            rooms[stored.id] = RuntimeRoom(
                stored.id,
                stored.name,
                runCatching { json.decodeFromString<RoomPlaybackState>(stored.stateJson) }.getOrDefault(RoomPlaybackState()),
                stored.version,
                stored.updatedAt,
            )
        }
    }

    suspend fun restore() {
        rooms.values.forEach { room ->
            room.lock.withLock {
                if (room.deleted) return@withLock
                val pausedState = room.state.copy(playing = false, effectiveAt = 0)
                if (pausedState != room.state) {
                    val nextVersion = room.version + 1
                    val updatedAt = clock()
                    store.saveRoom(room.id, json.encodeToString(pausedState), nextVersion, updatedAt)
                    room.state = pausedState
                    room.version = nextVersion
                    room.updatedAt = updatedAt
                }
                room.lastBridgeRevision = sync(room, room.state, forcePosition = true).revision
            }
        }
    }

    suspend fun create(name: String, requestedId: String? = null): RoomSummary {
        val state = RoomPlaybackState()
        val summary = store.createRoom(name.trim(), json.encodeToString(state), clock(), requestedId)
        val room = RuntimeRoom(summary.id, summary.name, state, summary.version, summary.updatedAt)
        rooms[summary.id] = room
        try {
            room.lastBridgeRevision = sync(room, state).revision
        } catch (failure: Throwable) {
            rooms.remove(summary.id)
            store.deleteRoom(summary.id)
            throw failure
        }
        return summary
    }

    suspend fun list(): List<RoomSummary> = rooms.values.sortedByDescending { it.updatedAt }.map { room ->
        val members = runCatching { bridge.status(room.id)?.memberCount ?: 0 }.getOrDefault(0)
        RoomSummary(room.id, room.name, members, room.version, room.updatedAt)
    }

    suspend fun detail(roomId: String): RoomDetail? {
        val room = rooms[roomId] ?: return null
        return room.lock.withLock {
            if (room.deleted) return@withLock null
            val status = runCatching { bridge.status(roomId) }.getOrNull()
            val state = if (status != null && status.currentTrackId.isNotBlank()) {
                room.state.copy(currentTrackId = status.currentTrackId, positionMs = status.positionMs, playing = status.playing)
            } else room.state
            RoomDetail(RoomSummary(room.id, room.name, status?.memberCount ?: 0, room.version, room.updatedAt), state)
        }
    }

    suspend fun update(roomId: String, command: UpdateRoomStateRequest): RoomDetail? {
        val room = rooms[roomId] ?: return null
        room.lock.withLock {
            if (room.deleted) return null
            val nextState = room.state.copy(
                queue = command.queue ?: room.state.queue,
                currentTrackId = command.currentTrackId ?: room.state.currentTrackId,
                positionMs = command.positionMs?.coerceAtLeast(0) ?: room.state.positionMs,
                playing = command.playing ?: room.state.playing,
                effectiveAt = 0,
            )
            commitState(room, nextState, forcePosition = command.positionMs != null)
        }
        return detail(roomId)
    }

    /**
     * Extends a room queue with NAS-computed similar tracks only while the current song is the
     * final queue item. Every browser may safely request this near the end of a song: the room
     * mutex makes the first request append tracks and all concurrent requests become no-ops.
     */
    suspend fun autofill(roomId: String, recentTrackIds: List<String>, limit: Int): RoomDetail? {
        val room = rooms[roomId] ?: return null
        room.lock.withLock {
            if (room.deleted) return null
            val currentId = room.state.currentTrackId ?: return@withLock
            val currentIndex = room.state.queue.indexOf(currentId)
            if (currentIndex < 0 || currentIndex != room.state.queue.lastIndex) return@withLock

            val exclusions = (recentTrackIds + room.state.queue.takeLast(12)).takeLast(12).toSet()
            var recommendations = store.similarTracks(currentId, exclusions, limit)
            if (recommendations?.items.isNullOrEmpty() && exclusions.size > 1) {
                recommendations = store.similarTracks(currentId, setOf(currentId), limit)
            }
            val known = room.state.queue.toMutableSet()
            val additions = recommendations?.items.orEmpty().map { it.track.id }.filter(known::add)
            if (additions.isEmpty()) return@withLock

            val nextState = room.state.copy(queue = room.state.queue + additions, effectiveAt = 0)
            commitState(room, nextState)
        }
        return detail(roomId)
    }

    suspend fun delete(roomId: String): Boolean {
        val room = rooms[roomId] ?: return false
        return room.lock.withLock {
            if (room.deleted) return@withLock false
            room.deleted = true
            try {
                bridge.delete(roomId)
                store.deleteRoom(roomId)
                rooms.remove(roomId, room)
                true
            } catch (failure: Throwable) {
                room.deleted = false
                throw failure
            }
        }
    }

    suspend fun reconcile(status: SendspinRoomStatus) {
        val room = rooms[status.id] ?: return
        room.lock.withLock {
            if (room.deleted) return@withLock
            if (status.revision > 0 && status.revision <= room.lastBridgeRevision) return@withLock
            val currentTrackId = status.currentTrackId.takeIf(String::isNotBlank) ?: room.state.currentTrackId
            val nextState = room.state.copy(
                currentTrackId = currentTrackId,
                positionMs = status.positionMs.coerceAtLeast(0),
                playing = status.playing,
            )
            if (nextState == room.state) {
                if (status.revision > 0) room.lastBridgeRevision = status.revision
                return@withLock
            }
            val nextVersion = room.version + 1
            val updatedAt = clock()
            store.saveRoom(room.id, json.encodeToString(nextState), nextVersion, updatedAt)
            room.state = nextState
            room.version = nextVersion
            room.updatedAt = updatedAt
            if (status.revision > 0) room.lastBridgeRevision = status.revision
        }
    }

    suspend fun websocketUrl(roomId: String): String? = rooms[roomId]?.takeUnless { it.deleted }?.let { bridge.websocketUrl(roomId) }

    /**
     * Commits a room transition without publishing a state that SQLite cannot recover. The
     * bridge response remains authoritative for playback position, but neither runtime state
     * nor that normalized response becomes visible until both durable writes have succeeded.
     */
    private suspend fun commitState(room: RuntimeRoom, candidate: RoomPlaybackState, forcePosition: Boolean = false) {
        val previousState = room.state
        val previousVersion = room.version
        val previousUpdatedAt = room.updatedAt
        val nextVersion = previousVersion + 1
        val updatedAt = clock()

        store.saveRoom(room.id, json.encodeToString(candidate), nextVersion, updatedAt)
        try {
            val bridgeStatus = sync(room, candidate, forcePosition)
            val committedState = candidate.copy(
                currentTrackId = bridgeStatus.currentTrackId.takeIf(String::isNotBlank) ?: candidate.currentTrackId,
                positionMs = bridgeStatus.positionMs,
                playing = bridgeStatus.playing,
            )
            if (committedState != candidate) {
                store.saveRoom(room.id, json.encodeToString(committedState), nextVersion, updatedAt)
            }
            room.state = committedState
            room.version = nextVersion
            room.updatedAt = updatedAt
            room.lastBridgeRevision = maxOf(room.lastBridgeRevision, bridgeStatus.revision)
        } catch (failure: Throwable) {
            val rollback = runCatching {
                store.saveRoom(room.id, json.encodeToString(previousState), previousVersion, previousUpdatedAt)
            }.onFailure(failure::addSuppressed)
            if (rollback.isSuccess) {
                runCatching { sync(room, previousState, forcePosition = true) }
                    .onSuccess { room.lastBridgeRevision = maxOf(room.lastBridgeRevision, it.revision) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    private suspend fun sync(room: RuntimeRoom, state: RoomPlaybackState, forcePosition: Boolean = false): SendspinRoomStatus {
        val resolved = state.queue.mapNotNull { id ->
            val track = store.track(id)
            val source = store.trackFile(id)?.toAbsolutePath()?.normalize()?.toString() ?: catalog.resolve(id) ?: return@mapNotNull null
            SendspinTrack(id, source, track?.title ?: id, track?.artist ?: "在线音源", track?.album.orEmpty(), track?.artworkUrl.orEmpty(), track?.durationMs ?: 0)
        }
        return bridge.update(
            room.id,
            SendspinRoomRequest(room.name, resolved, state.currentTrackId.orEmpty(), state.positionMs, state.playing, forcePosition),
        )
    }
}

class RuntimeRoom(
    val id: String,
    val name: String,
    @Volatile var state: RoomPlaybackState,
    @Volatile var version: Long,
    @Volatile var updatedAt: Long,
) {
    val lock = Mutex()
    @Volatile var lastBridgeRevision: Long = 0
    @Volatile var deleted: Boolean = false
}
