package com.metrolist.music.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerCoverTapBehaviorTest {
    @Test
    fun coverTapNoLongerOpensInlineLyrics() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        assertFalse(
            "Cover tap should toggle the cover radar overlay instead of opening inline lyrics",
            source.contains("onTap = { showInlineLyrics = true }"),
        )
    }

    @Test
    fun coverTapTogglesEmotionRadarModeWithoutResettingModeOnSongChanges() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        assertTrue(source.contains("var showCoverEmotionRadar by rememberSaveable"))
        assertTrue(source.contains("var coverEmotionRadarVisible by rememberSaveable"))
        assertTrue(source.contains("showCoverEmotionRadar = !showCoverEmotionRadar"))
        assertFalse(
            "Changing songs should hide the current radar view, not turn off radar mode",
            source.contains("LaunchedEffect(mediaMetadata?.id) {\n        showCoverEmotionRadar = false\n    }"),
        )
    }

    @Test
    fun thumbnailAcceptsCoverOverlayContent() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt")

        assertTrue(source.contains("coverOverlay: (@Composable () -> Unit)? = null"))
        assertTrue(source.contains("coverOverlay?.invoke()"))
    }

    @Test
    fun thumbnailReportsScrollStartSoRadarCanYieldToSwipe() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt")

        assertTrue(source.contains("onScrollStart: (() -> Unit)? = null"))
        assertTrue(source.contains("snapshotFlow { thumbnailLazyGridState.isScrollInProgress }"))
        assertTrue(source.contains("onScrollStart?.invoke()"))
    }

    @Test
    fun coverRadarTemporarilyHidesDuringSwipeAndRestoresAfterSongChanges() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        assertTrue(source.contains("coverEmotionRadarVisible = false"))
        assertTrue(source.contains("showCoverEmotionRadar && mediaMetadata != null"))
        assertTrue(source.contains("coverEmotionRadarVisible = true"))
        assertTrue(source.contains("onScrollStart = { coverEmotionRadarVisible = false }"))
    }

    @Test
    fun coverRadarFadesAndExpandsFromCenter() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        assertTrue(source.contains("AnimatedVisibility("))
        assertTrue(source.contains("visible = coverEmotionRadarVisible"))
        assertTrue(source.contains("enter = fadeIn"))
        assertTrue(source.contains("exit = fadeOut"))
        assertTrue(source.contains("revealProgress"))
        assertTrue(source.contains("it * revealProgress"))
    }

    @Test
    fun coverRadarShowsMetricLabelsAndValuesAtPoints() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        listOf("Val", "Eng", "Dan", "Aco", "Ins", "Live", "Sp").forEach { label ->
            assertTrue("Cover radar should show $label", source.contains("\"$label\""))
        }
        assertTrue(source.contains("normalizedMetricText()"))
        assertTrue(source.contains("drawText"))
    }

    @Test
    fun coverCanPromptAndStartDownloadAnalyzeWhenValuesAreMissing() {
        val playerSource = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")
        val thumbnailSource = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt")

        assertTrue(thumbnailSource.contains("onLongPress: (() -> Unit)? = null"))
        assertTrue(thumbnailSource.contains("onLongPress = { onLongPress?.invoke() }"))
        assertTrue(playerSource.contains("PlayerCoverAnalysisOverlay("))
        assertTrue(playerSource.contains("PlayerCoverPendingAnalysisOverlay("))
        assertTrue(playerSource.contains("\"长按下载并分析\""))
        assertTrue(playerSource.contains("runLocalFileDownloadAction("))
        assertTrue(playerSource.contains("mode = LocalFileDownloadMode.DownloadAndAnalyze"))
    }

    @Test
    fun coverLongPressStartsAnalysisOnlyAfterRadarModeIsOpen() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        assertTrue(source.contains("val startCoverAnalysisFromRadar: () -> Unit = {"))
        assertTrue(source.contains("if (showCoverEmotionRadar) {"))
        assertTrue(source.contains("startCoverDownloadAnalyze()"))
        assertTrue(source.contains("onLongPress = startCoverAnalysisFromRadar"))
        assertFalse(
            "Plain cover long press should not start analysis before the radar mode is opened",
            source.contains("onLongPress = startCoverDownloadAnalyze"),
        )
    }

    @Test
    fun playerAnalysisAlwaysShowsCoverAnimationForReanalysisAndMenuActions() {
        val playerSource = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")
        val menuSource = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt")

        assertFalse(
            "Cover long press should allow re-analysis for already analyzed local songs",
            playerSource.contains("currentShareLocalMusic?.hasCompleteAnalysis() != true"),
        )
        assertTrue(playerSource.contains("val showCoverAnalysisAnimation: () -> Unit = {"))
        assertTrue(playerSource.contains("onAnalysisStarted = showCoverAnalysisAnimation"))
        assertTrue(menuSource.contains("onAnalysisStarted: () -> Unit = {},"))
        assertTrue(menuSource.contains("if (pendingFileDownloadMode == LocalFileDownloadMode.DownloadAndAnalyze) {"))
        assertTrue(menuSource.contains("onAnalysisStarted()"))
    }

    @Test
    fun pendingCoverAnalysisInterpolatesRandomValuesInsteadOfFlashing() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        assertTrue(source.contains("val animatedMetrics = pendingCoverMetricLabels.mapIndexed"))
        assertTrue(source.contains("targetValue = targetSnapshot.metrics.getOrElse(index)"))
        assertTrue(source.contains("animationSpec = tween(durationMillis = 620)"))
        assertTrue(source.contains("val animatedBpm by animateFloatAsState"))
        assertTrue(source.contains("val animatedSnapshot = targetSnapshot.copy(metrics = animatedMetrics, bpm = animatedBpm.roundToInt())"))
        assertTrue(source.contains("val radarPath = pathFor(animatedSnapshot.metrics)"))
        assertTrue(source.contains("animatedSnapshot.emotionLabels"))
    }

    @Test
    fun networkSimilarPageCanReturnToCoverPendingAnalysisPrompt() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt")

        assertTrue(source.contains("\"当前为网络音乐，需要下载并分析后才能进行向量匹配\""))
        assertTrue(source.contains("PlayerLocalSimilarNetworkAnalysisPrompt("))
        assertTrue(source.contains("onRequestCoverAnalysis = {"))
        assertTrue(source.contains("playerPagerState.animateScrollToPage(0)"))
        assertTrue(source.contains("showCoverEmotionRadar = true"))
        assertTrue(source.contains("coverEmotionRadarVisible = true"))
        assertTrue(source.contains("\"去下载并分析\""))
    }

    private fun sourceFile(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var root: File? = File(System.getProperty("user.dir") ?: ".")
        while (root != null) {
            candidates += File(root, relativePath)
            candidates += File(root, relativePath.removePrefix("app/"))
            root = root.parentFile
        }

        return candidates
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$relativePath not found")
    }
}
