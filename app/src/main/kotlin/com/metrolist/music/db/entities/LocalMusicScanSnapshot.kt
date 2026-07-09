package com.metrolist.music.db.entities

data class LocalMusicScanSnapshot(
    val songId: String,
    val documentId: String,
    val fileSize: Long?,
    val dateModified: Long?,
    val durationSeconds: Int,
    val missingSince: Long?,
    val hasIncompleteAnalysis: Boolean,
)
