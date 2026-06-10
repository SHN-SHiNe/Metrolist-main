/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.artist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.AppBarHeight
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.toMediaItem as chinaSongToMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.MediaMetadataListItem
import com.metrolist.music.ui.component.Icon as BadgeIcon
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.component.shimmer.ListItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.ui.utils.fadingEdge
import com.metrolist.music.ui.utils.isScrollingUp
import com.metrolist.music.viewmodels.ArtistViewModel
import com.valentinilk.shimmer.shimmer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistOnlineScreen(
    navController: NavController,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val artistName by viewModel.artistNameFlow.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()
    val density = LocalDensity.current
    val systemBarsTopPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val headerOffset = with(density) { -(systemBarsTopPadding + AppBarHeight).roundToPx() }

    val transparentAppBar by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset < 100
        }
    }

    val thumbnail = songs.firstOrNull { !it.img.isNullOrBlank() }?.img
    val mediaItems = remember(songs) { songs.map { it.chinaSongToMediaItem() } }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            if (isLoading && songs.isEmpty()) {
                item(key = "shimmer") {
                    ShimmerHost(
                        modifier = Modifier.offset { IntOffset(x = 0, y = headerOffset) },
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1.1f)) {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .shimmer()
                                    .background(MaterialTheme.colorScheme.onSurface)
                                    .fadingEdge(top = systemBarsTopPadding + AppBarHeight, bottom = 200.dp),
                            )
                        }
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            TextPlaceholder(
                                height = 36.dp,
                                modifier = Modifier.fillMaxWidth(0.7f).padding(bottom = 16.dp),
                            )
                        }
                        repeat(6) { ListItemPlaceHolder() }
                    }
                }
            } else {
                item(key = "header") {
                    Box {
                        if (thumbnail != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .offset { IntOffset(x = 0, y = headerOffset) },
                            ) {
                                AsyncImage(
                                    model = thumbnail,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.TopCenter)
                                        .fadingEdge(bottom = 200.dp),
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = if (thumbnail != null) {
                                        LocalResources.current.displayMetrics.widthPixels.let { screenWidth ->
                                            with(density) { ((screenWidth / 1.2f) - 144).toDp() }
                                        }
                                    } else {
                                        16.dp
                                    },
                                ),
                        ) {
                            Text(
                                text = artistName,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 32.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = songs,
                    key = { index, song -> "${song.source}_${song.songmid}_$index" },
                ) { index, song ->
                    val metadata = song.toMediaMetadata()
                    val isCurrentSong = mediaMetadata?.id == metadata.id
                    val dbSong by database.song(metadata.id).collectAsStateWithLifecycle(initialValue = null)
                    MediaMetadataListItem(
                        mediaMetadata = metadata,
                        isActive = isCurrentSong,
                        isPlaying = isCurrentSong && isPlaying,
                        badges = {
                            dbSong?.song?.let { s ->
                                if (s.liked) BadgeIcon.Favorite()
                                if (s.inLibrary != null) BadgeIcon.Library()
                            }
                            val download by LocalDownloadUtil.current.getDownload(metadata.id)
                                .collectAsStateWithLifecycle(initialValue = null)
                            BadgeIcon.Download(download?.state)
                        },
                        trailingContent = {
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    val songEntity = metadata.toSongEntity()
                                    val artistEntities = metadata.artists.map {
                                        ArtistEntity(id = it.id ?: "china_artist_${it.name}", name = it.name)
                                    }
                                    database.query { upsert(songEntity) }
                                    val songForMenu = Song(song = songEntity, artists = artistEntities)
                                    menuState.show {
                                        SongMenu(
                                            originalSong = songForMenu,
                                            navController = navController,
                                            onDismiss = menuState::dismiss,
                                            hideArtistOption = true,
                                        )
                                    }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert),
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isCurrentSong) {
                                    playerConnection.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = artistName,
                                            items = mediaItems,
                                            startIndex = index,
                                        ),
                                    )
                                }
                            },
                    )
                }
            }
        }

        // Play All FAB
        if (songs.isNotEmpty()) {
            val isScrollingUp = lazyListState.isScrollingUp()
            AnimatedVisibility(
                visible = isScrollingUp,
                enter = slideInVertically { it * 2 },
                exit = slideOutVertically { it * 2 },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
            ) {
                FloatingActionButton(
                    modifier = Modifier.padding(16.dp),
                    onClick = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = artistName,
                                items = mediaItems,
                            ),
                        )
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = "Play All",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }

    TopAppBar(
        title = { if (!transparentAppBar) Text(artistName) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        colors = if (transparentAppBar) {
            TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        } else {
            TopAppBarDefaults.topAppBarColors()
        },
    )
}
