package com.shine.music.server

import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.MusicSourceConfig
import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.MusicSource
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class OnlineCatalog(private val store: MusicStore) {
    private val tracks = ConcurrentHashMap<String, ChinaSong>()

    suspend fun search(query: String, page: Int, limit: Int, sourceId: String): SearchResponse {
        store.activeSourceSecret()?.let { (name, url, key) ->
            ChinaMusicApi.configure(MusicSourceConfig(name = name, apiUrl = url, apiKey = key))
        }
        val source = MusicSource.entries.firstOrNull { it.id == sourceId } ?: MusicSource.ALL
        val result = ChinaMusicApi.search(query, page, limit, source).getOrThrow()
        val items = result.list.map { song ->
            val id = onlineId(song)
            tracks[id] = song
            OnlineTrack(
                id = id,
                title = song.name,
                artist = song.singer,
                album = song.albumName,
                artworkUrl = song.img,
                source = song.source,
                durationMs = song.durationSeconds.toLong() * 1000,
            )
        }
        return SearchResponse(items, result.total, page, limit)
    }

    suspend fun resolve(id: String): String? {
        val song = tracks[id] ?: return null
        store.activeSourceSecret()?.let { (name, url, key) ->
            ChinaMusicApi.configure(MusicSourceConfig(name = name, apiUrl = url, apiKey = key))
        }
        return ChinaMusicApi.getMusicUrlWithFallback(song).getOrNull()
    }

    private fun onlineId(song: ChinaSong): String {
        val value = "${song.source}:${song.hash ?: song.songmid}"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return "online-$digest"
    }
}
