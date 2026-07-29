package com.shine.music.server

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

data class ScannedFile(
    val id: String,
    val path: Path,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mimeType: String,
    val size: Long,
    val modifiedAt: Long,
)

class LibraryScanner(
    private val root: Path,
    private val store: MusicStore,
    private val cacheRoot: Path = root.resolve(".shine-cache"),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun scan(): ScanResult {
        Files.createDirectories(root)
        val scanId = UUID.randomUUID().toString()
        val startedAt = clock()
        store.startScan(scanId, startedAt)
        var discovered = 0
        var updated = 0
        try {
            Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() && it.extension.lowercase() in SUPPORTED_EXTENSIONS }.forEach { path ->
                    val file = readFile(path)
                    when (store.upsertTrack(scanId, file)) {
                        ScanChange.DISCOVERED -> discovered++
                        ScanChange.UPDATED -> updated++
                        ScanChange.UNCHANGED -> Unit
                    }
                }
            }
        } catch (error: Throwable) {
            store.finishScanJob(ScanResult(scanId, "failed", discovered, updated, 0, startedAt, clock()))
            throw error
        }
        val removed = store.finishScan(scanId)
        return ScanResult(scanId, "completed", discovered, updated, removed, startedAt, clock()).also(store::finishScanJob)
    }

    private fun readFile(path: Path): ScannedFile {
        val fallback = path.nameWithoutExtension.split(" - ", limit = 2)
        var title = fallback.firstOrNull().orEmpty().ifBlank { path.nameWithoutExtension }
        var artist = fallback.getOrNull(1).orEmpty().ifBlank { "未知艺术家" }
        var album = ""
        var duration = 0L
        var artwork: ByteArray? = null
        var artworkMime = "image/jpeg"
        runCatching { AudioFileIO.read(path.toFile()) }.getOrNull()?.let { audio ->
            title = audio.tag?.getFirst(FieldKey.TITLE)?.ifBlank { title } ?: title
            artist = audio.tag?.getFirst(FieldKey.ARTIST)?.ifBlank { artist } ?: artist
            album = audio.tag?.getFirst(FieldKey.ALBUM).orEmpty()
            duration = audio.audioHeader?.trackLength?.times(1000L) ?: 0L
            audio.tag?.firstArtwork?.let { embedded ->
                artwork = embedded.binaryData
                artworkMime = embedded.mimeType ?: artworkMime
            }
        }
        val normalized = path.toAbsolutePath().normalize().toString()
        val id = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        artwork?.takeIf { it.isNotEmpty() }?.let { bytes ->
            val covers = cacheRoot.resolve("covers")
            Files.createDirectories(covers)
            val extension = if (artworkMime.contains("png", ignoreCase = true)) "png" else "jpg"
            Files.write(covers.resolve("$id.$extension"), bytes)
        }
        return ScannedFile(
            id, path, title, artist, album, duration, mimeFor(path.extension), path.fileSize(), path.getLastModifiedTime().toMillis(),
        )
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "opus" -> "audio/ogg"
        else -> "audio/mpeg"
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "m4a", "aac", "wav", "opus")
    }
}
