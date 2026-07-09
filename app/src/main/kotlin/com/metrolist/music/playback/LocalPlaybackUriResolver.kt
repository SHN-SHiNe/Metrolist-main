package com.metrolist.music.playback

import com.metrolist.music.models.MediaMetadata

internal object LocalPlaybackUriResolver {
    fun resolve(
        requestedUri: String,
        metadata: MediaMetadata?,
        localContentUri: String?,
        isLocalPlaybackUri: (String) -> Boolean,
    ): String? {
        if (isLocalPlaybackUri(requestedUri)) return requestedUri

        metadata
            ?.playbackUri
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { metadata.isLocal || isLocalPlaybackUri(it) }
            ?.let { return it }

        return localContentUri
            ?.takeIf { it.isNotBlank() }
            ?.takeIf(isLocalPlaybackUri)
    }
}
