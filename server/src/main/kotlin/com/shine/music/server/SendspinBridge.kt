package com.shine.music.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class SendspinTrack(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String = "",
    val durationMs: Long = 0,
)

@Serializable
data class SendspinRoomRequest(
    val name: String,
    val queue: List<SendspinTrack>,
    val currentTrackId: String,
    val positionMs: Long,
    val playing: Boolean,
    val forcePosition: Boolean = false,
)

@Serializable
data class SendspinRoomStatus(
    val id: String,
    val name: String,
    val port: Int,
    val memberCount: Int,
    val currentTrackId: String = "",
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val revision: Long = 0,
)

interface SendspinBridge {
    suspend fun ready(): Boolean
    suspend fun update(roomId: String, request: SendspinRoomRequest): SendspinRoomStatus
    suspend fun status(roomId: String): SendspinRoomStatus?
    suspend fun websocketUrl(roomId: String): String?
    suspend fun delete(roomId: String): Boolean
}

class HttpSendspinBridge(
    private val baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val requestTimeout: Duration = Duration.ofSeconds(4),
) : SendspinBridge {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    override suspend fun ready(): Boolean = withContext(Dispatchers.IO) {
        try {
            client.send(request(healthUri()).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun update(roomId: String, request: SendspinRoomRequest): SendspinRoomStatus = withContext(Dispatchers.IO) {
        val response = client.send(
            request(roomUri(roomId)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.encodeToString(request))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "sendspin_bridge_${response.statusCode()}" }
        json.decodeFromString(response.body())
    }

    override suspend fun status(roomId: String): SendspinRoomStatus? = withContext(Dispatchers.IO) {
        val response = client.send(request(roomUri(roomId)).GET().build(), HttpResponse.BodyHandlers.ofString())
        when (response.statusCode()) {
            404 -> null
            in 200..299 -> json.decodeFromString(response.body())
            else -> error("sendspin_bridge_${response.statusCode()}")
        }
    }

    override suspend fun websocketUrl(roomId: String): String? = status(roomId)?.takeIf { it.port > 0 }?.let {
        "ws://127.0.0.1:${it.port}/sendspin"
    }

    override suspend fun delete(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val response = client.send(
            request(roomUri(roomId)).DELETE().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        when (response.statusCode()) {
            404 -> false
            in 200..299 -> true
            else -> error("sendspin_bridge_${response.statusCode()}")
        }
    }

    private fun request(uri: URI) = HttpRequest.newBuilder(uri).timeout(requestTimeout)
    private fun healthUri() = URI.create("$baseUrl/health")
    private fun roomUri(roomId: String) = URI.create("$baseUrl/rooms/$roomId")
}

class InMemorySendspinBridge : SendspinBridge {
    private val rooms = java.util.concurrent.ConcurrentHashMap<String, Pair<SendspinRoomRequest, SendspinRoomStatus>>()
    private val revision = java.util.concurrent.atomic.AtomicLong()
    override suspend fun ready() = true
    override suspend fun update(roomId: String, request: SendspinRoomRequest): SendspinRoomStatus {
        val prior = rooms[roomId]?.second
        val status = SendspinRoomStatus(roomId, request.name, prior?.port ?: 0, prior?.memberCount ?: 0, request.currentTrackId, request.positionMs, request.playing, revision.incrementAndGet())
        rooms[roomId] = request to status
        return status
    }
    override suspend fun status(roomId: String) = rooms[roomId]?.second
    override suspend fun websocketUrl(roomId: String): String? = null
    override suspend fun delete(roomId: String) = rooms.remove(roomId) != null
}
