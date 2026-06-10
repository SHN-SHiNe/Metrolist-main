package com.metrolist.chinamusic.model

import kotlinx.serialization.Serializable

/**
 * Supported Chinese music sources
 */
enum class MusicSource(val id: String, val displayName: String) {
    ALL("all", "聚合"),
    KUWO("kw", "酷我"),
    KUGOU("kg", "酷狗"),
    NETEASE("wy", "网易云"),
    QQ("tx", "QQ"),
    MIGU("mg", "咪咕");

    val isAggregate get() = this == ALL

    companion object {
        fun fromId(id: String): MusicSource? = entries.find { it.id == id }
        val realSources get() = entries.filter { it != ALL }
    }
}

/**
 * Audio quality levels
 */
enum class AudioQuality(val id: String, val displayName: String, val bitrate: Int) {
    LOW("128k", "标准", 128),
    HIGH("320k", "高品质", 320),
    LOSSLESS("flac", "无损", 900),
    HIRES("flac24bit", "Hi-Res", 1500);

    companion object {
        fun fromId(id: String): AudioQuality? = entries.find { it.id == id }
    }
}

/**
 * A song item from Chinese music sources
 */
@Serializable
data class ChinaSong(
    val name: String,
    val singer: String,
    val source: String,
    val songmid: String,
    val albumName: String = "",
    val albumId: String = "",
    val interval: String = "",
    val durationSeconds: Int = 0,
    val img: String? = null,
    val hash: String? = null, // For Kugou
    val copyrightId: String? = null, // For Migu
    val types: List<SongQualityType> = emptyList(),
)

@Serializable
data class SongQualityType(
    val type: String,
    val size: String? = null,
    val hash: String? = null, // For Kugou
)

/**
 * Search result wrapper
 */
@Serializable
data class SearchResult(
    val list: List<ChinaSong>,
    val total: Int,
    val page: Int,
    val allPage: Int,
    val limit: Int,
    val source: String,
)

@Serializable
data class SongComment(
    val id: String,
    val userName: String,
    val avatarUrl: String? = null,
    val content: String,
    val time: Long = 0,
    val likedCount: Int = 0,
)

/**
 * Songlist search result wrapper
 */
@Serializable
data class SonglistSearchResult(
    val list: List<SonglistItem>,
    val total: Int,
    val page: Int,
    val allPage: Int,
    val limit: Int,
    val source: String,
)

@Serializable
data class SonglistTag(
    val id: String,
    val name: String,
    val source: String,
)

/**
 * Playlist/Songlist item
 */
@Serializable
data class SonglistItem(
    val id: String,
    val name: String,
    val author: String = "",
    val img: String? = null,
    val playCount: Long = 0,
    val source: String,
)

/**
 * Playlist detail with songs
 */
@Serializable
data class SonglistDetail(
    val id: String,
    val name: String,
    val author: String = "",
    val img: String? = null,
    val desc: String = "",
    val songs: List<ChinaSong>,
    val total: Int,
    val page: Int,
    val allPage: Int,
    val source: String,
)

/**
 * Leaderboard/Chart item
 */
@Serializable
data class LeaderboardItem(
    val id: String,
    val name: String,
    val bangid: String,
    val source: String,
)

/**
 * Result of fetching songs from a leaderboard
 */
@Serializable
data class BoardSongsResult(
    val list: List<ChinaSong>,
    val total: Int,
    val page: Int,
    val limit: Int,
    val source: String,
)

/**
 * Music URL response from the custom API
 */
@Serializable
data class MusicUrlResponse(
    val code: Int,
    val url: String? = null,
    val message: String? = null,
)

/**
 * Lyrics response from the custom API
 */
@Serializable
data class LyricsResponse(
    val code: Int,
    val lyric: String? = null,
    val tlyric: String? = null,
    val lxlyric: String? = null,
    val message: String? = null,
)
