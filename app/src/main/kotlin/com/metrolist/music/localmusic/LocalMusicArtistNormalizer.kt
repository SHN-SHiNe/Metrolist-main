package com.metrolist.music.localmusic

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.utils.ArtistNameSplitter
import java.security.MessageDigest

suspend fun MusicDatabase.normalizeCompositeLocalArtists() {
    withTransaction {
        val compositeArtists = compositeLocalArtists()
        if (compositeArtists.isEmpty()) return@withTransaction

        compositeArtists.forEach { artist ->
            val artistNames = ArtistNameSplitter.split(artist.name)
            if (artistNames.size <= 1) return@forEach

            val maps = songArtistMapsByArtist(artist.id)
            if (maps.isEmpty()) return@forEach

            artistNames.forEachIndexed { offset, artistName ->
                val normalizedArtist = localArtistEntity(artistName, artist)
                upsert(normalizedArtist)
                maps.forEach { map ->
                    upsert(
                        SongArtistMap(
                            songId = map.songId,
                            artistId = normalizedArtist.id,
                            position = map.position * POSITION_BUCKET + offset,
                        ),
                    )
                }
            }

            deleteSongArtistMapsByArtistId(artist.id)
            deleteUnbookmarkedArtistById(artist.id)
        }
    }
}

private fun MusicDatabase.localArtistEntity(
    artistName: String,
    source: ArtistEntity,
): ArtistEntity {
    val artistId = localArtistId(artistName)
    val existing = getArtistById(artistId)
    return ArtistEntity(
        id = artistId,
        name = existing?.name ?: artistName,
        thumbnailUrl = existing?.thumbnailUrl ?: source.thumbnailUrl,
        channelId = existing?.channelId,
        lastUpdateTime = existing?.lastUpdateTime ?: source.lastUpdateTime,
        bookmarkedAt = existing?.bookmarkedAt,
        isLocal = true,
        isPodcastChannel = existing?.isPodcastChannel ?: false,
    )
}

fun localArtistId(artistName: String): String =
    "local_artist_${sha256(artistName.lowercase()).take(24)}"

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

private const val POSITION_BUCKET = 100
