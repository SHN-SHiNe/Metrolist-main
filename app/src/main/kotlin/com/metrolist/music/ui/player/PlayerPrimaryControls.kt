package com.metrolist.music.ui.player

import com.metrolist.music.playback.LocalSimilarNextState
import kotlin.math.roundToInt

internal enum class PlayerPrimaryControl {
    REPEAT,
    PREVIOUS,
    PLAY_PAUSE,
    NEXT,
    FAVORITE,
}

internal fun playerPrimaryControls(
    useNewPlayerDesign: Boolean,
): List<PlayerPrimaryControl> =
    buildList {
        if (useNewPlayerDesign) {
            add(PlayerPrimaryControl.PREVIOUS)
            add(PlayerPrimaryControl.PLAY_PAUSE)
            add(PlayerPrimaryControl.NEXT)
        } else {
            add(PlayerPrimaryControl.REPEAT)
            add(PlayerPrimaryControl.PREVIOUS)
            add(PlayerPrimaryControl.PLAY_PAUSE)
            add(PlayerPrimaryControl.NEXT)
            add(PlayerPrimaryControl.FAVORITE)
        }
    }

internal data class LocalSimilarAutoplayIconState(
    val isAnimated: Boolean,
    val alpha: Float,
)

internal enum class PlayerNextButtonState {
    DISABLED,
    LOADING,
    READY,
}

internal fun playerNextButtonState(
    canSkipNext: Boolean,
    localSimilarPlaybackMode: Boolean,
    localSimilarNextState: LocalSimilarNextState,
): PlayerNextButtonState =
    if (localSimilarPlaybackMode) {
        if (canSkipNext && localSimilarNextState == LocalSimilarNextState.READY) {
            PlayerNextButtonState.READY
        } else {
            PlayerNextButtonState.LOADING
        }
    } else if (canSkipNext) {
        PlayerNextButtonState.READY
    } else {
        PlayerNextButtonState.DISABLED
    }

internal fun localSimilarAutoplayIconState(enabled: Boolean): LocalSimilarAutoplayIconState =
    LocalSimilarAutoplayIconState(
        isAnimated = enabled,
        alpha = if (enabled) 1f else 0.5f,
    )

internal fun localSimilarAutoplayBeatDurationMillis(bpm: Float?): Int {
    val beatBpm = bpm
        ?.takeIf { it.isFinite() && it > 0f }
        ?: 96f

    return (60_000f / beatBpm.coerceIn(45f, 220f)).roundToInt()
}
