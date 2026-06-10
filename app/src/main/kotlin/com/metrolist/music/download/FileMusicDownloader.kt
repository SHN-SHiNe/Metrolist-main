package com.metrolist.music.download

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.getSystemService
import androidx.documentfile.provider.DocumentFile
import com.metrolist.chinamusic.ChinaMusicUtils
import com.metrolist.chinamusic.model.AudioQuality as ChinaAudioQuality
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.FileDownloadDirectoryUriKey
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.YTPlayerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.text.Normalizer

object FileMusicDownloader {
    enum class Quality(val label: String, val ytQuality: AudioQuality, val chinaQuality: ChinaAudioQuality) {
        LOW("标准音质", AudioQuality.LOW, ChinaAudioQuality.LOW),
        HIGH("高音质", AudioQuality.HIGH, ChinaAudioQuality.HIGH),
        LOSSLESS("无损优先", AudioQuality.VERY_HIGH, ChinaAudioQuality.LOSSLESS),
    }

    suspend fun download(
        context: Context,
        mediaMetadata: MediaMetadata,
        quality: Quality,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = resolveStream(context, mediaMetadata, quality)
            val extension = extensionFromMime(stream.mimeType)
            val fileName = sanitizeFileName("${mediaMetadata.artists.joinToString(", ") { it.name }} - ${mediaMetadata.title}.$extension")
            val audioBytes = buildTaggedAudio(
                mediaMetadata = mediaMetadata,
                bytes = stream.bytes,
                mimeType = stream.mimeType,
                coverBytes = downloadCover(mediaMetadata.thumbnailUrl),
            ) ?: stream.bytes
            val customDirectoryUri = context.dataStore.get(FileDownloadDirectoryUriKey, "")
            if (customDirectoryUri.isNotBlank()) {
                writeToCustomDirectory(context, customDirectoryUri, fileName, stream.mimeType, audioBytes)
                return@runCatching fileName
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, stream.mimeType)
                put(MediaStore.Audio.Media.TITLE, mediaMetadata.title)
                put(MediaStore.Audio.Media.ARTIST, mediaMetadata.artists.joinToString(", ") { it.name })
                put(MediaStore.Audio.Media.ALBUM, mediaMetadata.album?.title.orEmpty())
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SHiNe MUSIC")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    output.write(audioBytes)
                } ?: error("无法打开下载文件")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                fileName
            } catch (throwable: Throwable) {
                resolver.delete(uri, null, null)
                throw throwable
            }
        }.onFailure {
            Timber.tag("FileMusicDownloader").e(it, "File download failed")
        }
    }

    private suspend fun resolveStream(
        context: Context,
        mediaMetadata: MediaMetadata,
        quality: Quality,
    ): StreamData {
        if (ChinaMusicUtils.isChinaMediaId(mediaMetadata.id)) {
            val url = ChinaMusicUtils.getMusicUrl(
                mediaId = mediaMetadata.id,
                quality = quality.chinaQuality,
                title = mediaMetadata.title,
                artist = mediaMetadata.artists.joinToString(", ") { it.name },
                durationSeconds = mediaMetadata.duration,
            ).getOrThrow()
            return downloadBytes(url, guessMimeTypeFromUrl(url))
        }
        val connectivityManager = context.getSystemService<ConnectivityManager>() ?: error("网络服务不可用")
        val playbackData = YTPlayerUtils.playerResponseForPlayback(
            videoId = mediaMetadata.id,
            audioQuality = quality.ytQuality,
            connectivityManager = connectivityManager,
        ).getOrThrow()
        return downloadBytes(playbackData.streamUrl, playbackData.format.mimeType.substringBefore(';'))
    }

    private fun downloadBytes(url: String, mimeType: String): StreamData {
        val request = Request.Builder().url(url).build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：${response.code}")
            val bytes = response.body?.bytes() ?: error("下载内容为空")
            return StreamData(bytes, mimeType)
        }
    }

    private fun writeToCustomDirectory(
        context: Context,
        directoryUri: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ) {
        val directory = DocumentFile.fromTreeUri(context, Uri.parse(directoryUri))
            ?: error("无法打开自定义下载目录")
        directory.findFile(fileName)?.delete()
        val file = directory.createFile(mimeType, fileName)
            ?: error("无法在自定义目录创建文件")
        context.contentResolver.openOutputStream(file.uri)?.use { output ->
            output.write(bytes)
        } ?: error("无法写入自定义目录文件")
    }

    private fun buildTaggedAudio(
        mediaMetadata: MediaMetadata,
        bytes: ByteArray,
        mimeType: String,
        coverBytes: ByteArray?,
    ): ByteArray? {
        if (mimeType != "audio/mpeg") return null
        val tag = buildId3v23Tag(mediaMetadata, coverBytes)
        return ByteArrayOutputStream(tag.size + bytes.size).use { output ->
            output.write(tag)
            output.write(bytes)
            output.toByteArray()
        }
    }

    private fun buildId3v23Tag(mediaMetadata: MediaMetadata, coverBytes: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.writeTextFrame("TIT2", mediaMetadata.title)
        frames.writeTextFrame("TPE1", mediaMetadata.artists.joinToString("/") { it.name })
        mediaMetadata.album?.title?.takeIf { it.isNotBlank() }?.let { frames.writeTextFrame("TALB", it) }
        coverBytes?.let { frames.writeCoverFrame(it) }
        val frameBytes = frames.toByteArray()
        return ByteArrayOutputStream(10 + frameBytes.size).use { output ->
            output.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
            output.write(syncSafe(frameBytes.size))
            output.write(frameBytes)
            output.toByteArray()
        }
    }

    private fun ByteArrayOutputStream.writeTextFrame(id: String, value: String) {
        if (value.isBlank()) return
        val payload = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
        write(id.toByteArray(Charsets.ISO_8859_1))
        write(byteArrayOf(
            ((payload.size ushr 24) and 0xFF).toByte(),
            ((payload.size ushr 16) and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
            0,
            0,
        ))
        write(payload)
    }

    private fun ByteArrayOutputStream.writeCoverFrame(coverBytes: ByteArray) {
        val payload = ByteArrayOutputStream().use { output ->
            output.write(0)
            output.write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
            output.write(0)
            output.write(3)
            output.write(0)
            output.write(coverBytes)
            output.toByteArray()
        }
        write("APIC".toByteArray(Charsets.ISO_8859_1))
        write(byteArrayOf(
            ((payload.size ushr 24) and 0xFF).toByte(),
            ((payload.size ushr 16) and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
            0,
            0,
        ))
        write(payload)
    }

    private fun syncSafe(size: Int) = byteArrayOf(
        ((size ushr 21) and 0x7F).toByte(),
        ((size ushr 14) and 0x7F).toByte(),
        ((size ushr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte(),
    )

    private fun extensionFromMime(mimeType: String) = when {
        "mpeg" in mimeType -> "mp3"
        "mp4" in mimeType || "m4a" in mimeType -> "m4a"
        "webm" in mimeType -> "webm"
        "ogg" in mimeType || "opus" in mimeType -> "ogg"
        "flac" in mimeType -> "flac"
        else -> "m4a"
    }

    private fun guessMimeTypeFromUrl(url: String) = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        "m4a", "mp4" -> "audio/mp4"
        else -> "audio/mpeg"
    }

    private fun downloadCover(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val request = Request.Builder().url(url).build()
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.bytes()
            }
        }.getOrNull()
    }

    private fun sanitizeFileName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        return normalized.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(160)
    }

    private data class StreamData(val bytes: ByteArray, val mimeType: String)
}
