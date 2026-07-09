/**
 * Metrolist Project (C) 2026
 * Chinese music source search screen
 */

package com.metrolist.music.ui.screens.search

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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.ChipsRow
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
fun ChinaSearchScreen(
    navController: NavController,
    initialQuery: String? = null,
    searchType: String = "song",
    viewModel: ChinaSearchViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val currentMediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()

    val isSonglistMode = searchType == "songlist"

    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialQuery ?: ""))
    }

    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val songlistResults by viewModel.songlistResults.collectAsStateWithLifecycle()
    val isSonglistLoading by viewModel.isSonglistLoading.collectAsStateWithLifecycle()
    val songlistError by viewModel.songlistError.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()

    // Auto-search if initialQuery is provided
    LaunchedEffect(initialQuery, isSonglistMode) {
        if (!initialQuery.isNullOrBlank()) {
            if (isSonglistMode) {
                viewModel.searchSonglist(initialQuery)
            } else {
                viewModel.search(initialQuery)
            }
        }
    }

    // Load more when near bottom
    LaunchedEffect(lazyListState, isSonglistMode) {
        snapshotFlow {
            val layoutInfo = lazyListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= totalItems - 5
        }.distinctUntilChanged().collectLatest { nearEnd ->
            if (isSonglistMode) {
                if (nearEnd && !isSonglistLoading) {
                    viewModel.loadMoreSonglists()
                }
            } else {
                if (nearEnd && !isLoading) {
                    viewModel.loadMore()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (initialQuery.isNullOrBlank()) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
    ) {
        TopAppBar(
            title = { Text("国内源搜索") },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null
                    )
                }
            }
        )

        // Search input
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { textFieldValue = it },
            placeholder = { Text(if (isSonglistMode) "搜索歌单..." else "搜索歌曲...") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (textFieldValue.text.isNotBlank()) {
                        keyboardController?.hide()
                        if (isSonglistMode) {
                            viewModel.searchSonglist(textFieldValue.text)
                        } else {
                            viewModel.search(textFieldValue.text)
                        }
                    }
                }
            ),
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .focusRequester(focusRequester)
        )

        // Source selector chips
        SearchControlPanel(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ChipsRow(
                chips = MusicSource.entries.map { it to it.displayName },
                currentValue = currentSource,
                onValueUpdate = { viewModel.setSource(it) },
            )
        }

        // Error display
        val activeError = if (isSonglistMode) songlistError else error
        activeError?.let { errorMsg ->
            Text(
                text = errorMsg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Results list
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize()
        ) {
            if (isSonglistMode) {
                val songlists = songlistResults?.list ?: emptyList()

                items(
                    items = songlists,
                    key = { "${it.source}_${it.id}" }
                ) { songlist ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                keyboardController?.hide()
                                navController.navigate("china_songlist/${songlist.source}/${songlist.id}?name=${songlist.name}")
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = songlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (songlist.author.isNotBlank()) {
                                Text(
                                    text = songlist.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            painter = painterResource(R.drawable.arrow_forward),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isSonglistLoading) {
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

                if (!isSonglistLoading && songlists.isEmpty() && songlistResults != null) {
                    item {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                        ) {
                            Text(
                                text = "没有找到相关歌单",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                val songs = searchResults?.list ?: emptyList()

                items(
                    items = songs,
                    key = { "${it.source}_${it.songmid}" }
                ) { song ->
                    val mediaMetadata = remember(song) { song.toMediaMetadata() }
                    val isActive = currentMediaMetadata?.id == mediaMetadata.id

                    MediaMetadataListItem(
                        mediaMetadata = mediaMetadata,
                        isActive = isActive,
                        isPlaying = isPlaying,
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
                                Icon(
                                    painter = painterResource(R.drawable.more_vert),
                                    contentDescription = null
                                )
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
}
