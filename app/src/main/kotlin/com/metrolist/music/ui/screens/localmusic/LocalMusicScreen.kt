/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.localmusic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CONTENT_TYPE_HEADER
import com.metrolist.music.constants.CONTENT_TYPE_SONG
import com.metrolist.music.constants.LocalMusicSortDescendingKey
import com.metrolist.music.constants.LocalMusicSortType
import com.metrolist.music.constants.LocalMusicSortTypeKey
import com.metrolist.music.db.entities.LocalMusicEntity
import com.metrolist.music.db.entities.LocalSong
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.localmusic.advancedKeyDisplayName
import com.metrolist.music.localmusic.analysis.LocalMusicAnalysisState
import com.metrolist.music.localmusic.analysis.LocalMusicAnalysisStatus
import com.metrolist.music.localmusic.analysis.actionPresentation
import com.metrolist.music.localmusic.analysis.hasCompleteAnalysis
import com.metrolist.music.localmusic.analysis.hasCompleteEmotionAnalysis
import com.metrolist.music.localmusic.analysis.missingAnalysisLabels
import com.metrolist.music.localmusic.analysis.shouldShowInlineAnalysisAction
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.CoverPlaybackIndicatorState
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.HideOnScrollFAB
import com.metrolist.music.ui.component.LibrarySearchEmptyPlaceholder
import com.metrolist.music.ui.component.LibrarySearchHeader
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.PlayingIndicator
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.component.coverPlaybackIndicatorState
import com.metrolist.music.ui.menu.SongMenu
import coil3.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalMusicScreen(
    navController: NavController,
    viewModel: LocalMusicViewModel = hiltViewModel(),
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val scanSources by viewModel.scanSources.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filteredSongsState by viewModel.filteredSongs.collectAsStateWithLifecycle()
    val filteredSongs = filteredSongsState.songs
    val analysisStates by viewModel.analysisStates.collectAsStateWithLifecycle()
    val (sortType, onSortTypeChange) =
        rememberEnumPreference(LocalMusicSortTypeKey, LocalMusicSortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(LocalMusicSortDescendingKey, true)
    val lazyListState = rememberLazyListState()
    val queueTitle = stringResource(R.string.queue_local_music)
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var isPendingAnalysisExpanded by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            if (scanSources.isEmpty() && filteredSongs.isEmpty()) {
                item(key = "no_folder", contentType = CONTENT_TYPE_HEADER) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 72.dp, start = 24.dp, end = 24.dp),
                    ) {
                        EmptyPlaceholder(
                            icon = R.drawable.storage,
                            text = "请在设置 > 储存 中添加本地音乐来源和扫描规则",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { navController.navigate("settings/storage") }) {
                            Icon(
                                painter = painterResource(R.drawable.storage),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("去储存设置")
                        }
                    }
                }
            } else {

                item(key = "header", contentType = CONTENT_TYPE_HEADER) {
                    LibrarySearchHeader(
                        isSearchActive = isSearchActive,
                        searchQuery = searchQuery,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onBack = {
                            isSearchActive = false
                            viewModel.updateSearchQuery("")
                        },
                        keyboardController = keyboardController,
                        modifier = Modifier.padding(start = 16.dp),
                    ) {
                        SortHeader(
                            sortType = sortType,
                            sortDescending = sortDescending,
                            onSortTypeChange = onSortTypeChange,
                            onSortDescendingChange = onSortDescendingChange,
                            sortTypeText = { type ->
                                when (type) {
                                    LocalMusicSortType.CREATE_DATE -> R.string.sort_by_create_date
                                    LocalMusicSortType.NAME -> R.string.sort_by_name
                                    LocalMusicSortType.ARTIST -> R.string.sort_by_artist
                                    LocalMusicSortType.BPM -> R.string.sort_by_bpm
                                    LocalMusicSortType.KEY -> R.string.sort_by_key
                                    LocalMusicSortType.ENERGY -> R.string.sort_by_energy
                                }
                            },
                        )

                        Spacer(Modifier.weight(1f))

                        Text(
                            text =
                                if (filteredSongsState.isLoading) {
                                    "加载中"
                                } else {
                                    pluralStringResource(R.plurals.n_song, filteredSongs.size, filteredSongs.size)
                                },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )

                        IconButton(
                            onClick = { isSearchActive = true },
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp).size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                    }
                }

                if (filteredSongsState.isLoading) {
                    item(key = "loading_local_music", contentType = CONTENT_TYPE_HEADER) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "正在加载本地音乐",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                } else if (filteredSongs.isEmpty() && searchQuery.isNotBlank()) {
                    item(key = "empty_search_result", contentType = CONTENT_TYPE_HEADER) {
                        LibrarySearchEmptyPlaceholder(modifier = Modifier.animateItem())
                    }
                } else if (filteredSongs.isEmpty() && !scanState.isScanning) {
                    item(key = "empty_local_music", contentType = CONTENT_TYPE_HEADER) {
                        EmptyPlaceholder(
                            icon = R.drawable.music_note,
                            text = stringResource(R.string.local_music_empty),
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                val pendingSongs = filteredSongs.filterNot { it.localMusic.hasCompleteAnalysis() }
                val analyzedSongs = filteredSongs.filter { it.localMusic.hasCompleteAnalysis() }

                LocalMusicSongSection(
                    keyPrefix = "pending",
                    title = "待分析",
                    songs = pendingSongs,
                    showCount = true,
                    expanded = isPendingAnalysisExpanded,
                    onToggleExpanded = { isPendingAnalysisExpanded = !isPendingAnalysisExpanded },
                    mediaMetadataId = mediaMetadata?.id,
                    isPlaying = isPlaying,
                    analysisStates = analysisStates,
                    onAnalyze = viewModel::analyze,
                    onLongClick = { localSong ->
                        menuState.show {
                            SongMenu(
                                originalSong = localSong.song,
                                navController = navController,
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                    onPlay = { localSong ->
                        if (localSong.song.id == mediaMetadata?.id) {
                            playerConnection.togglePlayPause()
                        } else {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = queueTitle,
                                    items = listOf(localSong.song.toMediaItem(localSong.localMusic.contentUri)),
                                ),
                            )
                        }
                    },
                )

                LocalMusicSongSection(
                    keyPrefix = "analyzed",
                    title = "已分析",
                    songs = analyzedSongs,
                    showCount = false,
                    expanded = true,
                    onToggleExpanded = null,
                    mediaMetadataId = mediaMetadata?.id,
                    isPlaying = isPlaying,
                    analysisStates = analysisStates,
                    onAnalyze = viewModel::analyze,
                    onLongClick = { localSong ->
                        menuState.show {
                            SongMenu(
                                originalSong = localSong.song,
                                navController = navController,
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                    onPlay = { localSong ->
                        if (localSong.song.id == mediaMetadata?.id) {
                            playerConnection.togglePlayPause()
                        } else {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = queueTitle,
                                    items = listOf(localSong.song.toMediaItem(localSong.localMusic.contentUri)),
                                ),
                            )
                        }
                    },
                )
            }
        }

        HideOnScrollFAB(
            visible = filteredSongs.isNotEmpty(),
            lazyListState = lazyListState,
            icon = R.drawable.shuffle,
            onClick = {
                playerConnection.playQueue(
                    ListQueue(
                        title = queueTitle,
                        items = filteredSongs.shuffled().take(MAX_PLAY_QUEUE_SIZE).map { it.song.toMediaItem(it.localMusic.contentUri) },
                    ),
                )
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.LocalMusicSongSection(
    keyPrefix: String,
    title: String,
    songs: List<LocalSong>,
    showCount: Boolean,
    expanded: Boolean,
    onToggleExpanded: (() -> Unit)?,
    mediaMetadataId: String?,
    isPlaying: Boolean,
    analysisStates: Map<String, LocalMusicAnalysisState>,
    onAnalyze: (LocalSong) -> Unit,
    onLongClick: (LocalSong) -> Unit,
    onPlay: (LocalSong) -> Unit,
) {
    if (songs.isEmpty()) return
    item(key = "${keyPrefix}_header", contentType = CONTENT_TYPE_HEADER) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = onToggleExpanded != null) { onToggleExpanded?.invoke() }
                    .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Text(
                text = if (showCount) "$title ${songs.size}" else title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            if (onToggleExpanded != null) {
                Icon(
                    painter = painterResource(if (expanded) R.drawable.expand_less else R.drawable.expand_more),
                    contentDescription = if (expanded) "收起$title" else "展开$title",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    if (!expanded) return
    itemsIndexed(
        items = songs,
        key = { _, item -> "$keyPrefix:${item.localMusic.songId}" },
        contentType = { _, _ -> CONTENT_TYPE_SONG },
    ) { _, localSong ->
        LocalMusicListItem(
            localSong = localSong,
            isActive = localSong.song.id == mediaMetadataId,
            isPlaying = isPlaying,
            analysisState = analysisStates[localSong.localMusic.songId],
            onClick = { onPlay(localSong) },
            onLongClick = { onLongClick(localSong) },
            onAnalyze = { onAnalyze(localSong) },
            modifier = Modifier.fillMaxWidth().animateItem(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalMusicListItem(
    localSong: LocalSong,
    isActive: Boolean,
    isPlaying: Boolean,
    analysisState: LocalMusicAnalysisState?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }

    val isAnalyzing =
        analysisState?.status == LocalMusicAnalysisStatus.Queued ||
            analysisState?.status == LocalMusicAnalysisStatus.Running
    val hasCompleteAnalysis = localSong.localMusic.hasCompleteAnalysis()
    val hasCompleteEmotionAnalysis = localSong.localMusic.hasCompleteEmotionAnalysis()
    val missingAnalysisLabels =
        localSong.localMusic
            .missingAnalysisLabels()
            .takeIf { !hasCompleteAnalysis && it.isNotEmpty() }
            .orEmpty()
    val analysisAction = analysisState.actionPresentation(hasCompleteAnalysis)
    val showAnalysisAction = analysisState.shouldShowInlineAnalysisAction(hasCompleteEmotionAnalysis)

    Box(modifier = modifier.padding(horizontal = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(containerColor)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { onLongClick() },
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
            val playbackIndicatorState = localMusicCoverPlaybackIndicatorState(isActive, isPlaying)
            AsyncImage(
                model = localSong.song.song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (playbackIndicatorState != CoverPlaybackIndicatorState.HIDDEN) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                ) {
                    if (playbackIndicatorState == CoverPlaybackIndicatorState.PLAYING) {
                        PlayingIndicator(
                            color = Color.White,
                            modifier = Modifier.height(24.dp),
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
            ) {
            Text(
                text = localSong.song.song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = localSong.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                ) {
                    if (isAnalyzing) {
                        LocalMusicChip("分析中")
                    }
                    if (analysisState?.status == LocalMusicAnalysisStatus.Failed) {
                        LocalMusicChip("分析失败")
                    }
                localSong.localMusic.bpm?.let { LocalMusicChip("${it.toInt()} BPM") }
                localSong.localMusic.keyName?.academicKeyLabel()?.let { LocalMusicChip(it) }
                localSong.localMusic.keyName?.camelotKeyLabel()?.let { LocalMusicChip(it) }
                if (missingAnalysisLabels.isNotEmpty()) {
                    LocalMusicChip(
                        text = "MISS ${missingAnalysisLabels.joinToString(" ")}",
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                }
            }

            if (showAnalysisAction) {
                LocalMusicAnalysisActionButton(
                    label = analysisAction.label,
                    description = analysisAction.description,
                    progress = analysisAction.progress,
                    enabled = analysisAction.enabled && !isAnalyzing,
                    onClick = onAnalyze,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                EmotionRadar(
                    localMusic = localSong.localMusic,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
    }
}

internal fun localMusicCoverPlaybackIndicatorState(
    isActive: Boolean,
    isPlaying: Boolean,
): CoverPlaybackIndicatorState =
    coverPlaybackIndicatorState(
        isActive = isActive,
        isPlaying = isPlaying,
        showPlaybackStateOverlay = true,
        showPausedPlaybackIcon = true,
    )

@Composable
private fun LocalMusicAnalysisActionButton(
    label: String,
    description: String,
    progress: Float?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(4.dp),
    ) {
        progress?.let {
            CircularProgressIndicator(
                progress = { it.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize().padding(5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                strokeWidth = 2.dp,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                ),
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress == null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        textAlign = TextAlign.Center,
                    ),
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun EmotionRadar(
    localMusic: LocalMusicEntity,
    modifier: Modifier = Modifier,
) {
    val rawMetrics =
        listOf(
            localMusic.valence,
            localMusic.energy,
            localMusic.danceability,
            localMusic.acousticness,
            localMusic.instrumentalness,
            localMusic.liveness,
            localMusic.speechiness,
        )
    val hasMetrics = rawMetrics.any { it != null }
    val metrics = rawMetrics.map { value ->
        value?.let { if (it > 1f) (it / 100f).coerceIn(0f, 1f) else it.coerceIn(0f, 1f) } ?: 0f
    }
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
    val shapeColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sides = metrics.size
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * 0.39f

            fun point(index: Int, value: Float): Offset {
                val angle = (-PI / 2.0 + index * 2.0 * PI / sides).toFloat()
                return Offset(
                    x = center.x + cos(angle) * radius * value,
                    y = center.y + sin(angle) * radius * value,
                )
            }

            for (ring in 1..3) {
                val path = Path()
                repeat(sides) { index ->
                    val p = point(index, ring / 3f)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, color = lineColor, style = Stroke(width = 0.55.dp.toPx()))
            }

            repeat(sides) { index ->
                drawLine(
                    color = lineColor,
                    start = center,
                    end = point(index, 1f),
                    strokeWidth = 0.55.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            if (hasMetrics) {
                val shape = Path()
                metrics.forEachIndexed { index, value ->
                    val p = point(index, value)
                    if (index == 0) shape.moveTo(p.x, p.y) else shape.lineTo(p.x, p.y)
                }
                shape.close()
                drawPath(shape, color = shapeColor.copy(alpha = 0.22f))
                drawPath(shape, color = shapeColor, style = Stroke(width = 1.15.dp.toPx()))
                metrics.forEachIndexed { index, value ->
                    val p = point(index, value)
                    drawCircle(
                        color = shapeColor,
                        radius = 1.45.dp.toPx(),
                        center = p,
                    )
                }
            }
        }
        if (!hasMetrics) {
            Text(
                text = "待分析",
                style = MaterialTheme.typography.labelSmall,
                color = mutedColor,
            )
        }
    }
}

@Composable
private fun LocalMusicChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            lineHeight = 10.sp,
        ),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 3.dp, vertical = 0.dp),
    )
}

private fun LocalSong.subtitle(): String =
    joinByBullet(
        song.orderedArtists.joinToString { it.name },
        makeTimeString(song.song.duration * 1000L).takeIf { song.song.duration > 0 },
    )

private fun String.cleanDisplayText(): String? =
    replace("\uFFFD", "")
        .replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), "")
        .trim()
        .takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }

private fun String.academicKeyLabel(): String? {
    val key = cleanDisplayText() ?: return null
    val canonical = key.canonicalMusicKey() ?: return key
    return advancedKeyDisplayName(canonical) ?: canonical
}

private fun String.camelotKeyLabel(): String? {
    val canonical = canonicalMusicKey() ?: return null
    val camelot = keyToCamelot(canonical) ?: return null
    return "${camelot.first}${camelot.second}"
}

private fun String.canonicalMusicKey(): String? {
    val cleaned = cleanDisplayText() ?: return null
    val normalized =
        cleaned
            .replace("♯", "#")
            .replace("＃", "#")
            .replace("♭", "b")
            .replace(Regex("\\s+"), " ")
            .trim()
    val lower = normalized.lowercase()
    val camelotMatch =
        Regex("^([ab])(\\d{1,2})$", RegexOption.IGNORE_CASE).matchEntire(normalized)
            ?: Regex("^(\\d{1,2})([ab])$", RegexOption.IGNORE_CASE).matchEntire(normalized)
    if (camelotMatch != null) {
        val first = camelotMatch.groupValues[1]
        val second = camelotMatch.groupValues[2]
        val number = first.toIntOrNull() ?: second.toIntOrNull() ?: return normalized
        val mode = if (first.toIntOrNull() == null) first.uppercase() else second.uppercase()
        return camelotToKey(number, mode)
    }
    val note = normalized.substringBefore(" ").replaceFirstChar { it.uppercase() }
    return when {
        lower.endsWith(" minor") -> normalizeMusicKey("${note}m")
        lower.endsWith(" major") -> normalizeMusicKey(note)
        lower.endsWith(" min") -> normalizeMusicKey("${note}m")
        lower.endsWith(" maj") -> normalizeMusicKey(note)
        lower.endsWith("m") && normalized.length > 1 -> normalizeMusicKey(normalized.replaceFirstChar { it.uppercase() })
        else -> normalizeMusicKey(normalized.replaceFirstChar { it.uppercase() })
    }
}

private fun normalizeMusicKey(key: String): String? =
    when (key) {
        "C", "C#" -> key
        "Db" -> "C#"
        "D", "D#" -> key
        "Eb" -> "D#"
        "E", "Fb" -> "E"
        "F", "F#" -> key
        "Gb" -> "F#"
        "G", "G#" -> key
        "Ab" -> "G#"
        "A", "A#" -> key
        "Bb" -> "A#"
        "B", "Cb" -> "B"
        "Cm", "C#m" -> key
        "Dbm" -> "C#m"
        "Dm", "D#m" -> key
        "Ebm" -> "D#m"
        "Em", "Fbm" -> "Em"
        "Fm", "F#m" -> key
        "Gbm" -> "F#m"
        "Gm", "G#m" -> key
        "Abm" -> "G#m"
        "Am", "A#m" -> key
        "Bbm" -> "A#m"
        "Bm", "Cbm" -> "Bm"
        else -> null
    }

private fun keyToCamelot(key: String): Pair<Int, String>? =
    when (key) {
        "G#m" -> 1 to "A"
        "B" -> 1 to "B"
        "D#m" -> 2 to "A"
        "F#" -> 2 to "B"
        "A#m" -> 3 to "A"
        "C#" -> 3 to "B"
        "Fm" -> 4 to "A"
        "G#" -> 4 to "B"
        "Cm" -> 5 to "A"
        "D#" -> 5 to "B"
        "Gm" -> 6 to "A"
        "A#" -> 6 to "B"
        "Dm" -> 7 to "A"
        "F" -> 7 to "B"
        "Am" -> 8 to "A"
        "C" -> 8 to "B"
        "Em" -> 9 to "A"
        "G" -> 9 to "B"
        "Bm" -> 10 to "A"
        "D" -> 10 to "B"
        "F#m" -> 11 to "A"
        "A" -> 11 to "B"
        "C#m" -> 12 to "A"
        "E" -> 12 to "B"
        else -> null
    }

private fun camelotToKey(number: Int, mode: String): String? =
    when (number to mode.uppercase()) {
        1 to "A" -> "G#m"
        1 to "B" -> "B"
        2 to "A" -> "D#m"
        2 to "B" -> "F#"
        3 to "A" -> "A#m"
        3 to "B" -> "C#"
        4 to "A" -> "Fm"
        4 to "B" -> "G#"
        5 to "A" -> "Cm"
        5 to "B" -> "D#"
        6 to "A" -> "Gm"
        6 to "B" -> "A#"
        7 to "A" -> "Dm"
        7 to "B" -> "F"
        8 to "A" -> "Am"
        8 to "B" -> "C"
        9 to "A" -> "Em"
        9 to "B" -> "G"
        10 to "A" -> "Bm"
        10 to "B" -> "D"
        11 to "A" -> "F#m"
        11 to "B" -> "A"
        12 to "A" -> "C#m"
        12 to "B" -> "E"
        else -> null
    }

private const val MAX_PLAY_QUEUE_SIZE = 500
