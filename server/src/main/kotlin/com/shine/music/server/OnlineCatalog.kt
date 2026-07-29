package com.shine.music.server

import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.MusicSourceConfig
import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.chinamusic.model.SearchResult
import com.metrolist.chinamusic.model.SonglistDetail
import com.metrolist.chinamusic.model.SonglistSearchResult
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal fun parseOnlineSource(
    value: String?,
    default: MusicSource? = MusicSource.ALL,
    allowAggregate: Boolean = true,
): MusicSource {
    val source = when {
        value == null -> default ?: throw IllegalArgumentException("source_required")
        value.isBlank() -> throw IllegalArgumentException("invalid_source")
        else -> when (value.trim().lowercase()) {
            "all" -> MusicSource.ALL
            "netease", "wy" -> MusicSource.NETEASE
            "qq", "tx" -> MusicSource.QQ
            "kugou", "kg" -> MusicSource.KUGOU
            "kuwo", "kw" -> MusicSource.KUWO
            "migu", "mg" -> MusicSource.MIGU
            else -> throw IllegalArgumentException("invalid_source")
        }
    }
    require(allowAggregate || source != MusicSource.ALL) { "concrete_source_required" }
    return source
}

internal fun requireOnlineQuery(value: String?): String =
    value?.trim()?.takeIf { it.isNotEmpty() }?.also {
        require(it.length <= 200) { "query_too_long" }
    } ?: throw IllegalArgumentException("query_required")

internal fun requireOnlinePlaylistId(value: String?): String =
    value?.trim()?.takeIf { it.isNotEmpty() }?.also {
        require(it.length <= 512) { "playlist_id_too_long" }
    } ?: throw IllegalArgumentException("playlist_id_required")

internal fun parseOnlinePage(value: String?): Int {
    if (value == null) return 1
    val page = value.toIntOrNull() ?: throw IllegalArgumentException("invalid_page")
    require(page >= 1) { "invalid_page" }
    return page
}

internal fun parseOnlineLimit(value: String?, default: Int): Int {
    if (value == null) return default
    val limit = value.toIntOrNull() ?: throw IllegalArgumentException("invalid_limit")
    require(limit in 1..100) { "invalid_limit" }
    return limit
}

interface OnlineCatalogGateway {
    suspend fun search(query: String, page: Int, limit: Int, source: MusicSource): Result<SearchResult>
    suspend fun searchPlaylists(query: String, page: Int, limit: Int, source: MusicSource): Result<SonglistSearchResult>
    suspend fun playlistDetail(id: String, page: Int, limit: Int, source: MusicSource): Result<SonglistDetail>
    suspend fun resolve(song: ChinaSong): Result<String>
}

object ChinaMusicOnlineCatalogGateway : OnlineCatalogGateway {
    override suspend fun search(query: String, page: Int, limit: Int, source: MusicSource) =
        ChinaMusicApi.search(query, page, limit, source)

    override suspend fun searchPlaylists(query: String, page: Int, limit: Int, source: MusicSource) =
        ChinaMusicApi.searchSonglist(query, page, limit, source)

    override suspend fun playlistDetail(id: String, page: Int, limit: Int, source: MusicSource) =
        ChinaMusicApi.getSonglistDetail(id, page, limit, source)

    override suspend fun resolve(song: ChinaSong) = ChinaMusicApi.getMusicUrlWithFallback(song)
}

class OnlineCatalogUpstreamException(
    val operation: String,
    cause: Throwable,
) : RuntimeException("online_source_unavailable", cause)

class OnlineCatalog(
    private val store: MusicStore,
    private val gateway: OnlineCatalogGateway = ChinaMusicOnlineCatalogGateway,
) {
    private val tracks = ConcurrentHashMap<String, ChinaSong>()

    suspend fun search(query: String, page: Int, limit: Int, sourceId: String?): SearchResponse {
        configureSource()
        val source = parseOnlineSource(sourceId)
        val result = gateway.search(query, page, limit, source).orUpstreamFailure("track_search")
        val items = result.list.map(::register)
        return SearchResponse(items, result.total, page, limit)
    }

    suspend fun searchPlaylists(query: String, page: Int, limit: Int, sourceId: String?): OnlinePlaylistSearchResponse {
        configureSource()
        val source = parseOnlineSource(sourceId)
        val result = gateway.searchPlaylists(query, page, limit, source).orUpstreamFailure("playlist_search")
        return OnlinePlaylistSearchResponse(
            items = result.list.map { playlist ->
                OnlinePlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    author = playlist.author,
                    artworkUrl = playlist.img,
                    playCount = playlist.playCount,
                    source = playlist.source,
                )
            },
            total = result.total,
            page = page,
            limit = limit,
            allPages = result.allPage,
            source = result.source,
        )
    }

    suspend fun playlistDetail(id: String, page: Int, limit: Int, sourceId: String?): OnlinePlaylistDetailResponse {
        configureSource()
        val source = parseOnlineSource(sourceId, default = null, allowAggregate = false)
        val result = gateway.playlistDetail(id, page, limit, source).orUpstreamFailure("playlist_detail")
        return OnlinePlaylistDetailResponse(
            id = result.id,
            name = result.name,
            author = result.author,
            artworkUrl = result.img,
            description = result.desc,
            tracks = result.songs.map(::register),
            total = result.total,
            page = page,
            limit = limit,
            allPages = result.allPage,
            source = result.source,
        )
    }

    suspend fun resolve(id: String): String? {
        val song = tracks[id] ?: return null
        configureSource()
        return gateway.resolve(song).getOrNull()
    }

    private fun configureSource() {
        store.activeSourceSecret()?.let { (name, url, key) ->
            ChinaMusicApi.configure(MusicSourceConfig(name = name, apiUrl = url, apiKey = key))
        }
    }

    private fun register(song: ChinaSong): OnlineTrack {
        val id = onlineId(song)
        tracks[id] = song
        return OnlineTrack(
            id = id,
            title = song.name,
            artist = song.singer,
            album = song.albumName,
            artworkUrl = song.img,
            source = song.source,
            durationMs = song.durationSeconds.toLong() * 1000,
        )
    }

    private fun <T> Result<T>.orUpstreamFailure(operation: String): T =
        getOrElse { throw OnlineCatalogUpstreamException(operation, it) }

    private fun onlineId(song: ChinaSong): String {
        val value = "${song.source}:${song.hash ?: song.songmid}"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return "online-$digest"
    }
}
