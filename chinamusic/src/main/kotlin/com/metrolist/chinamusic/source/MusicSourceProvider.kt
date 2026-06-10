package com.metrolist.chinamusic.source

import com.metrolist.chinamusic.model.BoardSongsResult
import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.LeaderboardItem
import com.metrolist.chinamusic.model.SearchResult
import com.metrolist.chinamusic.model.SonglistDetail
import com.metrolist.chinamusic.model.SonglistSearchResult
import com.metrolist.chinamusic.model.SonglistTag

/**
 * Interface for Chinese music source implementations
 */
interface MusicSourceProvider {
    val sourceId: String
    val sourceName: String

    /**
     * Search for songs
     */
    suspend fun search(keyword: String, page: Int, limit: Int): SearchResult

    /**
     * Search for playlists/songlists
     */
    suspend fun searchSonglist(keyword: String, page: Int, limit: Int): SonglistSearchResult

    suspend fun getSonglistsByTag(sortId: String, tagId: String, page: Int, limit: Int): SonglistSearchResult =
        searchSonglist(tagId, page, limit)

    suspend fun getSonglistTags(): List<SonglistTag> = emptyList()

    suspend fun getSonglistGenreTags(): List<SonglistTag> = emptyList()

    /**
     * Get playlist detail with songs
     */
    suspend fun getSonglistDetail(id: String, page: Int, limit: Int): SonglistDetail

    /**
     * Enrich a SonglistDetail with individual song covers (optional, default no-op)
     */
    suspend fun enrichCovers(detail: SonglistDetail): SonglistDetail = detail

    suspend fun getBoards(): List<LeaderboardItem> = emptyList()

    suspend fun getBoardSongs(bangid: String, page: Int, limit: Int): BoardSongsResult =
        BoardSongsResult(emptyList(), 0, page, limit, sourceId)

    suspend fun getSongsByIds(ids: List<String>): List<ChinaSong> = emptyList()
}
