package com.metrolist.music.ui.screens.localmusic

import com.metrolist.music.ui.component.CoverPlaybackIndicatorState
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMusicCoverPlaybackIndicatorStateTest {
    @Test
    fun inactiveItemHidesIndicatorEvenWhenPlayerIsPlaying() {
        assertEquals(
            CoverPlaybackIndicatorState.HIDDEN,
            localMusicCoverPlaybackIndicatorState(
                isActive = false,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun activePlayingItemShowsPlayingIndicator() {
        assertEquals(
            CoverPlaybackIndicatorState.PLAYING,
            localMusicCoverPlaybackIndicatorState(
                isActive = true,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun activePausedItemShowsPausedPlayIcon() {
        assertEquals(
            CoverPlaybackIndicatorState.PAUSED,
            localMusicCoverPlaybackIndicatorState(
                isActive = true,
                isPlaying = false,
            ),
        )
    }
}
