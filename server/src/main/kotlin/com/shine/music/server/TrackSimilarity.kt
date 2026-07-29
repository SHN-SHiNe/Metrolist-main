package com.shine.music.server

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TrackFeatureVector(
    val trackId: String,
    val bpm: Float?,
    val keyName: String?,
    val valence: Float?,
    val energy: Float?,
    val danceability: Float?,
    val acousticness: Float?,
    val instrumentalness: Float?,
    val liveness: Float?,
    val speechiness: Float?,
)

data class RankedTrack(
    val trackId: String,
    val similarityPercent: Int,
    val bpmDelta: Float,
    val camelotDelta: Int,
)

/**
 * Server-side port of the Android local-similar selector.
 *
 * A recommendation must stay within five BPM, use a Camelot-compatible key,
 * and is then ranked by Euclidean distance across the original seven VibeNet
 * dimensions. Keeping this in one shared server implementation means every
 * browser hears the same continuation choices.
 */
object TrackSimilarity {
    fun rank(
        current: TrackFeatureVector?,
        candidates: List<TrackFeatureVector>,
        limit: Int = 30,
    ): List<RankedTrack> {
        current ?: return emptyList()
        if (!current.hasCompleteEmotionVector()) return emptyList()
        val currentBpm = current.bpm?.takeIf { it > 0f } ?: return emptyList()
        val currentCamelot = current.keyName?.canonicalMusicKey()?.let(::musicKeyToCamelot)
        val compatibleKeys = current.keyName?.compatibleCamelotKeys().orEmpty()

        return candidates.asSequence()
            .filter { it.trackId != current.trackId }
            .filter(TrackFeatureVector::hasCompleteEmotionVector)
            .mapNotNull { candidate ->
                val candidateBpm = candidate.bpm?.takeIf { it > 0f } ?: return@mapNotNull null
                val bpmDelta = kotlin.math.abs(candidateBpm - currentBpm)
                if (bpmDelta > 5f) return@mapNotNull null
                val candidateKey = candidate.keyName?.canonicalMusicKey()
                if (compatibleKeys.isNotEmpty() && candidateKey !in compatibleKeys) return@mapNotNull null
                val distance = emotionDistance(current, candidate)
                RankedTrack(
                    trackId = candidate.trackId,
                    similarityPercent = ((1f - distance / sqrt(7f)) * 100f).roundToInt().coerceIn(0, 100),
                    bpmDelta = bpmDelta,
                    camelotDelta = camelotDistance(currentCamelot, candidateKey?.let(::musicKeyToCamelot)),
                )
            }
            .sortedByDescending { it.similarityPercent }
            .take(limit.coerceIn(1, 100))
            .toList()
    }

    private fun emotionDistance(current: TrackFeatureVector, candidate: TrackFeatureVector): Float =
        sqrt(
            current.emotionVector().zip(candidate.emotionVector()).sumOf { (left, right) ->
                val delta = normalizeMetric(left ?: 0f) - normalizeMetric(right ?: 0f)
                delta.toDouble().pow(2.0)
            },
        ).toFloat()
}

fun TrackFeatureVector.hasCompleteEmotionVector(): Boolean = emotionVector().all { it != null }

fun TrackFeatureVector.emotionVector(): List<Float?> =
    listOf(valence, energy, danceability, acousticness, instrumentalness, liveness, speechiness)

fun normalizeMetric(value: Float): Float =
    if (value > 1f) (value / 100f).coerceIn(0f, 1f) else value.coerceIn(0f, 1f)

fun String.canonicalMusicKey(): String? {
    val cleaned = replace("\uFFFD", "")
        .replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), "")
        .trim()
        .takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
        ?: return null
    val normalized = cleaned
        .replace("♯", "#")
        .replace("＃", "#")
        .replace("♭", "b")
        .replace(Regex("\\s+"), " ")
        .trim()
    val camelot = Regex("^([ab])(\\d{1,2})$", RegexOption.IGNORE_CASE).matchEntire(normalized)
        ?: Regex("^(\\d{1,2})([ab])$", RegexOption.IGNORE_CASE).matchEntire(normalized)
    if (camelot != null) {
        val first = camelot.groupValues[1]
        val second = camelot.groupValues[2]
        val number = first.toIntOrNull() ?: second.toIntOrNull() ?: return null
        val mode = if (first.toIntOrNull() == null) first.uppercase() else second.uppercase()
        return camelotToMusicKey(number, mode)
    }
    val lower = normalized.lowercase()
    val note = normalized.substringBefore(" ").replaceFirstChar { it.uppercase() }
    val key = when {
        lower.endsWith(" minor") || lower.endsWith(" min") -> "${note}m"
        lower.endsWith(" major") || lower.endsWith(" maj") -> note
        lower.endsWith("m") && normalized.length > 1 -> normalized.replaceFirstChar { it.uppercase() }
        else -> normalized.replaceFirstChar { it.uppercase() }
    }
    return normalizeMusicKey(key)
}

