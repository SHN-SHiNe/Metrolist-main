package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.MusicSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class ChinaBoardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val source: MusicSource = MusicSource.fromId(
        savedStateHandle.get<String>("source") ?: "wy"
    ) ?: MusicSource.NETEASE
    val bangid: String = URLDecoder.decode(
        savedStateHandle.get<String>("bangid") ?: "", "UTF-8"
    )
    val boardName: String = URLDecoder.decode(
        savedStateHandle.get<String>("name") ?: "", "UTF-8"
    )

    private val _songs = MutableStateFlow<List<ChinaSong>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            ChinaMusicApi.getBoardSongs(bangid = bangid, page = 1, limit = 200, source = source)
                .onSuccess { result ->
                    _songs.value = result.list
                }
                .onFailure {
                    _error.value = it.message ?: "加载失败"
                    Timber.e(it, "Failed to load board songs")
                }
            _isLoading.value = false
        }
    }

    fun retry() {
        load()
    }

    fun refresh() {
        _songs.value = emptyList()
        load()
    }
}
