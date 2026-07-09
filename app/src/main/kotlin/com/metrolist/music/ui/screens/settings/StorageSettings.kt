/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.EnableSongCacheKey
import com.metrolist.music.constants.FileDownloadDirectoryUriKey
import com.metrolist.music.constants.MaxImageCacheSizeKey
import com.metrolist.music.constants.MaxSongCacheSizeKey
import com.metrolist.music.download.FileDownloadLocation
import com.metrolist.music.extensions.tryOrNull
import com.metrolist.music.localmusic.LocalMusicFileSize
import com.metrolist.music.localmusic.LocalMusicScanSource
import com.metrolist.music.localmusic.LocalMusicScanSourceKind
import com.metrolist.music.localmusic.LocalMusicScanner
import com.metrolist.music.ui.component.ActionPromptDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.screens.localmusic.LocalMusicScanState
import com.metrolist.music.ui.screens.localmusic.LocalMusicViewModel
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.ui.utils.formatFileSize
import com.metrolist.music.utils.rememberPreference
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalCoilApi::class, ExperimentalMaterial3Api::class, DelicateCoilApi::class)
@Composable
fun StorageSettings(
    navController: NavController,
    localMusicViewModel: LocalMusicViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val imageDiskCache = context.imageLoader.diskCache ?: return
    val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return
    val downloadCache = LocalPlayerConnection.current?.service?.downloadCache ?: return

    val coroutineScope = rememberCoroutineScope()
    val localMusicScanner =
        remember(context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                StorageSettingsEntryPoint::class.java,
            ).localMusicScanner()
        }
    val songCacheString = stringResource(R.string.song_cache).lowercase()
    val imageCacheString = stringResource(R.string.image_cache).lowercase()
    val (maxImageCacheSize, onMaxImageCacheSizeChange) = rememberPreference(
        key = MaxImageCacheSizeKey,
        defaultValue = 512
    )
    val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(
        key = MaxSongCacheSizeKey,
        defaultValue = 1024
    )
    val (enableSongCache, onEnableSongCacheChange) = rememberPreference(
        key = EnableSongCacheKey,
        defaultValue = true
    )
    val (fileDownloadDirectoryUri, onFileDownloadDirectoryUriChange) = rememberPreference(
        key = FileDownloadDirectoryUriKey,
        defaultValue = ""
    )
    val scanSources by localMusicViewModel.scanSources.collectAsStateWithLifecycle()
    val scanState by localMusicViewModel.scanState.collectAsStateWithLifecycle()
    val localMusicMinDurationSeconds by localMusicViewModel.minDurationSeconds.collectAsStateWithLifecycle()
    val localMusicFiles by database.localMusicFiles().collectAsStateWithLifecycle(emptyList())
    val localMusicTotalBytes = remember(localMusicFiles) {
        LocalMusicFileSize.totalBytes(localMusicFiles)
    }
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure {
                Timber.tag("StorageSettings").w(it, "Could not persist download directory read/write permission")
            }
            onFileDownloadDirectoryUriChange(uri.toString())
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    localMusicScanner.scan(uri, localMusicMinDurationSeconds)
                }
            }
        }
    }
    val localMusicFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let(localMusicViewModel::selectFolder)
    }

    var clearDownloads by remember { mutableStateOf(false) }
    var clearCacheDialog by remember { mutableStateOf(false) }
    var clearImageCacheDialog by remember { mutableStateOf(false) }

    // State for the confirmation dialog
    var showCacheWarningDialog by remember { mutableStateOf(false) }
    var cacheType by remember { mutableStateOf("") }
    var cacheUsage by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var onConfirmAction by remember { mutableStateOf<() -> Unit>({}) }

    var imageCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(imageDiskCache.size)
    }
    var playerCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(tryOrNull { playerCache.cacheSpace } ?: 0)
    }
    var downloadCacheSize by remember {
        mutableLongStateOf(tryOrNull { downloadCache.cacheSpace } ?: 0)
    }
    val imageCacheProgress by animateFloatAsState(
        targetValue =
            (imageCacheSize.toFloat() / (maxImageCacheSize * 1024 * 1024L)).coerceIn(
                0f,
                1f,
            ),
        label = "imageCacheProgress",
    )
    val playerCacheProgress by animateFloatAsState(
        targetValue =
            (playerCacheSize.toFloat() / (maxSongCacheSize * 1024 * 1024L)).coerceIn(
                0f,
                1f,
            ),
        label = "playerCacheProgress",
    )

    LaunchedEffect(maxImageCacheSize) {
        SingletonImageLoader.reset()
        if (maxImageCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                imageDiskCache.clear()
            }
        }
    }
    LaunchedEffect(maxSongCacheSize) {
        if (maxSongCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                playerCache.keys.forEach { key ->
                    playerCache.removeResource(key)
                }
            }
        }
    }

    LaunchedEffect(imageDiskCache) {
        while (isActive) {
            delay(500)
            imageCacheSize = imageDiskCache.size
        }
    }
    LaunchedEffect(playerCache) {
        while (isActive) {
            delay(500)
            playerCacheSize = tryOrNull { playerCache.cacheSpace } ?: 0
        }
    }
    LaunchedEffect(downloadCache) {
        while (isActive) {
            delay(500)
            downloadCacheSize = tryOrNull { downloadCache.cacheSpace } ?: 0
        }
    }

    if (clearDownloads) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_all_downloads),
            onDismiss = { clearDownloads = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    downloadCache.keys.forEach { key ->
                        downloadCache.removeResource(key)
                    }
                }
                clearDownloads = false
            },
            onCancel = { clearDownloads = false },
            content = {
                Text(text = stringResource(R.string.clear_downloads_dialog))
            },
        )
    }
    if (clearCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_song_cache),
            onDismiss = { clearCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    playerCache.keys.forEach { key ->
                        playerCache.removeResource(key)
                    }
                }
                clearCacheDialog = false
            },
            onCancel = { clearCacheDialog = false },
            content = {
                Text(text = stringResource(R.string.clear_song_cache_dialog))
            },
        )
    }
    if (clearImageCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_image_cache),
            onDismiss = { clearImageCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    val urlsToPreserve = mutableSetOf<String>()
                    val downloadedSongs =
                        try {
                            database.offlineCachedSongsByNameAsc().first()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    downloadedSongs.forEach { song ->
                        song.song.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                        song.album?.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                    }
                    val directory = imageDiskCache.directory.toFile()
                    if (directory.exists() && directory.isDirectory) {
                        directory.listFiles()?.forEach { file ->
                            if (file.isFile && !file.name.startsWith("journal")) {
                                val isPreserved = urlsToPreserve.any { hash -> file.name.startsWith(hash) }
                                if (!isPreserved) {
                                    file.delete()
                                }
                            }
                        }
                    }
                    imageDiskCache.clear()
                }
                clearImageCacheDialog = false
            },
            onCancel = { clearImageCacheDialog = false },
            content = {
                Text(text = stringResource(R.string.clear_image_cache_dialog))
            },
        )
    }

    // Confirmation Dialog
    if (showCacheWarningDialog) {
        AlertDialog(
            onDismissRequest = { showCacheWarningDialog = false },
            title = { Text(stringResource(R.string.cache_size_warning_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.cache_size_warning_message,
                        formatFileSize(cacheUsage),
                        cacheType,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmAction()
                        showCacheWarningDialog = false
                    },
                ) {
                    Text(
                        stringResource(R.string.cache_size_warning_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCacheWarningDialog = false }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            ).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top,
                ),
            ),
        )
        Material3SettingsGroup(
            title = stringResource(R.string.storage),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.storage),
                        title = { Text(stringResource(R.string.downloaded_songs)) },
                        description = {
                            Text(text = formatFileSize(downloadCacheSize))
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.music_note),
                        title = { Text(stringResource(R.string.file_downloaded_music)) },
                        description = {
                            Text(text = formatFileSize(localMusicTotalBytes))
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_all_downloads)) },
                        onClick = {
                            clearDownloads = true
                        },
                    ),
                ),
        )

        LocalMusicStorageSettingsGroup(
            scanSources = scanSources,
            scanState = scanState,
            fileDownloadDirectoryUri = fileDownloadDirectoryUri,
            minDurationSeconds = localMusicMinDurationSeconds,
            onChooseDownloadDirectory = { directoryPickerLauncher.launch(null) },
            onChooseFolder = { localMusicFolderPickerLauncher.launch(null) },
            onRescan = localMusicViewModel::rescan,
            onRemoveFolder = localMusicViewModel::removeFolder,
            onMinDurationChange = localMusicViewModel::updateMinDurationSeconds,
        )

        Material3SettingsGroup(
            title = stringResource(R.string.song_cache),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.enable_song_cache)) },
                    description = { Text(stringResource(R.string.enable_song_cache_desc)) },
                    trailingContent = {
                        Switch(
                            checked = enableSongCache,
                            onCheckedChange = onEnableSongCacheChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (enableSongCache) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onEnableSongCacheChange(!enableSongCache) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.max_song_cache_size)) },
                    enabled = enableSongCache,
                    description = {
                        val songCacheValues =
                            remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1) }
                        Column {
                            Text(
                                text = when (maxSongCacheSize) {
                                    0 -> stringResource(R.string.disable)
                                    -1 -> stringResource(R.string.unlimited)
                                    else -> formatFileSize(maxSongCacheSize * 1024 * 1024L)
                                }
                            )
                            Slider(
                                value = songCacheValues.indexOf(maxSongCacheSize).toFloat(),
                                enabled = enableSongCache,
                                onValueChange = {
                                    val newValue = songCacheValues[it.roundToInt()]
                                    val newLimitInBytes = if (newValue == -1) {
                                        Long.MAX_VALUE
                                    } else {
                                        newValue * 1024 * 1024L
                                    }

                                        if (newLimitInBytes < playerCacheSize) {
                                            cacheUsage = playerCacheSize
                                            cacheType = songCacheString
                                            onConfirmAction = { onMaxSongCacheSizeChange(newValue) }
                                            showCacheWarningDialog = true
                                        } else {
                                            onMaxSongCacheSizeChange(newValue)
                                        }
                                    },
                                    steps = songCacheValues.size - 2,
                                    valueRange = 0f..(songCacheValues.size - 1).toFloat(),
                                )
                                LinearProgressIndicator(
                                    progress = { playerCacheProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    strokeCap = StrokeCap.Round,
                                )
                                Spacer(modifier = Modifier.padding(2.dp))
                                Text(
                                    text =
                                        if (maxSongCacheSize == -1) {
                                            formatFileSize(playerCacheSize)
                                        } else {
                                            "${formatFileSize(playerCacheSize)} / ${
                                                formatFileSize(
                                                    maxSongCacheSize * 1024 * 1024L,
                                                )
                                            }"
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_song_cache)) },
                        onClick = {
                            clearCacheDialog = true
                        },
                    ),
                ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.image_cache),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.manage_search),
                        title = { Text(stringResource(R.string.max_image_cache_size)) },
                        description = {
                            val imageCacheValues =
                                remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192) }
                            Column {
                                Text(
                                    text =
                                        when (maxImageCacheSize) {
                                            0 -> stringResource(R.string.disable)
                                            else -> formatFileSize(maxImageCacheSize * 1024 * 1024L)
                                        },
                                )
                                Slider(
                                    value = imageCacheValues.indexOf(maxImageCacheSize).toFloat(),
                                    onValueChange = {
                                        val newValue = imageCacheValues[it.roundToInt()]
                                        val newLimitInBytes = newValue * 1024 * 1024L

                                        if (newLimitInBytes < imageCacheSize) {
                                            cacheUsage = imageCacheSize
                                            cacheType = imageCacheString
                                            onConfirmAction = { onMaxImageCacheSizeChange(newValue) }
                                            showCacheWarningDialog = true
                                        } else {
                                            onMaxImageCacheSizeChange(newValue)
                                        }
                                    },
                                    steps = imageCacheValues.size - 2,
                                    valueRange = 0f..(imageCacheValues.size - 1).toFloat(),
                                )
                                LinearProgressIndicator(
                                    progress = { imageCacheProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    strokeCap = StrokeCap.Round,
                                )
                                Spacer(modifier = Modifier.padding(2.dp))
                                Text(
                                    text = "${formatFileSize(imageCacheSize)} / ${
                                        formatFileSize(
                                            maxImageCacheSize * 1024 * 1024L,
                                        )
                                    }",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_image_cache)) },
                        onClick = {
                            clearImageCacheDialog = true
                        },
                    ),
                ),
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.storage)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun LocalMusicStorageSettingsGroup(
    scanSources: List<LocalMusicScanSource>,
    scanState: LocalMusicScanState,
    fileDownloadDirectoryUri: String,
    minDurationSeconds: Int,
    onChooseDownloadDirectory: () -> Unit,
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onMinDurationChange: (Int) -> Unit,
) {
    val hasSources = scanSources.isNotEmpty()
    val userSources = scanSources.filter { it.kind == LocalMusicScanSourceKind.USER }
    val isScanning = scanState.isScanning
    var sourcesExpanded by rememberSaveable { mutableStateOf(false) }
    val visibleSources =
        if (sourcesExpanded) {
            userSources
        } else {
            userSources.take(2)
        }
    val items =
        buildList {
            add(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.download),
                    title = { Text("下载保存位置") },
                    description = {
                        Text(
                            text = FileDownloadLocation.fromDirectoryUri(fileDownloadDirectoryUri).displayPath,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = onChooseDownloadDirectory,
                ),
            )
            add(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.storage),
                    title = { Text("添加扫描文件夹") },
                    description = {
                        Column {
                            Text("添加本机文件夹作为本地音乐来源")
                            Text(
                                text = "目前已添加的文件夹",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            if (userSources.isEmpty()) {
                                Text(
                                    text = "暂无额外扫描文件夹。下载保存位置会自动参与本地扫描。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            } else {
                                visibleSources.forEach { source ->
                                    LocalMusicScanSourceRow(
                                        source = source,
                                        isScanning = isScanning,
                                        onRemoveFolder = onRemoveFolder,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                                if (userSources.size > 2) {
                                    TextButton(
                                        onClick = { sourcesExpanded = !sourcesExpanded },
                                        modifier = Modifier.padding(top = 2.dp),
                                    ) {
                                        Text(if (sourcesExpanded) "收起" else "展开全部 ${userSources.size} 条")
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isScanning,
                    onClick = onChooseFolder,
                ),
            )
            add(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.refresh),
                    title = { Text("重新扫描全部来源") },
                    description = {
                        Column {
                            Text(
                                text =
                                    when {
                                        isScanning -> stringResource(R.string.scanning_music, scanState.scannedFiles)
                                        scanState.error != null -> stringResource(R.string.local_music_scan_error, scanState.error)
                                        hasSources -> "重新扫描下载保存位置和已添加文件夹"
                                        else -> "添加扫描文件夹或设置下载保存位置后即可扫描"
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isScanning) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                                scanState.currentFile?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    },
                    enabled = hasSources && !isScanning,
                    onClick = onRescan,
                ),
            )
            add(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.tune),
                    title = { Text("扫描规则") },
                    description = {
                        Text(
                            text = "过滤 ${minDurationSeconds} 秒以下的短音频",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            MaterialIconButton(
                                onClick = { onMinDurationChange((minDurationSeconds - 5).coerceAtLeast(0)) },
                                enabled = !isScanning && minDurationSeconds > 0,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.remove),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = "${minDurationSeconds}s",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                modifier = Modifier.widthIn(min = 42.dp),
                            )
                            MaterialIconButton(
                                onClick = { onMinDurationChange((minDurationSeconds + 5).coerceAtMost(180)) },
                                enabled = !isScanning && minDurationSeconds < 180,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.add),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                ),
            )
        }

    Material3SettingsGroup(
        title = stringResource(R.string.local_music),
        items = items,
    )
}

@Composable
private fun LocalMusicScanSourceRow(
    source: LocalMusicScanSource,
    isScanning: Boolean,
    onRemoveFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.titleLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = source.displayLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (source.kind == LocalMusicScanSourceKind.USER) {
            MaterialIconButton(
                onClick = { onRemoveFolder(source.uri) },
                enabled = !isScanning,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun LocalMusicScanSource.titleLabel(): String =
    if (kind == LocalMusicScanSourceKind.DOWNLOAD) {
        "下载目录来源"
    } else {
        "扫描文件夹"
    }

private fun LocalMusicScanSource.displayLabel(): String {
    val label = FileDownloadLocation.fromDirectoryUri(uri).displayPath
    return if (kind == LocalMusicScanSourceKind.DOWNLOAD) {
        "下载目录：$label"
    } else {
        label
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface StorageSettingsEntryPoint {
    fun localMusicScanner(): LocalMusicScanner
}
