/**
 * Metrolist Project (C) 2026
 * Inline Chinese music songlist/playlist search, embedded within the main SearchScreen
 */

package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.music.LocalDatabase
import com.metrolist.music.constants.MiniPlayerBottomSpacing
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.constants.NavigationBarHeight
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.viewmodels.ChinaSearchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChinaSonglistInline(
    query: String,
    onNavigateToSonglist: (source: String, id: String, name: String) -> Unit = { _, _, _ -> },
    onSearch: (String) -> Unit = {},
    viewModel: ChinaSearchViewModel = hiltViewModel(),
) {
    val database = LocalDatabase.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val songlistResults by viewModel.songlistResults.collectAsStateWithLifecycle()
    val isSonglistLoading by viewModel.isSonglistLoading.collectAsStateWithLifecycle()
    val songlistError by viewModel.songlistError.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val searchHistory by database.searchHistory().collectAsStateWithLifecycle(initialValue = emptyList())

    val lazyListState = rememberLazyListState()

    // Auto-search when query or source changes (with debounce)
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(500L)
            viewModel.searchSonglist(query)
        }
    }

    LaunchedEffect(currentSource) {
        if (query.isNotBlank()) {
            viewModel.searchSonglist(query)
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
            if (nearEnd && !isSonglistLoading) {
                viewModel.loadMoreSonglists()
            }
        }
    }

    val songlists = songlistResults?.list ?: emptyList()

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
        if (songlistError != null) {
            item {
                Text(
                    text = songlistError ?: "",
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

        items(songlists, key = { "${it.source}_${it.id}" }) { songlist ->
            ListItem(
                headlineContent = {
                    Text(songlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        if (currentSource == MusicSource.ALL) {
                            val sourceName = MusicSource.fromId(songlist.source)?.displayName ?: songlist.source
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text(
                                    text = sourceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = songlist.author.ifEmpty { "未知作者" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                leadingContent = {
                    AsyncImage(
                        model = songlist.img,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.small)
                    )
                },
                modifier = Modifier.clickable {
                    keyboardController?.hide()
                    onNavigateToSonglist(songlist.source, songlist.id, songlist.name)
                }
            )
        }

        if (isSonglistLoading) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { CircularProgressIndicator() }
            }
        }

        if (!isSonglistLoading && songlists.isEmpty() && songlistResults != null) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                ) {
                    Text(
                        "没有找到相关歌单",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(MiniPlayerHeight + MiniPlayerBottomSpacing + NavigationBarHeight))
        }
    }
}
