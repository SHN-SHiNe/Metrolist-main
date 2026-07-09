/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.EntryPointAccessors
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LocalMusicEntity
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.di.LocalMusicAnalysisEntryPoint
import com.metrolist.music.download.FileMusicDownloader
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.localmusic.analysis.LocalMusicAnalysisStatus
import com.metrolist.music.localmusic.analysis.hasCompleteAnalysis
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.viewmodels.CachePlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime

@Composable
fun SongMenu(
    originalSong: Song,
    event: Event? = null,
    navController: NavController,
    playlistSong: PlaylistSong? = null,
    playlistBrowseId: String? = null,
    onDismiss: () -> Unit,
    isFromCache: Boolean = false,
    hideArtistOption: Boolean = false,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val localMusicAnalysisManager =
        remember(context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                LocalMusicAnalysisEntryPoint::class.java,
            ).localMusicAnalysisManager()
        }
    val songState = database.song(originalSong.id).collectAsStateWithLifecycle(initialValue = originalSong)
    val song = songState.value ?: originalSong
    val downloadedLocalMusic by database.localMusic(song.id).collectAsStateWithLifecycle(initialValue = null)
    val activeDownloadedLocalMusic = downloadedLocalMusic?.takeIf { it.missingSince == null }
    val localMusicAnalysisStates by localMusicAnalysisManager.states.collectAsStateWithLifecycle()
    val localMusicAnalysisState = activeDownloadedLocalMusic?.songId?.let(localMusicAnalysisStates::get)
    val isLocalMusicAnalyzing =
        localMusicAnalysisState?.status == LocalMusicAnalysisStatus.Queued ||
            localMusicAnalysisState?.status == LocalMusicAnalysisStatus.Running
    val isLocalSong =
        isLocalMenuSong(
            isLocalMetadata = song.song.isLocal,
            mediaId = song.id,
            libraryIsLocal = false,
            hasLocalFile = activeDownloadedLocalMusic != null,
        )
    val menuVisibility = musicMenuVisibility(isLocalSong)
    val download by LocalDownloadUtil.current
        .getDownload(originalSong.id)
        .collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val listenTogetherManager = LocalListenTogetherManager.current

    val cacheViewModel = hiltViewModel<CachePlaylistViewModel>()

    val isPinned by database.speedDialDao.isPinned(song.id).collectAsStateWithLifecycle(initialValue = false)

    // Podcast subscription state for episodes
    val podcastEntity by produceState<PodcastEntity?>(initialValue = null, song) {
        val podcastId = song.song.albumId
        if (song.song.isEpisode && podcastId != null) {
            database.podcast(podcastId).collect { value = it }
        }
    }
    val isPodcastSubscribed = podcastEntity?.bookmarkedAt != null

    val orderedArtists by produceState(initialValue = emptyList<ArtistEntity>(), song) {
        withContext(Dispatchers.IO) {
            val artistMaps = database.songArtistMap(song.id).sortedBy { it.position }
            val sorted =
                artistMaps.mapNotNull { map ->
                    song.artists.firstOrNull { it.id == map.artistId }
                }
            value = sorted
        }
    }

    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val TextFieldValueSaver: Saver<TextFieldValue, *> =
        Saver(
            save = { it.text },
            restore = { text -> TextFieldValue(text, TextRange(text.length)) },
        )

    var titleField by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(TextFieldValue(song.song.title))
    }

    var artistField by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(
            TextFieldValue(
                song.artists
                    .firstOrNull()
                    ?.name
                    .orEmpty(),
            ),
        )
    }

    if (showEditDialog) {
        TextFieldDialog(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.edit),
                    contentDescription = null,
                )
            },
            title = {
                Text(text = stringResource(R.string.edit_song))
            },
            textFields =
                listOf(
                    stringResource(R.string.song_title) to titleField,
                    stringResource(R.string.artist_name) to artistField,
                ),
            onTextFieldsChange = { index, newValue ->
                if (index == 0) {
                    titleField = newValue
                } else {
                    artistField = newValue
                }
            },
            onDoneMultiple = { values ->
                val newTitle = values[0]
                val newArtist = values[1]

                coroutineScope.launch {
                    database.query {
                        update(song.song.copy(title = newTitle))
                        val artist = song.artists.firstOrNull()
                        if (artist != null) {
                            update(artist.copy(name = newArtist))
                        }
                    }

                    showEditDialog = false
                    onDismiss()
                }
            },
            onDismiss = { showEditDialog = false },
        )
    }

    var showChoosePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showErrorPlaylistAddDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteLocalMusicDialog by rememberSaveable { mutableStateOf(false) }
    var deleteLocalMusicInProgress by rememberSaveable { mutableStateOf(false) }
    var fileDownloadMode by rememberSaveable { mutableStateOf<LocalFileDownloadMode?>(null) }
    var fileDownloadInProgress by rememberSaveable { mutableStateOf(false) }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = { playlist ->
            database.withTransaction {
                insert(song.toMediaMetadata())
            }
            listOf(song.id)
        },
        onGetSongIds = { listOf(song.id) },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
    )

    fileDownloadMode?.let { pendingFileDownloadMode ->
        SongFileDownloadQualityDialog(
            inProgress = fileDownloadInProgress,
            confirmText = if (pendingFileDownloadMode == LocalFileDownloadMode.DownloadAndAnalyze) "下载并分析" else "开始下载",
            onDismiss = { fileDownloadMode = null },
            onQualitySelected = { quality ->
                fileDownloadInProgress = true
                coroutineScope.launch {
                    val metadata = song.toMediaMetadata()
                    val result =
                        runLocalFileDownloadAction(
                            context = context,
                            database = database,
                            mediaMetadata = metadata,
                            quality = quality,
                            mode = pendingFileDownloadMode,
                            analysisManager = localMusicAnalysisManager,
                        )
                    fileDownloadInProgress = false
                    fileDownloadMode = null
                    Toast.makeText(
                        context,
                        result.fold(
                            onSuccess = { actionResult ->
                                when {
                                    actionResult.reusedExisting && actionResult.analysisStarted ->
                                        "已在本地，开始分析：${actionResult.displayPath}"
                                    actionResult.reusedExisting ->
                                        "已在本地：${actionResult.displayPath}"
                                    actionResult.analysisStarted ->
                                        "已下载到 ${actionResult.displayPath}，开始分析"
                                    else ->
                                        "已下载到 ${actionResult.displayPath}"
                                }
                            },
                            onFailure = { "下载失败：${it.message ?: "未知错误"}" },
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    onDismiss()
                }
            },
        )
    }

    if (showErrorPlaylistAddDialog) {
        ListDialog(
            onDismiss = {
                showErrorPlaylistAddDialog = false
                onDismiss()
            },
        ) {
            item {
                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.already_in_playlist)) },
                    leadingContent = {
                        Image(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier = Modifier.clickable { showErrorPlaylistAddDialog = false },
                )
            }

            items(listOf(song)) { song ->
                SongListItem(song = song)
            }
        }
    }

    if (showDeleteLocalMusicDialog && activeDownloadedLocalMusic != null) {
        AlertDialog(
            onDismissRequest = {
                if (!deleteLocalMusicInProgress) {
                    showDeleteLocalMusicDialog = false
                }
            },
            title = { Text("删除本地文件") },
            text = {
                Text("将删除音频文件，并从本地音乐库数据库中移除这首歌。此操作无法撤销。")
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteLocalMusicInProgress,
                    onClick = {
                        val localMusic = activeDownloadedLocalMusic
                        deleteLocalMusicInProgress = true
                        coroutineScope.launch {
                            val result =
                                deleteLocalMusicFileAndDatabase(
                                    context = context,
                                    database = database,
                                    localMusic = localMusic,
                                )
                            deleteLocalMusicInProgress = false
                            showDeleteLocalMusicDialog = false
                            Toast.makeText(
                                context,
                                result.fold(
                                    onSuccess = { "已删除本地文件和数据库记录" },
                                    onFailure = { "删除失败：${it.message ?: "未知错误"}" },
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                            if (result.isSuccess) {
                                onDismiss()
                            }
                        }
                    },
                ) {
                    Text(if (deleteLocalMusicInProgress) "删除中" else "删除")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleteLocalMusicInProgress,
                    onClick = { showDeleteLocalMusicDialog = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(
                items = song.artists.distinctBy { it.id },
                key = { "menu_song_artist_${it.id}" },
            ) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .height(ListItemHeight)
                            .clickable {
                                val artistId = artist.id ?: "china_artist_${Uri.encode(artist.name)}"
                                navController.navigate("artist/${Uri.encode(artistId)}?name=${Uri.encode(artist.name)}")
                                showSelectArtistDialog = false
                                onDismiss()
                            }.padding(horizontal = 12.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = artist.thumbnailUrl,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(ListThumbnailSize)
                                    .clip(CircleShape),
                        )
                    }
                    Text(
                        text = artist.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    SongListItem(
        song = song,
        badges = {},
        trailingContent = {
            // For episodes, show saved state and toggle save for later
            val isEpisode = song.song.isEpisode
            val isFavorite = if (isEpisode) song.song.inLibrary != null else song.song.liked
            IconButton(
                onClick = {
                    if (isEpisode) {
                        // Episode: toggle save for later (same pattern as songs)
                        val isCurrentlySaved = song.song.inLibrary != null
                        database.query {
                            update(
                                song.song.copy(
                                    inLibrary = if (isCurrentlySaved) null else LocalDateTime.now(),
                                    isEpisode = true,
                                ),
                            )
                        }
                    } else {
                        // Regular song: toggle like
                        val s = song.song.toggleLike()
                        database.query {
                            update(s)
                        }
                        syncUtils.likeSong(s)
                    }
                },
            ) {
                Icon(
                    painter = painterResource(if (isFavorite) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (isFavorite) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    contentDescription = null,
                )
            }
        },
    )

    HorizontalDivider()

    Spacer(modifier = Modifier.height(12.dp))

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost

    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        item {
            NewActionGrid(
                actions =
                    listOf(
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.edit),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = stringResource(R.string.edit),
                            onClick = { showEditDialog = true },
                        ),
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_add),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = stringResource(R.string.add_to_playlist),
                            onClick = { showChoosePlaylistDialog = true },
                        ),
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.share),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = stringResource(R.string.share),
                            onClick = {
                                onDismiss()
                                val intent =
                                    Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(
                                    Intent.EXTRA_TEXT,
                                    if (song.id.startsWith("china_")) {
                                        com.metrolist.chinamusic.PlaylistUrlParser.getSongUrlFromMediaId(song.id)
                                            ?: song.song.title
                                    } else {
                                        "${song.song.title} - ${song.artists.joinToString { it.name }}"
                                    }
                                )
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                        ),
                    ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                columns = if (isGuest) 2 else 3,
            )
        }
        item {
            Material3MenuGroup(
                items =
                    listOfNotNull(
                        if (listenTogetherManager != null && listenTogetherManager.isInRoom && !listenTogetherManager.isHost) {
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.suggest_to_host)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.queue_music),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    val durationMs = if (song.song.duration > 0) song.song.duration.toLong() * 1000 else 180000L
                                    val trackInfo =
                                        com.metrolist.music.listentogether.TrackInfo(
                                            id = song.id,
                                            title = song.song.title,
                                            artist = orderedArtists.joinToString(", ") { it.name },
                                            album = song.song.albumName,
                                            duration = durationMs,
                                            thumbnail = song.thumbnailUrl,
                                        )
                                    listenTogetherManager.suggestTrack(trackInfo)
                                    onDismiss()
                                },
                            )
                        } else {
                            null
                        },
                        if (!isGuest) {
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.play_next)) },
                                description = { Text(text = stringResource(R.string.play_next_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.playlist_play),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    playerConnection.playNext(song.toMediaItem())
                                },
                            )
                        } else {
                            null
                        },
                        if (!isGuest) {
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.add_to_queue)) },
                                description = { Text(text = stringResource(R.string.add_to_queue_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.queue_music),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    playerConnection.addToQueue(song.toMediaItem())
                                },
                            )
                        } else {
                            null
                        },
                    ),
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items =
                    buildList {
                        add(
                            Material3MenuItemData(
                                title = {
                                    Text(
                                        text = if (isPinned) stringResource(R.string.unpin_from_speed_dial) else stringResource(R.string.pin_to_speed_dial),
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(if (isPinned) R.drawable.remove else R.drawable.add),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        if (isPinned) {
                                            database.speedDialDao.delete(song.id)
                                        } else {
                                            database.speedDialDao.insert(
                                                SpeedDialItem(
                                                    id = song.id,
                                                    title = song.song.title,
                                                    subtitle = song.artists.joinToString(", ") { it.name },
                                                    subtitleIds = song.artists.joinToString(", ") { it.id },
                                                    thumbnailUrl = song.song.thumbnailUrl,
                                                    type = "SONG",
                                                    explicit = song.song.explicit,
                                                    albumId = song.album?.id,
                                                    albumName = song.album?.title
                                                ),
                                            )
                                        }
                                    }
                                    onDismiss()
                                },
                            ),
                        )
                        if (isLocalSong && activeDownloadedLocalMusic != null) {
                            add(
                                Material3MenuItemData(
                                    title = {
                                        Text(
                                            text =
                                                when {
                                                    isLocalMusicAnalyzing -> "分析中"
                                                    activeDownloadedLocalMusic.hasCompleteAnalysis() -> "重新分析"
                                                    else -> "立即分析"
                                                },
                                        )
                                    },
                                    description = { Text(text = "重新生成情绪、BPM 和调性") },
                                    icon = {
                                        if (isLocalMusicAnalyzing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(R.drawable.advanced_search),
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    onClick =
                                        if (isLocalMusicAnalyzing) {
                                            null
                                        } else {
                                            {
                                                localMusicAnalysisManager.analyze(activeDownloadedLocalMusic)
                                                onDismiss()
                                            }
                                        },
                                ),
                            )
                        }
                        if (event != null) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.remove_from_history)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        onDismiss()
                                        database.query {
                                            delete(event)
                                        }
                                    },
                                ),
                            )
                        }
                        if (playlistSong != null) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.remove_from_playlist)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        playlistSong.let { ps ->
                                            val capturedSetVideoId = ps.map.setVideoId
                                            database.transaction {
                                                move(
                                                    ps.map.playlistId,
                                                    ps.map.position,
                                                    Int.MAX_VALUE
                                                )
                                                delete(ps.map.copy(position = Int.MAX_VALUE))
                                            }
                                            playlistBrowseId?.let { browseId ->
                                                syncUtils.scheduleRemoveFromPlaylist(
                                                    browseId,
                                                    ps.map.songId,
                                                    ps.map.playlistId
                                                ) {
                                                    capturedSetVideoId
                                                }
                                            }
                                            onDismiss()
                                        }
                                    },
                                ),
                            )
                        }
                        if (isFromCache) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.remove_from_cache)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        onDismiss()
                                        cacheViewModel.removeSongFromCache(song.id)
                                    },
                                ),
                            )
                        }
                    },
            )
        }

        if (isLocalSong && activeDownloadedLocalMusic != null) {
            item { Spacer(modifier = Modifier.height(12.dp)) }
            item {
                Material3MenuGroup(
                    items =
                        listOf(
                            Material3MenuItemData(
                                title = { Text(text = "删除本地文件") },
                                description = { Text(text = "删除音频文件，并移除本地音乐数据库记录") },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showDeleteLocalMusicDialog = true
                                },
                            ),
                        ),
                )
            }
        }

        if (menuVisibility.showDownloadAction) {
            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Material3MenuGroup(
                    items =
                        buildList {
                            when (download?.state) {
                                Download.STATE_COMPLETED -> {
                                    add(
                                        Material3MenuItemData(
                                            title = {
                                                Text(
                                                    text = stringResource(R.string.remove_download),
                                                )
                                            },
                                            icon = {
                                                Icon(
                                                    painter = painterResource(R.drawable.offline),
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                DownloadService.sendRemoveDownload(
                                                    context,
                                                    ExoDownloadService::class.java,
                                                    song.id,
                                                    false,
                                                )
                                            },
                                        ),
                                    )
                                }

                                Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                                    add(
                                        Material3MenuItemData(
                                            title = { Text(text = stringResource(R.string.downloading)) },
                                            icon = {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            },
                                            onClick = {
                                                DownloadService.sendRemoveDownload(
                                                    context,
                                                    ExoDownloadService::class.java,
                                                    song.id,
                                                    false,
                                                )
                                            },
                                        ),
                                    )
                                }

                                else -> Unit
                            }
                            if (download?.state != Download.STATE_QUEUED && download?.state != Download.STATE_DOWNLOADING) {
                                add(
                                    Material3MenuItemData(
                                        title = { Text(text = "下载到本地") },
                                        description = { Text(text = "保存为本地音乐文件") },
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.download),
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            fileDownloadMode = LocalFileDownloadMode.DownloadOnly
                                        },
                                    ),
                                )
                                add(
                                    Material3MenuItemData(
                                        title = { Text(text = "下载并分析") },
                                        description = { Text(text = "保存后分析情绪、BPM 和调性") },
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.advanced_search),
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            fileDownloadMode = LocalFileDownloadMode.DownloadAndAnalyze
                                        },
                                    ),
                                )
                            }
                        },
                )
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }

        if (menuVisibility.showOnlineMetadataActions) {
            item {
                Material3MenuGroup(
                    items =
                        buildList {
                            // Don't show "View Artist" for podcast episodes or when already on artist page
                            if (!song.song.isEpisode && !hideArtistOption) {
                                add(
                                    Material3MenuItemData(
                                        title = { Text(text = stringResource(R.string.view_artist)) },
                                        description = { Text(text = song.artists.joinToString { it.name }) },
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.artist),
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            if (song.artists.size == 1) {
                                                Timber.tag("ChinaArtist").d("SongMenu navigate artist id=${song.artists[0].id} name=${song.artists[0].name}")
                                                val artistId = song.artists[0].id ?: "china_artist_${Uri.encode(song.artists[0].name)}"
                                                navController.navigate("artist/${Uri.encode(artistId)}?name=${Uri.encode(song.artists[0].name)}")
                                                onDismiss()
                                            } else {
                                                showSelectArtistDialog = true
                                            }
                                        },
                                    ),
                                )
                            }
                            if (song.song.albumId != null) {
                                // Show "View Podcast" for episodes, "View Album" for songs
                                val isPodcast = song.song.isEpisode
                                add(
                                    Material3MenuItemData(
                                        title = { Text(text = stringResource(if (isPodcast) R.string.view_podcast else R.string.view_album)) },
                                        description = {
                                            song.song.albumName?.let {
                                                Text(text = it)
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                painter = painterResource(if (isPodcast) R.drawable.mic else R.drawable.album),
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            onDismiss()
                                            navController.navigate(
                                                "search_input_with_query?q=${Uri.encode(song.song.albumName.orEmpty())}&source=CHINA_SONGLIST"
                                            )
                                        },
                                    ),
                                )
                            }
                        },
                )
            }
        }
    }
}

