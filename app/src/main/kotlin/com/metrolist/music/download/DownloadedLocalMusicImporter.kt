package com.metrolist.music.download

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LocalMusicEntity
import com.metrolist.music.localmusic.LocalMusicSourceTrackId
import com.metrolist.music.models.MediaMetadata
import java.time.LocalDateTime

suspend fun MusicDatabase.importDownloadedLocalFile(
    mediaMetadata: MediaMetadata,
    downloadedFile: FileMusicDownloadResult,
): LocalMusicEntity {
    localMusicBySongId(mediaMetadata.id)
        ?.takeIf { it.missingSince == null }
        ?.let { return it }

    val now = LocalDateTime.now()
    val nowMillis = System.currentTimeMillis()
    var importedLocalMusic: LocalMusicEntity? = null
    withTransaction {
        val existingTargetLocalMusic = localMusicBySongId(mediaMetadata.id)
        val existingLocalByFile =
            localMusicByContentOrDocument(
                contentUri = downloadedFile.contentUri,
                treeUri = downloadedFile.treeUri,
                documentId = downloadedFile.documentId,
            )
        val identity =
            LocalMusicSourceTrackId.resolveIdentity(
                existingSongId = existingLocalByFile?.songId,
                sourceTrackId = mediaMetadata.id,
                stableSongId = mediaMetadata.id,
            )
        val rebindFromSongId = identity.rebindFromSongId
        val existingTarget = getSongById(identity.songId)?.song
        val existingRebind = rebindFromSongId?.let { getSongById(it)?.song }
        val existing = existingTarget ?: existingRebind
        val mergedTotalPlayTime =
            (existingTarget?.totalPlayTime ?: 0L) +
                (existingRebind?.totalPlayTime ?: 0L)
        val localSong =
            mediaMetadata
                .copy(
                    isLocal = true,
                    inLibrary = existing?.inLibrary ?: mediaMetadata.inLibrary ?: now,
                ).toSongEntity()
                .copy(
                    liked = existing?.liked ?: mediaMetadata.liked,
                    likedDate = existing?.likedDate ?: mediaMetadata.likedDate,
                    totalPlayTime = mergedTotalPlayTime,
                    dateDownload = existing?.dateDownload,
                    lyricsOffset = existing?.lyricsOffset ?: 0,
                    romanizeLyrics = existing?.romanizeLyrics ?: true,
                    isDownloaded = existing?.isDownloaded ?: false,
                    isCached = existing?.isCached ?: false,
                )

        if (existingTarget == null) {
            insert(mediaMetadata.copy(isLocal = true, inLibrary = localSong.inLibrary)) {
                localSong
            }
        } else {
            update(localSong)
        }

        if (rebindFromSongId != null) {
            transferPlaylistSongMaps(rebindFromSongId, identity.songId)
            if (existingRebind != null) {
                transferSongActivityReferences(rebindFromSongId, identity.songId)
            }
            deleteLocalMusicBySongId(rebindFromSongId)
            if (rebindFromSongId.startsWith("local_")) {
                deleteSongById(rebindFromSongId)
            }
        }

        val analysisSource = existingLocalByFile ?: existingTargetLocalMusic
        val localMusic =
            LocalMusicEntity(
                songId = identity.songId,
                contentUri = downloadedFile.contentUri,
                treeUri = downloadedFile.treeUri,
                documentId = downloadedFile.documentId,
                displayName = downloadedFile.fileName,
                mimeType = downloadedFile.mimeType,
                fileSize = downloadedFile.byteCount,
                dateModified = nowMillis,
                lastScannedAt = nowMillis,
                scanGeneration = nowMillis,
                missingSince = null,
                bpm = analysisSource?.bpm,
                keyName = analysisSource?.keyName,
                valence = analysisSource?.valence,
                energy = analysisSource?.energy,
                danceability = analysisSource?.danceability,
                acousticness = analysisSource?.acousticness,
                instrumentalness = analysisSource?.instrumentalness,
                liveness = analysisSource?.liveness,
                speechiness = analysisSource?.speechiness,
                moodSummary = analysisSource?.moodSummary,
                hasArtwork = mediaMetadata.thumbnailUrl != null || analysisSource?.hasArtwork == true,
            )
        upsert(localMusic)
        importedLocalMusic = localMusic
    }
    return checkNotNull(importedLocalMusic) {
        "Downloaded local music import did not produce a local music row"
    }
}
