package com.metrolist.music.ui.player

import kotlin.math.absoluteValue

private const val SettledPageOffsetThreshold = 0.001f

fun shouldCollapsePlayerFromPagerDownDrag(
    currentPage: Int,
    currentPageOffsetFraction: Float,
    availableY: Float,
    isUserInput: Boolean,
): Boolean =
    isUserInput &&
        currentPage == 0 &&
        currentPageOffsetFraction.absoluteValue <= SettledPageOffsetThreshold &&
        availableY > 0f
