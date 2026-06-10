package com.metrolist.music.ui.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.constants.LoudnessLevel
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.screens.comments.SongCommentsContent

@Composable
fun getLoudnessLevelLabel(loudnessLevel: LoudnessLevel): String {
    return when (loudnessLevel) {
        LoudnessLevel.AGGRESSIVE -> stringResource(R.string.loudness_level_aggressive)
        LoudnessLevel.LOUD -> stringResource(R.string.loudness_level_loud)
        LoudnessLevel.BALANCED -> stringResource(R.string.loudness_level_balanced)
        LoudnessLevel.QUIET -> stringResource(R.string.loudness_level_quiet)
    }
}

@Composable
fun ShowMediaInfo(videoId: String) {
    if (videoId.isBlank()) return
    val bottomSheetPageState = LocalBottomSheetPageState.current

    SongCommentsContent(
        mediaId = videoId,
        modifier = Modifier
            .padding(WindowInsets.systemBars.asPaddingValues())
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        onBack = bottomSheetPageState::dismiss,
    )
}

@Composable
fun ShowMediaInfo(
    videoId: String,
    onBack: @Composable ColumnScope.() -> Unit,
) {
    if (videoId.isBlank()) return
    val bottomSheetPageState = LocalBottomSheetPageState.current

    SongCommentsContent(
        mediaId = videoId,
        modifier = Modifier
            .padding(WindowInsets.systemBars.asPaddingValues())
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        onBack = {
            bottomSheetPageState.show(onBack)
        },
    )
}