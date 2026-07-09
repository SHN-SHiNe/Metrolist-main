/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp

class VibenetOutputParserTest {
    @Test
    fun parsesFlatOutputInVibenetLabelOrder() {
        val result =
            VibenetOutputParser.parse(
                floatArrayOf(
                    0f, // acousticness likelihood
                    0.25f, // danceability
                    0.75f, // energy
                    2f, // instrumentalness likelihood
                    -2f, // liveness likelihood
                    0.15f, // speechiness
                    0.85f, // valence
                ),
            )

        assertEquals(sigmoid(0f), result.acousticness, 0.0001f)
        assertEquals(0.25f, result.danceability, 0.0001f)
        assertEquals(0.75f, result.energy, 0.0001f)
        assertEquals(sigmoid(2f), result.instrumentalness, 0.0001f)
        assertEquals(sigmoid(-2f), result.liveness, 0.0001f)
        assertEquals(0.15f, result.speechiness, 0.0001f)
        assertEquals(0.85f, result.valence, 0.0001f)
    }

    @Test
    fun parsesBatchedOutputAndClampsContinuousValues() {
        val result =
            VibenetOutputParser.parse(
                arrayOf(
                    floatArrayOf(
                        0f,
                        -0.25f,
                        1.75f,
                        0f,
                        0f,
                        -1f,
                        2f,
                    ),
                ),
            )

        assertEquals(0f, result.danceability, 0.0001f)
        assertEquals(1f, result.energy, 0.0001f)
        assertEquals(0f, result.speechiness, 0.0001f)
        assertEquals(1f, result.valence, 0.0001f)
    }

    private fun sigmoid(value: Float): Float =
        (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
}
