/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMusicTagWriter
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun writeMp3Tags(
        uri: Uri,
        result: LocalMusicAnalysisResult,
    ): LocalMusicTagWriteResult =
        withContext(Dispatchers.IO) {
            if (!looksLikeMp3(uri)) {
                return@withContext LocalMusicTagWriteResult.Skipped("Only MP3 ID3 writing is supported")
            }
            runCatching {
                val temp = File.createTempFile("local-analysis-", ".mp3", context.cacheDir)
                try {
                    val existingTag =
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            stripExistingId3(input, temp)
                        } ?: return@withContext LocalMusicTagWriteResult.Failed("Cannot open local audio file")
                    val tag = Id3AnalysisTagBuilder.build(result.normalized(), existingTag)
                    context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                        output.write(tag)
                        temp.inputStream().use { audio -> audio.copyTo(output) }
                    } ?: return@withContext LocalMusicTagWriteResult.Failed("Cannot open local audio file for writing")
                    LocalMusicTagWriteResult.Written
                } finally {
                    temp.delete()
                }
            }.getOrElse { error ->
                LocalMusicTagWriteResult.Failed(error.message ?: error::class.java.simpleName, error)
            }
        }

    private fun looksLikeMp3(uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase()
        if (mime == "audio/mpeg" || mime == "audio/mp3" || mime == "audio/mpeg3") return true
        val displayName = queryDisplayName(uri)?.lowercase()
        if (displayName?.endsWith(".mp3") == true) return true
        return Uri.decode(uri.toString()).substringBefore('?').lowercase().endsWith(".mp3")
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()

    private fun stripExistingId3(input: InputStream, audioFile: File): ExistingId3Tag {
        val header = input.readNBytesCompat(ID3_HEADER_SIZE)
        audioFile.outputStream().use { output ->
            if (header.size < ID3_HEADER_SIZE || String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") {
                output.write(header)
                input.copyTo(output)
                return ExistingId3Tag(version = 4, frames = emptyList())
            }
            val version = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = syncSafeInt(header, 6)
            val tagBytes = input.readNBytesCompat(tagSize)
            input.copyTo(output)
            return ExistingId3Tag(
                version = version.takeIf { it in 3..4 } ?: 4,
                frames = Id3AnalysisTagBuilder.preservedFrames(tagBytes, version, flags),
            )
        }
    }

    private fun InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var total = 0
        while (total < count) {
            val read = read(buffer, total, count - total)
            if (read == -1) break
            total += read
        }
        return if (total == count) buffer else buffer.copyOf(total)
    }

    private companion object {
        const val ID3_HEADER_SIZE = 10
    }
}

data class ExistingId3Tag(
    val version: Int,
    val frames: List<ByteArray>,
)

object Id3AnalysisTagBuilder {
    private val managedTxxxDescriptions =
        setOf(
            "VALENCE",
            "ENERGY",
            "DANCEABILITY",
            "ACOUSTICNESS",
            "INSTRUMENTALNESS",
            "LIVENESS",
            "SPEECHINESS",
            "WM/MOOD",
            "MOOD",
        )

