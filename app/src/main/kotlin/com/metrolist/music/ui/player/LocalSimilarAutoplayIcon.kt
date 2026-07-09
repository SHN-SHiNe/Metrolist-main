package com.metrolist.music.ui.player

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.metrolist.music.R

@Composable
internal fun LocalSimilarAutoplayIcon(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    bpm: Float? = null,
    pulse: Float? = null,
) {
    val state = localSimilarAutoplayIconState(enabled)
    val animatedPulse = pulse ?: rememberLocalSimilarAutoplayPulse(state.isAnimated, bpm)

    Box(modifier = modifier) {
        if (state.isAnimated) {
            Icon(
                painter = painterResource(R.drawable.similar),
                contentDescription = null,
                tint = tint.copy(alpha = 0.38f * animatedPulse),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = 1f + 0.16f * animatedPulse
                            scaleX = scale
                            scaleY = scale
                        },
            )
        }
        Icon(
            painter = painterResource(R.drawable.similar),
            contentDescription = null,
            tint = tint,
            modifier =
                Modifier
                    .fillMaxSize()
                    .alpha(if (state.isAnimated) 0.58f + 0.42f * animatedPulse else state.alpha),
        )
    }
}

@Composable
internal fun rememberLocalSimilarAutoplayPulse(
    enabled: Boolean,
    bpm: Float?,
): Float {
    if (!enabled) return 0f

    val beatDurationMillis = localSimilarAutoplayBeatDurationMillis(bpm)
    return key(beatDurationMillis) {
        val transition = rememberInfiniteTransition(label = "localSimilarAutoplayPulse-$beatDurationMillis")
        val animatedPulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        keyframes {
                            durationMillis = beatDurationMillis
                            1f at 0
                            1f at (beatDurationMillis * 0.12f).toInt()
                            0f at beatDurationMillis using LinearOutSlowInEasing
                        },
                ),
            label = "localSimilarAutoplayPulseValue-$beatDurationMillis",
        )
        animatedPulse
    }
}
