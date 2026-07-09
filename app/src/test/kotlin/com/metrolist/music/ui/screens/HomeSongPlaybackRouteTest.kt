package com.metrolist.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSongPlaybackRouteTest {
    @Test
    fun localDatabaseHitUsesLocalSingleSongPlayback() {
        assertEquals(
            HomeSongPlaybackRoute.LOCAL_SINGLE_SONG,
            homeSongPlaybackRoute("content://media/external/audio/media/42"),
        )
    }

    @Test
    fun missingLocalDatabaseEntryUsesOnlineRadioPlayback() {
        assertEquals(
            HomeSongPlaybackRoute.ONLINE_RADIO,
            homeSongPlaybackRoute(null),
        )
    }

    @Test
    fun blankLocalDatabaseEntryUsesOnlineRadioPlayback() {
        assertEquals(
            HomeSongPlaybackRoute.ONLINE_RADIO,
            homeSongPlaybackRoute(""),
        )
    }
}