    fun build(
        result: LocalMusicAnalysisResult,
        existing: ExistingId3Tag = ExistingId3Tag(version = 4, frames = emptyList()),
    ): ByteArray {
        val version = existing.version.takeIf { it in 3..4 } ?: 4
        val frames = ArrayList<ByteArray>(existing.frames.size + 12)
        frames += existing.frames
        frames += textFrame("TBPM", "%.2f".format(java.util.Locale.US, result.bpm), version)
        frames += textFrame("TKEY", result.keyName, version)
        frames += textFrame("TMOO", result.moodSummary(), version)
        frames += userTextFrame("VALENCE", result.valence, version)
        frames += userTextFrame("ENERGY", result.energy, version)
        frames += userTextFrame("DANCEABILITY", result.danceability, version)
        frames += userTextFrame("ACOUSTICNESS", result.acousticness, version)
        frames += userTextFrame("INSTRUMENTALNESS", result.instrumentalness, version)
        frames += userTextFrame("LIVENESS", result.liveness, version)
        frames += userTextFrame("SPEECHINESS", result.speechiness, version)
        frames += userTextFrame("WM/Mood", result.moodSummary(), version)
        frames += userTextFrame("MOOD", result.moodSummary(), version)
        frames += commentFrame(result.moodSummary(), version)

        val body = ByteArrayOutputStream()
        frames.forEach(body::write)
        val bodyBytes = body.toByteArray()
        return ByteArrayOutputStream(ID3_HEADER_SIZE + bodyBytes.size).apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(version)
            write(0)
            write(0)
            write(syncSafeBytes(bodyBytes.size))
            write(bodyBytes)
        }.toByteArray()
    }

    fun preservedFrames(
        tag: ByteArray,
        version: Int,
        flags: Int = 0,
    ): List<ByteArray> {
        if (version !in 3..4) return emptyList()
        val preserved = mutableListOf<ByteArray>()
        var offset = 0
        if ((flags and 0x40) != 0 && tag.size >= 4) {
            offset += when (version) {
                3 -> 4 + int32(tag, 0)
                else -> syncSafeInt(tag, 0)
            }
        }
        while (offset + 10 <= tag.size) {
            val frameId = String(tag, offset, 4, Charsets.ISO_8859_1)
            if (frameId.any { !it.isLetterOrDigit() }) break
            val frameSize = if (version == 4) syncSafeInt(tag, offset + 4) else int32(tag, offset + 4)
            if (frameSize <= 0 || offset + 10 + frameSize > tag.size) break
            val frame = tag.copyOfRange(offset, offset + 10 + frameSize)
            val payload = tag.copyOfRange(offset + 10, offset + 10 + frameSize)
            if (!isManagedFrame(frameId, payload)) {
                preserved += frame
            }
            offset += 10 + frameSize
        }
        return preserved
    }

    private fun isManagedFrame(frameId: String, payload: ByteArray): Boolean =
        when (frameId) {
            "TBPM", "TKEY", "TMOO" -> true
            "TXXX" -> decodeUserTextDescription(payload)?.uppercase() in managedTxxxDescriptions
            "COMM" -> {
                val (language, description) = decodeCommentIdentity(payload)
                description.isBlank() && language.lowercase() in setOf("xxx", "eng")
            }
            else -> false
        }

    private fun textFrame(
        frameId: String,
        text: String,
        version: Int,
    ): ByteArray = frame(frameId, byteArrayOf(textEncoding(version)) + encodedText(text, version), version)

    private fun userTextFrame(
        description: String,
        value: Float,
        version: Int,
    ): ByteArray = userTextFrame(description, "%.3f".format(java.util.Locale.US, value), version)

    private fun userTextFrame(
        description: String,
        text: String,
        version: Int,
    ): ByteArray =
        frame(
            "TXXX",
            byteArrayOf(textEncoding(version)) +
                encodedText(description, version) +
                textTerminator(version) +
                encodedText(text, version),
            version,
        )

    private fun commentFrame(text: String, version: Int): ByteArray =
        frame(
            "COMM",
            byteArrayOf(textEncoding(version)) +
                "xxx".toByteArray(Charsets.ISO_8859_1) +
                textTerminator(version) +
                encodedText(text, version),
            version,
        )

    private fun textEncoding(version: Int): Byte =
        if (version == 3) TEXT_ENCODING_UTF16 else TEXT_ENCODING_UTF8

    private fun encodedText(text: String, version: Int): ByteArray =
        if (version == 3) {
            text.toByteArray(Charsets.UTF_16)
        } else {
            text.toByteArray(Charsets.UTF_8)
        }

    private fun textTerminator(version: Int): ByteArray =
        if (version == 3) byteArrayOf(0, 0) else byteArrayOf(0)

    private fun frame(
        frameId: String,
        payload: ByteArray,
        version: Int,
    ): ByteArray =
        ByteArrayOutputStream(10 + payload.size).apply {
            write(frameId.toByteArray(Charsets.ISO_8859_1))
            if (version == 4) {
                write(syncSafeBytes(payload.size))
            } else {
                write(int32Bytes(payload.size))
            }
            write(byteArrayOf(0, 0))
            write(payload)
        }.toByteArray()

    private fun decodeUserTextDescription(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        val data = payload.copyOfRange(1, payload.size)
        val separator = findTerminator(data, encoding)
        if (separator < 0) return null
        return decodeEncodedText(data.copyOfRange(0, separator), encoding)
    }

    private fun decodeCommentIdentity(payload: ByteArray): Pair<String, String> {
        if (payload.size < 4) return "xxx" to ""
        val language = String(payload, 1, 3, Charsets.ISO_8859_1)
        val encoding = payload[0].toInt() and 0xFF
        val data = payload.copyOfRange(4, payload.size)
        val separator = findTerminator(data, encoding)
        val description =
            if (separator < 0) {
                ""
            } else {
                decodeEncodedText(data.copyOfRange(0, separator), encoding).orEmpty()
            }
        return language to description
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
        return String(data.trimTrailingZeroes(), charset).replace("\u0000", "").trim().takeIf { it.isNotEmpty() }
    }

    private fun findTerminator(data: ByteArray, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var index = 0
            while (index + 1 < data.size) {
                if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) return index
                index += 2
            }
            return -1
        }
        for (index in data.indices) {
            if (data[index] == 0.toByte()) return index
        }
        return -1
    }

    private fun ByteArray.trimTrailingZeroes(): ByteArray {
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return copyOfRange(0, end)
    }

    private const val ID3_HEADER_SIZE = 10
    private const val TEXT_ENCODING_UTF16 = 1.toByte()
    private const val TEXT_ENCODING_UTF8 = 3.toByte()
}

private fun syncSafeInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0x7F) shl 21) or
        ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
        ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
        (bytes[offset + 3].toInt() and 0x7F)

private fun syncSafeBytes(value: Int): ByteArray =
    byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

private fun int32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun int32Bytes(value: Int): ByteArray =
    byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
