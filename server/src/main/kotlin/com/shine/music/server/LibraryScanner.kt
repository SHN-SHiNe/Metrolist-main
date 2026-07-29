package com.shine.music.server

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.extension
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.concurrent.withLock

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
    val scannedAt: Long,
    val libraryId: String = DEFAULT_LIBRARY_ID,
)

data class AudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artwork: ByteArray? = null,
    val artworkMime: String = "image/jpeg",
)

class LibraryScanner(
    private val root: Path,
    private val store: MusicStore,
    private val cacheRoot: Path = root.resolve(".shine-cache"),
    private val clock: () -> Long = System::currentTimeMillis,
    private val libraryId: String = DEFAULT_LIBRARY_ID,
    private val metadataReader: (Path) -> AudioMetadata = ::readAudioMetadata,
) {
    private val scanLock = ReentrantLock()
    private val realRoot by lazy { root.toRealPath() }

    fun scan(allowEmpty: Boolean = false): ScanResult = scanLock.withLock {
        Files.createDirectories(root)
        val scanId = UUID.randomUUID().toString()
        val startedAt = clock()
        store.startScan(scanId, startedAt, libraryId)
        var discovered = 0
        var updated = 0
        var seen = 0
        val changed = ArrayList<ScannedFile>(SCAN_BATCH_SIZE)
        val unchanged = ArrayList<String>(SCAN_BATCH_SIZE)
        try {
            store.scanSession(libraryId).use { session ->
                fun flush() {
                    if (changed.isEmpty() && unchanged.isEmpty()) return
                    session.applyBatch(scanId, changed, unchanged, startedAt)
                    changed.clear()
                    unchanged.clear()
                }
                Files.walk(root).use { paths ->
                    paths.filter { it.isRegularFile() && it.extension.lowercase() in SUPPORTED_EXTENSIONS }.forEach { path ->
                        seen++
                        val snapshot = snapshot(path)
                        val previous = session.fingerprint(snapshot.id)
                        if (previous != null && previous.size == snapshot.size && previous.modifiedAt == snapshot.modifiedAt) {
                            unchanged += snapshot.id
                        } else {
                            changed += readFile(snapshot, startedAt)
                            if (previous == null) discovered++ else updated++
                        }
                        if (changed.size + unchanged.size >= SCAN_BATCH_SIZE) flush()
                    }
                }
                flush()
            }
        } catch (error: Throwable) {
            store.finishScanJob(ScanResult(scanId, "failed", discovered, updated, 0, startedAt, clock(), libraryId))
            throw error
        }
        if (!allowEmpty && seen == 0 && store.activeTrackCount(libraryId) > 0) {
            return ScanResult(scanId, "offline", discovered, updated, 0, startedAt, clock(), libraryId).also(store::finishScanJob)
        }
        val removed = store.finishScan(scanId, libraryId)
        return ScanResult(scanId, "completed", discovered, updated, removed, startedAt, clock(), libraryId).also(store::finishScanJob)
    }

    fun index(path: Path): ScanChange = scanLock.withLock {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(normalizedRoot)) { "track_outside_music_directory" }
        require(normalized.isRegularFile() && normalized.extension.lowercase() in SUPPORTED_EXTENSIONS) { "unsupported_audio_file" }
        store.upsertTrack("single-${UUID.randomUUID()}", readFile(snapshot(normalized), clock()))
    }

    fun moveToTrash(trackId: String, trashRoot: Path, now: Long): Boolean = scanLock.withLock {
        val source = store.trackFile(trackId) ?: return false
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalized = source.toAbsolutePath().normalize()
        require(normalized.startsWith(normalizedRoot)) { "track_outside_music_directory" }
        require(normalized.toRealPath().startsWith(realRoot)) { "track_outside_music_directory" }
        trashRoot.createDirectories()
        Files.move(normalized, trashRoot.resolve("$now-${normalized.fileName}"), StandardCopyOption.REPLACE_EXISTING)
        store.markTrackDeleted(trackId, now)
    }

    private fun snapshot(path: Path): FileSnapshot {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.toRealPath().startsWith(realRoot)) { "track_outside_music_directory" }
        val id = MessageDigest.getInstance("SHA-256").digest(normalized.toString().toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        return FileSnapshot(id, normalized, normalized.fileSize(), normalized.getLastModifiedTime().toMillis())
    }

    private fun readFile(snapshot: FileSnapshot, scannedAt: Long): ScannedFile {
        val path = snapshot.path
        val fallback = path.nameWithoutExtension.split(" - ", limit = 2)
        val fallbackTitle = fallback.firstOrNull().orEmpty().ifBlank { path.nameWithoutExtension }
        val fallbackArtist = fallback.getOrNull(1).orEmpty().ifBlank { "未知艺术家" }
        val metadata = runCatching { metadataReader(path) }.getOrNull()
        val title = metadata?.title?.ifBlank { fallbackTitle } ?: fallbackTitle
        val artist = metadata?.artist?.ifBlank { fallbackArtist } ?: fallbackArtist
        val album = metadata?.album.orEmpty()
        val duration = metadata?.durationMs ?: 0L
        metadata?.artwork?.takeIf { it.isNotEmpty() }?.let { bytes ->
            val covers = cacheRoot.resolve("covers")
            Files.createDirectories(covers)
            val extension = if (metadata.artworkMime.contains("png", ignoreCase = true)) "png" else "jpg"
            Files.write(covers.resolve("${snapshot.id}.$extension"), bytes)
        }
        return ScannedFile(
            snapshot.id, path, title, artist, album, duration, mimeFor(path.extension), snapshot.size, snapshot.modifiedAt, scannedAt, libraryId,
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
        private const val SCAN_BATCH_SIZE = 256
        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "m4a", "aac", "wav", "opus")
    }
}

private data class FileSnapshot(val id: String, val path: Path, val size: Long, val modifiedAt: Long)

private fun readAudioMetadata(path: Path): AudioMetadata {
    val audio = AudioFileIO.read(path.toFile())
    return AudioMetadata(
        title = audio.tag?.getFirst(FieldKey.TITLE).orEmpty(),
        artist = audio.tag?.getFirst(FieldKey.ARTIST).orEmpty(),
        album = audio.tag?.getFirst(FieldKey.ALBUM).orEmpty(),
        durationMs = audio.audioHeader?.trackLength?.times(1000L) ?: 0L,
        artwork = audio.tag?.firstArtwork?.binaryData,
        artworkMime = audio.tag?.firstArtwork?.mimeType ?: "image/jpeg",
    )
}
