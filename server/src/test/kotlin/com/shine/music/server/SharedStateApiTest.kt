package com.shine.music.server

import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.chinamusic.model.SearchResult
import com.metrolist.chinamusic.model.SonglistDetail
import com.metrolist.chinamusic.model.SonglistSearchResult
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

    @Test
    fun `online search tracks can be favorited and recorded across a server restart`() {
        val root = Files.createTempDirectory("shine-online-shared-test")
        val music = root.resolve("music")
        Files.createDirectories(music)
        val song = ChinaSong(
            name = "稻香",
            singer = "周杰伦",
            source = "wy",
            songmid = "song-1",
            albumName = "魔杰座",
            durationSeconds = 223,
            img = "https://img.example/cover.jpg",
        )
        val gateway = object : OnlineCatalogGateway {
            override suspend fun search(query: String, page: Int, limit: Int, source: MusicSource) =
                Result.success(SearchResult(listOf(song), 1, page, 1, limit, source.id))

            override suspend fun searchPlaylists(query: String, page: Int, limit: Int, source: MusicSource) =
                Result.success(SonglistSearchResult(emptyList(), 0, page, 1, limit, source.id))

            override suspend fun playlistDetail(id: String, page: Int, limit: Int, source: MusicSource) =
                Result.success(SonglistDetail(id, "", "", songs = emptyList(), total = 0, page = page, allPage = 1, source = source.id))

            override suspend fun resolve(song: ChinaSong) = Result.success("https://audio.example/song.mp3")
        }
        lateinit var onlineTrack: OnlineTrack
        lateinit var playlistId: String

        testApplication {
            application {
                shineModule(
                    AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false),
                    clock = { 1_234 },
                    onlineCatalogGateway = gateway,
                )
            }
            val api = createClient { install(ContentNegotiation) { json() } }
            onlineTrack = api.get("/api/search?q=%E7%A8%BB%E9%A6%99&source=netease").body<SearchResponse>().items.single()

            assertEquals(HttpStatusCode.OK, api.put("/api/favorites/${onlineTrack.id}").status)
            assertEquals(HttpStatusCode.Created, api.post("/api/history") { jsonBody(HistoryRequest(onlineTrack.id)) }.status)
            val playlist = api.post("/api/playlists") { jsonBody(CreatePlaylistRequest("在线收藏")) }.body<PlaylistSummary>()
            playlistId = playlist.id
            val playlistDetail = api.put("/api/playlists/${playlist.id}") {
                jsonBody(UpdatePlaylistRequest(trackIds = listOf(onlineTrack.id), expectedVersion = playlist.version))
            }.body<PlaylistDetail>()
            assertEquals(listOf(onlineTrack.id), playlistDetail.tracks.map(Track::id))
            val room = api.post("/api/rooms") { jsonBody(CreateRoomRequest("在线房间")) }.body<RoomSummary>()
            val roomDetail = api.put("/api/rooms/${room.id}/state") {
                jsonBody(UpdateRoomStateRequest(listOf(onlineTrack.id), onlineTrack.id, 5_000, false))
            }.body<RoomDetail>()
            assertEquals(listOf(onlineTrack.id), roomDetail.state.queue)
            assertEquals(
                HttpStatusCode.Accepted,
                api.post("/api/downloads") {
                    jsonBody(DownloadRequest(trackId = onlineTrack.id, title = onlineTrack.title, artist = onlineTrack.artist))
                }.status,
            )
        }

        testApplication {
            application {
                shineModule(
                    AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false),
                    clock = { 2_345 },
                    onlineCatalogGateway = gateway,
                )
            }
            val api = createClient { install(ContentNegotiation) { json() } }
            val favorite = api.get("/api/favorites").body<List<Track>>().single()
            val history = api.get("/api/history").body<List<HistoryEntry>>().single().track

            assertEquals(onlineTrack.id, favorite.id)
            assertEquals("稻香", favorite.title)
            assertEquals("https://img.example/cover.jpg", favorite.artworkUrl)
            assertEquals(onlineTrack.id, history.id)
            assertEquals("周杰伦", history.artist)
            assertEquals(
                listOf(onlineTrack.id),
                api.get("/api/playlists/$playlistId").body<PlaylistDetail>().tracks.map(Track::id),
            )
            assertTrue(api.get("/api/library").body<TrackPage>().items.isEmpty())
        }
    }

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.jsonBody(value: T) {
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        setBody(value)
    }
}
