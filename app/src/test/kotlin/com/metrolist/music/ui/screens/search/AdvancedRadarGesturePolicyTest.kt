package com.metrolist.music.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedRadarGesturePolicyTest {
    @Test
    fun touchingRadarPointGivesGestureToRadarEvenWhenDragIsVertical() {
        assertEquals(
            RadarGestureOwner.RadarPoint,
            resolveRadarGestureOwner(startedOnPoint = true),
        )
    }

    @Test
    fun touchingOutsideRadarPointGivesGestureToPageScroll() {
        assertEquals(
            RadarGestureOwner.PageScroll,
            resolveRadarGestureOwner(startedOnPoint = false),
        )
    }

    @Test
    fun emotionGlowRadiusGrowsWithToleranceAndClamps() {
        assertEquals(18f, emotionGlowRadiusDp(-1f))
        assertEquals(18f, emotionGlowRadiusDp(0f))
        assertEquals(63f, emotionGlowRadiusDp(0.5f))
        assertEquals(108f, emotionGlowRadiusDp(1f))
        assertEquals(108f, emotionGlowRadiusDp(2f))
    }
}
