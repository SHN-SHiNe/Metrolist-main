package com.metrolist.music.ui.screens.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.models.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.models.toSong
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.MediaMetadataListItem
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.viewmodels.ChinaSonglistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChinaSonglistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
    viewModel: ChinaSonglistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    // Check if this songlist is already saved as a playlist
    val chinaBrowseId = "china_${viewModel.sourceId}_${viewModel.songlistId}"
    val dbPlaylist by database.playlistByBrowseId(chinaBrowseId).collectAsStateWithLifecycle(initialValue = null)

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (lazyListState.firstVisibleItemIndex > 0) {
                        Text(detail?.name ?: viewModel.songlistName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.navigateUp() },
                        onLongClick = { navController.backToMain() },
                    ) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding()),
        ) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error ?: "加载失败", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = { viewModel.retry() }) { Text("重试") }
                    }
                }
                else -> {
                    val songs = detail?.songs ?: emptyList()
                    LazyColumn(
                        state = lazyListState,
                        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                    ) {
                        item(key = "header") {
                            detail?.let { d ->
                                ChinaSonglistHeader(
                                    name = d.name,
                                    author = d.author,
                                    img = d.img,
                                    songCount = songs.size,
                                    isSaved = dbPlaylist != null,
                                    onPlayAll = {
                                        if (songs.isNotEmpty()) {
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = d.name,
                                                    items = songs.map { it.toMediaItem() },
                                                )
                                            )
                                        }
                                    },
                                    onSaveToLibrary = {
                                        if (dbPlaylist != null) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                database.transaction {
                                                    clearPlaylist(dbPlaylist!!.playlist.id)
                                                    delete(dbPlaylist!!.playlist)
                                                }
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "已从媒体库移除", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            return@ChinaSonglistHeader
                                        }
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val playlistEntity = PlaylistEntity(
                                                name = d.name,
                                                browseId = chinaBrowseId,
                                                isEditable = false,
                                                thumbnailUrl = d.img,
                                                remoteSongCount = songs.size,
                                                bookmarkedAt = java.time.LocalDateTime.now(),
                                            )
                                            val songMetadataList = songs.map { it.toMediaMetadata() }
                                            database.transaction {
                                                insert(playlistEntity)
                                                songMetadataList.forEach { insert(it) }
                                                val songIds = songMetadataList.map { it.id to null as String? }
                                                val createdPlaylist = database.playlistBlocking(playlistEntity.id)
                                                    ?: return@transaction
                                                database.addSongsToPlaylist(createdPlaylist, songIds, setInLibrary = false)
                                            }
                                            launch(Dispatchers.Main) {
                                                Toast.makeText(context, "已添加到媒体库", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onShare = {
                                        val shareUrl = when (viewModel.sourceId) {
                                            "wy" -> "https://music.163.com/#/playlist?id=${viewModel.songlistId}"
                                            "tx" -> "https://y.qq.com/n/ryqq/playlist/${viewModel.songlistId}"
                                            else -> "shine://china_songlist/${viewModel.sourceId}/${viewModel.songlistId}"
                                        }
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(d.name, shareUrl))
                                        Toast.makeText(context, "已复制到剪切板", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            } ?: run {
                                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                                    Text(viewModel.songlistName, style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }

                        itemsIndexed(songs, key = { _, s -> s.songmid }) { index, song ->
                            val metadata = song.toMediaMetadata()
                            MediaMetadataListItem(
                                mediaMetadata = metadata,
                                isActive = mediaMetadata?.id == metadata.id,
                                isPlaying = isPlaying,
                                modifier = Modifier.clickable {
                                    if (mediaMetadata?.id == metadata.id) {
                                        playerConnection.togglePlayPause()
                                    } else {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = detail?.name ?: "",
                                                items = songs.map { it.toMediaItem() },
                                                startIndex = index,
                                            )
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = metadata.toSong(),
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                        onLongClick = {},
                                    ) {
                                        Icon(painterResource(R.drawable.more_vert), contentDescription = null)
                                    }
                                },
                            )
                        }

                        if (songs.isEmpty() && !isLoading) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("暂无歌曲", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChinaSonglistHeader(
    name: String,
    author: String,
    img: String?,
    songCount: Int,
    isSaved: Boolean = false,
    onPlayAll: () -> Unit,
    onSaveToLibrary: () -> Unit = {},
    onShare: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            AsyncImage(
                model = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onSaveToLibrary,
                    color = if (isSaved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(
                                if (isSaved) R.drawable.library_add_check else R.drawable.library_add
                            ),
                            contentDescription = if (isSaved) "已在媒体库" else "添加到媒体库",
                            tint = if (isSaved) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Surface(
                    onClick = onShare,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = "分享",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Surface(
                    onClick = onPlayAll,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = "播放全部",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        if (author.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "$songCount 首歌曲",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

    }
}
