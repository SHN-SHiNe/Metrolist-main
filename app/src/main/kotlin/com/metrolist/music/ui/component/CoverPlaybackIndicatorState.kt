package com.metrolist.music.ui.component

internal enum class CoverPlaybackIndicatorState {
    HIDDEN,
    PLAYING,
    PAUSED,
}

internal fun coverPlaybackIndicatorState(
    isActive: Boolean,
    isPlaying: Boolean,
    showPlaybackStateOverlay: Boolean,
    showPausedPlaybackIcon: Boolean,
): CoverPlaybackIndicatorState {
    if (!showPlaybackStateOverlay || !isActive) return CoverPlaybackIndicatorState.HIDDEN
    if (isPlaying) return CoverPlaybackIndicatorState.PLAYING
    return if (showPausedPlaybackIcon) CoverPlaybackIndicatorState.PAUSED else CoverPlaybackIndicatorState.HIDDEN
}
