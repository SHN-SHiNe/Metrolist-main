/**
 * Metrolist Project (C) 2026
 * Chinese music artist detail screen
 */

package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.R
import com.metrolist.music.constants.MiniPlayerBottomSpacing
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.constants.NavigationBarHeight
import com.metrolist.music.models.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.MediaMetadataListItem
import com.metrolist.music.viewmodels.ChinaSearchViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChinaArtistScreen(
    navController: NavController,
    artistName: String,
    viewModel: ChinaSearchViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val currentMediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()

    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()

    // Auto-search artist name
    LaunchedEffect(artistName) {
        if (artistName.isNotBlank()) {
            viewModel.search(artistName)
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
    val artistImageUrl = remember(songs) {
        songs.firstOrNull()?.img
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
    ) {
        // Artist header with background image
        item(key = "artist_header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                // Background image
                if (artistImageUrl != null) {
                    AsyncImage(
                        model = artistImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Gradient overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    MaterialTheme.colorScheme.surface,
                                )
                            )
                        )
                )

                // Back button
                IconButton(
                    onClick = { navController.navigateUp() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 40.dp, start = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        tint = Color.White
                    )
                }

                // Artist name
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
        }

        // Song count
        item(key = "song_count") {
            if (songs.isNotEmpty()) {
                Text(
                    text = "${songs.size}首歌曲",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
        }

        // Song list
        items(
            items = songs,
            key = { "${it.source}_${it.songmid}" }
        ) { song ->
            val mediaMetadata = remember(song) { song.toMediaMetadata() }
            val songMediaId = "china_${song.source}_${song.songmid}"
            val isCurrentSong = currentMediaMetadata?.id == songMediaId
            val sourceName = remember(song.source) {
                MusicSource.fromId(song.source)?.displayName ?: song.source
            }

            MediaMetadataListItem(
                mediaMetadata = mediaMetadata,
                isActive = isCurrentSong,
                isPlaying = isPlaying,
                modifier = Modifier.clickable {
                    if (isCurrentSong) {
                        playerConnection.togglePlayPause()
                    } else {
                        val mediaItems = songs.map { it.toMediaItem() }
                        val index = songs.indexOf(song)
                        playerConnection.playQueue(
                            ListQueue(
                                title = artistName,
                                items = mediaItems,
                                startIndex = if (index >= 0) index else 0,
                            )
                        )
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Source badge
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = sourceName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = {
                            val songEntity = mediaMetadata.toSongEntity()
                            val artistEntities = mediaMetadata.artists.map {
                                ArtistEntity(id = it.id ?: "china_artist_${it.name.hashCode()}", name = it.name)
                            }
                            val songForMenu = Song(song = songEntity, artists = artistEntities)
                            menuState.show {
                                SongMenu(
                                    originalSong = songForMenu,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                    }
                }
            )
        }

        if (isLoading) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        if (!isLoading && songs.isEmpty() && searchResults != null) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                ) {
                    Text(
                        text = "没有找到相关歌曲",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Bottom spacing for mini player
        item {
            Spacer(
                modifier = Modifier.height(
                    MiniPlayerHeight + MiniPlayerBottomSpacing + NavigationBarHeight
                )
            )
        }
    }
}
