/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.lastfm.LastFM
import com.metrolist.music.constants.LastFMUseSendLikes
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data class Error(val message: String) : SyncStatus()
    data object Completed : SyncStatus()
}

data class SyncState(
    val overallStatus: SyncStatus = SyncStatus.Idle,
    val likedSongs: SyncStatus = SyncStatus.Idle,
    val librarySongs: SyncStatus = SyncStatus.Idle,
    val uploadedSongs: SyncStatus = SyncStatus.Idle,
    val likedAlbums: SyncStatus = SyncStatus.Idle,
    val uploadedAlbums: SyncStatus = SyncStatus.Idle,
    val artists: SyncStatus = SyncStatus.Idle,
    val playlists: SyncStatus = SyncStatus.Idle,
    val currentOperation: String = ""
)

@Singleton
class SyncUtils @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var lastfmSendLikes = false

    init {
        syncScope.launch {
            context.dataStore.data
                .map { it[LastFMUseSendLikes] ?: false }
                .distinctUntilChanged()
                .collect { lastfmSendLikes = it }
        }
    }

    private fun markCompleted() {
        _syncState.value = SyncState(overallStatus = SyncStatus.Completed)
    }

    private suspend fun updateLastFmLove(song: SongEntity) {
        if (!lastfmSendLikes) return
        try {
            val dbSong = database.song(song.id).firstOrNull()
            LastFM.setLoveStatus(
                artist = dbSong?.artists?.joinToString { it.name }.orEmpty(),
                track = song.title,
                love = song.liked,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to update Last.fm love status")
        }
    }

    fun performFullSync() = markCompleted()

    suspend fun performFullSyncSuspend() = markCompleted()

    fun tryAutoSync() = markCompleted()

    fun runAllSyncs() = markCompleted()

    fun likeSong(s: SongEntity) {
        syncScope.launch {
            updateLastFmLove(s)
        }
    }

    fun subscribeChannel(channelId: String, subscribe: Boolean) = Unit

    fun savePodcast(podcastId: String, save: Boolean) = Unit

    fun saveEpisode(episodeId: String, save: Boolean, setVideoId: String? = null) = Unit

    fun syncLikedSongs() = markCompleted()

    fun syncLibrarySongs() = markCompleted()

    fun syncUploadedSongs() = markCompleted()

    fun syncLikedAlbums() = markCompleted()

    fun syncUploadedAlbums() = markCompleted()

    fun syncArtistsSubscriptions() = markCompleted()

    fun syncSavedPlaylists() = markCompleted()

    fun syncAutoSyncPlaylists() = markCompleted()

    fun syncAllAlbums() = markCompleted()

    fun syncAllArtists() = markCompleted()

    fun syncPodcastSubscriptions() = markCompleted()

    fun syncEpisodesForLater() = markCompleted()

    fun cleanupDuplicatePlaylists() = markCompleted()

    fun clearAllSyncedContent() = markCompleted()

    fun clearPodcastData() = markCompleted()

    suspend fun syncLikedSongsSuspend() = markCompleted()

    suspend fun syncLibrarySongsSuspend() = markCompleted()

    suspend fun syncUploadedSongsSuspend() = markCompleted()

    suspend fun syncLikedAlbumsSuspend() = markCompleted()

    suspend fun syncUploadedAlbumsSuspend() = markCompleted()

    suspend fun syncArtistsSubscriptionsSuspend() = markCompleted()

    suspend fun syncPodcastSubscriptionsSuspend() = markCompleted()

    suspend fun syncEpisodesForLaterSuspend() = markCompleted()

    suspend fun syncSavedPlaylistsSuspend() = markCompleted()

    suspend fun syncAutoSyncPlaylistsSuspend() = markCompleted()

    suspend fun cleanupDuplicatePlaylistsSuspend() = markCompleted()

    suspend fun clearAllSyncedContentSuspend() = markCompleted()

    suspend fun syncAllAlbumsSuspend() = markCompleted()

    suspend fun syncAllArtistsSuspend() = markCompleted()

    suspend fun clearAllLibraryData() = withContext(Dispatchers.IO) {
        markCompleted()
    }

    suspend fun removeFromPlaylistAndAwaitSync(
        browseId: String,
        songId: String,
        setVideoId: String,
        playlistId: String
    ) = Unit

    fun registerPendingAdd(browseId: String, songId: String) = Unit

    fun unregisterPendingAdd(browseId: String, songId: String) = Unit

    fun scheduleRemoveFromPlaylist(
        browseId: String,
        songId: String,
        playlistId: String,
        getSetVideoId: suspend () -> String?
    ) = Unit

    fun cancelAllSyncs() {
        _syncState.value = SyncState()
    }
}
