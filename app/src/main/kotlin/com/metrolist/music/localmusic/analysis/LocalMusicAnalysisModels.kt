/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import com.metrolist.music.db.entities.LocalMusicEntity
import java.util.Locale
import kotlin.math.roundToInt

data class LocalMusicAnalysisResult(
    val bpm: Float,
    val keyName: String,
    val valence: Float,
    val energy: Float,
    val danceability: Float,
    val acousticness: Float,
    val instrumentalness: Float,
    val liveness: Float,
    val speechiness: Float,
) {
    fun normalized(): LocalMusicAnalysisResult =
        copy(
            bpm = bpm.coerceAtLeast(0f),
            keyName = keyName.trim(),
            valence = normalizeEmotion(valence),
            energy = normalizeEmotion(energy),
            danceability = normalizeEmotion(danceability),
            acousticness = normalizeEmotion(acousticness),
            instrumentalness = normalizeEmotion(instrumentalness),
            liveness = normalizeEmotion(liveness),
            speechiness = normalizeEmotion(speechiness),
        )

    fun moodSummary(): String =
        normalized().let { result ->
            listOf(
                "VALENCE" to result.valence,
                "ENERGY" to result.energy,
                "DANCEABILITY" to result.danceability,
                "ACOUSTICNESS" to result.acousticness,
                "INSTRUMENTALNESS" to result.instrumentalness,
                "LIVENESS" to result.liveness,
                "SPEECHINESS" to result.speechiness,
            ).joinToString(" | ") { (name, value) ->
                "$name:${formatMetric(value)}"
            }
        }
}

data class VibenetEmotionResult(
    val acousticness: Float,
    val danceability: Float,
    val energy: Float,
    val instrumentalness: Float,
    val liveness: Float,
    val speechiness: Float,
    val valence: Float,
) {
    fun normalized(): VibenetEmotionResult =
        copy(
            acousticness = normalizeEmotion(acousticness),
            danceability = normalizeEmotion(danceability),
            energy = normalizeEmotion(energy),
            instrumentalness = normalizeEmotion(instrumentalness),
            liveness = normalizeEmotion(liveness),
            speechiness = normalizeEmotion(speechiness),
            valence = normalizeEmotion(valence),
        )
}

enum class LocalMusicAnalysisStatus {
    Idle,
    Queued,
    Running,
    Complete,
    Failed,
}

data class LocalMusicAnalysisState(
    val status: LocalMusicAnalysisStatus = LocalMusicAnalysisStatus.Idle,
    val message: String? = null,
    val progress: Float? = null,
)

sealed interface LocalMusicTagWriteResult {
    data object Written : LocalMusicTagWriteResult
    data class Skipped(val reason: String) : LocalMusicTagWriteResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : LocalMusicTagWriteResult
}

fun LocalMusicEntity.hasCompleteAnalysis(): Boolean =
    bpm?.let { it > 0f } == true &&
        !keyName.isNullOrBlank() &&
        hasCompleteEmotionAnalysis()

fun LocalMusicEntity.hasCompleteEmotionAnalysis(): Boolean =
        valence != null &&
        energy != null &&
        danceability != null &&
        acousticness != null &&
        instrumentalness != null &&
        liveness != null &&
        speechiness != null

fun LocalMusicEntity.missingAnalysisLabels(): List<String> =
    buildList {
        if (bpm?.let { it > 0f } != true) add("BPM")
        if (keyName.isNullOrBlank()) add("KEY")
        if (valence == null) add("VAL")
        if (energy == null) add("ENG")
        if (danceability == null) add("DAN")
        if (acousticness == null) add("ACO")
        if (instrumentalness == null) add("INS")
        if (liveness == null) add("LIV")
        if (speechiness == null) add("SP")
    }

fun LocalMusicEntity.availableAnalysisLabels(): List<String> =
    buildList {
        bpm?.takeIf { it > 0f }?.let { add("${it.roundToInt()} BPM") }
        keyName?.cleanAnalysisLabelText()?.let { add(it) }
    }

private fun String.cleanAnalysisLabelText(): String? =
    replace("\uFFFD", "")
        .replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }

data class LocalMusicAnalysisActionPresentation(
    val label: String,
    val description: String,
    val progress: Float? = null,
    val enabled: Boolean = true,
)

fun LocalMusicAnalysisState?.actionPresentation(hasCompleteAnalysis: Boolean): LocalMusicAnalysisActionPresentation {
    val state = this ?: LocalMusicAnalysisState()
    return when (state.status) {
        LocalMusicAnalysisStatus.Queued ->
            LocalMusicAnalysisActionPresentation(
                label = "排队中",
                description = "等待分析 ${state.progressPercent(defaultProgress = 0.05f)}",
                progress = state.normalizedProgress(defaultProgress = 0.05f),
                enabled = false,
            )

        LocalMusicAnalysisStatus.Running -> {
            val progress = state.normalizedProgress(defaultProgress = 0.1f)
            LocalMusicAnalysisActionPresentation(
                label = state.progressPercent(defaultProgress = progress),
                description = "${state.message ?: "分析中"} ${state.progressPercent(defaultProgress = progress)}",
                progress = progress,
                enabled = false,
            )
        }

        LocalMusicAnalysisStatus.Failed ->
            LocalMusicAnalysisActionPresentation(
                label = "重试",
                description = "分析失败",
            )

        LocalMusicAnalysisStatus.Complete ->
            LocalMusicAnalysisActionPresentation(
                label = "已完成",
                description = "分析完成 100%",
                progress = 1f,
                enabled = false,
            )

        LocalMusicAnalysisStatus.Idle ->
            if (hasCompleteAnalysis) {
                LocalMusicAnalysisActionPresentation(
                    label = "重新分析",
                    description = "重新写入分析结果",
                )
            } else {
                LocalMusicAnalysisActionPresentation(
                    label = "待分析",
                    description = "点击立即分析",
                )
            }
    }
}

fun LocalMusicAnalysisState?.shouldShowInlineAnalysisAction(hasCompleteEmotionAnalysis: Boolean): Boolean {
    val state = this ?: LocalMusicAnalysisState()
    return when (state.status) {
        LocalMusicAnalysisStatus.Queued,
        LocalMusicAnalysisStatus.Running,
        -> true

        LocalMusicAnalysisStatus.Failed -> !hasCompleteEmotionAnalysis
        LocalMusicAnalysisStatus.Idle,
        LocalMusicAnalysisStatus.Complete,
        -> !hasCompleteEmotionAnalysis
    }
}

private fun LocalMusicAnalysisState.normalizedProgress(defaultProgress: Float): Float =
    (progress ?: defaultProgress).coerceIn(0f, 1f)

private fun LocalMusicAnalysisState.progressPercent(defaultProgress: Float): String =
    "${(normalizedProgress(defaultProgress) * 100).roundToInt()}%"

private fun normalizeEmotion(value: Float): Float =
    if (value > 10f) {
        (value / 100f).coerceIn(0f, 1f)
    } else {
        value.coerceIn(0f, 1f)
    }

private fun formatMetric(value: Float): String = String.format(Locale.US, "%.3f", value)
