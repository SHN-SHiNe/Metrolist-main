package com.shine.music.server

data class AudioAnalysisResult(
    val bpm: Float,
    val keyName: String,
    val valence: Float,
    val energy: Float,
    val danceability: Float,
    val acousticness: Float,
    val instrumentalness: Float,
    val liveness: Float,
    val speechiness: Float,
)

data class DecodedAudioSegment(
    val startSeconds: Double,
    val samples: FloatArray,
    val sampleRate: Int,
)

data class VibenetMelSpectrogram(
    val data: FloatArray,
    val melBands: Int,
    val frames: Int,
)

data class VibenetEmotionResult(
    val acousticness: Float,
    val danceability: Float,
    val energy: Float,
    val instrumentalness: Float,
    val liveness: Float,
    val speechiness: Float,
    val valence: Float,
) {
    fun normalized() = copy(
        acousticness = acousticness.coerceIn(0f, 1f),
        danceability = danceability.coerceIn(0f, 1f),
        energy = energy.coerceIn(0f, 1f),
        instrumentalness = instrumentalness.coerceIn(0f, 1f),
        liveness = liveness.coerceIn(0f, 1f),
        speechiness = speechiness.coerceIn(0f, 1f),
        valence = valence.coerceIn(0f, 1f),
    )
}

internal fun averageEmotionResults(results: List<VibenetEmotionResult>): VibenetEmotionResult {
    require(results.isNotEmpty()) { "At least one VibeNet result is required" }
    fun average(metric: (VibenetEmotionResult) -> Float) = results.map(metric).average().toFloat().coerceIn(0f, 1f)
    return VibenetEmotionResult(
        acousticness = average(VibenetEmotionResult::acousticness),
        danceability = average(VibenetEmotionResult::danceability),
        energy = average(VibenetEmotionResult::energy),
        instrumentalness = average(VibenetEmotionResult::instrumentalness),
        liveness = average(VibenetEmotionResult::liveness),
        speechiness = average(VibenetEmotionResult::speechiness),
        valence = average(VibenetEmotionResult::valence),
    )
}
