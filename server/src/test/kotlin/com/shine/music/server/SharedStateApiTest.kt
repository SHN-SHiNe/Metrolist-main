package com.shine.music.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedStateApiTest {
    @Test
    fun `visitors share favorites playlists history and masked source settings`() = testApplication {
        val root = Files.createTempDirectory("shine-shared-test")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("Shared Song - Family.mp3").writeBytes(byteArrayOf(1, 2, 3))
        application { shineModule(AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false), clock = { 1234 }) }
        val api = createClient { install(ContentNegotiation) { json() } }
        api.post("/api/scans")
        val track = api.get("/api/library").body<TrackPage>().items.single()

        assertEquals(HttpStatusCode.OK, api.put("/api/favorites/${track.id}").status)
        assertEquals(track.id, api.get("/api/favorites").body<List<Track>>().single().id)

        val created = api.post("/api/playlists") { jsonBody(CreatePlaylistRequest("家庭歌单")) }.body<PlaylistSummary>()
        val detail = api.put("/api/playlists/${created.id}") {
            jsonBody(UpdatePlaylistRequest(trackIds = listOf(track.id), expectedVersion = created.version))
        }.body<PlaylistDetail>()
        assertEquals(listOf(track.id), detail.tracks.map { it.id })

        assertEquals(HttpStatusCode.Created, api.post("/api/history") { jsonBody(HistoryRequest(track.id)) }.status)
        assertEquals(track.id, api.get("/api/history").body<List<HistoryEntry>>().single().track.id)

        api.post("/api/settings/sources") {
            jsonBody(SourceConfigRequest("家庭音源", "https://music.example/api", "super-secret-key"))
        }
        val source = api.get("/api/settings/sources").body<List<SourceConfigView>>().single()
        assertTrue(source.apiKeyMasked.endsWith("-key"))
        assertFalse(source.apiKeyMasked.contains("super-secret"))

        assertEquals(HttpStatusCode.OK, api.delete("/api/favorites/${track.id}").status)
        assertTrue(api.get("/api/favorites").body<List<Track>>().isEmpty())
    }

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.jsonBody(value: T) {
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        setBody(value)
    }
}
