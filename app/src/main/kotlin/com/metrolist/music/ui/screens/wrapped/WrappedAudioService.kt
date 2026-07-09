/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class WrappedAudioService(
    private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var player: ExoPlayer? = null
    private var playbackJob: Job? = null

    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()

    private fun initPlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Timber.tag("WrappedAudioService").e(error, "Player error")
                        playbackJob?.cancel()
                    }
                })
            }
        }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        player?.volume = if (_isMuted.value) 0f else 1f
    }

    private fun prepareTheme() {
        initPlayer()
        val mediaItem = MediaItem.Builder()
            .setUri(getThemeUri())
            .setMediaId(THEME_MEDIA_ID)
            .build()
        player?.setMediaItem(mediaItem)
        player?.prepare()
    }

    fun playTrack(_songId: String?) {
        if (player?.currentMediaItem?.mediaId == THEME_MEDIA_ID) {
            Timber.tag("WrappedAudioService").d("Wrapped theme is already loaded or playing.")
            if (player?.isPlaying == false) player?.play()
            return
        }
        playbackJob?.cancel()

        playbackJob = scope.launch {
            try {
                prepareTheme()
                player?.seekTo(0)
                player?.play()
                player?.volume = if (_isMuted.value) 0f else 1f
            } catch (e: Exception) {
                Timber.tag("WrappedAudioService").e(e, "Error during playback preparation")
            }
        }
    }

    private fun getThemeUri(): Uri =
        "android.resource://${context.packageName}/${R.raw.wrapped_theme}".toUri()

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun release() {
        playbackJob?.cancel()
        player?.release()
        player = null
        Timber.tag("WrappedAudioService").d("Player released.")
    }

    private companion object {
        const val THEME_MEDIA_ID = "wrapped_theme"
    }
}
