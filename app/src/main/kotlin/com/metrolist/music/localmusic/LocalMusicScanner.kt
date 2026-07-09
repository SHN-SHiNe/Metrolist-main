/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.LocalMusicEntity
import com.metrolist.music.db.entities.LocalMusicScanSnapshot
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.utils.ArtistNameSplitter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class LocalMusicScanProgress(
    val scannedFiles: Int,
    val currentFile: String?,
)

data class LocalAudioDocument(
    val contentUri: Uri,
    val treeUri: String,
    val documentId: String,
    val displayName: String,
    val mimeType: String?,
    val size: Long?,
    val lastModified: Long?,
)

@Singleton
class LocalMusicScanner
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val tagReader: LocalMusicTagReader,
) {
    suspend fun scan(
        treeUri: Uri,
        minDurationSeconds: Int = 0,
        onProgress: suspend (LocalMusicScanProgress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val generation = System.currentTimeMillis()
        var scanned = 0
        val treeUriString = treeUri.toString()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)

        val documents = mutableListOf<LocalAudioDocument>()
        walk(treeUri, rootDocumentUri) { document ->
            documents += document
        }

        val documentsById = documents.associateBy { it.documentId }
        val existingSnapshot = database.localMusicScanSnapshot(treeUriString)
        val existingByDocumentId = existingSnapshot.associateBy { it.documentId }
        val scanPlan =
            LocalMusicScanPlanner.plan(
                currentDocuments = documents.map { it.toScanDocument() },
                existingDocuments = existingSnapshot.map { it.toExistingDocument() },
                minDurationSeconds = minDurationSeconds,
            )

        restoreReappearedUnchangedDocuments(scanPlan.unchanged, documentsById)

        val counter = AtomicInteger(scanPlan.unchanged.size)
        if (scanPlan.unchanged.isNotEmpty() || scanPlan.toImport.isEmpty()) {
            onProgress(LocalMusicScanProgress(counter.get(), null))
        }
        val scanSlots = Semaphore(SCAN_PARALLELISM)
        coroutineScope {
            scanPlan
                .toImport
                .mapNotNull { documentsById[it.documentId] }
                .map { document ->
                    async {
                        scanSlots.withPermit {
                            val current = counter.incrementAndGet()
                            onProgress(LocalMusicScanProgress(current, document.displayName))
                            val existingLocal =
                                if (document.documentId in existingByDocumentId) {
                                    database.localMusicByDocument(document.treeUri, document.documentId)
                                } else {
                                    null
                                }
                            importDocument(document, generation, minDurationSeconds, existingLocal)
                        }
                    }
                }.awaitAll()
        }
        scanned = documents.size

        database.withTransaction {
            scanPlan.missingSongIds.chunked(SQLITE_MAX_VARIABLES).forEach { songIds ->
                markMissingLocalMusicBySongIds(songIds, System.currentTimeMillis())
            }
        }
        if (scanPlan.skippedMissingBecauseCurrentScanWasEmpty) {
            Timber.tag("LocalMusicScanner").w(
                "Skipped marking local music missing because scan returned no documents for %s",
                treeUriString,
            )
        }
        onProgress(LocalMusicScanProgress(scanned, null))
        scanned
    }

    private suspend fun restoreReappearedUnchangedDocuments(
        unchangedDocuments: List<UnchangedLocalMusicDocument>,
        documentsById: Map<String, LocalAudioDocument>,
    ) {
        val reappeared =
            unchangedDocuments
                .filter { it.wasMissing }
                .mapNotNull { unchanged ->
                    documentsById[unchanged.documentId]?.let { unchanged to it }
                }
        if (reappeared.isEmpty()) return

        database.withTransaction {
            reappeared.forEach { (unchanged, document) ->
                restoreLocalMusic(
                    songId = unchanged.songId,
                    contentUri = document.contentUri.toString(),
                    mimeType = document.mimeType,
                    fileSize = document.size,
                    dateModified = document.lastModified,
                )
            }
        }
    }

    private suspend fun walk(
        treeUri: Uri,
        directoryUri: Uri,
        onAudio: suspend (LocalAudioDocument) -> Unit,
    ) {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(directoryUri),
            )
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

        val cursor =
            context.contentResolver.query(childrenUri, projection, null, null, null)
                ?: throw LocalMusicScanReadException("无法读取本地音乐目录，请重新授权扫描文件夹")

        cursor.use {
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                throw LocalMusicScanReadException("本地音乐目录返回的数据不完整，请重新授权扫描文件夹")
            }

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val displayName = cursor.getString(nameIndex).orEmpty()
                val mimeType = cursor.getStringOrNull(mimeIndex)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                if (LocalMusicScanFilter.shouldSkip(displayName, documentId)) {
                    continue
                } else if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, documentUri, onAudio)
                } else if (LocalMusicScanFilter.isSupportedPlayableAudio(displayName, mimeType)) {
                    onAudio(
                        LocalAudioDocument(
                            contentUri = documentUri,
                            treeUri = treeUri.toString(),
                            documentId = documentId,
                            displayName = displayName,
                            mimeType = mimeType,
                            size = cursor.getLongOrNull(sizeIndex),
                            lastModified = cursor.getLongOrNull(modifiedIndex),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun importDocument(
        document: LocalAudioDocument,
        generation: Long,
        minDurationSeconds: Int,
        existingLocal: LocalMusicEntity?,
    ) {
        val stableSongId = stableSongId(document.treeUri, document.documentId)
        val nowMillis = System.currentTimeMillis()
        val tags = tagReader.read(document.contentUri, document.displayName, existingLocal?.songId ?: stableSongId)
        val identity =
            LocalMusicSourceTrackId.resolveIdentity(
                existingSongId = existingLocal?.songId,
                sourceTrackId = tags.sourceTrackId,
                stableSongId = stableSongId,
        )
        val songId = identity.songId
        val existingTargetLocal = database.localMusicBySongId(songId)
        if (tags.durationSeconds in 0 until minDurationSeconds) {
            existingLocal
                ?.takeIf { it.missingSince == null }
                ?.let {
                    database.markMissingLocalMusicBySongIds(listOf(it.songId), System.currentTimeMillis())
                }
            return
        }
        val now = LocalDateTime.now()
        val dateModified =
            document.lastModified
                ?.takeIf { it > 0L }
                ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
        val existingSong = database.getSongById(songId)
        val artistNames = ArtistNameSplitter.split(tags.artist)
        val albumTitle = tags.album?.trim().orEmpty().ifBlank { null }
        val albumId = albumTitle?.let { localId("album", "$it|${artistNames.firstOrNull().orEmpty()}") }
        val rebindFromSongId = identity.rebindFromSongId
        val existingRebindSong = rebindFromSongId?.let { database.getSongById(it)?.song }
        val mergedTotalPlayTime =
            (existingSong?.song?.totalPlayTime ?: 0L) +
                (existingRebindSong?.totalPlayTime ?: 0L)
        val artistEntities =
            artistNames.map { artistName ->
                ArtistEntity(
                    id = localId("artist", artistName.lowercase()),
                    name = artistName,
                    isLocal = true,
                )
            }

        try {
            database.withTransaction {
                upsert(
                    SongEntity(
                        id = songId,
                        title = tags.title,
                        duration = tags.durationSeconds,
                        thumbnailUrl = tags.thumbnailUri,
                        albumId = albumId,
                        albumName = albumTitle,
                        dateModified = dateModified,
                        liked = existingSong?.song?.liked ?: existingRebindSong?.liked ?: false,
                        likedDate = existingSong?.song?.likedDate ?: existingRebindSong?.likedDate,
                        totalPlayTime = mergedTotalPlayTime,
                        inLibrary = existingSong?.song?.inLibrary ?: existingRebindSong?.inLibrary ?: now,
                        dateDownload = existingSong?.song?.dateDownload ?: existingRebindSong?.dateDownload,
                        isLocal = true,
                        lyricsOffset = existingSong?.song?.lyricsOffset ?: existingRebindSong?.lyricsOffset ?: 0,
                        romanizeLyrics = existingSong?.song?.romanizeLyrics ?: existingRebindSong?.romanizeLyrics ?: true,
                        isDownloaded = existingSong?.song?.isDownloaded ?: existingRebindSong?.isDownloaded ?: false,
                        isUploaded = false,
                        isVideo = false,
                        isEpisode = false,
                        playbackPosition = existingSong?.song?.playbackPosition ?: existingRebindSong?.playbackPosition,
                        isCached = existingSong?.song?.isCached ?: existingRebindSong?.isCached ?: false,
                    ),
                )

                if (rebindFromSongId != null) {
                    transferPlaylistSongMaps(rebindFromSongId, songId)
                    if (existingRebindSong != null) {
                        transferSongActivityReferences(rebindFromSongId, songId)
                    }
                    deleteLocalMusicBySongId(rebindFromSongId)
                    if (rebindFromSongId.startsWith("local_")) {
                        deleteSongById(rebindFromSongId)
                    }
                }

                deleteSongArtistMaps(songId)
                artistEntities.forEachIndexed { index, artist ->
                    upsert(artist)
                    upsert(
                        SongArtistMap(
                            songId = songId,
                            artistId = artist.id,
                            position = index,
                        ),
                    )
                }

                deleteSongAlbumMaps(songId)
                if (albumId != null) {
                    upsert(
                        AlbumEntity(
                            id = albumId,
                            title = albumTitle,
                            thumbnailUrl = tags.thumbnailUri,
                            songCount = 0,
                            duration = 0,
                            lastUpdateTime = now,
                            isLocal = true,
                        ),
                    )
                    upsert(SongAlbumMap(songId, albumId, 0))
                    deleteAlbumArtistMaps(albumId)
                    artistEntities.take(1).forEachIndexed { index, artist ->
                        upsert(AlbumArtistMap(albumId, artist.id, index))
                    }
                }

                upsert(
                    LocalMusicEntity(
                        songId = songId,
                        contentUri = document.contentUri.toString(),
                        treeUri = document.treeUri,
                        documentId = document.documentId,
                        displayName = document.displayName,
                        mimeType = document.mimeType,
                        fileSize = document.size,
                        dateModified = document.lastModified,
                        lastScannedAt = nowMillis,
                        scanGeneration = generation,
                        missingSince = null,
                        bpm = tags.bpm ?: existingLocal?.bpm ?: existingTargetLocal?.bpm,
                        keyName = tags.keyName ?: existingLocal?.keyName ?: existingTargetLocal?.keyName,
                        valence = tags.valence ?: existingLocal?.valence ?: existingTargetLocal?.valence,
                        energy = tags.energy ?: existingLocal?.energy ?: existingTargetLocal?.energy,
                        danceability = tags.danceability ?: existingLocal?.danceability ?: existingTargetLocal?.danceability,
                        acousticness = tags.acousticness ?: existingLocal?.acousticness ?: existingTargetLocal?.acousticness,
                        instrumentalness = tags.instrumentalness ?: existingLocal?.instrumentalness ?: existingTargetLocal?.instrumentalness,
                        liveness = tags.liveness ?: existingLocal?.liveness ?: existingTargetLocal?.liveness,
                        speechiness = tags.speechiness ?: existingLocal?.speechiness ?: existingTargetLocal?.speechiness,
                        moodSummary = tags.moodSummary ?: existingLocal?.moodSummary ?: existingTargetLocal?.moodSummary,
                        hasArtwork = tags.hasArtwork || existingLocal?.hasArtwork == true || existingTargetLocal?.hasArtwork == true,
                    ),
                )
            }
        } catch (error: Throwable) {
            Timber.tag("LocalMusicScanner").w(error, "Failed to import %s", document.contentUri)
        }
    }

    private fun LocalAudioDocument.toScanDocument(): LocalMusicScanDocument =
        LocalMusicScanDocument(
            documentId = documentId,
            fileSize = size,
            dateModified = lastModified,
        )

    private fun LocalMusicScanSnapshot.toExistingDocument(): LocalMusicExistingDocument =
        LocalMusicExistingDocument(
            songId = songId,
            documentId = documentId,
            fileSize = fileSize,
            dateModified = dateModified,
            durationSeconds = durationSeconds,
            missingSince = missingSince,
            hasIncompleteAnalysis = hasIncompleteAnalysis,
        )

    private fun stableSongId(treeUri: String, documentId: String): String = localId("song", "$treeUri|$documentId")

    private fun localId(prefix: String, value: String): String = "local_${prefix}_${sha256(value).take(24)}"

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
        if (index >= 0 && !isNull(index)) getLong(index) else null

    private companion object {
        const val SCAN_PARALLELISM = 4
        const val SQLITE_MAX_VARIABLES = 900
    }
}

class LocalMusicScanReadException(message: String) : IllegalStateException(message)
