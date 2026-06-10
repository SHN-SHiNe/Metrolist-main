/**
 * Metrolist Project (C) 2026
 * Extensions for converting Chinese music source data to Metrolist models
 */

package com.metrolist.music.models

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.metrolist.chinamusic.ChinaMusicUtils
import com.metrolist.chinamusic.model.ChinaSong
import java.nio.charset.Charset

fun ChinaSong.toMediaMetadata(): MediaMetadata {
    val mediaId = ChinaMusicUtils.createMediaId(this)
    return MediaMetadata(
        id = mediaId,
        title = name.fixChinaMojibake(),
        artists = singer.split("、", ",", "/", "·").map { it.trim() }.filter { it.isNotEmpty() }.map {
            val artistName = it.fixChinaMojibake()
            MediaMetadata.Artist(id = "china_artist_${Uri.encode(artistName)}", name = artistName)
        },
        duration = durationSeconds,
        thumbnailUrl = img,
        album = if (albumName.isNotEmpty()) {
            MediaMetadata.Album(id = albumId, title = albumName.fixChinaMojibake())
        } else null,
    )
}

private fun String.fixChinaMojibake(): String {
    if (isBlank()) return this
    val hasMojibakeMarker = any { it == '�' || it.code in 0xE000..0xF8FF }
    if (!hasMojibakeMarker) return this
    return runCatching {
        toByteArray(Charset.forName("GBK")).toString(Charsets.UTF_8)
    }.getOrDefault(this).takeIf { it.isNotBlank() } ?: this
}

fun ChinaSong.toMediaItem(): MediaItem {
    val metadata = toMediaMetadata()
    return MediaItem.Builder()
        .setMediaId(metadata.id)
        .setUri(metadata.id)
        .setCustomCacheKey(metadata.id)
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(metadata.title)
                .setSubtitle(metadata.artists.joinToString { it.name })
                .setArtist(metadata.artists.joinToString { it.name })
                .setArtworkUri(metadata.thumbnailUrl?.toUri())
                .setAlbumTitle(metadata.album?.title)
                .setAlbumArtist(metadata.artists.firstOrNull()?.name)
                .setDisplayTitle(metadata.title)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setExtras(Bundle().apply {
                    metadata.thumbnailUrl?.let { putString("artwork_uri", it) }
                })
                .build()
        )
        .build()
}
