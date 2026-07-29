package com.shine.music.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MediaStreamApiTest {
    @Test
    fun `visitor can seek within a NAS track using HTTP range`() = testApplication {
        val root = Files.createTempDirectory("shine-stream-test")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("range.mp3").writeBytes(byteArrayOf(10, 20, 30, 40, 50))
        application { shineModule(AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false)) }
        val api = createClient { install(ContentNegotiation) { json() } }
        api.post("/api/scans")
        val track = api.get("/api/library").body<TrackPage>().items.single()

        val response = api.get("/api/media/${track.id}/stream") {
            header(HttpHeaders.Range, "bytes=1-3")
        }

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertContentEquals(byteArrayOf(20, 30, 40), response.body<ByteArray>())
        assertEquals("bytes 1-3/5", response.headers[HttpHeaders.ContentRange])
    }
}
