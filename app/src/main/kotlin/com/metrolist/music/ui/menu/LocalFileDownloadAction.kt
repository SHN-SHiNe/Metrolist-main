package com.metrolist.music.ui.menu

import android.content.Context
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LocalMusicEntity
import com.metrolist.music.download.FileMusicDownloader
import com.metrolist.music.download.importDownloadedLocalFile
import com.metrolist.music.localmusic.analysis.LocalMusicAnalysisManager
import com.metrolist.music.models.MediaMetadata

internal enum class LocalFileDownloadMode {
    DownloadOnly,
    DownloadAndAnalyze,
}

internal data class LocalFileDownloadActionResult(
    val localMusic: LocalMusicEntity,
    val reusedExisting: Boolean,
    val displayPath: String,
    val analysisStarted: Boolean,
)

internal suspend fun runLocalFileDownloadAction(
    context: Context,
    database: MusicDatabase,
    mediaMetadata: MediaMetadata,
    quality: FileMusicDownloader.Quality,
    mode: LocalFileDownloadMode,
    analysisManager: LocalMusicAnalysisManager?,
): Result<LocalFileDownloadActionResult> =
    runCatching {
        val existingLocalMusic =
            database
                .localMusicBySongId(mediaMetadata.id)
                ?.takeIf { it.missingSince == null }

        var downloadedDisplayPath: String? = null
        val localMusic =
            existingLocalMusic
                ?: FileMusicDownloader
                    .download(context, mediaMetadata, quality)
                    .getOrThrow()
                    .let { downloadedFile ->
                        downloadedDisplayPath = downloadedFile.displayPath
                        database.importDownloadedLocalFile(mediaMetadata, downloadedFile)
                    }

        val analysisStarted =
            if (mode == LocalFileDownloadMode.DownloadAndAnalyze && analysisManager != null) {
                analysisManager.analyze(localMusic)
                true
            } else {
                false
            }

        LocalFileDownloadActionResult(
            localMusic = localMusic,
            reusedExisting = existingLocalMusic != null,
            displayPath = downloadedDisplayPath ?: existingLocalMusic?.displayName ?: localMusic.displayName,
            analysisStarted = analysisStarted,
        )
    }
