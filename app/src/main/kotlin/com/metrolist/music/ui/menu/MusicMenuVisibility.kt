package com.metrolist.music.ui.menu

internal data class MusicMenuVisibility(
    val showDownloadAction: Boolean,
    val showOnlineMetadataActions: Boolean,
    val showSongCommentsAction: Boolean,
)

internal fun musicMenuVisibility(isLocalSong: Boolean): MusicMenuVisibility =
    MusicMenuVisibility(
        showDownloadAction = !isLocalSong,
        showOnlineMetadataActions = !isLocalSong,
        showSongCommentsAction = !isLocalSong,
    )

internal fun isLocalMenuSong(
    isLocalMetadata: Boolean,
    mediaId: String,
    libraryIsLocal: Boolean,
    hasLocalFile: Boolean = false,
): Boolean =
    isLocalMetadata || mediaId.startsWith("local_") || libraryIsLocal || hasLocalFile
