/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.metrolist.music.db.entities.LocalMusicEntity
import com.metrolist.music.db.entities.LocalSong
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.utils.joinByBullet
import kotlin.math.min
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_ENDED
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CropAlbumArtKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.HidePlayerThumbnailKey
import com.metrolist.music.constants.HideStatusBarOnFullscreenKey
import com.metrolist.music.constants.KeepScreenOn
import com.metrolist.music.constants.LocalSimilarAutoplayKey
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.PlayerButtonsStyle
import com.metrolist.music.constants.PlayerButtonsStyleKey
import com.metrolist.music.constants.PlayerHorizontalPadding
import com.metrolist.music.constants.QueuePeekHeight
import com.metrolist.music.constants.SleepTimerDefaultKey
import com.metrolist.music.constants.SleepTimerFadeOutKey
import com.metrolist.music.constants.SleepTimerStopAfterCurrentSongKey
import com.metrolist.music.constants.SliderStyle
import com.metrolist.music.constants.SliderStyleKey
import com.metrolist.music.constants.SquigglySliderKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.constants.UseNewPlayerDesignKey
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.di.LocalMusicAnalysisEntryPoint
import com.metrolist.music.download.FileMusicDownloader
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.localmusic.LocalSimilarSongSelector
import com.metrolist.music.localmusic.advancedKeyDisplayName
import com.metrolist.music.localmusic.analysis.LocalMusicAnalysisState
import com.metrolist.music.localmusic.analysis.LocalMusicAnalysisStatus
import com.metrolist.music.localmusic.analysis.hasCompleteAnalysis
import com.metrolist.music.localmusic.toLocalSimilarSongAnalysis
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.BottomSheet
import com.metrolist.music.ui.component.BottomSheetState
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.Lyrics
import com.metrolist.music.ui.component.PlayerSliderTrack
import com.metrolist.music.ui.component.ResizableIconButton
import com.metrolist.music.ui.component.SquigglySlider
import com.metrolist.music.ui.component.WavySlider
import com.metrolist.music.ui.component.rememberBottomSheetState
import com.metrolist.music.ui.menu.LocalFileDownloadMode
import com.metrolist.music.ui.menu.PlayerMenu
import com.metrolist.music.ui.menu.runLocalFileDownloadAction
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.ui.theme.PlayerColorExtractor
import com.metrolist.music.ui.theme.PlayerSliderColors
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.ui.utils.ShowOffsetDialog
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import java.util.Locale
import com.metrolist.music.ui.component.Icon as MIcon
import com.metrolist.music.constants.SleepTimerDefaultKey
import com.metrolist.music.utils.dataStore
import androidx.datastore.preferences.core.edit
import com.metrolist.chinamusic.ChinaMusicUtils
import com.metrolist.chinamusic.model.AudioQuality
import com.metrolist.music.constants.SleepTimerFadeOutKey
import com.metrolist.music.constants.SleepTimerStopAfterCurrentSongKey
import java.io.File
import java.net.URL


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val menuState = LocalMenuState.current
    val sleepTimerDefaultSetTemplate = stringResource(R.string.sleep_timer_default_set)
    val copiedTitleStr = stringResource(R.string.copied_title)
    val copiedArtistStr = stringResource(R.string.copied_artist)
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val (useNewPlayerDesign, onUseNewPlayerDesignChange) =
        rememberPreference(
            UseNewPlayerDesignKey,
            defaultValue = true,
        )
    val (hidePlayerThumbnail, onHidePlayerThumbnailChange) = rememberPreference(HidePlayerThumbnailKey, false)
    val (hideStatusBarOnFullscreen) = rememberPreference(HideStatusBarOnFullscreenKey, false)
    val cropAlbumArt by rememberPreference(CropAlbumArtKey, false)

    var showInlineLyrics by rememberSaveable {
        mutableStateOf(false)
    }
    var showCoverEmotionRadar by rememberSaveable {
        mutableStateOf(false)
    }
    var coverEmotionRadarVisible by rememberSaveable {
        mutableStateOf(false)
    }

    var isFullScreen by rememberSaveable {
        mutableStateOf(false)
    }

    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.BLUR,
    )
    val playerButtonsStyle by rememberEnumPreference(
        key = PlayerButtonsStyleKey,
        defaultValue = PlayerButtonsStyle.DEFAULT,
    )

    val isSystemInDarkTheme = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val useDarkTheme =
        remember(darkTheme, isSystemInDarkTheme) {
            if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
        }

    val shouldUseDarkButtonColors =
        remember(playerBackground, useDarkTheme) {
            when (playerBackground) {
                PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> true
                PlayerBackgroundStyle.DEFAULT -> useDarkTheme
            }
        }

    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val isKeepScreenOn by rememberPreference(KeepScreenOn, false)
    val keepScreenOn = isPlaying && isKeepScreenOn

    DisposableEffect(playerBackground, state.isExpanded, useDarkTheme, keepScreenOn, isFullScreen, hideStatusBarOnFullscreen) {
        val window = (context as? android.app.Activity)?.window
        if (window != null && state.isExpanded) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)

            when (playerBackground) {
                PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> {
                    insetsController.isAppearanceLightStatusBars = false
                }

                PlayerBackgroundStyle.DEFAULT -> {
                    insetsController.isAppearanceLightStatusBars = !useDarkTheme
                }
            }

            if (isFullScreen && hideStatusBarOnFullscreen) {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            }

            if (keepScreenOn && state.isExpanded) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !useDarkTheme
                insetsController.show(WindowInsetsCompat.Type.statusBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    BackHandler(enabled = state.isExpanded) {
        timber.log.Timber.tag("BackDebug").d("Player BackHandler triggered! isExpanded=${state.isExpanded} isCollapsed=${state.isCollapsed} value=${state.value} progress=${state.progress}")
        state.collapseSoft()
    }

    val onBackgroundColor =
        when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface
        }
    val useBlackBackground =
        remember(isSystemInDarkTheme, darkTheme, pureBlack) {
            val useDarkTheme =
                if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            useDarkTheme && pureBlack
        }

    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val database = LocalDatabase.current
    val currentLocalMusic by database.localMusic(mediaMetadata?.id ?: "").collectAsStateWithLifecycle(initialValue = null)
    val currentShareLocalMusic = currentLocalMusic?.takeIf { it.missingSince == null }
    val localMusicAnalysisManager =
        remember(context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                LocalMusicAnalysisEntryPoint::class.java,
            ).localMusicAnalysisManager()
        }
    val localMusicAnalysisStates by localMusicAnalysisManager.states.collectAsStateWithLifecycle()
    val coverAnalysisState = currentShareLocalMusic?.songId?.let(localMusicAnalysisStates::get)
    val currentLocalRecommendationSource =
        isCurrentLocalRecommendationSource(
            isLocalMetadata = mediaMetadata?.isLocal == true,
            currentLocalMusic = currentLocalMusic,
        )
    val shouldLoadAnalyzedLocalSongs =
        currentLocalRecommendationSource &&
            currentLocalMusic?.hasCompleteEmotionVector() == true &&
            currentLocalMusic?.bpm?.let { it > 0f } == true
    val analyzedLocalSongsFlow =
        remember(database, mediaMetadata?.id, shouldLoadAnalyzedLocalSongs) {
            if (shouldLoadAnalyzedLocalSongs) {
                database.analyzedLocalSongs().map<List<LocalSong>, List<LocalSong>?> { it }
            } else {
                flowOf(emptyList<LocalSong>())
            }
        }
    val analyzedLocalSongs by analyzedLocalSongsFlow.collectAsStateWithLifecycle(initialValue = null)
    val localSimilarSongs = remember(mediaMetadata?.id, currentLocalRecommendationSource, currentLocalMusic, analyzedLocalSongs) {
        when {
            !currentLocalRecommendationSource -> emptyList()
            currentLocalMusic == null || analyzedLocalSongs == null -> null
            else ->
                recommendedLocalSongs(
                    currentSongId = mediaMetadata?.id,
                    current = currentLocalMusic,
                    songs = analyzedLocalSongs.orEmpty(),
                )
        }
    }
    val localSimilarEmptyText = remember(mediaMetadata?.id, currentLocalRecommendationSource, currentLocalMusic, analyzedLocalSongs) {
        localRecommendationEmptyText(
            currentSongId = mediaMetadata?.id,
            isCurrentLocal = currentLocalRecommendationSource,
            current = currentLocalMusic,
            candidates = analyzedLocalSongs,
        )
    }
    val nowPlayingAnalysis = remember(currentLocalMusic) {
        currentLocalMusic?.toNowPlayingAnalysis()
    }
    val coverRadarLocalMusic =
        currentLocalMusic?.takeIf {
            it.hasCompleteEmotionVector()
        }
    var coverDownloadAnalyzeInProgress by rememberSaveable {
        mutableStateOf(false)
    }
    var coverExternalAnalysisInProgress by rememberSaveable {
        mutableStateOf(false)
    }
    val coverAnalysisRunning =
        coverAnalysisState?.status == LocalMusicAnalysisStatus.Queued ||
            coverAnalysisState?.status == LocalMusicAnalysisStatus.Running
    val coverAnalysisBusy =
        coverDownloadAnalyzeInProgress ||
            coverExternalAnalysisInProgress ||
            coverAnalysisRunning
    LaunchedEffect(mediaMetadata?.id) {
        coverExternalAnalysisInProgress = false
    }
    LaunchedEffect(coverAnalysisState?.status) {
        if (coverAnalysisState?.status == LocalMusicAnalysisStatus.Queued ||
            coverAnalysisState?.status == LocalMusicAnalysisStatus.Running ||
            coverAnalysisState?.status == LocalMusicAnalysisStatus.Complete ||
            coverAnalysisState?.status == LocalMusicAnalysisStatus.Failed
        ) {
            coverExternalAnalysisInProgress = false
        }
    }
    LaunchedEffect(coverExternalAnalysisInProgress) {
        if (coverExternalAnalysisInProgress) {
            delay(1500)
            if (!coverAnalysisRunning && !coverDownloadAnalyzeInProgress) {
                coverExternalAnalysisInProgress = false
            }
        }
    }
    LaunchedEffect(mediaMetadata?.id, showCoverEmotionRadar, coverRadarLocalMusic?.songId, coverAnalysisBusy) {
        coverEmotionRadarVisible = false
        if (showCoverEmotionRadar && mediaMetadata != null) {
            delay(90)
            coverEmotionRadarVisible = true
        }
    }
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val automix by playerConnection.service.automixItems.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val localSimilarAutoplay by rememberPreference(LocalSimilarAutoplayKey, false)
    val primaryControls =
        remember(useNewPlayerDesign) {
            playerPrimaryControls(
                useNewPlayerDesign = useNewPlayerDesign,
            )
        }
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
    val localSimilarNextState by playerConnection.service.localSimilarNextState.collectAsStateWithLifecycle()
    val isMuted by playerConnection.isMuted.collectAsStateWithLifecycle()

    val sliderStyle by rememberEnumPreference(SliderStyleKey, SliderStyle.WAVY)
    val squigglySlider by rememberPreference(SquigglySliderKey, defaultValue = false)

    // Listen Together state (reactive)
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRoleState = listenTogetherManager?.role?.collectAsStateWithLifecycle(initialValue = RoomRole.NONE)
    val isListenTogetherGuest = listenTogetherRoleState?.value == RoomRole.GUEST

    // Cast state - safely access castConnectionHandler to prevent crashes during service lifecycle changes
    val castHandler =
        remember(playerConnection) {
            try {
                playerConnection.service.castConnectionHandler
            } catch (e: Exception) {
                null
            }
        }
    val isCasting by castHandler?.isCasting?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val castPosition by castHandler?.castPosition?.collectAsStateWithLifecycle() ?: remember { mutableLongStateOf(0L) }
    val castDuration by castHandler?.castDuration?.collectAsStateWithLifecycle() ?: remember { mutableLongStateOf(0L) }
    val castIsPlaying by castHandler?.castIsPlaying?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val localSimilarPlaybackMode = localSimilarAutoplay && mediaMetadata?.isLocal == true && !isCasting
    val nextButtonState =
        remember(canSkipNext, localSimilarPlaybackMode, localSimilarNextState) {
            playerNextButtonState(
                canSkipNext = canSkipNext,
                localSimilarPlaybackMode = localSimilarPlaybackMode,
                localSimilarNextState = localSimilarNextState,
            )
        }
    val nextButtonEnabled = nextButtonState != PlayerNextButtonState.DISABLED && !isListenTogetherGuest

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.isExpanded) {
        if (state.isExpanded) {
            delay(100)
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // Ignore if focus request fails
            }
        }
    }

    // Use Cast state when casting, otherwise local player
    val effectiveIsPlaying = if (isCasting) castIsPlaying else isPlaying

    // Use State objects for position/duration to pass to MiniPlayer without causing recomposition
    // These states persist across playback state changes to ensure continuous progress updates
    val positionState = remember { mutableLongStateOf(0L) }
    val durationState = remember { mutableLongStateOf(0L) }

    // Convenience accessors for local use
    var position by positionState
    var duration by durationState

    val effectivePosition by remember {
        derivedStateOf {
            if (isCasting) {
                castPosition
            } else {
                position
            }
        }
    }

    var sliderPosition by remember {
        mutableStateOf<Long?>(null)
    }
    // Track when we last manually set position to avoid Cast overwriting it
    var lastManualSeekTime by remember { mutableLongStateOf(0L) }

    var gradientColors by remember {
        mutableStateOf<List<Color>>(emptyList())
    }
    val gradientColorsCache = remember { mutableMapOf<String, List<Color>>() }

    if (!canSkipNext && automix.isNotEmpty()) {
        playerConnection.service.addToQueueAutomix(automix[0], 0)
    }

    val defaultGradientColors = listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant)
    val fallbackColor = MaterialTheme.colorScheme.surface.toArgb()

    LaunchedEffect(mediaMetadata?.id, playerBackground) {
        if (playerBackground == PlayerBackgroundStyle.GRADIENT) {
            val currentMetadata = mediaMetadata
            if (currentMetadata != null && currentMetadata.thumbnailUrl != null) {
                val cachedColors = gradientColorsCache[currentMetadata.id]
                if (cachedColors != null) {
                    gradientColors = cachedColors
                    return@LaunchedEffect
                }
                withContext(Dispatchers.IO) {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(currentMetadata.thumbnailUrl)
                            .size(100, 100)
                            .allowHardware(false)
                            .memoryCacheKey("gradient_${currentMetadata.id}")
                            .build()

                    val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
                    if (result != null) {
                        val bitmap = result.image?.toBitmap()
                        if (bitmap != null) {
                            val palette =
                                withContext(Dispatchers.Default) {
                                    Palette
                                        .from(bitmap)
                                        .maximumColorCount(8)
                                        .resizeBitmapArea(100 * 100)
                                        .generate()
                                }
                            val extractedColors =
                                PlayerColorExtractor.extractGradientColors(
                                    palette = palette,
                                    fallbackColor = fallbackColor,
                                )
                            gradientColorsCache[currentMetadata.id] = extractedColors
                            withContext(Dispatchers.Main) { gradientColors = extractedColors }
                        }
                    }
                }
            }
        } else {
            gradientColors = emptyList()
        }
    }

    val TextBackgroundColor by animateColorAsState(
        targetValue =
            when (playerBackground) {
                PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
                PlayerBackgroundStyle.BLUR -> Color.White
                PlayerBackgroundStyle.GRADIENT -> Color.White
            },
        label = "TextBackgroundColor",
    )

    val icBackgroundColor by animateColorAsState(
        targetValue =
            when (playerBackground) {
                PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.surface
                PlayerBackgroundStyle.BLUR -> Color.Black
                PlayerBackgroundStyle.GRADIENT -> Color.Black
            },
        label = "icBackgroundColor",
    )

    val (textButtonColor, iconButtonColor) =
        when {
            playerBackground == PlayerBackgroundStyle.BLUR ||
                playerBackground == PlayerBackgroundStyle.GRADIENT -> {
                when (playerButtonsStyle) {
                    PlayerButtonsStyle.DEFAULT -> {
                        Pair(Color.White, Color.Black)
                    }

                    PlayerButtonsStyle.PRIMARY -> {
                        Pair(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onPrimary,
                        )
                    }

                    PlayerButtonsStyle.TERTIARY -> {
                        Pair(
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                }
            }

            else -> {
                when (playerButtonsStyle) {
                    PlayerButtonsStyle.DEFAULT -> {
                        if (useDarkTheme) {
                            Pair(Color.White, Color.Black)
                        } else {
                            Pair(Color.Black, Color.White)
                        }
                    }

                    PlayerButtonsStyle.PRIMARY -> {
                        Pair(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onPrimary,
                        )
                    }

                    PlayerButtonsStyle.TERTIARY -> {
                        Pair(
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                }
            }
        }

    // Separate colors for Previous/Next buttons in PRIMARY/TERTIARY modes
    val (sideButtonContainerColor, sideButtonContentColor) =
        when {
            playerBackground == PlayerBackgroundStyle.BLUR ||
                playerBackground == PlayerBackgroundStyle.GRADIENT -> {
                when (playerButtonsStyle) {
                    PlayerButtonsStyle.DEFAULT -> {
                        Pair(
                            Color.White.copy(alpha = 0.2f),
                            Color.White,
                        )
                    }

                    PlayerButtonsStyle.PRIMARY -> {
                        Pair(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    PlayerButtonsStyle.TERTIARY -> {
                        Pair(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            else -> {
                when (playerButtonsStyle) {
                    PlayerButtonsStyle.DEFAULT -> {
                        Pair(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    PlayerButtonsStyle.PRIMARY -> {
                        Pair(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    PlayerButtonsStyle.TERTIARY -> {
                        Pair(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

    val download by LocalDownloadUtil.current
        .getDownload(mediaMetadata?.id ?: "")
        .collectAsStateWithLifecycle(initialValue = null)

    val sleepTimerEnabled =
        remember(
            playerConnection.service.sleepTimer.triggerTime,
            playerConnection.service.sleepTimer.pauseWhenSongEnd,
        ) {
            playerConnection.service.sleepTimer.isActive
        }

    var sleepTimerTimeLeft by remember {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(sleepTimerEnabled) {
        if (sleepTimerEnabled) {
            while (isActive) {
                sleepTimerTimeLeft =
                    if (playerConnection.service.sleepTimer.pauseWhenSongEnd) {
                        playerConnection.player.duration - playerConnection.player.currentPosition
                    } else {
                        playerConnection.service.sleepTimer.triggerTime - System.currentTimeMillis()
                    }
                delay(1000L)
            }
        }
    }

    val scope = rememberCoroutineScope()
    val showCoverAnalysisAnimation: () -> Unit = {
        if (mediaMetadata != null) {
            showCoverEmotionRadar = true
            coverEmotionRadarVisible = false
            coverExternalAnalysisInProgress = true
            scope.launch {
                delay(90)
                coverEmotionRadarVisible = true
            }
        }
    }
    val startCoverDownloadAnalyze: () -> Unit = {
        val metadata = mediaMetadata
        if (metadata != null && !coverAnalysisBusy) {
            showCoverAnalysisAnimation()
            coverDownloadAnalyzeInProgress = true
            scope.launch {
                val result =
                    runLocalFileDownloadAction(
                        context = context,
                        database = database,
                        mediaMetadata = metadata,
                        quality = FileMusicDownloader.Quality.HIGH,
                        mode = LocalFileDownloadMode.DownloadAndAnalyze,
                        analysisManager = localMusicAnalysisManager,
                    )
                coverDownloadAnalyzeInProgress = false
                Toast
                    .makeText(
                        context,
                        result.fold(
                            onSuccess = { actionResult ->
                                when {
                                    actionResult.reusedExisting && actionResult.analysisStarted ->
                                        "已在本地，开始分析：${actionResult.displayPath}"
                                    actionResult.analysisStarted ->
                                        "已下载到 ${actionResult.displayPath}，开始分析"
                                    actionResult.reusedExisting ->
                                        "已在本地：${actionResult.displayPath}"
                                    else ->
                                        "已下载到 ${actionResult.displayPath}"
                                }
                            },
                            onFailure = { "下载失败：${it.message ?: "未知错误"}" },
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }
    var showSleepTimerDialog by remember {
        mutableStateOf(false)
    }

    val sleepTimerDefault by rememberPreference(SleepTimerDefaultKey, 30f)
    var sleepTimerValue by remember {
        mutableFloatStateOf(sleepTimerDefault)
    }
    val isAtDefault by remember {
        derivedStateOf { sleepTimerValue.roundToInt() == sleepTimerDefault.roundToInt() }
    }
    val sleepTimerStopAfterCurrentSong by rememberPreference(SleepTimerStopAfterCurrentSongKey, false)
    val sleepTimerFadeOut by rememberPreference(SleepTimerFadeOutKey, false)


    if (showSleepTimerDialog) {
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { showSleepTimerDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.bedtime),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.sleep_timer)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSleepTimerDialog = false
                        playerConnection.service.sleepTimer.start(
                            minute = sleepTimerValue.roundToInt(),
                            stopAfterCurrentSong = sleepTimerStopAfterCurrentSong,
                            fadeOut = sleepTimerFadeOut,
                        )
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSleepTimerDialog = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.minute,
                                sleepTimerValue.roundToInt(),
                                sleepTimerValue.roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    Slider(
                        value = sleepTimerValue,
                        onValueChange = { sleepTimerValue = it },
                        valueRange = 5f..120f,
                        steps = (120 - 5) / 5 - 1,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isAtDefault) {
                            FilledIconButton(
                                onClick = {
                                    scope.launch {
                                        context.dataStore.edit { settings ->
                                            settings[SleepTimerDefaultKey] = sleepTimerValue
                                        }
                                    }
                                    Toast.makeText(
                                        context,
                                        String.format(sleepTimerDefaultSetTemplate, sleepTimerValue.roundToInt()),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Text(stringResource(R.string.set_as_default))
                            }
                        } else {
                            OutlinedIconButton(
                                onClick = {
                                    scope.launch {
                                        context.dataStore.edit { settings ->
                                            settings[SleepTimerDefaultKey] = sleepTimerValue
                                        }
                                    }
                                    Toast.makeText(
                                        context,
                                        String.format(sleepTimerDefaultSetTemplate, sleepTimerValue.roundToInt()),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) {
                                Text(stringResource(R.string.set_as_default))
                            }
                        }

                        OutlinedIconButton(
                            onClick = {
                                showSleepTimerDialog = false
                                playerConnection.service.sleepTimer.start(minute = -1)
                            },
                        ) {
                            Text(stringResource(R.string.end_of_song))
                        }
                    }
                }
            },
        )
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // Position update - only for local playback
    // When casting, we use castPosition directly to avoid sync issues
    // Use isPlaying instead of playbackState to ensure continuous updates during playback
    LaunchedEffect(isPlaying, isCasting) {
        if (!isCasting && isPlaying) {
            while (isActive) {
                delay(100) // Update more frequently for smoother progress bar
                if (sliderPosition == null) { // Only update if user isn't dragging
                    position = playerConnection.player.currentPosition
                    duration = playerConnection.player.duration
                }
            }
        }
    }
    val startCoverAnalysisFromRadar: () -> Unit = {
        if (showCoverEmotionRadar) {
            startCoverDownloadAnalyze()
        }
    }

    // Also update position when playback state changes (e.g., song change, seek)
    LaunchedEffect(playbackState, mediaMetadata?.id) {
        if (!isCasting) {
            position = playerConnection.player.currentPosition
            duration = playerConnection.player.duration
        }
    }

    // When casting, use Cast position/duration directly
    // But wait a bit after manual seeks to let Cast catch up
    LaunchedEffect(isCasting, castPosition, castDuration) {
        if (isCasting && sliderPosition == null) {
            val timeSinceManualSeek = System.currentTimeMillis() - lastManualSeekTime
            if (timeSinceManualSeek > 1500) {
                // Only update from Cast if we haven't manually seeked recently
                position = castPosition
                if (castDuration > 0) duration = castDuration
            }
        }
    }

    val dismissedBound = QueuePeekHeight + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val queueSheetState =
        rememberBottomSheetState(
            dismissedBound = dismissedBound,
            expandedBound = state.expandedBound,
            collapsedBound = dismissedBound + 1.dp,
            initialAnchor = 1,
        )

    val bottomSheetBackgroundColor =
        when (playerBackground) {
            PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> {
                MaterialTheme.colorScheme.surfaceContainer
            }

            else -> {
                if (useBlackBackground) {
                    Color.Black
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            }
        }

    val backgroundAlpha = state.progress.coerceIn(0f, 1f)

    BottomSheet(
        state = state,
        modifier = modifier,
        background = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(bottomSheetBackgroundColor),
            ) {
                when (playerBackground) {
                    PlayerBackgroundStyle.BLUR -> {
                        val thumbnailUrl = mediaMetadata?.thumbnailUrl
                        if (thumbnailUrl != null) {
                            val blurAmount = if (useDarkTheme) 150.dp else 100.dp
                            var baseUrl by remember { mutableStateOf(thumbnailUrl) }
                            var overlayUrl by remember { mutableStateOf<String?>(null) }
                            val overlayAlpha = remember { Animatable(0f) }
                            LaunchedEffect(thumbnailUrl) {
                                if (thumbnailUrl != baseUrl) {
                                    // If a previous transition was in progress, adopt overlay as new base
                                    if (overlayUrl != null) {
                                        baseUrl = overlayUrl!!
                                    }
                                    overlayUrl = thumbnailUrl
                                    overlayAlpha.snapTo(0f)
                                    overlayAlpha.animateTo(1f, tween(durationMillis = 2000))
                                    baseUrl = thumbnailUrl
                                    overlayUrl = null
                                }
                            }
                            val baseRequest = remember(baseUrl) {
                                ImageRequest.Builder(context)
                                    .data(baseUrl)
                                    .size(100, 100)
                                    .allowHardware(false)
                                    .build()
                            }
                            Box(modifier = Modifier.alpha(backgroundAlpha)) {
                                AsyncImage(
                                    model = baseRequest,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().blur(blurAmount),
                                )
                                overlayUrl?.let { url ->
                                    val overlayRequest = remember(url) {
                                        ImageRequest.Builder(context)
                                            .data(url)
                                            .size(100, 100)
                                            .allowHardware(false)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = overlayRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { alpha = overlayAlpha.value }
                                            .blur(blurAmount),
                                    )
                                }
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.45f)),
                                )
                            }
                        }
                    }

                    PlayerBackgroundStyle.GRADIENT -> {
                        AnimatedContent(
                            targetState = gradientColors,
                            transitionSpec = {
                                fadeIn(tween(800)).togetherWith(fadeOut(tween(800)))
                            },
                            label = "gradientBackground",
                        ) { colors ->
                            if (colors.isNotEmpty()) {
                                val gradientColorStops =
                                    if (colors.size >= 3) {
                                        arrayOf(
                                            0.0f to colors[0],
                                            0.5f to colors[1],
                                            1.0f to colors[2],
                                        )
                                    } else {
                                        arrayOf(
                                            0.0f to colors[0],
                                            0.6f to colors[0].copy(alpha = 0.7f),
                                            1.0f to Color.Black,
                                        )
                                    }
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .alpha(backgroundAlpha)
                                        .background(Brush.verticalGradient(colorStops = gradientColorStops))
                                        .background(Color.Black.copy(alpha = 0.2f)),
                                )
                            }
                        }
                    }

                    else -> {
                        PlayerBackgroundStyle.DEFAULT
                    }
                }
            }
        },
        onDismiss =
            if (!isListenTogetherGuest) {
                {
                    playerConnection.service.clearAutomix()
                    playerConnection.player.stop()
                    playerConnection.player.clearMediaItems()
                }
            } else {
                null
            },
        collapsedContent = {
            MiniPlayer(
                positionState = positionState,
                durationState = durationState,
                navController = navController,
                onClick = { state.expandSoft() },
            )
        },
    ) {
        val controlsContent: @Composable ColumnScope.(MediaMetadata) -> Unit = { mediaMetadata ->
            val playPauseRoundness by animateDpAsState(
                targetValue = if (isPlaying) 24.dp else 36.dp,
                animationSpec = tween(durationMillis = 90, easing = LinearEasing),
                label = "playPauseRoundness",
            )

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding),
            ) {
                AnimatedContent(
                    targetState = showInlineLyrics,
                    label = "ThumbnailAnimation",
                ) { showLyrics ->
                    if (showLyrics) {
                        Row {
                            if (hidePlayerThumbnail) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(ThumbnailCornerRadius))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.small_icon),
                                        contentDescription = null,
                                        modifier =
                                            Modifier
                                                .size(32.dp),
                                        tint = textButtonColor.copy(alpha = 0.7f),
                                    )
                                }
                            } else {
                                AsyncImage(
                                    model = mediaMetadata.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                                    modifier =
                                        Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(ThumbnailCornerRadius))
                                            .clickable { showInlineLyrics = false },
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(0.dp))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    AnimatedContent(
                        targetState = mediaMetadata.title,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "",
                    ) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = TextBackgroundColor,
                            modifier =
                                Modifier
                                    .basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                                    .combinedClickable(
                                        enabled = true,
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = {
                                            if (showInlineLyrics) {
                                                showInlineLyrics = false
                                            } else {
                                                val artist = mediaMetadata.artists.firstOrNull()
                                                if (artist != null) {
                                                    val artistId = artist.id ?: "china_artist_${artist.name}"
                                                    navController.navigate("artist/${android.net.Uri.encode(artistId)}?name=${android.net.Uri.encode(artist.name)}")
                                                    state.collapseSoft()
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            val clip = ClipData.newPlainText(copiedTitleStr, title)
                                            clipboardManager.setPrimaryClip(clip)
                                            Toast
                                                .makeText(context, copiedTitleStr, Toast.LENGTH_SHORT)
                                                .show()
                                        },
                                    ),
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (mediaMetadata.explicit) MIcon.Explicit()

                        if (mediaMetadata.artists.any { it.name.isNotBlank() }) {
                            val annotatedString =
                                buildAnnotatedString {
                                    mediaMetadata.artists.forEachIndexed { index, artist ->
                                        val tag = "artist_${artist.id ?: artist.name}"
                                        pushStringAnnotation(tag = tag, annotation = "${artist.id.orEmpty()}|${artist.name}")
                                        withStyle(SpanStyle(color = TextBackgroundColor, fontSize = 16.sp)) {
                                            append(artist.name)
                                        }
                                        pop()
                                        if (index != mediaMetadata.artists.lastIndex) append(", ")
                                    }
                                }

                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                                        .padding(end = 12.dp),
                            ) {
                                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                                var clickOffset by remember { mutableStateOf<Offset?>(null) }
                                Text(
                                    text = annotatedString,
                                    style = MaterialTheme.typography.titleMedium.copy(color = TextBackgroundColor),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { layoutResult = it },
                                    modifier =
                                        Modifier
                                            .pointerInput(Unit) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val tapPosition = event.changes.firstOrNull()?.position
                                                        if (tapPosition != null) {
                                                            clickOffset = tapPosition
                                                        }
                                                    }
                                                }
                                            }.combinedClickable(
                                                enabled = true,
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() },
                                                onClick = {
                                                    if (showInlineLyrics) {
                                                        showInlineLyrics = false
                                                    } else {
                                                        val tapPosition = clickOffset
                                                        val layout = layoutResult
                                                        if (tapPosition != null && layout != null) {
                                                            val offset = layout.getOffsetForPosition(tapPosition)
                                                            annotatedString
                                                                .getStringAnnotations(offset, offset)
                                                                .firstOrNull()
                                                                ?.let { ann ->
                                                                    val parts = ann.item.split("|", limit = 2)
                                                                    val artistName = parts.getOrNull(1).orEmpty()
                                                                    val artistId = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "china_artist_$artistName"
                                                                    if (artistName.isNotBlank()) {
                                                                        navController.navigate("artist/${android.net.Uri.encode(artistId)}?name=${android.net.Uri.encode(artistName)}")
                                                                        state.collapseSoft()
                                                                    }
                                                                }
                                                        }
                                                    }
                                                },
                                                onLongClick = {
                                                    val clip =
                                                        ClipData.newPlainText(
                                                            copiedArtistStr,
                                                            annotatedString,
                                                        )
                                                    clipboardManager.setPrimaryClip(clip)
                                                    Toast
                                                        .makeText(
                                                            context,
                                                            copiedArtistStr,
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                },
                                            ),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                if (useNewPlayerDesign) {
                    val shareShape =
                        RoundedCornerShape(
                            topStart = 50.dp,
                            bottomStart = 50.dp,
                            topEnd = 3.dp,
                            bottomEnd = 3.dp,
                        )

                    val favShape =
                        RoundedCornerShape(
                            topStart = 3.dp,
                            bottomStart = 3.dp,
                            topEnd = 50.dp,
                            bottomEnd = 50.dp,
                        )

                    val middleShape = RoundedCornerShape(3.dp)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnimatedContent(targetState = showInlineLyrics, label = "ShareButton") { showLyrics ->
                            if (showLyrics) {
                                FilledIconButton(
                                    onClick = { isFullScreen = !isFullScreen },
                                    shape = shareShape,
                                    colors =
                                        IconButtonDefaults.filledIconButtonColors(
                                            containerColor = textButtonColor,
                                            contentColor = iconButtonColor,
                                        ),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.fullscreen),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        scope.launch {
                                            shareAudioFile(context, mediaMetadata, currentShareLocalMusic)
                                        }
                                    },
                                    shape = shareShape,
                                    colors =
                                        IconButtonDefaults.filledIconButtonColors(
                                            containerColor = textButtonColor,
                                            contentColor = iconButtonColor,
                                        ),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.share),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }

                        AnimatedContent(targetState = showInlineLyrics, label = "LikeButton") { showLyrics ->
                            if (showLyrics) {
                                val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
                                FilledIconButton(
                                    onClick = {
                                        menuState.show {
                                            com.metrolist.music.ui.menu.LyricsMenu(
                                                lyricsProvider = { currentLyrics },
                                                songProvider = { currentSong?.song },
                                                mediaMetadataProvider = { mediaMetadata },
                                                onDismiss = menuState::dismiss,
                                                onShowOffsetDialog = {
                                                    bottomSheetPageState.show {
                                                        ShowOffsetDialog(
                                                            songProvider = { currentSong?.song },
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    },
                                    shape = favShape,
                                    colors =
                                        IconButtonDefaults.filledIconButtonColors(
                                            containerColor = textButtonColor,
                                            contentColor = iconButtonColor,
                                        ),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_horiz),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            } else {
                                // For episodes, show saved state (inLibrary); for songs, show liked state
                                val isEpisode = currentSong?.song?.isEpisode == true
                                val isFavorite = if (isEpisode) currentSong?.song?.inLibrary != null else currentSong?.song?.liked == true
                                FilledIconButton(
                                    onClick = playerConnection::toggleLike,
                                    shape = favShape,
                                    colors =
                                        IconButtonDefaults.filledIconButtonColors(
                                            containerColor = textButtonColor,
                                            contentColor = iconButtonColor,
                                        ),
                                    modifier = Modifier.size(42.dp),
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(
                                                if (isFavorite) {
                                                    R.drawable.favorite
                                                } else {
                                                    R.drawable.favorite_border
                                                },
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    AnimatedContent(targetState = showInlineLyrics, label = "ShareButton") { showLyrics ->
                        if (showLyrics) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(textButtonColor)
                                        .clickable { isFullScreen = !isFullScreen },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.fullscreen),
                                    contentDescription = null,
                                    tint = iconButtonColor,
                                    modifier =
                                        Modifier
                                            .align(Alignment.Center)
                                            .size(24.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(textButtonColor)
                                        .clickable {
                                            val intent =
                                                Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    type = "text/plain"
                                                    putExtra(
                                                        Intent.EXTRA_TEXT,
                                                        "${mediaMetadata.title} - ${mediaMetadata.artists.joinToString { it.name }}",
                                                    )
                                                }
                                            context.startActivity(Intent.createChooser(intent, null))
                                        },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.share),
                                    contentDescription = null,
                                    tint = iconButtonColor,
                                    modifier =
                                        Modifier
                                            .align(Alignment.Center)
                                            .size(24.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.size(12.dp))

                    AnimatedContent(targetState = showInlineLyrics, label = "LikeButton") { showLyrics ->
                        if (showLyrics) {
                            val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
                            Box(
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(textButtonColor)
                                        .clickable {
                                            menuState.show {
                                                com.metrolist.music.ui.menu.LyricsMenu(
                                                    lyricsProvider = { currentLyrics },
                                                    songProvider = { currentSong?.song },
                                                    mediaMetadataProvider = { mediaMetadata },
                                                    onDismiss = menuState::dismiss,
                                                    onShowOffsetDialog = {
                                                        bottomSheetPageState.show {
                                                            ShowOffsetDialog(
                                                                songProvider = { currentSong?.song },
                                                            )
                                                        }
                                                    },
                                                )
                                            }
                                        },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_horiz),
                                    contentDescription = null,
                                    tint = iconButtonColor,
                                    modifier =
                                        Modifier
                                            .align(Alignment.Center)
                                            .size(24.dp),
                                )
                            }
                        } else {
                            PlayerMoreMenuButton(
                                mediaMetadata = mediaMetadata,
                                navController = navController,
                                state = state,
                                textButtonColor = textButtonColor,
                                iconButtonColor = iconButtonColor,
                                onAnalysisStarted = showCoverAnalysisAnimation,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            when (sliderStyle) {
                SliderStyle.DEFAULT -> {
                    Slider(
                        value = (sliderPosition ?: effectivePosition).toFloat(),
                        valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                        onValueChange = {
                            if (!isListenTogetherGuest) {
                                sliderPosition = it.toLong()
                            }
                        },
                        onValueChangeFinished = {
                            if (!isListenTogetherGuest) {
                                sliderPosition?.let {
                                    if (isCasting) {
                                        castHandler?.seekTo(it)
                                        lastManualSeekTime = System.currentTimeMillis()
                                    } else {
                                        playerConnection.player.seekTo(it)
                                    }
                                    position = it
                                }
                                sliderPosition = null
                            }
                        },
                        enabled = !isListenTogetherGuest,
                        colors = PlayerSliderColors.getSliderColors(textButtonColor, playerBackground, useDarkTheme),
                        modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
                    )
                }

                SliderStyle.WAVY -> {
                    if (squigglySlider) {
                        SquigglySlider(
                            value = (sliderPosition ?: effectivePosition).toFloat(),
                            valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                            onValueChange = {
                                sliderPosition = it.toLong()
                            },
                            onValueChangeFinished = {
                                sliderPosition?.let {
                                    if (isCasting) {
                                        castHandler?.seekTo(it)
                                        lastManualSeekTime = System.currentTimeMillis()
                                    } else {
                                        playerConnection.player.seekTo(it)
                                    }
                                    position = it
                                }
                                sliderPosition = null
                            },
                            modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
                            colors = PlayerSliderColors.getSliderColors(textButtonColor, playerBackground, useDarkTheme),
                            isPlaying = effectiveIsPlaying,
                        )
                    } else {
                        WavySlider(
                            value = (sliderPosition ?: effectivePosition).toFloat(),
                            valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                            onValueChange = {
                                sliderPosition = it.toLong()
                            },
                            onValueChangeFinished = {
                                sliderPosition?.let {
                                    if (isCasting) {
                                        castHandler?.seekTo(it)
                                        lastManualSeekTime = System.currentTimeMillis()
                                    } else {
                                        playerConnection.player.seekTo(it)
                                    }
                                    position = it
                                }
                                sliderPosition = null
                            },
                            colors = PlayerSliderColors.getSliderColors(textButtonColor, playerBackground, useDarkTheme),
                            modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
                            isPlaying = effectiveIsPlaying,
                        )
                    }
                }

                SliderStyle.SLIM -> {
                    Slider(
                        value = (sliderPosition ?: effectivePosition).toFloat(),
                        valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                        onValueChange = {
                            if (!isListenTogetherGuest) {
                                sliderPosition = it.toLong()
                            }
                        },
                        onValueChangeFinished = {
                            if (!isListenTogetherGuest) {
                                sliderPosition?.let {
                                    if (isCasting) {
                                        castHandler?.seekTo(it)
                                        lastManualSeekTime = System.currentTimeMillis()
                                    } else {
                                        playerConnection.player.seekTo(it)
                                    }
                                    position = it
                                }
                                sliderPosition = null
                            }
                        },
                        enabled = !isListenTogetherGuest,
                        thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                        track = { sliderState ->
                            PlayerSliderTrack(
                                sliderState = sliderState,
                                colors = PlayerSliderColors.getSliderColors(textButtonColor, playerBackground, useDarkTheme),
                            )
                        },
                        modifier = Modifier.padding(horizontal = PlayerHorizontalPadding),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding + 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = makeTimeString(sliderPosition ?: effectivePosition),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (duration != C.TIME_UNSET) makeTimeString(duration) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Source & quality chip (absolutely centered)
                run {
                    val songId = mediaMetadata?.id ?: ""
                    var audioBitrateKbps by remember { mutableStateOf(0) }
                    var audioMimeType by remember { mutableStateOf("") }

                    LaunchedEffect(songId, playbackState) {
                        try {
                            val format = playerConnection.player.audioFormat
                            val bitrate = format?.bitrate ?: format?.averageBitrate ?: 0
                            audioBitrateKbps = if (bitrate > 0) bitrate / 1000 else 0
                            audioMimeType = format?.sampleMimeType ?: format?.containerMimeType ?: ""
                        } catch (_: Exception) {
                            audioBitrateKbps = 0
                            audioMimeType = ""
                        }
                    }

                    val sourceShort = remember(songId, mediaMetadata?.isLocal) {
                        when {
                            mediaMetadata?.isLocal == true || songId.startsWith("local_") -> "LOC"
                            songId.startsWith("china_") -> {
                                val parts = songId.removePrefix("china_").split("_", limit = 2)
                                com.metrolist.chinamusic.model.MusicSource.fromId(parts.getOrElse(0) { "" })?.id?.uppercase() ?: "CN"
                            }
                            else -> "YT"
                        }
                    }

                    val formatName = remember(audioMimeType) {
                        when {
                            audioMimeType.contains("flac", ignoreCase = true) -> "FLAC"
                            audioMimeType.contains("opus", ignoreCase = true) -> "OPUS"
                            audioMimeType.contains("vorbis", ignoreCase = true) -> "OGG"
                            audioMimeType.contains("mp4a") || audioMimeType.contains("aac", ignoreCase = true) -> "AAC"
                            audioMimeType.contains("mp3", ignoreCase = true) || audioMimeType.contains("mpeg", ignoreCase = true) -> "MP3"
                            audioMimeType.contains("wav", ignoreCase = true) || audioMimeType.contains("pcm", ignoreCase = true) -> "WAV"
                            audioMimeType.contains("webm", ignoreCase = true) -> "WEBM"
                            audioMimeType.isNotEmpty() -> audioMimeType.substringAfterLast("/").uppercase()
                            else -> ""
                        }
                    }

                    val qualityText = buildString {
                        if (formatName.isNotEmpty()) append(formatName)
                        if (audioBitrateKbps > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${audioBitrateKbps}k")
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = TextBackgroundColor.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = sourceShort,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextBackgroundColor.copy(alpha = 0.8f),
                                maxLines = 1,
                            )
                        }
                        if (qualityText.isNotEmpty()) {
                            Text(
                                text = qualityText,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextBackgroundColor.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            AnimatedVisibility(
                visible = !isFullScreen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                Column {
                    if (useNewPlayerDesign) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PlayerHorizontalPadding),
                        ) {
                            val backInteractionSource = remember { MutableInteractionSource() }
                            val nextInteractionSource = remember { MutableInteractionSource() }
                            val playPauseInteractionSource = remember { MutableInteractionSource() }

                            val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()
                            val isBackPressed by backInteractionSource.collectIsPressedAsState()
                            val isNextPressed by nextInteractionSource.collectIsPressedAsState()

                            val playPauseWeight by animateFloatAsState(
                                targetValue =
                                    if (isPlayPausePressed) {
                                        1.9f
                                    } else if (isBackPressed || isNextPressed) {
                                        1.1f
                                    } else {
                                        1.3f
                                    },
                                animationSpec =
                                    spring(
                                        dampingRatio = 0.6f,
                                        stiffness = 500f,
                                    ),
                                label = "playPauseWeight",
                            )

                            val backButtonWeight by animateFloatAsState(
                                targetValue =
                                    if (isBackPressed) {
                                        0.65f
                                    } else if (isPlayPausePressed) {
                                        0.35f
                                    } else {
                                        0.45f
                                    },
                                animationSpec =
                                    spring(
                                        dampingRatio = 0.6f,
                                        stiffness = 500f,
                                    ),
                                label = "backButtonWeight",
                            )

                            val nextButtonWeight by animateFloatAsState(
                                targetValue =
                                    if (isNextPressed) {
                                        0.65f
                                    } else if (isPlayPausePressed) {
                                        0.35f
                                    } else {
                                        0.45f
                                    },
                                animationSpec =
                                    spring(
                                        dampingRatio = 0.6f,
                                        stiffness = 500f,
                                    ),
                                label = "nextButtonWeight",
                            )

                            FilledIconButton(
                                onClick = playerConnection::seekToPrevious,
                                enabled = (canSkipPrevious || localSimilarPlaybackMode) && !isListenTogetherGuest,
                                shape = RoundedCornerShape(50),
                                interactionSource = backInteractionSource,
                                colors =
                                    IconButtonDefaults.filledIconButtonColors(
                                        containerColor = sideButtonContainerColor,
                                        contentColor = sideButtonContentColor,
                                    ),
                                modifier =
                                    Modifier
                                        .height(68.dp)
                                        .weight(backButtonWeight),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_previous),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            FilledIconButton(
                                onClick = {
                                    if (isListenTogetherGuest) {
                                        playerConnection.toggleMute()
                                        return@FilledIconButton
                                    }
                                    if (isCasting) {
                                        if (castIsPlaying) {
                                            castHandler?.pause()
                                        } else {
                                            castHandler?.play()
                                        }
                                    } else if (playbackState == STATE_ENDED) {
                                        playerConnection.player.seekTo(0, 0)
                                        playerConnection.player.playWhenReady = true
                                    } else {
                                        playerConnection.togglePlayPause()
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                interactionSource = playPauseInteractionSource,
                                colors =
                                    IconButtonDefaults.filledIconButtonColors(
                                        containerColor = textButtonColor,
                                        contentColor = iconButtonColor,
                                    ),
                                modifier =
                                    Modifier
                                        .height(68.dp)
                                        .weight(playPauseWeight)
                                        .focusRequester(focusRequester),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(
                                                if (isListenTogetherGuest) {
                                                    if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                                                } else {
                                                    if (effectiveIsPlaying) R.drawable.pause else R.drawable.play
                                                },
                                            ),
                                        contentDescription =
                                            if (isListenTogetherGuest) {
                                                if (isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute)
                                            } else {
                                                if (effectiveIsPlaying) stringResource(R.string.pause) else stringResource(R.string.play)
                                            },
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text =
                                            if (isListenTogetherGuest) {
                                                if (isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute)
                                            } else {
                                                if (effectiveIsPlaying) stringResource(R.string.pause) else stringResource(R.string.play)
                                            },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            FilledIconButton(
                                onClick = playerConnection::seekToNext,
                                enabled = nextButtonEnabled,
                                shape = RoundedCornerShape(50),
                                interactionSource = nextInteractionSource,
                                colors =
                                    IconButtonDefaults.filledIconButtonColors(
                                        containerColor = sideButtonContainerColor,
                                        contentColor = sideButtonContentColor,
                                    ),
                                modifier =
                                    Modifier
                                        .height(68.dp)
                                        .weight(nextButtonWeight),
                            ) {
                                PlayerNextButtonIcon(
                                    state =
                                        if (nextButtonEnabled) {
                                            nextButtonState
                                        } else {
                                            PlayerNextButtonState.DISABLED
                                        },
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PlayerHorizontalPadding),
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                ResizableIconButton(
                                    icon =
                                        when (repeatMode) {
                                            Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL -> R.drawable.repeat
                                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                            else -> throw IllegalStateException()
                                        },
                                    color = TextBackgroundColor,
                                    modifier =
                                        Modifier
                                            .size(32.dp)
                                            .padding(4.dp)
                                            .align(Alignment.Center)
                                            .alpha(if (isListenTogetherGuest) 0.5f else 1f),
                                    enabled = !isListenTogetherGuest,
                                    onClick = {
                                        playerConnection.player.toggleRepeatMode()
                                    },
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                ResizableIconButton(
                                    icon = R.drawable.skip_previous,
                                    enabled = (canSkipPrevious || localSimilarPlaybackMode) && !isListenTogetherGuest,
                                    color = TextBackgroundColor,
                                    modifier =
                                        Modifier
                                            .size(32.dp)
                                            .align(Alignment.Center)
                                            .alpha(if (isListenTogetherGuest) 0.5f else 1f),
                                    onClick = playerConnection::seekToPrevious,
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            Box(
                                modifier =
                                    Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(playPauseRoundness))
                                        .background(textButtonColor)
                                        .clickable {
                                            if (isListenTogetherGuest) {
                                                playerConnection.toggleMute()
                                                return@clickable
                                            }
                                            if (isCasting) {
                                                if (castIsPlaying) {
                                                    castHandler?.pause()
                                                } else {
                                                    castHandler?.play()
                                                }
                                            } else if (playbackState == STATE_ENDED) {
                                                playerConnection.player.seekTo(0, 0)
                                                playerConnection.player.playWhenReady = true
                                            } else {
                                                playerConnection.player.togglePlayPause()
                                            }
                                        }
                                        .focusRequester(focusRequester),
                            ) {
                                Image(
                                    painter =
                                        painterResource(
                                            if (isListenTogetherGuest) {
                                                if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                                            } else if (playbackState ==
                                                STATE_ENDED
                                            ) {
                                                R.drawable.replay
                                            } else if (effectiveIsPlaying) {
                                                R.drawable.pause
                                            } else {
                                                R.drawable.play
                                            },
                                        ),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(iconButtonColor),
                                    modifier =
                                        Modifier
                                            .align(Alignment.Center)
                                            .size(36.dp),
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            Box(modifier = Modifier.weight(1f)) {
                                PlayerNextSmallButton(
                                    state =
                                        if (nextButtonEnabled) {
                                            nextButtonState
                                        } else {
                                            PlayerNextButtonState.DISABLED
                                        },
                                    enabled = nextButtonEnabled,
                                    color = TextBackgroundColor,
                                    modifier =
                                        Modifier
                                            .size(32.dp)
                                            .align(Alignment.Center)
                                            .alpha(if (isListenTogetherGuest) 0.5f else 1f),
                                    onClick = playerConnection::seekToNext,
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                // For episodes, show saved state (inLibrary); for songs, show liked state
                                val isEpisode = currentSong?.song?.isEpisode == true
                                val isFavorite = if (isEpisode) currentSong?.song?.inLibrary != null else currentSong?.song?.liked == true
                                ResizableIconButton(
                                    icon = if (isFavorite) R.drawable.favorite else R.drawable.favorite_border,
                                    color = if (isFavorite) MaterialTheme.colorScheme.error else TextBackgroundColor,
                                    modifier =
                                        Modifier
                                            .size(32.dp)
                                            .padding(4.dp)
                                            .align(Alignment.Center),
                                    onClick = playerConnection::toggleLike,
                                )
                            }
                        }
                    }
                }
            }
        }

        when (LocalConfiguration.current.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                // Calculate vertical padding like OuterTune
                val density = LocalDensity.current
                val verticalPadding =
                    max(
                        WindowInsets.systemBars.getTop(density),
                        WindowInsets.systemBars.getBottom(density),
                    )
                val verticalPaddingDp = with(density) { verticalPadding.toDp() }
                val verticalWindowInsets = WindowInsets(left = 0.dp, top = verticalPaddingDp, right = 0.dp, bottom = verticalPaddingDp)

                Row(
                    modifier =
                        Modifier
                            .windowInsetsPadding(
                                WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).add(verticalWindowInsets),
                            ).padding(bottom = 24.dp)
                            .fillMaxSize(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .nestedScroll(state.preUpPostDownNestedScrollConnection),
                    ) {
                        // Remember lambdas to prevent unnecessary recomposition
                        val currentSliderPosition by rememberUpdatedState(sliderPosition)
                        val sliderPositionProvider = remember { { currentSliderPosition } }
                        val isExpandedProvider = remember(state) { { state.isExpanded } }
                        AnimatedContent(
                            targetState = showInlineLyrics,
                            label = "Lyrics",
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                        ) { showLyrics ->
                            if (showLyrics) {
                                InlineLyricsView(
                                    mediaMetadata = mediaMetadata,
                                    showLyrics = showLyrics,
                                    positionProvider = { effectivePosition },
                                )
                            } else {
                                Thumbnail(
                                    sliderPositionProvider = sliderPositionProvider,
                                    modifier = Modifier.animateContentSize(),
                                    isPlayerExpanded = isExpandedProvider,
                                    onTap = {
                                        if (mediaMetadata != null) {
                                            showCoverEmotionRadar = !showCoverEmotionRadar
                                            coverEmotionRadarVisible = false
                                        }
                                    },
                                    onLongPress = startCoverAnalysisFromRadar,
                                    onScrollStart = { coverEmotionRadarVisible = false },
                                    isLandscape = true,
                                    isListenTogetherGuest = isListenTogetherGuest,
                                    analysis = nowPlayingAnalysis,
                                    coverOverlay =
                                        mediaMetadata?.let {
                                            {
                                                androidx.compose.animation.AnimatedVisibility(
                                                    visible = coverEmotionRadarVisible,
                                                    enter = fadeIn(animationSpec = tween(220)),
                                                    exit = fadeOut(animationSpec = tween(180)),
                                                ) {
                                                        PlayerCoverAnalysisOverlay(
                                                            localMusic = coverRadarLocalMusic,
                                                            analysisState = coverAnalysisState,
                                                            downloadAnalyzeInProgress = coverAnalysisBusy,
                                                            modifier = Modifier.fillMaxSize(),
                                                        )
                                                }
                                            }
                                        },
                                )
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .weight(if (showInlineLyrics) 0.65f else 1f, false)
                                .animateContentSize()
                                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
                    ) {
                        Spacer(Modifier.weight(1f))

                        mediaMetadata?.let {
                            controlsContent(it)
                        }

                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            else -> {
                val bottomPadding by animateDpAsState(
                    targetValue = if (isFullScreen) 0.dp else queueSheetState.collapsedBound,
                    label = "bottomPadding",
                )
                val playerPagerState = rememberPagerState(pageCount = { 2 })
                val localSimilarListState = rememberLazyListState()
                val playerPageCollapseConnection =
                    remember(state, playerPagerState) {
                        object : NestedScrollConnection {
                            private var collapsingPlayer = false

                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource,
                            ): Offset {
                                val shouldCollapse =
                                    shouldCollapsePlayerFromPagerDownDrag(
                                        currentPage = playerPagerState.currentPage,
                                        currentPageOffsetFraction = playerPagerState.currentPageOffsetFraction,
                                        availableY = available.y,
                                        isUserInput = source == NestedScrollSource.UserInput,
                                    ) && consumed.y == 0f

                                return if (shouldCollapse) {
                                    collapsingPlayer = true
                                    state.dispatchRawDelta(available.y)
                                    available.copy(x = 0f)
                                } else {
                                    Offset.Zero
                                }
                            }

                            override suspend fun onPreFling(available: Velocity): Velocity {
                                return if (collapsingPlayer) {
                                    state.performFling(-available.y, null)
                                    available.copy(x = 0f)
                                } else {
                                    Velocity.Zero
                                }
                            }

                            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                collapsingPlayer = false
                                return Velocity.Zero
                            }
                        }
                    }
                VerticalPager(
                    state = playerPagerState,
                    flingBehavior =
                        PagerDefaults.flingBehavior(
                            state = playerPagerState,
                            snapPositionalThreshold = 0.12f,
                        ),
                    modifier =
                        Modifier
                            .nestedScroll(playerPageCollapseConnection)
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                            .padding(bottom = bottomPadding)
                            .bottomContentFade(32.dp)
                            .animateContentSize(),
                ) { page ->
                    when (page) {
                        0 -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    // Remember lambdas to prevent unnecessary recomposition
                                    val currentSliderPosition by rememberUpdatedState(sliderPosition)
                                    val sliderPositionProvider = remember { { currentSliderPosition } }
                                    val isExpandedProvider = remember(state) { { state.isExpanded } }
                                    AnimatedContent(
                                        targetState = showInlineLyrics,
                                        label = "Lyrics",
                                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                                    ) { showLyrics ->
                                        if (showLyrics) {
                                            InlineLyricsView(
                                                mediaMetadata = mediaMetadata,
                                                showLyrics = showLyrics,
                                                positionProvider = { effectivePosition },
                                            )
                                        } else {
                                            Thumbnail(
                                                sliderPositionProvider = sliderPositionProvider,
                                                modifier = Modifier.nestedScroll(state.preUpPostDownNestedScrollConnection),
                                                isPlayerExpanded = isExpandedProvider,
                                                onTap = {
                                                    if (mediaMetadata != null) {
                                                        showCoverEmotionRadar = !showCoverEmotionRadar
                                                        coverEmotionRadarVisible = false
                                                    }
                                                },
                                                onLongPress = startCoverAnalysisFromRadar,
                                                onScrollStart = { coverEmotionRadarVisible = false },
                                                isListenTogetherGuest = isListenTogetherGuest,
                                                analysis = nowPlayingAnalysis,
                                                coverOverlay =
                                                    mediaMetadata?.let {
                                                        {
                                                            androidx.compose.animation.AnimatedVisibility(
                                                                visible = coverEmotionRadarVisible,
                                                                enter = fadeIn(animationSpec = tween(220)),
                                                                exit = fadeOut(animationSpec = tween(180)),
                                                            ) {
                                                                PlayerCoverAnalysisOverlay(
                                                                    localMusic = coverRadarLocalMusic,
                                                                    analysisState = coverAnalysisState,
                                                                    downloadAnalyzeInProgress = coverAnalysisBusy,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                )
                                                            }
                                                        }
                                                    },
                                            )
                                        }
                                    }
                                }

                                mediaMetadata?.let {
                                    controlsContent(it)
                                }

                                Spacer(Modifier.height(30.dp))
                            }
                        }

                        else -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Spacer(
                                    Modifier.height(
                                        WindowInsets.systemBars.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding(),
                                    ),
                                )
                                MiniPlayer(
                                    positionState = positionState,
                                    durationState = durationState,
                                    navController = navController,
                                    forceTransparentBackground = true,
                                    onClick = {
                                        scope.launch {
                                            playerPagerState.animateScrollToPage(0)
                                        }
                                    },
                                )
                                PlayerLocalSimilarScreen(
                                    currentSongId = mediaMetadata?.id,
                                    currentLocalMusic = currentLocalMusic,
                                    similarSongs = localSimilarSongs,
                                    emptyText = localSimilarEmptyText,
                                    showNetworkAnalysisAction = !currentLocalRecommendationSource && mediaMetadata != null,
                                    listState = localSimilarListState,
                                    isPlaying = effectiveIsPlaying,
                                    chipColor = TextBackgroundColor,
                                    onRequestCoverAnalysis = {
                                        showCoverEmotionRadar = true
                                        coverEmotionRadarVisible = false
                                        scope.launch {
                                            playerPagerState.animateScrollToPage(0)
                                            delay(90)
                                            coverEmotionRadarVisible = true
                                        }
                                    },
                                    onSongClick = { index ->
                                        val recommendations = localSimilarSongs.orEmpty()
                                        if (index in recommendations.indices) {
                                            scope.launch {
                                                localSimilarListState.scrollToItem(0)
                                                playerPagerState.animateScrollToPage(0)
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = "相似本地音乐",
                                                        items = recommendations.map { it.song.song.toMediaItem(it.song.localMusic.contentUri) },
                                                        startIndex = index,
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).topContentFade(32.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !isFullScreen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            Queue(
                state = queueSheetState,
                playerBottomSheetState = state,
                navController = navController,
                background =
                    if (useBlackBackground) {
                        Color.Black
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                onBackgroundColor = onBackgroundColor,
                TextBackgroundColor = TextBackgroundColor,
                textButtonColor = textButtonColor,
                iconButtonColor = iconButtonColor,
                pureBlack = pureBlack,
                showInlineLyrics = showInlineLyrics,
                playerBackground = playerBackground,
                onToggleLyrics = {
                    showInlineLyrics = !showInlineLyrics
                },
            )
        }
    }
}

private fun Modifier.bottomContentFade(fadeHeight: Dp): Modifier =
    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        val fadePx = fadeHeight.toPx().coerceAtMost(size.height)
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - fadePx,
                    endY = size.height,
                ),
            blendMode = BlendMode.DstIn,
        )
    }

private fun Modifier.topContentFade(fadeHeight: Dp): Modifier =
    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        val fadePx = fadeHeight.toPx().coerceAtMost(size.height)
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = fadePx,
                ),
            blendMode = BlendMode.DstIn,
        )
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerLocalSimilarScreen(
    currentSongId: String?,
    currentLocalMusic: LocalMusicEntity?,
    similarSongs: List<PlayerLocalRecommendation>?,
    emptyText: String,
    showNetworkAnalysisAction: Boolean,
    listState: LazyListState,
    isPlaying: Boolean,
    chipColor: Color,
    onRequestCoverAnalysis: () -> Unit,
    onSongClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recommendations = similarSongs.orEmpty()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = 0.dp,
                bottom = 112.dp,
            ),
    ) {
        item(key = "similar_title") {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(
                    text = "相似本地音乐",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "按 BPM、调性和 7 维情绪向量匹配",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (similarSongs == null) {
            item(key = "similar_loading") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
                ) {
                    ContainedLoadingIndicator()
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        } else if (recommendations.isEmpty()) {
            item(key = "similar_empty") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
                ) {
                    if (showNetworkAnalysisAction) {
                        PlayerLocalSimilarNetworkAnalysisPrompt(
                            text = emptyText,
                            onRequestCoverAnalysis = onRequestCoverAnalysis,
                        )
                    } else {
                        Text(
                            text = emptyText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        itemsIndexed(
            items = recommendations,
            key = { _, item -> "player_similar_${item.song.localMusic.songId}" },
        ) { index, recommendation ->
            PlayerLocalMusicCard(
                recommendation = recommendation,
                currentLocalMusic = currentLocalMusic,
                isActive = recommendation.song.localMusic.songId == currentSongId,
                isPlaying = isPlaying && recommendation.song.localMusic.songId == currentSongId,
                chipColor = chipColor,
                onClick = { onSongClick(index) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "similar_bottom_space") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun PlayerLocalSimilarNetworkAnalysisPrompt(
    text: String,
    onRequestCoverAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRequestCoverAnalysis,
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            modifier = Modifier.padding(top = 18.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.advanced_search),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = "去下载并分析", maxLines = 1)
        }
    }
}

@Composable
private fun PlayerLocalMusicCard(
    recommendation: PlayerLocalRecommendation,
    currentLocalMusic: LocalMusicEntity?,
    isActive: Boolean,
    isPlaying: Boolean,
    chipColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val localSong = recommendation.song
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .height(84.dp)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(model = localSong.song.song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (isPlaying) {
                Icon(painter = painterResource(R.drawable.play), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
        }
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
            Text(text = localSong.song.song.title, style = MaterialTheme.typography.bodyMedium, color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = localSong.playerArtistLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                PlayerLocalMusicChip("${recommendation.similarityPercent}%", chipColor)
                localSong.localMusic.bpm?.let { PlayerLocalMusicChip(it.bpmChipLabel(currentLocalMusic?.bpm), chipColor) }
                localSong.localMusic.keyName?.academicKeyLabel()?.let { PlayerLocalMusicChip(it, chipColor) }
                localSong.localMusic.keyName?.camelotChipLabel(currentLocalMusic?.keyName)?.let { PlayerLocalMusicChip(it, chipColor) }
            }
        }
        PlayerEmotionRadar(current = currentLocalMusic, recommended = localSong.localMusic, modifier = Modifier.size(64.dp))
    }
}

@Composable
private fun PlayerCoverEmotionRadar(localMusic: LocalMusicEntity, modifier: Modifier = Modifier) {
    val metrics = localMusic.emotionVector().normalizedEmotionMetrics()
    var revealStarted by remember(localMusic.songId) { mutableStateOf(false) }
    LaunchedEffect(localMusic.songId) {
        revealStarted = true
    }
    val revealProgress by animateFloatAsState(
        targetValue = if (revealStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 560),
        label = "coverEmotionRadarReveal",
    )
    val animatedMetrics = metrics.map { it * revealProgress }
    val gridColor = Color.White.copy(alpha = 0.24f)
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    val strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
    val labelColor = Color.White.copy(alpha = 0.92f)

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.42f)),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
        ) {
            val sides = 7
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * 0.38f
            val labelPaint =
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = labelColor.toArgb()
                    textSize = 10.5.sp.toPx()
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                }

            fun point(index: Int, value: Float): Offset {
                val angle = (-PI / 2.0 + index * 2.0 * PI / sides).toFloat()
                return Offset(
                    x = center.x + cos(angle) * radius * value,
                    y = center.y + sin(angle) * radius * value,
                )
            }

            fun labelAnchor(index: Int, animatedValue: Float): Offset {
                val awayFromCenter = (animatedValue + 0.18f).coerceIn(0.22f, 1.08f)
                return point(index, awayFromCenter)
            }

            fun pathFor(values: List<Float>): Path {
                val path = Path()
                repeat(sides) { index ->
                    val p = point(index, values.getOrElse(index) { 0f })
                    if (index == 0) {
                        path.moveTo(p.x, p.y)
                    } else {
                        path.lineTo(p.x, p.y)
                    }
                }
                path.close()
                return path
            }

            for (ring in 1..5) {
                val path = Path()
                repeat(sides) { index ->
                    val p = point(index, ring / 5f)
                    if (index == 0) {
                        path.moveTo(p.x, p.y)
                    } else {
                        path.lineTo(p.x, p.y)
                    }
                }
                path.close()
                drawPath(path, color = gridColor, style = Stroke(width = 0.8.dp.toPx()))
            }

            repeat(sides) { index ->
                drawLine(
                    color = gridColor,
                    start = center,
                    end = point(index, 1f),
                    strokeWidth = 0.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            val radarPath = pathFor(animatedMetrics)
            drawPath(radarPath, color = fillColor)
            drawPath(radarPath, color = strokeColor, style = Stroke(width = 2.dp.toPx()))
            animatedMetrics.forEachIndexed { index, value ->
                val point = point(index, value)
                drawCircle(
                    color = strokeColor,
                    radius = 3.2.dp.toPx(),
                    center = point,
                )
                val rawValue = metrics.getOrElse(index) { 0f }
                val label = coverEmotionMetricLabels.getOrElse(index) { "" }
                val textPoint = labelAnchor(index, value)
                labelPaint.textAlign =
                    when {
                        textPoint.x < center.x - 6.dp.toPx() -> android.graphics.Paint.Align.RIGHT
                        textPoint.x > center.x + 6.dp.toPx() -> android.graphics.Paint.Align.LEFT
                        else -> android.graphics.Paint.Align.CENTER
                    }
                drawContext.canvas.nativeCanvas.drawText(
                    "$label ${rawValue.normalizedMetricText()}",
                    textPoint.x,
                    textPoint.y + 3.5.dp.toPx(),
                    labelPaint,
                )
            }
        }
    }
}

private val coverEmotionMetricLabels = listOf("Val", "Eng", "Dan", "Aco", "Ins", "Live", "Sp")
private val pendingCoverMetricLabels = coverEmotionMetricLabels

@Composable
private fun PlayerEmotionRadar(current: LocalMusicEntity?, recommended: LocalMusicEntity, modifier: Modifier = Modifier) {
    val currentRawMetrics = current?.emotionVector().orEmpty()
    val recommendedRawMetrics = recommended.emotionVector()
    val hasCurrentMetrics = currentRawMetrics.any { it != null }
    val hasRecommendedMetrics = recommendedRawMetrics.any { it != null }
    val currentMetrics = currentRawMetrics.normalizedEmotionMetrics()
    val recommendedMetrics = recommendedRawMetrics.normalizedEmotionMetrics()
    val sides = maxOf(currentMetrics.size, recommendedMetrics.size, 7)
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val recommendedColor = MaterialTheme.colorScheme.primary
    val currentFillColor = MaterialTheme.colorScheme.tertiary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        if (hasRecommendedMetrics) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = min(size.width, size.height) * 0.39f
                fun point(index: Int, value: Float): Offset {
                    val angle = (-PI / 2.0 + index * 2.0 * PI / sides).toFloat()
                    return Offset(center.x + cos(angle) * radius * value, center.y + sin(angle) * radius * value)
                }
                fun pathFor(values: List<Float>): Path {
                    val path = Path()
                    repeat(sides) { index ->
                        val p = point(index, values.getOrElse(index) { 0f })
                        if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    return path
                }
                for (ring in 1..5) {
                    val path = Path()
                    repeat(sides) { index ->
                        val p = point(index, ring / 5f)
                        if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(path, color = lineColor, style = Stroke(width = 0.55.dp.toPx()))
                }
                repeat(sides) { index -> drawLine(color = lineColor, start = center, end = point(index, 1f), strokeWidth = 0.55.dp.toPx(), cap = StrokeCap.Round) }
                if (hasCurrentMetrics && currentMetrics.isNotEmpty()) {
                    drawPath(pathFor(currentMetrics), color = currentFillColor.copy(alpha = 0.42f))
                }
                if (recommendedMetrics.isNotEmpty()) {
                    drawPath(pathFor(recommendedMetrics), color = recommendedColor, style = Stroke(width = 1.15.dp.toPx()))
                    recommendedMetrics.forEachIndexed { index, value -> drawCircle(color = recommendedColor, radius = 1.45.dp.toPx(), center = point(index, value)) }
                }
            }
        } else {
            Text(text = "待分析", style = MaterialTheme.typography.labelSmall, color = mutedColor)
        }
    }
}

@Composable
private fun PlayerNextSmallButton(
    state: PlayerNextButtonState,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clickable(
                    enabled = enabled,
                    onClick = onClick,
                )
                .alpha(if (enabled) 1f else 0.5f),
    ) {
        PlayerNextButtonIcon(
            state = state,
            color = color,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PlayerNextButtonIcon(
    state: PlayerNextButtonState,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            fadeIn(animationSpec = tween(120)) togetherWith fadeOut(animationSpec = tween(90))
        },
        label = "nextButtonState",
        modifier = modifier,
    ) { targetState ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (targetState == PlayerNextButtonState.LOADING) {
                CircularProgressIndicator(
                    color = color,
                    strokeWidth = 2.dp,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(5.dp),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.skip_next),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PlayerLocalMusicChip(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            lineHeight = 10.sp,
        ),
        color = color.copy(alpha = 0.8f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.15f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun PlayerCoverAnalysisOverlay(
    localMusic: LocalMusicEntity?,
    analysisState: LocalMusicAnalysisState?,
    downloadAnalyzeInProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    val shouldShowPendingAnalysis = downloadAnalyzeInProgress || localMusic == null
    if (shouldShowPendingAnalysis) {
        PlayerCoverPendingAnalysisOverlay(
            analysisState = analysisState,
            downloadAnalyzeInProgress = downloadAnalyzeInProgress,
            modifier = modifier,
        )
    } else {
        PlayerCoverEmotionRadar(localMusic = localMusic, modifier = modifier)
    }
}

@Composable
private fun PlayerCoverPendingAnalysisOverlay(
    analysisState: LocalMusicAnalysisState?,
    downloadAnalyzeInProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    val isAnalyzing =
        downloadAnalyzeInProgress ||
            analysisState?.status == LocalMusicAnalysisStatus.Queued ||
            analysisState?.status == LocalMusicAnalysisStatus.Running
    val progress = analysisState?.progress?.coerceIn(0f, 1f) ?: if (downloadAnalyzeInProgress) 0.08f else null
    var targetSnapshot by remember { mutableStateOf(PendingCoverAnalysisSnapshot.placeholder()) }

    LaunchedEffect(isAnalyzing) {
        if (isAnalyzing) {
            while (isActive) {
                targetSnapshot = PendingCoverAnalysisSnapshot.random()
                delay(760)
            }
        } else {
            targetSnapshot = PendingCoverAnalysisSnapshot.placeholder()
        }
    }

    val animatedMetrics = pendingCoverMetricLabels.mapIndexed { index, _ ->
        val animatedMetric by animateFloatAsState(
            targetValue = targetSnapshot.metrics.getOrElse(index) { 0f },
            animationSpec = tween(durationMillis = 620),
            label = "pendingCoverMetric$index",
        )
        animatedMetric
    }
    val animatedBpm by animateFloatAsState(
        targetValue = targetSnapshot.bpm.toFloat(),
        animationSpec = tween(durationMillis = 620),
        label = "pendingCoverBpm",
    )
    val animatedSnapshot = targetSnapshot.copy(metrics = animatedMetrics, bpm = animatedBpm.roundToInt())
    val gridColor = Color.White.copy(alpha = 0.22f)
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isAnalyzing) 0.28f else 0.14f)
    val strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isAnalyzing) 0.76f else 0.42f)
    val labelColor = Color.White.copy(alpha = 0.92f)
    val statusText =
        when {
            isAnalyzing -> analysisState?.message ?: "准备分析"
            analysisState?.status == LocalMusicAnalysisStatus.Failed -> "分析失败"
            else -> "待分析"
        }
    val hintText = if (isAnalyzing) "正在生成情绪值" else "长按下载并分析"
    val emotionLabels =
        if (isAnalyzing) {
            animatedSnapshot.emotionLabels
        } else {
            coverEmotionMetricLabels.map { "$it --" }
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(Color.Black.copy(alpha = 0.42f)),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(26.dp),
        ) {
            val sides = 7
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * 0.36f

            fun point(index: Int, value: Float): Offset {
                val angle = (-PI / 2.0 + index * 2.0 * PI / sides).toFloat()
                return Offset(
                    x = center.x + cos(angle) * radius * value,
                    y = center.y + sin(angle) * radius * value,
                )
            }

            fun pathFor(values: List<Float>): Path {
                val path = Path()
                repeat(sides) { index ->
                    val p = point(index, values.getOrElse(index) { 0f })
                    if (index == 0) {
                        path.moveTo(p.x, p.y)
                    } else {
                        path.lineTo(p.x, p.y)
                    }
                }
                path.close()
                return path
            }

            for (ring in 1..5) {
                drawPath(
                    path = pathFor(List(sides) { ring / 5f }),
                    color = gridColor,
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
            repeat(sides) { index ->
                drawLine(
                    color = gridColor,
                    start = center,
                    end = point(index, 1f),
                    strokeWidth = 0.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            val radarPath = pathFor(animatedSnapshot.metrics)
            drawPath(radarPath, color = fillColor)
            drawPath(radarPath, color = strokeColor, style = Stroke(width = 1.8.dp.toPx()))
            animatedSnapshot.metrics.forEachIndexed { index, value ->
                drawCircle(
                    color = strokeColor,
                    radius = 2.8.dp.toPx(),
                    center = point(index, value),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
        ) {
            PlayerCoverAnalysisChip(if (isAnalyzing) "${animatedSnapshot.bpm} BPM" else "BPM --", labelColor)
            PlayerCoverAnalysisChip(if (isAnalyzing) targetSnapshot.key else "Key --", labelColor)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.advanced_search),
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = hintText,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor.copy(alpha = 0.76f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            progress?.let {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { it.coerceIn(0f, 1f) },
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.86f),
                    trackColor = Color.White.copy(alpha = 0.18f),
                    modifier = Modifier.fillMaxWidth(0.58f),
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
        ) {
            emotionLabels.chunked(4).forEach { rowLabels ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    rowLabels.forEach { label ->
                        PlayerCoverAnalysisChip(label, labelColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerCoverAnalysisChip(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            lineHeight = 10.sp,
        ),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.13f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

private data class PendingCoverAnalysisSnapshot(
    val metrics: List<Float>,
    val bpm: Int,
    val key: String,
) {
    val emotionLabels: List<String>
        get() =
            coverEmotionMetricLabels.mapIndexed { index, label ->
                "$label ${metrics.getOrElse(index) { 0f }.normalizedMetricText()}"
            }

    companion object {
        fun placeholder(): PendingCoverAnalysisSnapshot =
            PendingCoverAnalysisSnapshot(
                metrics = List(7) { 0.16f },
                bpm = 0,
                key = "Key --",
            )

        fun random(): PendingCoverAnalysisSnapshot =
            PendingCoverAnalysisSnapshot(
                metrics = List(7) { Random.nextFloat().coerceIn(0f, 1f) },
                bpm = Random.nextInt(68, 178),
                key = pendingCoverKeys.random(),
            )
    }
}

private val pendingCoverKeys =
    listOf("1A", "2A", "3A", "4A", "5A", "6A", "7A", "8A", "9A", "10A", "11A", "12A", "1B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B", "10B", "11B", "12B")

private data class PlayerLocalRecommendation(val song: LocalSong, val similarityPercent: Int)

internal fun isCurrentLocalRecommendationSource(
    isLocalMetadata: Boolean,
    currentLocalMusic: LocalMusicEntity?,
): Boolean =
    isLocalMetadata || (currentLocalMusic != null && currentLocalMusic.missingSince == null)

private fun localRecommendationEmptyText(
    currentSongId: String?,
    isCurrentLocal: Boolean,
    current: LocalMusicEntity?,
    candidates: List<LocalSong>?,
): String =
    when {
        currentSongId == null -> "正在读取当前播放信息"
        !isCurrentLocal -> "当前为网络音乐，需要下载并分析后才能进行向量匹配"
        current == null -> "正在读取当前本地音乐信息"
        candidates == null -> "正在加载本地音乐分析数据"
        !current.hasCompleteEmotionVector() -> "当前歌曲缺少完整情绪值，无法计算相似音乐"
        current.bpm?.takeIf { it > 0f } == null -> "当前歌曲缺少 BPM，无法计算相似音乐"
        candidates.none { it.localMusic.songId != currentSongId } -> "本地库中还没有其他已分析歌曲"
        else -> "暂无匹配的本地推荐音乐"
    }

private fun recommendedLocalSongs(currentSongId: String?, current: LocalMusicEntity?, songs: List<LocalSong>): List<PlayerLocalRecommendation> {
    if (currentSongId == null || current == null) return emptyList()
    val songsById = songs.associateBy { it.localMusic.songId }
    return LocalSimilarSongSelector.recommendations(
        current = current.toLocalSimilarSongAnalysis(),
        candidates = songs.map { it.localMusic.toLocalSimilarSongAnalysis() },
    ).mapNotNull { recommendation ->
        songsById[recommendation.song.songId]?.let { song ->
            PlayerLocalRecommendation(
                song = song,
                similarityPercent = recommendation.similarityPercent,
            )
        }
    }
}

private fun LocalMusicEntity.emotionVector(): List<Float?> = listOf(valence, energy, danceability, acousticness, instrumentalness, liveness, speechiness)
private fun LocalMusicEntity.hasCompleteEmotionVector(): Boolean = emotionVector().all { it != null }
private fun List<Float?>.normalizedEmotionMetrics(): List<Float> = map { value -> value?.let(::normalizeMetric) ?: 0f }
private fun normalizeMetric(value: Float): Float = if (value > 1f) (value / 100f).coerceIn(0f, 1f) else value.coerceIn(0f, 1f)
private fun LocalSong.playerArtistLine(): String = joinByBullet(song.orderedArtists.joinToString { it.name }, song.album?.title).ifBlank { "-" }
private fun String.cleanDisplayText(): String? = replace("\uFFFD", "").replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), "").trim().takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
private fun LocalMusicEntity.toNowPlayingAnalysis(): PlayerNowPlayingAnalysis? {
    val topLabels =
        buildList {
            bpm?.let { add("${it.roundToInt()} BPM") }
            keyName?.academicKeyLabel()?.let { add(it) }
            keyName?.camelotChipLabel(null)?.let { add(it) }
        }
    val emotionLabels =
        listOf(
            "Val" to valence,
            "Eng" to energy,
            "Dan" to danceability,
            "Aco" to acousticness,
            "Ins" to instrumentalness,
            "Live" to liveness,
            "Sp" to speechiness,
        ).mapNotNull { (label, value) ->
            value?.let { "$label ${it.normalizedMetricText()}" }
        }
    return PlayerNowPlayingAnalysis(
        topLabels = topLabels,
        emotionLabels = emotionLabels,
    ).takeIf { it.topLabels.isNotEmpty() || it.emotionLabels.isNotEmpty() }
}

private fun Float.normalizedMetricText(): String =
    String.format(Locale.US, "%.3f", normalizeMetric(this))

private fun Float.bpmChipLabel(currentBpm: Float?): String {
    val roundedBpm = roundToInt()
    val delta = currentBpm?.let { (this - it).roundToInt() }
    return if (delta != null) "$roundedBpm BPM ${delta.signedDeltaText()}" else "$roundedBpm BPM"
}

private fun String.academicKeyLabel(): String? {
    val key = cleanDisplayText() ?: return null
    val canonical = key.canonicalMusicKey() ?: return key
    return advancedKeyDisplayName(canonical) ?: canonical.toAcademicKeyName()
}

private fun String.camelotChipLabel(currentKey: String?): String? {
    val currentCode = currentKey?.canonicalMusicKey()?.camelotDisplayCode()
    val recommendedCode = canonicalMusicKey()?.camelotDisplayCode() ?: return null
    val delta = currentCode?.let { recommendedCode.number.circularDeltaFrom(it.number) }
    return if (delta != null) "${recommendedCode.label} ${delta.signedDeltaText()}" else recommendedCode.label
}

private fun String.canonicalMusicKey(): String? {
    val cleaned = cleanDisplayText() ?: return null
    val normalized = cleaned
        .replace("♯", "#")
        .replace("＃", "#")
        .replace("♭", "b")
        .replace(Regex("\\s+"), " ")
        .trim()
    val lower = normalized.lowercase()
    val camelotMatch = Regex("^([ab])(\\d{1,2})$", RegexOption.IGNORE_CASE).matchEntire(normalized)
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

private fun String.toAcademicKeyName(): String =
    this

private data class CamelotDisplayCode(val label: String, val number: Int)

private fun String.camelotDisplayCode(): CamelotDisplayCode? {
    val camelot = keyToCamelot(this) ?: return null
    return CamelotDisplayCode(label = "${camelot.first}${camelot.second}", number = camelot.first)
}

private fun Int.signedDeltaText(): String =
    when {
        this > 0 -> "+$this"
        this < 0 -> toString()
        else -> "±0"
    }

private fun Int.circularDeltaFrom(reference: Int): Int {
    var delta = this - reference
    if (delta > 6) delta -= 12
    if (delta < -6) delta += 12
    return delta
}

private fun String.compatibleCamelotKeys(): Set<String> {
    val canonical = canonicalMusicKey() ?: return emptySet()
    val camelot = keyToCamelot(canonical) ?: return emptySet()
    val num = camelot.first
    val mode = camelot.second
    return setOf(
        camelotToKey(num, mode),
        camelotToKey(num, if (mode == "A") "B" else "A"),
        camelotToKey((num % 12) + 1, mode),
        camelotToKey(((num - 2) % 12) + 1, mode),
    ).filterNotNull().toSet()
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InlineLyricsView(
    mediaMetadata: MediaMetadata?,
    showLyrics: Boolean,
    positionProvider: () -> Long,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle(initialValue = -1)
    val lyrics = remember(currentLyrics) { currentLyrics?.lyrics?.trim() }
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    var appInForeground by remember {
        mutableStateOf(
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
    }
    DisposableEffect(Unit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer =
            LifecycleEventObserver { _, _ ->
                appInForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val nextMetadata =
        remember(queueWindows, currentWindowIndex) {
            if (currentWindowIndex >= 0 && currentWindowIndex + 1 < queueWindows.size) {
                queueWindows[currentWindowIndex + 1].mediaItem.metadata
            } else {
                null
            }
        }

    LaunchedEffect(mediaMetadata?.id, currentLyrics) {
        if (mediaMetadata != null && currentLyrics == null) {
            delay(500)
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val entryPoint =
                        EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            com.metrolist.music.di.LyricsHelperEntryPoint::class.java,
                        )
                    val lyricsHelper = entryPoint.lyricsHelper()
                    val fetchedLyricsWithProvider = lyricsHelper.getLyrics(mediaMetadata)
                    database.query {
                        upsert(LyricsEntity(mediaMetadata.id, fetchedLyricsWithProvider.lyrics, fetchedLyricsWithProvider.provider))
                    }
                } catch (e: Exception) {
                    // Handle error
                }
            }
        }
    }

    // Prefetch lyrics for the next queue item only while the lyrics pane is visible, the app is in the
    // foreground, and the current track's lyrics row has finished loading (avoids competing with the
    // active fetch).
    LaunchedEffect(
        nextMetadata?.id,
        showLyrics,
        appInForeground,
        mediaMetadata?.id,
        currentLyrics,
    ) {
        if (!showLyrics || !appInForeground || nextMetadata == null) return@LaunchedEffect
        val loadedForCurrent =
            currentLyrics?.let { lyrics ->
                mediaMetadata == null || lyrics.id == mediaMetadata.id
            } == true
        if (mediaMetadata != null && !loadedForCurrent) return@LaunchedEffect
        val nextId = nextMetadata.id
        delay(400)
        if (!showLyrics || !appInForeground || !isActive) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val existing = database.lyrics(nextId).first()
                if (existing != null) return@withContext
                val entryPoint =
                    EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        com.metrolist.music.di.LyricsHelperEntryPoint::class.java,
                    )
                val lyricsHelper = entryPoint.lyricsHelper()
                val fetched = lyricsHelper.getLyrics(nextMetadata)
                database.query {
                    upsert(LyricsEntity(nextId, fetched.lyrics, fetched.provider))
                }
            } catch (_: Exception) {
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            lyrics == null -> {
                ContainedLoadingIndicator()
            }

            lyrics == LyricsEntity.LYRICS_NOT_FOUND -> {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                val lyricsContent: @Composable () -> Unit = {
                    Lyrics(
                        sliderPositionProvider = positionProvider,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        showLyrics = showLyrics,
                    )
                }
                ProvideTextStyle(
                    value =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        ),
                ) {
                    lyricsContent()
                }
            }
        }
    }
}

@Composable
fun MoreActionsButton(
    mediaMetadata: MediaMetadata,
    navController: NavController,
    state: BottomSheetState,
    textButtonColor: Color,
    iconButtonColor: Color,
    onAnalysisStarted: () -> Unit = {},
) {
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current

    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(textButtonColor)
                .clickable {
                    menuState.show {
                        PlayerMenu(
                            mediaMetadata = mediaMetadata,
                            navController = navController,
                            playerBottomSheetState = state,
                            onShowDetailsDialog = {
                                bottomSheetPageState.show {
                                    ShowMediaInfo(mediaMetadata.id) {
                                        PlayerMenu(
                                            mediaMetadata = mediaMetadata,
                                            navController = navController,
                                            playerBottomSheetState = state,
                                            onShowDetailsDialog = {
                                                bottomSheetPageState.show {
                                                    ShowMediaInfo(mediaMetadata.id)
                                                }
                                            },
                                            onAnalysisStarted = onAnalysisStarted,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                }
                            },
                            onAnalysisStarted = onAnalysisStarted,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
    ) {
        Image(
            painter = painterResource(R.drawable.more_horiz),
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconButtonColor),
        )
    }
}

@Composable
private fun PlayerMoreMenuButton(
    mediaMetadata: MediaMetadata,
    navController: NavController,
    state: BottomSheetState,
    textButtonColor: Color,
    iconButtonColor: Color,
    onAnalysisStarted: () -> Unit = {},
) {
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(textButtonColor)
                .clickable {
                    menuState.show {
                        PlayerMenu(
                            mediaMetadata = mediaMetadata,
                            navController = navController,
                            playerBottomSheetState = state,
                            onShowDetailsDialog = {
                                bottomSheetPageState.show {
                                    ShowMediaInfo(mediaMetadata.id) {
                                        PlayerMenu(
                                            mediaMetadata = mediaMetadata,
                                            navController = navController,
                                            playerBottomSheetState = state,
                                            onShowDetailsDialog = {
                                                bottomSheetPageState.show {
                                                    ShowMediaInfo(mediaMetadata.id)
                                                }
                                            },
                                            onAnalysisStarted = onAnalysisStarted,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                }
                            },
                            onAnalysisStarted = onAnalysisStarted,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
    ) {
        Image(
            painter = painterResource(R.drawable.more_horiz),
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconButtonColor),
        )
    }
}

private suspend fun shareAudioFile(
    context: Context,
    mediaMetadata: MediaMetadata,
    localMusic: LocalMusicEntity?,
) {
    if (localMusic != null) {
        shareExistingAudioFile(context, mediaMetadata, localMusic)
        return
    }

    shareChinaSongMp3(context, mediaMetadata)
}

private fun shareExistingAudioFile(
    context: Context,
    mediaMetadata: MediaMetadata,
    localMusic: LocalMusicEntity,
) {
    val uri = Uri.parse(localMusic.contentUri)
    val fileName = localMusic.displayName.ifBlank { mediaMetadata.title }
    val mimeType =
        localMusic.mimeType
            ?.takeIf { it.startsWith("audio/") }
            ?: if (fileName.endsWith(".mp3", ignoreCase = true)) "audio/mpeg" else "audio/*"

    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, null))
}

private suspend fun shareChinaSongMp3(
    context: Context,
    mediaMetadata: MediaMetadata,
) {
    if (!ChinaMusicUtils.isChinaMediaId(mediaMetadata.id)) {
        Toast.makeText(context, "当前歌曲暂不支持分享 MP3 文件", Toast.LENGTH_SHORT).show()
        return
    }

    Toast.makeText(context, "正在准备 320K MP3", Toast.LENGTH_SHORT).show()

    val file = withContext(Dispatchers.IO) {
        val artist = mediaMetadata.artists.joinToString("、") { it.name }
        val musicUrl =
            ChinaMusicUtils
                .getMusicUrl(
                    mediaId = mediaMetadata.id,
                    quality = AudioQuality.HIGH,
                    title = mediaMetadata.title,
                    artist = artist,
                    durationSeconds = mediaMetadata.duration,
                ).getOrThrow()

        val shareDir = File(context.cacheDir, "shared_audio").apply { mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() }
        val fileName = "${mediaMetadata.title} - $artist".sanitizeFileName().ifBlank { mediaMetadata.id } + ".mp3"
        File(shareDir, fileName).also { target ->
            URL(musicUrl).openStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun String.sanitizeFileName(): String =
    replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(120)
