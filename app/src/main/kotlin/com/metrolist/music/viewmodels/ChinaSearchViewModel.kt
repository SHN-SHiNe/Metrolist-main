/**
 * Metrolist Project (C) 2026
 * ViewModel for Chinese music source search
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.chinamusic.model.SearchResult
import com.metrolist.chinamusic.model.SonglistItem
import com.metrolist.chinamusic.model.SonglistSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ChinaSearchViewModel @Inject constructor() : ViewModel() {

    private val _searchResults = MutableStateFlow<SearchResult?>(null)
    val searchResults: StateFlow<SearchResult?> = _searchResults

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _currentSource = MutableStateFlow(MusicSource.ALL)
    val currentSource: StateFlow<MusicSource> = _currentSource

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Songlist search
    private val _songlistResults = MutableStateFlow<SonglistSearchResult?>(null)
    val songlistResults: StateFlow<SonglistSearchResult?> = _songlistResults

    private val _isSonglistLoading = MutableStateFlow(false)
    val isSonglistLoading: StateFlow<Boolean> = _isSonglistLoading

    private val _songlistError = MutableStateFlow<String?>(null)
    val songlistError: StateFlow<String?> = _songlistError

    private var currentQuery = ""
    private var currentPage = 1
    private var searchJob: Job? = null
    private var songlistQuery = ""
    private var songlistPage = 1
    private var songlistJob: Job? = null

    fun setSource(source: MusicSource) {
        _currentSource.value = source
        if (currentQuery.isNotEmpty()) {
            search(currentQuery)
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        currentQuery = query
        currentPage = 1
        _error.value = null

        if (query.isBlank()) {
            _searchResults.value = null
            return
        }

        _isLoading.value = true
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            Timber.tag("ChinaSearch").d("Searching: '$query' on ${_currentSource.value.displayName}")
            ChinaMusicApi.search(
                keyword = query,
                page = 1,
                limit = 30,
                source = _currentSource.value,
            ).onSuccess { result ->
                if (currentQuery != query) return@onSuccess
                Timber.tag("ChinaSearch").d("Search success: ${result.list.size} results")
                _searchResults.value = result
                _isLoading.value = false
            }.onFailure { e ->
                if (e is CancellationException || currentQuery != query) return@onFailure
                Timber.tag("ChinaSearch").e(e, "Search failed for source=${_currentSource.value.id}")
                _error.value = "${_currentSource.value.displayName}: ${e.message ?: "搜索失败"}"  
                _isLoading.value = false
            }
        }
    }

    fun searchSonglist(query: String) {
        songlistJob?.cancel()
        songlistQuery = query
        songlistPage = 1
        _songlistError.value = null

        if (query.isBlank()) {
            _songlistResults.value = null
            return
        }

        _isSonglistLoading.value = true
        songlistJob = viewModelScope.launch(Dispatchers.IO) {
            ChinaMusicApi.searchSonglist(
                keyword = query,
                page = 1,
                limit = 20,
                source = _currentSource.value,
            ).onSuccess { result ->
                if (songlistQuery != query) return@onSuccess
                Timber.tag("ChinaSearch").d("Songlist search success: ${result.list.size} results")
                _songlistResults.value = result
                _isSonglistLoading.value = false
            }.onFailure { e ->
                if (e is CancellationException || songlistQuery != query) return@onFailure
                Timber.tag("ChinaSearch").e(e, "Songlist search failed")
                _songlistError.value = "${_currentSource.value.displayName}: ${e.message ?: "歌单搜索失败"}"
                _isSonglistLoading.value = false
            }
        }
    }

    fun loadMoreSonglists() {
        val current = _songlistResults.value ?: return
        if (songlistPage >= current.allPage) return
        if (_isSonglistLoading.value) return

        _isSonglistLoading.value = true
        songlistPage++

        viewModelScope.launch(Dispatchers.IO) {
            ChinaMusicApi.searchSonglist(
                keyword = songlistQuery,
                page = songlistPage,
                limit = 20,
                source = _currentSource.value,
            ).onSuccess { result ->
                _songlistResults.value = SonglistSearchResult(
                    list = current.list + result.list,
                    total = result.total,
                    page = songlistPage,
                    allPage = result.allPage,
                    limit = result.limit,
                    source = result.source,
                )
                _isSonglistLoading.value = false
            }.onFailure { e ->
                if (e is CancellationException) return@onFailure
                Timber.tag("ChinaSearch").e(e, "Load more songlists failed")
                songlistPage--
                _isSonglistLoading.value = false
            }
        }
    }

    fun loadMore() {
        val current = _searchResults.value ?: return
        if (currentPage >= current.allPage) return
        if (_isLoading.value) return

        _isLoading.value = true
        currentPage++

        viewModelScope.launch(Dispatchers.IO) {
            ChinaMusicApi.search(
                keyword = currentQuery,
                page = currentPage,
                limit = 30,
                source = _currentSource.value,
            ).onSuccess { result ->
                val combined = SearchResult(
                    list = current.list + result.list,
                    total = result.total,
                    page = currentPage,
                    allPage = result.allPage,
                    limit = result.limit,
                    source = result.source,
                )
                _searchResults.value = combined
                _isLoading.value = false
            }.onFailure { e ->
                if (e is CancellationException) return@onFailure
                Timber.tag("ChinaSearch").e(e, "Load more failed")
                currentPage--
                _isLoading.value = false
            }
        }
    }
}