fun String.compatibleCamelotKeys(): Set<String> {
    val canonical = canonicalMusicKey() ?: return emptySet()
    val camelot = musicKeyToCamelot(canonical) ?: return emptySet()
    val number = camelot.first
    val mode = camelot.second
    return setOfNotNull(
        camelotToMusicKey(number, mode),
        camelotToMusicKey(number, if (mode == "A") "B" else "A"),
        camelotToMusicKey(number % 12 + 1, mode),
        camelotToMusicKey((number + 10) % 12 + 1, mode),
    )
}

fun camelotLabel(keyName: String?): String? =
    keyName?.canonicalMusicKey()?.let(::musicKeyToCamelot)?.let { (number, mode) -> "$number$mode" }

private fun camelotDistance(left: Pair<Int, String>?, right: Pair<Int, String>?): Int {
    if (left == null || right == null) return 0
    if (left == right) return 0
    if (left.first == right.first) return 1
    val ring = kotlin.math.abs(left.first - right.first).let { minOf(it, 12 - it) }
    return ring + if (left.second == right.second) 0 else 1
}

private fun normalizeMusicKey(key: String): String? = when (key) {
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
    "Cm", "C#m", "Dm", "D#m", "Em", "Fm", "F#m", "Gm", "G#m", "Am", "A#m", "Bm",
    -> key
    "Db" -> "C#"
    "Eb" -> "D#"
    "Fb" -> "E"
    "Gb" -> "F#"
    "Ab" -> "G#"
    "Bb" -> "A#"
    "Cb" -> "B"
    "Dbm" -> "C#m"
    "Ebm" -> "D#m"
    "Fbm" -> "Em"
    "Gbm" -> "F#m"
    "Abm" -> "G#m"
    "Bbm" -> "A#m"
    "Cbm" -> "Bm"
    else -> null
}

fun musicKeyToCamelot(key: String): Pair<Int, String>? = when (key) {
    "G#m" -> 1 to "A"; "B" -> 1 to "B"; "D#m" -> 2 to "A"; "F#" -> 2 to "B"
    "A#m" -> 3 to "A"; "C#" -> 3 to "B"; "Fm" -> 4 to "A"; "G#" -> 4 to "B"
    "Cm" -> 5 to "A"; "D#" -> 5 to "B"; "Gm" -> 6 to "A"; "A#" -> 6 to "B"
    "Dm" -> 7 to "A"; "F" -> 7 to "B"; "Am" -> 8 to "A"; "C" -> 8 to "B"
    "Em" -> 9 to "A"; "G" -> 9 to "B"; "Bm" -> 10 to "A"; "D" -> 10 to "B"
    "F#m" -> 11 to "A"; "A" -> 11 to "B"; "C#m" -> 12 to "A"; "E" -> 12 to "B"
    else -> null
}

fun camelotToMusicKey(number: Int, mode: String): String? = when (number to mode.uppercase()) {
    1 to "A" -> "G#m"; 1 to "B" -> "B"; 2 to "A" -> "D#m"; 2 to "B" -> "F#"
    3 to "A" -> "A#m"; 3 to "B" -> "C#"; 4 to "A" -> "Fm"; 4 to "B" -> "G#"
    5 to "A" -> "Cm"; 5 to "B" -> "D#"; 6 to "A" -> "Gm"; 6 to "B" -> "A#"
    7 to "A" -> "Dm"; 7 to "B" -> "F"; 8 to "A" -> "Am"; 8 to "B" -> "C"
    9 to "A" -> "Em"; 9 to "B" -> "G"; 10 to "A" -> "Bm"; 10 to "B" -> "D"
    11 to "A" -> "F#m"; 11 to "B" -> "A"; 12 to "A" -> "C#m"; 12 to "B" -> "E"
    else -> null
}
