package com.shine.music.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomSyncApiTest {
    @Test
    fun `visitor can delete a room and stop its Sendspin bridge`() = testApplication {
        val root = Files.createTempDirectory("shine-room-delete-test")
        val bridge = InMemorySendspinBridge()
        application {
            shineModule(
                AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), scanOnStart = false),
                sendspinBridge = bridge,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }
        val room = client.post("/api/rooms") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(CreateRoomRequest("待删除房间"))
        }.body<RoomSummary>()

        assertEquals(io.ktor.http.HttpStatusCode.NoContent, client.delete("/api/rooms/${room.id}").status)
        assertEquals(io.ktor.http.HttpStatusCode.NotFound, client.get("/api/rooms/${room.id}").status)
        assertTrue(client.get("/api/rooms").body<List<RoomSummary>>().none { it.id == room.id })
        assertNull(bridge.status(room.id))
        assertEquals(io.ktor.http.HttpStatusCode.NotFound, client.delete("/api/rooms/${room.id}").status)
    }

    @Test
    fun `room state is persisted and forwarded to the Sendspin bridge`() = testApplication {
        val root = Files.createTempDirectory("shine-room-test")
        val bridge = InMemorySendspinBridge()
        application {
            shineModule(
                AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), scanOnStart = false, sendspinInternalToken = "test-token"),
                clock = { 10_000 },
                sendspinBridge = bridge,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }
        val room = client.post("/api/rooms") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(CreateRoomRequest("客厅"))
        }.body<RoomSummary>()

        val updated = client.put("/api/rooms/${room.id}/state") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(UpdateRoomStateRequest(listOf("track-1"), "track-1", 1200, true))
        }.body<RoomDetail>()

        assertEquals(1, updated.summary.version)
        assertEquals("track-1", updated.state.currentTrackId)
        assertTrue(updated.state.playing)
        assertEquals("track-1", bridge.status(room.id)?.currentTrackId)
        assertEquals(updated, client.get("/api/rooms/${room.id}").body())

        bridge.update(room.id, SendspinRoomRequest("客厅", emptyList(), "track-2", 50, true))
        val eventRevision = checkNotNull(bridge.status(room.id)).revision
        assertEquals(
            io.ktor.http.HttpStatusCode.NotFound,
            client.post("/internal/sendspin/events") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(SendspinRoomStatus(room.id, "客厅", 8927, 2, "poison", 0, false, Long.MAX_VALUE))
            }.status,
        )
        client.post("/internal/sendspin/events") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header("X-SHiNe-Internal-Token", "test-token")
            setBody(SendspinRoomStatus(room.id, "客厅", 8927, 2, "track-2", 50, true, eventRevision))
        }
        val reconciled = client.get("/api/rooms/${room.id}").body<RoomDetail>()
        assertEquals("track-2", reconciled.state.currentTrackId)
        assertEquals(50, reconciled.state.positionMs)
    }
}
