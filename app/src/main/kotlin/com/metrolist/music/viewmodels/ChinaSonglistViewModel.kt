package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.chinamusic.model.SonglistDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ChinaSonglistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val songlistId: String = savedStateHandle.get<String>("id") ?: ""
    val sourceId: String = savedStateHandle.get<String>("source") ?: "wy"
    val songlistName: String = savedStateHandle.get<String>("name") ?: ""

    private val source: MusicSource
        get() = MusicSource.fromId(sourceId) ?: MusicSource.NETEASE

    private val _detail = MutableStateFlow<SonglistDetail?>(null)
    val detail = _detail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        if (songlistId.isNotBlank()) {
            loadDetail()
        }
    }

    private var coverJob: Job? = null

    fun loadDetail() {
        coverJob?.cancel()
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            ChinaMusicApi.getSonglistDetail(
                id = songlistId,
                page = 1,
                limit = 200,
                source = source,
            ).onSuccess { detail ->
                _detail.value = detail
                _isLoading.value = false
                // Load covers in background without blocking UI
                coverJob = viewModelScope.launch(Dispatchers.IO) {
                    ChinaMusicApi.enrichSonglistCovers(detail, source).onSuccess { enriched ->
                        if (enriched.songs != detail.songs) {
                            _detail.value = enriched
                        }
                    }
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to load songlist detail $songlistId")
                _error.value = e.message ?: "加载歌单失败"
                _isLoading.value = false
            }
        }
    }

    fun retry() = loadDetail()
}
