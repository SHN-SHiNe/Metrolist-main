/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.localmusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.FileDownloadDirectoryUriKey
import com.metrolist.music.constants.LocalMusicFolderUriKey
import com.metrolist.music.constants.LocalMusicFolderUrisKey
import com.metrolist.music.constants.LocalMusicMinDurationSecondsKey
import com.metrolist.music.constants.LocalMusicSortDescendingKey
import com.metrolist.music.constants.LocalMusicSortType
import com.metrolist.music.constants.LocalMusicSortTypeKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LocalSong
import com.metrolist.music.extensions.matchesNormalizedQuery
import com.metrolist.music.extensions.normalizeForSearch
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.localmusic.LocalMusicScanProgress
import com.metrolist.music.localmusic.LocalMusicScanSource
import com.metrolist.music.localmusic.LocalMusicScanSources
import com.metrolist.music.localmusic.LocalMusicScanner
import com.metrolist.music.localmusic.analysis.LocalMusicAnalysisManager
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

data class LocalMusicScanState(
    val isScanning: Boolean = false,
    val scannedFiles: Int = 0,
    val currentFile: String? = null,
    val error: String? = null,
)

data class LocalMusicSongsState(
    val songs: List<LocalSong> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class LocalMusicViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val scanner: LocalMusicScanner,
    private val analysisManager: LocalMusicAnalysisManager,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val debouncedSearchQuery =
        _searchQuery
            .debounce(300)
            .stateIn(viewModelScope, SharingStarted.Lazily, "")

    private val _scanState = MutableStateFlow(LocalMusicScanState())
    val scanState = _scanState.asStateFlow()
    val analysisStates = analysisManager.states

    private var scanJob: Job? = null

    val scanSources =
        context.dataStore.data
            .map {
                LocalMusicScanSources.effectiveSources(
                    legacyFolderUri = it[LocalMusicFolderUriKey],
                    userFolderUris = it[LocalMusicFolderUrisKey],
                    fileDownloadDirectoryUri = it[FileDownloadDirectoryUriKey],
                )
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val minDurationSeconds =
        context.dataStore.data
            .map { it[LocalMusicMinDurationSecondsKey] ?: DEFAULT_MIN_DURATION_SECONDS }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Lazily, DEFAULT_MIN_DURATION_SECONDS)

    fun updateMinDurationSeconds(seconds: Int) {
        viewModelScope.launch {
            context.dataStore.edit { it[LocalMusicMinDurationSecondsKey] = seconds.coerceIn(0, 180) }
        }
    }

    private val localSongsState =
        combine(
            database.localSongs()
                .map<List<LocalSong>, Pair<List<LocalSong>, Boolean>> { it to false }
                .onStart { emit(emptyList<LocalSong>() to true) },
            context.dataStore.data.map {
                it[LocalMusicSortTypeKey].toEnum(LocalMusicSortType.CREATE_DATE) to
                    (it[LocalMusicSortDescendingKey] ?: true)
            }.distinctUntilChanged(),
        ) { result, sort ->
            val (songs, isLoading) = result
            val (sortType, descending) = sort
            LocalMusicSongsState(
                songs = songs.sorted(sortType, descending),
                isLoading = isLoading,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, LocalMusicSongsState())

    val filteredSongs =
        combine(
            localSongsState,
            debouncedSearchQuery,
        ) { state, query ->
            val songs =
                if (query.isBlank()) {
                    state.songs
                } else {
                    val normalized = query.normalizeForSearch()
                    state.songs.filter { localSong ->
                        matchesNormalizedQuery(
                            normalized,
                            localSong.song.song.title,
                            localSong.song.orderedArtists.joinToString { it.name },
                            localSong.song.album?.title,
                            localSong.localMusic.displayName,
                            localSong.localMusic.keyName,
                            localSong.localMusic.bpm?.toString(),
                            localSong.localMusic.energy?.toString(),
                        )
                    }
                }
            LocalMusicSongsState(
                songs = songs,
                isLoading = state.isLoading,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, LocalMusicSongsState())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun analyze(localSong: LocalSong) {
        analysisManager.analyze(localSong)
    }

    fun selectFolder(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure {
                Timber.tag("LocalMusicViewModel").w(it, "Could not persist local music tree read permission")
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure {
                Timber.tag("LocalMusicViewModel").w(it, "Could not persist local music tree write permission")
            }
            context.dataStore.edit {
                it[LocalMusicFolderUrisKey] =
                    LocalMusicScanSources.addUserFolder(
                        it[LocalMusicFolderUrisKey],
                        uri.toString(),
                    )
            }
            scan(uri.toString())
        }
    }

    fun removeFolder(uri: String) {
        viewModelScope.launch {
            context.dataStore.edit {
                it[LocalMusicFolderUrisKey] =
                    LocalMusicScanSources.removeUserFolder(
                        it[LocalMusicFolderUrisKey],
                        uri,
                    )
                if (it[LocalMusicFolderUriKey] == uri) {
                    it.remove(LocalMusicFolderUriKey)
                }
            }
        }
    }

    fun rescan() {
        scan(scanSources.value)
    }

    private fun scan(sources: List<LocalMusicScanSource>) {
        if (sources.isEmpty() || scanJob?.isActive == true) return
        scanJob =
            viewModelScope.launch {
                _scanState.value = LocalMusicScanState(isScanning = true)
                var scannedTotal = 0
                var firstError: String? = null
                sources.forEach { source ->
                    runCatching {
                        scanner.scan(Uri.parse(source.uri), minDurationSeconds.value) { progress: LocalMusicScanProgress ->
                            _scanState.value =
                                LocalMusicScanState(
                                    isScanning = true,
                                    scannedFiles = scannedTotal + progress.scannedFiles,
                                    currentFile = progress.currentFile,
                                )
                        }
                    }.onSuccess { scanned ->
                        scannedTotal += scanned
                        _scanState.value = LocalMusicScanState(isScanning = true, scannedFiles = scannedTotal)
                    }.onFailure { error ->
                        Timber.tag("LocalMusicViewModel").w(error, "Local music scan failed for %s", source.uri)
                        firstError = firstError ?: (error.message ?: error::class.java.simpleName)
                    }
                }
                _scanState.value =
                    LocalMusicScanState(
                        scannedFiles = scannedTotal,
                        error = firstError,
                    )
            }
    }

    private fun scan(uriString: String) {
        if (scanJob?.isActive == true) return
        scanJob =
            viewModelScope.launch {
                _scanState.value = LocalMusicScanState(isScanning = true)
                runCatching {
                    scanner.scan(Uri.parse(uriString), minDurationSeconds.value) { progress: LocalMusicScanProgress ->
                        _scanState.value =
                            LocalMusicScanState(
                                isScanning = true,
                                scannedFiles = progress.scannedFiles,
                                currentFile = progress.currentFile,
                            )
                    }
                }.onSuccess { scanned ->
                    _scanState.value = LocalMusicScanState(scannedFiles = scanned)
                }.onFailure { error ->
                    Timber.tag("LocalMusicViewModel").w(error, "Local music scan failed")
                    _scanState.value =
                        LocalMusicScanState(
                            isScanning = false,
                            error = error.message ?: error::class.java.simpleName,
                        )
                }
            }
    }

    private fun List<LocalSong>.sorted(
        sortType: LocalMusicSortType,
        descending: Boolean,
    ): List<LocalSong> {
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }
        val sorted =
            when (sortType) {
                LocalMusicSortType.CREATE_DATE -> sortedBy { it.localMusic.lastScannedAt }
                LocalMusicSortType.NAME -> sortedWith(compareBy(collator) { it.song.song.title })
                LocalMusicSortType.ARTIST -> sortedWith(compareBy(collator) { localSong ->
                    localSong.song.orderedArtists.joinToString("") { it.name }
                })
                LocalMusicSortType.BPM -> sortedWith(compareBy(nullsLast()) { it.localMusic.bpm })
                LocalMusicSortType.KEY -> sortedWith(compareBy(nullsLast(collator)) { it.localMusic.keyName })
                LocalMusicSortType.ENERGY -> sortedWith(compareBy(nullsLast()) { it.localMusic.energy })
            }
        return if (descending) sorted.asReversed() else sorted
    }
}

private const val DEFAULT_MIN_DURATION_SECONDS = 30
