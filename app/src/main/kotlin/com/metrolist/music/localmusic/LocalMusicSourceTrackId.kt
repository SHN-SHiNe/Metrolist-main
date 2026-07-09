package com.metrolist.music.localmusic

object LocalMusicSourceTrackId {
    const val ID3_DESCRIPTION = "SHINE_SOURCE_TRACK_ID"

    data class IdentityResolution(
        val songId: String,
        val rebindFromSongId: String? = null,
    )

    fun normalize(value: String?): String? =
        value
            ?.trim()
            ?.replace(Regex("[\\p{Cntrl}]"), "")
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { !it.startsWith("local_") }

    fun resolveIdentity(
        existingSongId: String?,
        sourceTrackId: String?,
        stableSongId: String,
    ): IdentityResolution {
        val normalizedSource = normalize(sourceTrackId)
        if (normalizedSource != null) {
            return IdentityResolution(
                songId = normalizedSource,
                rebindFromSongId = existingSongId?.takeIf { it != normalizedSource },
            )
        }
        return IdentityResolution(
            songId = existingSongId ?: stableSongId,
        )
    }
}
