package com.shine.music.server

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val serverTime: Long,
)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val mimeType: String,
    val size: Long,
    val modifiedAt: Long,
    val artworkUrl: String? = null,
    val favorite: Boolean = false,
)

@Serializable
data class TrackPage(
    val items: List<Track>,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val revision: Long = 0,
)

@Serializable
data class ScanResult(
    val id: String,
    val status: String,
    val discovered: Int,
    val updated: Int,
    val removed: Int,
    val startedAt: Long,
    val completedAt: Long,
)

@Serializable
data class CreatePlaylistRequest(val name: String)

@Serializable
data class UpdatePlaylistRequest(val name: String? = null, val trackIds: List<String>? = null, val expectedVersion: Long? = null)

@Serializable
data class PlaylistSummary(val id: String, val name: String, val version: Long, val trackCount: Int, val updatedAt: Long)

@Serializable
data class PlaylistDetail(val id: String, val name: String, val version: Long, val tracks: List<Track>, val updatedAt: Long)

@Serializable
data class HistoryRequest(val trackId: String, val playedAt: Long? = null)

@Serializable
data class HistoryEntry(val id: Long, val track: Track, val playedAt: Long)

@Serializable
data class SourceConfigRequest(val name: String, val apiUrl: String, val apiKey: String, val enabled: Boolean = true)

@Serializable
data class SourceConfigView(val id: String, val name: String, val apiUrl: String, val apiKeyMasked: String, val enabled: Boolean, val updatedAt: Long)

@Serializable
data class DownloadRequest(
    val trackId: String? = null,
    val url: String? = null,
    val title: String,
    val artist: String = "未知艺术家",
)

@Serializable
data class DownloadJob(
    val id: String,
    val title: String,
    val artist: String,
    val status: String,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class OnlineTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String? = null,
    val source: String,
    val durationMs: Long = 0,
)

@Serializable
data class SearchResponse(val items: List<OnlineTrack>, val total: Int, val page: Int, val limit: Int)

@Serializable
data class CreateRoomRequest(val name: String, val id: String? = null)

@Serializable
data class RoomSummary(val id: String, val name: String, val memberCount: Int, val version: Long, val updatedAt: Long)

@Serializable
data class RoomPlaybackState(
    val queue: List<String> = emptyList(),
    val currentTrackId: String? = null,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val effectiveAt: Long = 0,
)

@Serializable
data class UpdateRoomStateRequest(
    val queue: List<String>? = null,
    val currentTrackId: String? = null,
    val positionMs: Long? = null,
    val playing: Boolean? = null,
)

@Serializable
data class RoomDetail(val summary: RoomSummary, val state: RoomPlaybackState)

@Serializable
data class MessageResponse(val status: String)
