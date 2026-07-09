package com.metrolist.music.localmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSimilarPlaybackHistoryTest {
    @Test
    fun keepsChronologicalPreviousSongsForSwipeBackBuffer() {
        val history = LocalSimilarPlaybackHistory(maxSize = 12)

        listOf("a", "b", "c", "d").forEach(history::recordVisible)

        assertEquals(listOf("a", "b", "c"), history.previousSongIds(limit = 10))
        assertEquals("d", history.currentSongId)
    }

    @Test
    fun movingBackDoesNotTurnTheSongWeCameFromIntoPreviousHistory() {
        val history = LocalSimilarPlaybackHistory(maxSize = 12)

        listOf("a", "b", "c").forEach(history::recordVisible)
        history.recordVisible("b")

        assertEquals(listOf("a"), history.previousSongIds(limit = 10))
        assertEquals("c", history.nextHistorySongId())
    }

    @Test
    fun appendingAfterGoingBackDropsForwardBranch() {
        val history = LocalSimilarPlaybackHistory(maxSize = 12)

        listOf("a", "b", "c").forEach(history::recordVisible)
        history.recordVisible("b")
        history.recordVisible("d")

        assertEquals(listOf("a", "b"), history.previousSongIds(limit = 10))
        assertEquals(null, history.nextHistorySongId())
    }

    @Test
    fun previousSongIdsAreLimitedWithoutDroppingCurrentSong() {
        val history = LocalSimilarPlaybackHistory(maxSize = 12)

        (1..14).forEach { history.recordVisible("song-$it") }

        assertEquals((4..13).map { "song-$it" }, history.previousSongIds(limit = 10))
        assertEquals("song-14", history.currentSongId)
    }
}
