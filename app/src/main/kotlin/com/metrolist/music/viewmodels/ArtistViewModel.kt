/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: MusicDatabase,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    private val routeName = savedStateHandle.get<String>("name")?.let { Uri.decode(it) }

    private val _artistName = MutableStateFlow(
        routeName
            ?: if (artistId.startsWith("china_artist_")) {
                Uri.decode(artistId.removePrefix("china_artist_"))
            } else {
                ""
            }
    )
    val artistNameFlow = _artistName.asStateFlow()
    val artistName: String get() = _artistName.value

    private val _songs = MutableStateFlow<List<ChinaSong>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    val thumbnailUrl: String?
        get() = _songs.value.filter { !it.img.isNullOrBlank() }.randomOrNull()?.img

    init {
        loadSongs()
    }

    private fun loadSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true

            // If the ID doesn't have a china_artist_ prefix, look up the name from the database
            if (routeName.isNullOrBlank() && !artistId.startsWith("china_artist_")) {
                val dbArtist = database.artist(artistId).first()
                val name = dbArtist?.artist?.name ?: artistId
                _artistName.value = name
                Timber.tag("ArtistViewModel").d("artistId=%s routeName=%s dbName=%s searchName=%s", artistId, routeName, dbArtist?.artist?.name, _artistName.value)
            } else {
                Timber.tag("ArtistViewModel").d("artistId=%s routeName=%s searchName=%s", artistId, routeName, _artistName.value)
            }

            val searchName = _artistName.value
            if (searchName.isNotBlank()) {
                ChinaMusicApi.search(
                    keyword = searchName,
                    page = 1,
                    limit = 50,
                    source = MusicSource.ALL,
                ).onSuccess { result ->
                    _songs.value = result.list
                }.onFailure {
                    reportException(it)
                }
            }
            _isLoading.value = false
        }
    }
}
