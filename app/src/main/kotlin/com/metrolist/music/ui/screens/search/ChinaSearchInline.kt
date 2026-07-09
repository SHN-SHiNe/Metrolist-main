/**
 * Metrolist Project (C) 2026
 * Inline Chinese music source search, embedded within the main SearchScreen
 */

package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.constants.MiniPlayerBottomSpacing
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.constants.NavigationBarHeight
import com.metrolist.music.models.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.ui.component.MediaMetadataListItem
import com.metrolist.music.ui.component.Icon as BadgeIcon
import com.metrolist.music.viewmodels.ChinaSearchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChinaSearchInline(
    query: String,
    navController: androidx.navigation.NavController,
    onSearch: (String) -> Unit = {},
    viewModel: ChinaSearchViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentMediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val searchHistory by database.searchHistory().collectAsStateWithLifecycle(initialValue = emptyList())

    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()

    // Auto-search when query changes (with debounce)
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(500L)
            viewModel.search(query)
            onSearch(query)
        }
    }

    // Load more when near bottom
    LaunchedEffect(lazyListState) {
        snapshotFlow {
            val layoutInfo = lazyListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= totalItems - 5
        }.distinctUntilChanged().collectLatest { nearEnd ->
            if (nearEnd && !isLoading) {
                viewModel.loadMore()
            }
        }
    }

    val songs = searchResults?.list ?: emptyList()

    LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
        // Source selector chips
        item {
            SearchControlPanel(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                ChipsRow(
                    chips = MusicSource.entries.map { it to it.displayName },
                    currentValue = currentSource,
                    onValueUpdate = { viewModel.setSource(it) },
                )
            }
        }

        // Error display
        if (error != null) {
            item {
                Text(
                    text = error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // Search history when no query
        if (query.isBlank() && searchHistory.isNotEmpty()) {
            item {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    searchHistory.forEach { history ->
                        SuggestionChip(
                            onClick = { onSearch(history.query) },
                            label = { Text(history.query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            shape = RoundedCornerShape(8.dp),
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            border = null,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        if (query.isBlank()) return@LazyColumn

        items(songs, key = { "${it.source}_${it.songmid}" }) { song ->
            val mediaMetadata = remember(song) { song.toMediaMetadata() }
            val isActive = currentMediaMetadata?.id == mediaMetadata.id
            val dbSong by database.song(mediaMetadata.id).collectAsStateWithLifecycle(initialValue = null)
            MediaMetadataListItem(
                mediaMetadata = mediaMetadata,
                isActive = isActive,
                isPlaying = isPlaying,
                badges = {
                    dbSong?.song?.let { s ->
                        if (s.liked) BadgeIcon.Favorite()
                        if (s.inLibrary != null) BadgeIcon.Library()
                    }
                    val download by LocalDownloadUtil.current.getDownload(mediaMetadata.id)
                        .collectAsStateWithLifecycle(initialValue = null)
                    BadgeIcon.Download(download?.state)
                },
                modifier = Modifier.clickable {
                    keyboardController?.hide()
                    val mediaItems = songs.map { it.toMediaItem() }
                    val index = songs.indexOf(song)
                    playerConnection.playQueue(
                        ListQueue(
                            title = "国内源搜索",
                            items = mediaItems,
                            startIndex = if (index >= 0) index else 0,
                        )
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (currentSource == MusicSource.ALL) {
                            val sourceName = MusicSource.fromId(song.source)?.displayName ?: song.source
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Text(
                                    text = sourceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        IconButton(onClick = {
                            keyboardController?.hide()
                            val songEntity = mediaMetadata.toSongEntity()
                            val artistEntities = mediaMetadata.artists.map {
                                ArtistEntity(id = it.id ?: "china_artist_${it.name}", name = it.name)
                            }
                            database.query { upsert(songEntity) }
                            val songForMenu = Song(song = songEntity, artists = artistEntities)
                            menuState.show {
                                SongMenu(
                                    originalSong = songForMenu,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        }) {
                            Icon(painter = painterResource(R.drawable.more_vert), contentDescription = null)
                        }
                    }
                }
            )
        }

        if (isLoading) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { CircularProgressIndicator() }
            }
        }
        if (!isLoading && songs.isEmpty() && searchResults != null) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                ) {
                    Text("没有找到相关歌曲", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(modifier = Modifier.height(MiniPlayerHeight + MiniPlayerBottomSpacing + NavigationBarHeight)) }
    }
}
