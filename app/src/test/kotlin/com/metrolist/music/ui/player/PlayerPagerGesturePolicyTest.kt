package com.metrolist.music.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPagerGesturePolicyTest {
    @Test
    fun collapsesPlayerWhenUserDragsDownFromSettledPlayerPage() {
        assertTrue(
            shouldCollapsePlayerFromPagerDownDrag(
                currentPage = 0,
                currentPageOffsetFraction = 0f,
                availableY = 24f,
                isUserInput = true,
            ),
        )
    }

    @Test
    fun doesNotCollapseWhenSimilarPageCanReturnToPlayerPage() {
        assertFalse(
            shouldCollapsePlayerFromPagerDownDrag(
                currentPage = 1,
                currentPageOffsetFraction = 0f,
                availableY = 24f,
                isUserInput = true,
            ),
        )
    }

    @Test
    fun doesNotCollapseWhilePagerIsBetweenPages() {
        assertFalse(
            shouldCollapsePlayerFromPagerDownDrag(
                currentPage = 0,
                currentPageOffsetFraction = 0.2f,
                availableY = 24f,
                isUserInput = true,
            ),
        )
    }

    @Test
    fun doesNotCollapseForUpwardDragOrProgrammaticScroll() {
        assertFalse(
            shouldCollapsePlayerFromPagerDownDrag(
                currentPage = 0,
                currentPageOffsetFraction = 0f,
                availableY = -24f,
                isUserInput = true,
            ),
        )
        assertFalse(
            shouldCollapsePlayerFromPagerDownDrag(
                currentPage = 0,
                currentPageOffsetFraction = 0f,
                availableY = 24f,
                isUserInput = false,
            ),
        )
    }
}
