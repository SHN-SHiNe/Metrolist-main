package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverPlaybackIndicatorStateTest {
    @Test
    fun playingActiveItemShowsPlayingIndicator() {
        assertEquals(
            CoverPlaybackIndicatorState.PLAYING,
            coverPlaybackIndicatorState(
                isActive = true,
                isPlaying = true,
                showPlaybackStateOverlay = true,
                showPausedPlaybackIcon = false,
            ),
        )
    }

    @Test
    fun pausedActiveItemCanHideStaticPlayIcon() {
        assertEquals(
            CoverPlaybackIndicatorState.HIDDEN,
            coverPlaybackIndicatorState(
                isActive = true,
                isPlaying = false,
                showPlaybackStateOverlay = true,
                showPausedPlaybackIcon = false,
            ),
        )
    }

    @Test
    fun pausedActiveItemKeepsDefaultStaticPlayIconForOtherScreens() {
        assertEquals(
            CoverPlaybackIndicatorState.PAUSED,
            coverPlaybackIndicatorState(
                isActive = true,
                isPlaying = false,
                showPlaybackStateOverlay = true,
                showPausedPlaybackIcon = true,
            ),
        )
    }

    @Test
    fun inactiveItemShowsNothing() {
        assertEquals(
            CoverPlaybackIndicatorState.HIDDEN,
            coverPlaybackIndicatorState(
                isActive = false,
                isPlaying = true,
                showPlaybackStateOverlay = true,
                showPausedPlaybackIcon = false,
            ),
        )
    }
}
