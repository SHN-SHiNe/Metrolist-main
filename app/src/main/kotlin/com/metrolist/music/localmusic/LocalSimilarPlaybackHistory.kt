package com.metrolist.music.localmusic

class LocalSimilarPlaybackHistory(
    private val maxSize: Int,
) {
    private val songIds = mutableListOf<String>()
    private var currentIndex = -1

    val currentSongId: String?
        get() = songIds.getOrNull(currentIndex)

    fun recordVisible(songId: String) {
        val existingIndex = songIds.indexOf(songId)
        if (existingIndex >= 0) {
            currentIndex = existingIndex
            return
        }

        if (currentIndex in 0 until songIds.lastIndex) {
            songIds.subList(currentIndex + 1, songIds.size).clear()
        }

        songIds += songId
        currentIndex = songIds.lastIndex
        trimToMaxSize()
    }

    fun clear() {
        songIds.clear()
        currentIndex = -1
    }

    fun previousSongIds(limit: Int): List<String> =
        if (currentIndex <= 0) {
            emptyList()
        } else {
            songIds
                .take(currentIndex)
                .takeLast(limit.coerceAtLeast(0))
        }

    fun nextHistorySongId(): String? =
        songIds.getOrNull(currentIndex + 1)

    fun recentSongIds(limit: Int): List<String> =
        buildList {
            currentSongId?.let(::add)
            addAll(previousSongIds(Int.MAX_VALUE).asReversed())
            nextHistorySongId()?.let(::add)
        }.distinct().take(limit.coerceAtLeast(0))

    private fun trimToMaxSize() {
        while (songIds.size > maxSize && currentIndex > 0) {
            songIds.removeAt(0)
            currentIndex--
        }
    }
}
