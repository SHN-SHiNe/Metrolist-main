/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

data class LocalAudioTags(
    val sourceTrackId: String?,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSeconds: Int,
    val thumbnailUri: String?,
    val hasArtwork: Boolean,
    val bpm: Float?,
    val keyName: String?,
    val valence: Float?,
    val energy: Float?,
    val danceability: Float?,
    val acousticness: Float?,
    val instrumentalness: Float?,
    val liveness: Float?,
    val speechiness: Float?,
    val moodSummary: String?,
)

@Singleton
class LocalMusicTagReader
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(uri: Uri, displayName: String, songId: String): LocalAudioTags {
        val basic = readBasicTags(uri, displayName, songId)
        val id3 = readId3v2(uri)
        val moodSummary =
            listOf(
                id3["TMOO"],
                id3["TXXX:WM/Mood"],
                id3["TXXX:WM/MOOD"],
                id3["TXXX:MOOD"],
                id3["TXXX:Mood"],
                id3["COMM::xxx"],
                id3["COMM::eng"],
            ).firstOrNull { !it.isNullOrBlank() }

        fun metric(name: String): Float? =
            id3["TXXX:$name"]?.toFloatValue()
                ?: moodSummary?.metricFromSummary(name)

        return basic.copy(
            sourceTrackId =
                listOf(
                    id3["TXXX:${LocalMusicSourceTrackId.ID3_DESCRIPTION}"],
                    id3["TXXX:SOURCE_TRACK_ID"],
                    id3["TXXX:SourceTrackId"],
                ).firstNotNullOfOrNull(LocalMusicSourceTrackId::normalize),
            bpm = id3["TBPM"]?.toFloatValue(),
            keyName = id3["TKEY"]?.sanitizeKeyName(),
            valence = metric("VALENCE"),
            energy = metric("ENERGY"),
            danceability = metric("DANCEABILITY"),
            acousticness = metric("ACOUSTICNESS"),
            instrumentalness = metric("INSTRUMENTALNESS"),
            liveness = metric("LIVENESS"),
            speechiness = metric("SPEECHINESS"),
            moodSummary = moodSummary?.trimToNull(),
        )
    }

    private fun readBasicTags(uri: Uri, displayName: String, songId: String): LocalAudioTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trimToNull()
                    ?: displayName.substringBeforeLast('.').ifBlank { displayName }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trimToNull()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trimToNull()
            val durationSeconds =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.let { (it / 1000L).toInt() }
                    ?: -1
            val artwork = retriever.embeddedPicture
            val artworkUri = artwork?.let { writeArtwork(songId, it) }

            LocalAudioTags(
                sourceTrackId = null,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSeconds,
                thumbnailUri = artworkUri,
                hasArtwork = artworkUri != null,
                bpm = null,
                keyName = null,
                valence = null,
                energy = null,
                danceability = null,
                acousticness = null,
                instrumentalness = null,
                liveness = null,
                speechiness = null,
                moodSummary = null,
            )
        } catch (error: Throwable) {
            Timber.tag("LocalMusicTagReader").w(error, "Failed to read basic tags for %s", uri)
            LocalAudioTags(
                sourceTrackId = null,
                title = displayName.substringBeforeLast('.').ifBlank { displayName },
                artist = null,
                album = null,
                durationSeconds = -1,
                thumbnailUri = null,
                hasArtwork = false,
                bpm = null,
                keyName = null,
                valence = null,
                energy = null,
                danceability = null,
                acousticness = null,
                instrumentalness = null,
                liveness = null,
                speechiness = null,
                moodSummary = null,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeArtwork(songId: String, bytes: ByteArray): String? =
        runCatching {
            val dir = File(context.filesDir, "local_music_art").apply { mkdirs() }
            val file = File(dir, "$songId.jpg")
            file.writeBytes(bytes)
            file.toUri().toString()
        }.getOrNull()

    private fun readId3v2(uri: Uri): Map<String, String> =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = input.readNBytesCompat(10)
                if (header.size < 10 || String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") {
                    return@use emptyMap()
                }
                val major = header[3].toInt() and 0xFF
                if (major !in 3..4) return@use emptyMap()
                val flags = header[5].toInt() and 0xFF
                val tagSize = syncSafeInt(header, 6)
                if (tagSize <= 0 || tagSize > MAX_ID3_SIZE) return@use emptyMap()
                val tag = input.readNBytesCompat(tagSize)
                parseFrames(tag, major, flags)
            } ?: emptyMap()
        }.onFailure {
            Timber.tag("LocalMusicTagReader").w(it, "Failed to read ID3 tags for %s", uri)
        }.getOrDefault(emptyMap())

    private fun parseFrames(tag: ByteArray, major: Int, flags: Int): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var offset = 0
        if ((flags and 0x40) != 0 && tag.size >= 4) {
            offset += when (major) {
                3 -> 4 + int32(tag, 0)
                else -> syncSafeInt(tag, 0)
            }
        }

        while (offset + 10 <= tag.size) {
            val frameId = String(tag, offset, 4, Charsets.ISO_8859_1)
            if (frameId.any { !it.isLetterOrDigit() }) break
            val frameSize = if (major == 4) syncSafeInt(tag, offset + 4) else int32(tag, offset + 4)
            if (frameSize <= 0 || offset + 10 + frameSize > tag.size) break
            val frame = tag.copyOfRange(offset + 10, offset + 10 + frameSize)

            when {
                frameId == "TXXX" -> {
                    val (description, text) = decodeUserTextFrame(frame)
                    if (!description.isNullOrBlank() && !text.isNullOrBlank()) {
                        values["TXXX:${description.trim()}"] = text.trim()
                    }
                }

                frameId == "COMM" -> {
                    val (language, description, text) = decodeCommentFrame(frame)
                    if (!text.isNullOrBlank()) {
                        values["COMM:${description.orEmpty()}:$language"] = text.trim()
                        if (description.isNullOrBlank()) {
                            values["COMM::$language"] = text.trim()
                        }
                    }
                }

                frameId in setOf("TBPM", "TKEY", "TMOO") -> {
                    decodeTextFrame(frame)?.trimToNull()?.let { values[frameId] = it }
                }
            }

            offset += 10 + frameSize
        }
        return values
    }

    private fun decodeTextFrame(frame: ByteArray): String? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt() and 0xFF
        return decodeEncodedText(frame.copyOfRange(1, frame.size), encoding)
    }

    private fun decodeUserTextFrame(frame: ByteArray): Pair<String?, String?> {
        if (frame.isEmpty()) return null to null
        val encoding = frame[0].toInt() and 0xFF
        val data = frame.copyOfRange(1, frame.size)
        val separator = findTerminator(data, 0, encoding)
        if (separator < 0) return null to decodeEncodedText(data, encoding)
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        val description = decodeEncodedText(data.copyOfRange(0, separator), encoding)
        val text = decodeEncodedText(data.copyOfRange(separator + step, data.size), encoding)
        return description to text
    }

    private fun decodeCommentFrame(frame: ByteArray): Triple<String, String?, String?> {
        if (frame.size < 4) return Triple("xxx", null, null)
        val encoding = frame[0].toInt() and 0xFF
        val language = String(frame, 1, 3, Charsets.ISO_8859_1)
        val data = frame.copyOfRange(4, frame.size)
        val separator = findTerminator(data, 0, encoding)
        if (separator < 0) return Triple(language, null, decodeEncodedText(data, encoding))
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        val description = decodeEncodedText(data.copyOfRange(0, separator), encoding)
        val text = decodeEncodedText(data.copyOfRange(separator + step, data.size), encoding)
        return Triple(language, description, text)
    }

    private fun decodeEncodedText(data: ByteArray, encoding: Int): String? {
        if (data.isEmpty()) return null
        val charset =
            when (encoding) {
                1 -> Charset.forName("UTF-16")
                2 -> Charset.forName("UTF-16BE")
                3 -> Charsets.UTF_8
                else -> Charsets.ISO_8859_1
            }
        return String(data.trimTrailingZeroes(), charset).replace("\u0000", "").trimToNull()
    }

    private fun findTerminator(data: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var index = start
            while (index + 1 < data.size) {
                if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) return index
                index += 2
            }
            return -1
        }
        for (index in start until data.size) {
            if (data[index] == 0.toByte()) return index
        }
        return -1
    }

    private fun ByteArray.trimTrailingZeroes(): ByteArray {
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return copyOfRange(0, end)
    }

    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    private fun String.sanitizeKeyName(): String? =
        trim()
            .replace("\uFFFD", "")
            .replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), "")
            .replace(Regex("\\s+"), " ")
            .takeIf { key -> key.isNotBlank() && key.any { it.isLetterOrDigit() } }

    private fun String.toFloatValue(): Float? =
        Regex("""[-+]?\d+(?:\.\d+)?""")
            .find(this)
            ?.value
            ?.toFloatOrNull()

    private fun String.metricFromSummary(name: String): Float? =
        Regex("""(?i)\b${Regex.escape(name)}\b\s*[:=]\s*([-+]?\d+(?:\.\d+)?)""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()

    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var total = 0
        while (total < count) {
            val read = read(buffer, total, count - total)
            if (read == -1) break
            total += read
        }
        return if (total == count) buffer else buffer.copyOf(total)
    }

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun int32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private companion object {
        const val MAX_ID3_SIZE = 2 * 1024 * 1024
    }
}
