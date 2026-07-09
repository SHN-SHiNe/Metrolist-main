/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "local_music",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["songId"], unique = true),
        Index(value = ["treeUri", "documentId"], unique = true),
        Index(value = ["missingSince"]),
        Index(value = ["missingSince", "lastScannedAt"]),
        Index(value = ["missingSince", "bpm", "keyName"]),
    ],
)
data class LocalMusicEntity(
    @PrimaryKey val songId: String,
    val contentUri: String,
    val treeUri: String,
    val documentId: String,
    val displayName: String,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val dateModified: Long? = null,
    val lastScannedAt: Long,
    val scanGeneration: Long,
    val missingSince: Long? = null,
    val bpm: Float? = null,
    val keyName: String? = null,
    val valence: Float? = null,
    val energy: Float? = null,
    val danceability: Float? = null,
    val acousticness: Float? = null,
    val instrumentalness: Float? = null,
    val liveness: Float? = null,
    val speechiness: Float? = null,
    val moodSummary: String? = null,
    @ColumnInfo(defaultValue = "0")
    val hasArtwork: Boolean = false,
)