internal suspend fun deleteLocalMusicFileAndDatabase(
    context: Context,
    database: MusicDatabase,
    localMusic: LocalMusicEntity,
): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(localMusic.contentUri)
            val fileDeleted =
                when (uri.scheme) {
                    "content" -> deleteContentLocalMusicFile(context, uri)
                    "file" -> {
                        val file = File(uri.path ?: "")
                        !file.exists() || file.delete()
                    }
                    else -> false
                }

            check(fileDeleted) { "无法删除本地文件" }

            database.withTransaction {
                deleteLocalMusicBySongId(localMusic.songId)
                deleteSongById(localMusic.songId)
            }
            database.speedDialDao.delete(localMusic.songId)
        }
    }

private fun deleteContentLocalMusicFile(
    context: Context,
    uri: Uri,
): Boolean {
    DocumentFile.fromSingleUri(context, uri)?.let { document ->
        return !document.exists() || document.delete()
    }
    return context.contentResolver.delete(uri, null, null) > 0
}

@Composable
private fun SongFileDownloadQualityDialog(
    inProgress: Boolean,
    confirmText: String,
    onDismiss: () -> Unit,
    onQualitySelected: (FileMusicDownloader.Quality) -> Unit,
) {
    var selectedQuality by rememberSaveable { mutableStateOf(FileMusicDownloader.Quality.HIGH) }

    AlertDialog(
        onDismissRequest = {
            if (!inProgress) onDismiss()
        },
        title = { Text("选择下载音质") },
        text = {
            Column {
                FileMusicDownloader.Quality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !inProgress) { selectedQuality = quality }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedQuality == quality,
                            onClick = { selectedQuality = quality },
                            enabled = !inProgress,
                        )
                        Text(
                            text = quality.label,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (inProgress) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inProgress,
                onClick = { onQualitySelected(selectedQuality) },
            ) {
                Text(if (inProgress) "下载中" else confirmText)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !inProgress,
                onClick = onDismiss,
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
