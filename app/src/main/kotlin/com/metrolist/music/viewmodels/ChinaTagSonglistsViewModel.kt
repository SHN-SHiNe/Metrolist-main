package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.chinamusic.model.SonglistItem
import com.metrolist.chinamusic.model.SonglistSearchResult
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class ChinaTagSonglistsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val source = MusicSource.fromId(savedStateHandle.get<String>("source").orEmpty()) ?: MusicSource.KUGOU
    val tagId = URLDecoder.decode(savedStateHandle.get<String>("tag").orEmpty(), "UTF-8")
    val tagName = URLDecoder.decode(savedStateHandle.get<String>("name").orEmpty(), "UTF-8").ifBlank { tagId }

    private val _songlists = MutableStateFlow<List<SonglistItem>>(emptyList())
    val songlists: StateFlow<List<SonglistItem>> = _songlists

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var page = 1
    private var allPage = 1

    init {
        load(reset = true)
    }

    fun retry() {
        load(reset = true)
    }

    fun refresh() {
        load(reset = true)
    }

    fun loadMore() {
        if (_isLoading.value || page >= allPage) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            val nextPage = if (reset) 1 else page + 1
            ChinaMusicApi.getSonglistsByTag(tagId = tagId, page = nextPage, limit = 30, source = source)
                .onSuccess { result: SonglistSearchResult ->
                    page = result.page
                    allPage = result.allPage
                    _songlists.value = if (reset) result.list else _songlists.value + result.list
                }
                .onFailure {
                    _error.value = it.message ?: "加载失败"
                    reportException(it)
                }
            _isLoading.value = false
        }
    }
}
