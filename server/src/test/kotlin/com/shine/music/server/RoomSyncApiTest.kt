package com.shine.music.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSyncApiTest {
    @Test
    fun `devices in a room receive versioned scheduled playback state`() = testApplication {
        val root = Files.createTempDirectory("shine-room-test")
        application { shineModule(AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), scanOnStart = false), clock = { 10_000 }) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
        }
        val room = client.post("/api/rooms") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(CreateRoomRequest("客厅"))
        }.body<RoomSummary>()
        val codec = Json { ignoreUnknownKeys = true }

        withTimeout(3_000) {
            val session = client.webSocketSession("/ws/rooms/${room.id}")
            val snapshot = codec.decodeFromString<RoomEnvelope>((session.incoming.receive() as Frame.Text).readText())
            assertEquals("snapshot", snapshot.type)
            session.send(Frame.Text(codec.encodeToString(RoomCommand("state", "device-a", listOf("track-1"), "track-1", 1200, true, 11_000))))
            val state = codec.decodeFromString<RoomEnvelope>((session.incoming.receive() as Frame.Text).readText())
            assertEquals("state", state.type)
            assertEquals(1, state.version)
            assertEquals("track-1", state.state?.currentTrackId)
            assertTrue(state.state?.playing == true)
            session.close(CloseReason(CloseReason.Codes.NORMAL, "test complete"))
        }
    }
}
