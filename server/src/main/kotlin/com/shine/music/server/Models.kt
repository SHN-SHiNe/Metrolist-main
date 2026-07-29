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
    val libraryId: String = DEFAULT_LIBRARY_ID,
    val analysis: TrackAnalysis = TrackAnalysis(),
)

@Serializable
data class TrackAnalysis(
    val status: String = "pending",
    val progress: Float = 0f,
    val message: String? = null,
    val bpm: Float? = null,
    val keyName: String? = null,
    val camelot: String? = null,
    val valence: Float? = null,
    val energy: Float? = null,
    val danceability: Float? = null,
    val acousticness: Float? = null,
    val instrumentalness: Float? = null,
    val liveness: Float? = null,
    val speechiness: Float? = null,
    val analyzedAt: Long? = null,
)

@Serializable
data class SimilarTrack(
    val track: Track,
    val similarityPercent: Int,
    val bpmDelta: Float,
    val camelotDelta: Int,
)

@Serializable
data class SimilarTracksResponse(
    val seed: Track,
    val items: List<SimilarTrack>,
)

@Serializable
data class AnalysisSummary(
    val available: Boolean,
    val implementation: String,
    val unavailableReason: String? = null,
    val total: Int,
    val pending: Int,
    val queued: Int,
    val running: Int,
    val completed: Int,
    val failed: Int,
)

@Serializable
data class AnalysisEnqueueRequest(
    val trackIds: List<String> = emptyList(),
    val missingOnly: Boolean = true,
)

@Serializable
data class AnalysisEnqueueResponse(
    val queued: Int,
    val draining: Boolean,
)

@Serializable
data class AdvancedSearchRequest(
    val text: String = "",
    val bpm: Float? = null,
    val bpmTolerance: Float = 5f,
    val keyName: String? = null,
    val keyTolerance: Int = 0,
    val emotionTolerance: Float = 0.08f,
    val valence: Float? = null,
    val energy: Float? = null,
    val danceability: Float? = null,
    val acousticness: Float? = null,
    val instrumentalness: Float? = null,
    val liveness: Float? = null,
    val speechiness: Float? = null,
    val limit: Int = 100,
)

@Serializable
data class AdvancedSearchItem(
    val track: Track,
    val similarityPercent: Int,
)

@Serializable
data class AdvancedSearchResponse(
    val items: List<AdvancedSearchItem>,
    val totalCandidates: Int,
)

@Serializable
data class RadioNextRequest(
    val currentTrackId: String,
    val recentTrackIds: List<String> = emptyList(),
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
    val libraryId: String? = null,
)

const val DEFAULT_LIBRARY_ID = "default"

@Serializable
data class MusicLibraryRequest(
    val name: String,
    val path: String,
    val deviceType: String = "local",
    val readOnly: Boolean = true,
    val enabled: Boolean = true,
    val downloadTarget: Boolean = false,
)

@Serializable
data class MusicLibraryView(
    val id: String,
    val name: String,
    val path: String,
    val deviceType: String,
    val readOnly: Boolean,
    val enabled: Boolean,
    val downloadTarget: Boolean,
    val status: String,
    val trackCount: Int,
    val lastScanAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
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
