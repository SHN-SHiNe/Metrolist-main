package com.shine.music.server

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class RoomHub(
    private val store: MusicStore,
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

    fun create(name: String): RoomSummary {
        val state = RoomPlaybackState()
        val summary = store.createRoom(name.trim(), json.encodeToString(state), clock())
        rooms[summary.id] = RuntimeRoom(summary.id, summary.name, state, summary.version, summary.updatedAt)
        return summary
    }

    fun list(): List<RoomSummary> = rooms.values.sortedByDescending { it.updatedAt }.map {
        RoomSummary(it.id, it.name, it.clients.size, it.version, it.updatedAt)
    }

    suspend fun join(roomId: String, session: DefaultWebSocketServerSession): RuntimeRoom? {
        val room = rooms[roomId] ?: return null
        room.clients += session
        broadcast(room, "snapshot")
        return room
    }

    suspend fun leave(room: RuntimeRoom, session: DefaultWebSocketServerSession) {
        room.clients -= session
        broadcast(room, "members")
    }

    suspend fun handle(room: RuntimeRoom, session: DefaultWebSocketServerSession, command: RoomCommand) {
        if (command.type == "ping") {
            session.send(json.encodeToString(RoomEnvelope("pong", room.id, room.version, null, clock(), room.clients.size, command.clientTime)))
            return
        }
        room.lock.withLock {
            room.state = room.state.copy(
                queue = command.queue ?: room.state.queue,
                currentTrackId = command.currentTrackId ?: room.state.currentTrackId,
                positionMs = command.positionMs ?: room.state.positionMs,
                playing = command.playing ?: room.state.playing,
                effectiveAt = command.effectiveAt ?: clock() + 750,
            )
            room.version++
            room.updatedAt = clock()
            store.saveRoom(room.id, json.encodeToString(room.state), room.version, room.updatedAt)
        }
        broadcast(room, "state")
    }

    fun decode(text: String): RoomCommand = json.decodeFromString(text)

    private suspend fun broadcast(room: RuntimeRoom, type: String) {
        val text = json.encodeToString(RoomEnvelope(type, room.id, room.version, room.state, clock(), room.clients.size))
        room.clients.forEach { session -> runCatching { session.send(Frame.Text(text)) }.onFailure { room.clients -= session } }
    }
}

class RuntimeRoom(
    val id: String,
    val name: String,
    @Volatile var state: RoomPlaybackState,
    @Volatile var version: Long,
    @Volatile var updatedAt: Long,
) {
    val clients = CopyOnWriteArraySet<DefaultWebSocketServerSession>()
    val lock = Mutex()
}
