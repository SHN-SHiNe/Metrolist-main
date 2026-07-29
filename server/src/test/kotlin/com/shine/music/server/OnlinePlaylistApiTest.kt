package com.shine.music.server

import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.chinamusic.model.SearchResult
import com.metrolist.chinamusic.model.SonglistDetail
import com.metrolist.chinamusic.model.SonglistItem
import com.metrolist.chinamusic.model.SonglistSearchResult
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class OnlinePlaylistApiTest {
    @Test
    fun `visitor can search playlists with web source aliases and open their tracks`() = testApplication {
        val gateway = StubOnlineCatalogGateway()
        val root = Files.createTempDirectory("shine-online-playlist-api")
        application {
            shineModule(
                AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), scanOnStart = false),
                onlineCatalogGateway = gateway,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val tracks = client.get("/api/search?q=稻香&source=qq")
        assertEquals(HttpStatusCode.OK, tracks.status)
        assertEquals(MusicSource.QQ, gateway.lastSource)

        val search = client.get("/api/search/playlists?q=华语&source=netease&page=2&limit=10")
        val detail = client.get("/api/search/playlists/detail?id=list-1&source=netease&page=1&limit=20")

        assertEquals(HttpStatusCode.OK, search.status)
        assertEquals("华语精选", search.body<OnlinePlaylistSearchResponse>().items.single().name)
        assertEquals(MusicSource.NETEASE, gateway.lastSource)
        assertEquals(HttpStatusCode.OK, detail.status)
        assertEquals("稻香", detail.body<OnlinePlaylistDetailResponse>().tracks.single().title)
    }

    @Test
    fun `online routes reject invalid parameters before contacting a source`() = testApplication {
        val root = Files.createTempDirectory("shine-online-validation-api")
        application {
            shineModule(AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), scanOnStart = false))
        }

        listOf(
            "/api/search?q=歌曲&source=unknown",
            "/api/search?q=歌曲&page=0",
            "/api/search/playlists?q=歌单&limit=101",
            "/api/search/playlists/detail?id=&source=netease",
            "/api/search/playlists/detail?id=list-1&source=all",
            "/api/search/playlists/detail?id=list-1",
        ).forEach { path ->
            assertEquals(HttpStatusCode.BadRequest, client.get(path).status, path)
        }
    }

    @Test
    fun `upstream playlist failure is reported as bad gateway`() = testApplication {
        val root = Files.createTempDirectory("shine-online-upstream-api")
        application {
            shineModule(
                AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), scanOnStart = false),
                onlineCatalogGateway = StubOnlineCatalogGateway(failPlaylistSearch = true),
            )
        }
        val apiClient = createClient { install(ContentNegotiation) { json() } }

        val response = apiClient.get("/api/search/playlists?q=华语&source=wy")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals(
            mapOf("error" to "online_source_unavailable", "operation" to "playlist_search"),
            response.body<Map<String, String>>(),
        )
    }
}

private class StubOnlineCatalogGateway(
    private val failPlaylistSearch: Boolean = false,
) : OnlineCatalogGateway {
    var lastSource: MusicSource? = null

    override suspend fun search(query: String, page: Int, limit: Int, source: MusicSource): Result<SearchResult> =
        Result.success(SearchResult(emptyList(), 0, page, 1, limit, source.id)).also { lastSource = source }

    override suspend fun searchPlaylists(query: String, page: Int, limit: Int, source: MusicSource): Result<SonglistSearchResult> {
        lastSource = source
        if (failPlaylistSearch) return Result.failure(IllegalStateException("provider unavailable"))
        return Result.success(
            SonglistSearchResult(
                list = listOf(SonglistItem("list-1", "华语精选", "浩南", source = source.id)),
                total = 1,
                page = page,
                allPage = 1,
                limit = limit,
                source = source.id,
            ),
        )
    }

    override suspend fun playlistDetail(id: String, page: Int, limit: Int, source: MusicSource): Result<SonglistDetail> {
        lastSource = source
        return Result.success(
            SonglistDetail(
                id = id,
                name = "华语精选",
                author = "浩南",
                songs = listOf(ChinaSong("稻香", "周杰伦", source.id, "song-1")),
                total = 1,
                page = page,
                allPage = 1,
                source = source.id,
            ),
        )
    }

    override suspend fun resolve(song: ChinaSong): Result<String> =
        Result.success("https://audio.example/${song.songmid}.mp3")
}
