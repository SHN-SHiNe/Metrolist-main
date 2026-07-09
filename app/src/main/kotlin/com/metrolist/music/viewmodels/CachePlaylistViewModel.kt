/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.SimpleCache
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.extensions.filterExplicit
import com.metrolist.music.extensions.filterVideoSongs
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class CachePlaylistViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        @PlayerCache private val playerCache: SimpleCache,
    ) : ViewModel() {
        private val _cachedSongs = MutableStateFlow<List<Song>>(emptyList())
        val cachedSongs: StateFlow<List<Song>> = _cachedSongs

        init {
            viewModelScope.launch {
                while (true) {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                    val explicitOfflineSongs = database.offlineCachedSongsByCreateDateAsc().first()
                    val cachedIds = playerCache.keys.toSet()

                    val songs =
                        if (cachedIds.isNotEmpty()) {
                            database.getSongsByIds(cachedIds.toList())
                        } else {
                            emptyList()
                        }

                    val completeSongs =
                        songs.filter {
                            val contentLength = it.format?.contentLength
                            contentLength != null && playerCache.isCached(it.song.id, 0, contentLength)
                        }
                    if (completeSongs.isNotEmpty()) {
                        val now = LocalDateTime.now()
                        database.query {
                            completeSongs.forEach {
                                if (it.song.dateDownload == null) {
                                    update(it.song.copy(dateDownload = now))
                                }
                            }
                        }
                    }

                    val songsById = (explicitOfflineSongs + completeSongs).associateBy { it.song.id }
                    val orderedIds =
                        OfflineCachedSongs
                            .merge(
                                explicitOffline =
                                    explicitOfflineSongs.map {
                                        OfflineCachedSongRef(it.song.id, it.song.dateDownload)
                                    },
                                playerCache =
                                    completeSongs.map {
                                        OfflineCachedSongRef(it.song.id, it.song.dateDownload ?: LocalDateTime.now())
                                    },
                            ).map { it.id }

                    _cachedSongs.value =
                        orderedIds
                            .mapNotNull { songsById[it] }
                            .filterExplicit(hideExplicit)
                            .filterVideoSongs(hideVideoSongs)

                    delay(1000)
                }
            }
        }

        fun removeSongFromCache(songId: String) {
            playerCache.removeResource(songId)
        }
    }
