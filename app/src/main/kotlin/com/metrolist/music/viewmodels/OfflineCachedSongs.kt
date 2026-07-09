package com.metrolist.music.viewmodels

import java.time.LocalDateTime

data class OfflineCachedSongRef(
    val id: String,
    val cachedAt: LocalDateTime?,
)

object OfflineCachedSongs {
    fun merge(
        explicitOffline: List<OfflineCachedSongRef>,
        playerCache: List<OfflineCachedSongRef>,
    ): List<OfflineCachedSongRef> {
        val firstSeen = linkedMapOf<String, Int>()
        val merged = linkedMapOf<String, OfflineCachedSongRef>()

        (explicitOffline + playerCache).forEach { ref ->
            if (ref.id.isBlank()) return@forEach
            firstSeen.putIfAbsent(ref.id, firstSeen.size)
            val existing = merged[ref.id]
            merged[ref.id] =
                when {
                    existing == null -> ref
                    existing.cachedAt == null -> ref
                    ref.cachedAt == null -> existing
                    ref.cachedAt.isAfter(existing.cachedAt) -> ref
                    else -> existing
                }
        }

        return merged.values.sortedWith(
            compareByDescending<OfflineCachedSongRef> { it.cachedAt ?: LocalDateTime.MIN }
                .thenBy { firstSeen[it.id] ?: Int.MAX_VALUE },
        )
    }
}
