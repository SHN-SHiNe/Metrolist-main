package com.shine.music.server

import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.chinamusic.model.SearchResult
import com.metrolist.chinamusic.model.SonglistDetail
import com.metrolist.chinamusic.model.SonglistSearchResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OnlineCatalogTest {
    @Test
    fun `web and ChinaMusic source ids resolve to the same source`() {
        val aliases = mapOf(
            "all" to MusicSource.ALL,
            "netease" to MusicSource.NETEASE,
            "wy" to MusicSource.NETEASE,
            "qq" to MusicSource.QQ,
            "tx" to MusicSource.QQ,
            "kugou" to MusicSource.KUGOU,
            "kg" to MusicSource.KUGOU,
            "kuwo" to MusicSource.KUWO,
            "kw" to MusicSource.KUWO,
            "migu" to MusicSource.MIGU,
            "mg" to MusicSource.MIGU,
        )

        aliases.forEach { (id, expected) ->
            assertEquals(expected, parseOnlineSource(id))
        }
        assertFailsWith<IllegalArgumentException> { parseOnlineSource("unknown") }
    }

    @Test
    fun `online catalog request values reject missing and out of range input`() {
        assertEquals("周杰伦", requireOnlineQuery("  周杰伦  "))
        assertEquals("playlist-1", requireOnlinePlaylistId(" playlist-1 "))
        assertEquals(1, parseOnlinePage(null))
        assertEquals(8, parseOnlinePage("8"))
        assertEquals(30, parseOnlineLimit(null, 30))
        assertEquals(100, parseOnlineLimit("100", 30))

        assertFailsWith<IllegalArgumentException> { requireOnlineQuery(" ") }
        assertFailsWith<IllegalArgumentException> { requireOnlineQuery("x".repeat(201)) }
        assertFailsWith<IllegalArgumentException> { requireOnlinePlaylistId(null) }
        assertFailsWith<IllegalArgumentException> { requireOnlinePlaylistId("x".repeat(513)) }
        assertFailsWith<IllegalArgumentException> { parseOnlinePage("0") }
        assertFailsWith<IllegalArgumentException> { parseOnlinePage("one") }
        assertFailsWith<IllegalArgumentException> { parseOnlineLimit("101", 30) }
        assertFailsWith<IllegalArgumentException> { parseOnlineSource(null, default = null) }
        assertFailsWith<IllegalArgumentException> { parseOnlineSource("all", allowAggregate = false) }
    }

    @Test
    fun `playlist detail exposes online tracks that can immediately be resolved`() = runBlocking {
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
                Result.success(SearchResult(emptyList(), 0, page, 1, limit, source.id))

            override suspend fun searchPlaylists(query: String, page: Int, limit: Int, source: MusicSource) =
                Result.success(SonglistSearchResult(emptyList(), 0, page, 1, limit, source.id))

            override suspend fun playlistDetail(id: String, page: Int, limit: Int, source: MusicSource) =
                Result.success(SonglistDetail(id, "华语精选", "浩南", songs = listOf(song), total = 1, page = page, allPage = 1, source = source.id))

            override suspend fun resolve(song: ChinaSong) = Result.success("https://audio.example/song.mp3")
        }
        val root = Files.createTempDirectory("shine-online-catalog")
        lateinit var track: OnlineTrack
        MusicStore(root.resolve("music.db")).use { store ->
            val catalog = OnlineCatalog(store, gateway)

            val detail = catalog.playlistDetail("list-1", 1, 20, "netease")
            track = detail.tracks.single()
            catalog.materialize(track.id, 123)

            assertEquals("华语精选", detail.name)
            assertEquals("稻香", track.title)
            assertEquals(223_000L, track.durationMs)
            assertEquals("https://audio.example/song.mp3", catalog.resolve(track.id))
            assertEquals(0, store.listTracks(null, 0, 10).total)
            assertNull(store.trackFile(track.id))
            assertEquals(0, store.analysisSummary(true, "test").total)
        }

        MusicStore(root.resolve("music.db")).use { reopened ->
            val restoredCatalog = OnlineCatalog(reopened, gateway)

            assertEquals("https://audio.example/song.mp3", restoredCatalog.resolve(track.id))
            val restored = checkNotNull(reopened.track(track.id))
            assertEquals("稻香", restored.title)
            assertEquals("周杰伦", restored.artist)
            assertEquals("https://img.example/cover.jpg", restored.artworkUrl)
            assertNull(reopened.trackFile(track.id))
        }
    }
}
