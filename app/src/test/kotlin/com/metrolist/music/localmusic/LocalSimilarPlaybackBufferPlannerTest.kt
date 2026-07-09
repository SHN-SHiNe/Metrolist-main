package com.metrolist.music.localmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSimilarPlaybackBufferPlannerTest {
    @Test
    fun placesCurrentSongBetweenPreviousHistoryAndPreparedNextSong() {
        val plan =
            LocalSimilarPlaybackBufferPlanner.plan(
                current = LocalSimilarPlaybackBufferItem(id = "current", value = "current-item"),
                previous = LocalSimilarPlaybackBufferItem(id = "previous", value = "previous-item"),
                next = LocalSimilarPlaybackBufferItem(id = "next", value = "next-item"),
            )

        assertEquals(listOf("previous-item", "current-item", "next-item"), plan.items)
        assertEquals(1, plan.currentIndex)
    }

    @Test
    fun keepsMultiplePreviousHistoryItemsBeforeCurrentSong() {
        val plan =
            LocalSimilarPlaybackBufferPlanner.plan(
                current = LocalSimilarPlaybackBufferItem(id = "current", value = "current-item"),
                previousItems =
                    listOf(
                        LocalSimilarPlaybackBufferItem(id = "older", value = "older-item"),
                        LocalSimilarPlaybackBufferItem(id = "previous", value = "previous-item"),
                    ),
                next = LocalSimilarPlaybackBufferItem(id = "next", value = "next-item"),
            )

        assertEquals(listOf("older-item", "previous-item", "current-item", "next-item"), plan.items)
        assertEquals(2, plan.currentIndex)
    }

    @Test
    fun keepsCurrentAtZeroWhenOnlyNextSongIsPrepared() {
        val plan =
            LocalSimilarPlaybackBufferPlanner.plan(
                current = LocalSimilarPlaybackBufferItem(id = "current", value = "current-item"),
                previous = null,
                next = LocalSimilarPlaybackBufferItem(id = "next", value = "next-item"),
            )

        assertEquals(listOf("current-item", "next-item"), plan.items)
        assertEquals(0, plan.currentIndex)
    }

    @Test
    fun removesDuplicateHistoryAndNextItems() {
        val plan =
            LocalSimilarPlaybackBufferPlanner.plan(
                current = LocalSimilarPlaybackBufferItem(id = "current", value = "current-item"),
                previousItems =
                    listOf(
                        LocalSimilarPlaybackBufferItem(id = "older", value = "older-item"),
                        LocalSimilarPlaybackBufferItem(id = "older", value = "duplicate-older"),
                        LocalSimilarPlaybackBufferItem(id = "current", value = "duplicate-current"),
                    ),
                next = LocalSimilarPlaybackBufferItem(id = "current", value = "duplicate-current-next"),
            )

        assertEquals(listOf("older-item", "current-item"), plan.items)
        assertEquals(1, plan.currentIndex)
    }

    @Test
    fun preservesCurrentPlaybackWhenOnlySurroundingBufferChanges() {
        val update =
            LocalSimilarPlaybackBufferPlanner.updateStrategy(
                currentPlayerIds = listOf("current"),
                currentPlayerIndex = 0,
                plannedIds = listOf("previous", "current", "next"),
                plannedCurrentIndex = 1,
            )

        assertEquals(LocalSimilarPlaybackBufferUpdateStrategy.PRESERVE_CURRENT_ITEM, update)
    }

    @Test
    fun replacesTimelineWhenCurrentSongChanges() {
        val update =
            LocalSimilarPlaybackBufferPlanner.updateStrategy(
                currentPlayerIds = listOf("old"),
                currentPlayerIndex = 0,
                plannedIds = listOf("previous", "current", "next"),
                plannedCurrentIndex = 1,
            )

        assertEquals(LocalSimilarPlaybackBufferUpdateStrategy.REPLACE_TIMELINE, update)
    }
}
