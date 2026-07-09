package com.metrolist.music.ui.player

import com.metrolist.music.playback.LocalSimilarNextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPrimaryControlsTest {
    @Test
    fun newPlayerDesignKeepsPrimaryControlsFocusedOnTransport() {
        val controls =
            playerPrimaryControls(
                useNewPlayerDesign = true,
            )

        assertEquals(
            listOf(
                PlayerPrimaryControl.PREVIOUS,
                PlayerPrimaryControl.PLAY_PAUSE,
                PlayerPrimaryControl.NEXT,
            ),
            controls,
        )
    }

    @Test
    fun oldPlayerDesignKeepsPrimaryControlsFocusedOnTransport() {
        val controls =
            playerPrimaryControls(
                useNewPlayerDesign = false,
            )

        assertEquals(
            listOf(
                PlayerPrimaryControl.REPEAT,
                PlayerPrimaryControl.PREVIOUS,
                PlayerPrimaryControl.PLAY_PAUSE,
                PlayerPrimaryControl.NEXT,
                PlayerPrimaryControl.FAVORITE,
            ),
            controls,
        )
    }

    @Test
    fun localSimilarAutoplayIconAnimatesOnlyWhenEnabled() {
        val disabled = localSimilarAutoplayIconState(enabled = false)
        val enabled = localSimilarAutoplayIconState(enabled = true)

        assertFalse(disabled.isAnimated)
        assertEquals(0.5f, disabled.alpha)
        assertTrue(enabled.isAnimated)
        assertEquals(1f, enabled.alpha)
    }

    @Test
    fun localSimilarAutoplayBeatDurationFollowsBpmWithFallbackAndBounds() {
        assertEquals(500, localSimilarAutoplayBeatDurationMillis(120f))
        assertEquals(625, localSimilarAutoplayBeatDurationMillis(null))
        assertEquals(1333, localSimilarAutoplayBeatDurationMillis(20f))
        assertEquals(273, localSimilarAutoplayBeatDurationMillis(260f))
    }

    @Test
    fun nextButtonShowsLoadingUntilLocalSimilarNextSongIsReady() {
        assertEquals(
            PlayerNextButtonState.LOADING,
            playerNextButtonState(
                canSkipNext = false,
                localSimilarPlaybackMode = true,
                localSimilarNextState = LocalSimilarNextState.LOADING,
            ),
        )
        assertEquals(
            PlayerNextButtonState.LOADING,
            playerNextButtonState(
                canSkipNext = false,
                localSimilarPlaybackMode = true,
                localSimilarNextState = LocalSimilarNextState.READY,
            ),
        )
        assertEquals(
            PlayerNextButtonState.READY,
            playerNextButtonState(
                canSkipNext = true,
                localSimilarPlaybackMode = true,
                localSimilarNextState = LocalSimilarNextState.READY,
            ),
        )
    }

    @Test
    fun nextButtonKeepsRegularQueueSemanticsOutsideLocalSimilarPlayback() {
        assertEquals(
            PlayerNextButtonState.READY,
            playerNextButtonState(
                canSkipNext = true,
                localSimilarPlaybackMode = false,
                localSimilarNextState = LocalSimilarNextState.IDLE,
            ),
        )
        assertEquals(
            PlayerNextButtonState.DISABLED,
            playerNextButtonState(
                canSkipNext = false,
                localSimilarPlaybackMode = false,
                localSimilarNextState = LocalSimilarNextState.IDLE,
            ),
        )
    }
}
