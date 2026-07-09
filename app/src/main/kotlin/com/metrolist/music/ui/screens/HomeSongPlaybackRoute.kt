package com.metrolist.music.ui.screens

internal enum class HomeSongPlaybackRoute {
    LOCAL_SINGLE_SONG,
    ONLINE_RADIO,
}

internal fun homeSongPlaybackRoute(localContentUri: String?): HomeSongPlaybackRoute =
    if (localContentUri.isNullOrBlank()) {
        HomeSongPlaybackRoute.ONLINE_RADIO
    } else {
        HomeSongPlaybackRoute.LOCAL_SINGLE_SONG
    }
