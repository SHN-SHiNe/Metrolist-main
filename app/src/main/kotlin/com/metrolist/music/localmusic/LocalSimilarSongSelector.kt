package com.metrolist.music.localmusic

import com.metrolist.music.db.entities.LocalMusicEntity
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class LocalSimilarSongAnalysis(
    val songId: String,
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

data class LocalSimilarSongRecommendation(
    val song: LocalSimilarSongAnalysis,
    val similarityPercent: Int,
)

object LocalSimilarSongSelector {
    fun recommendations(
        current: LocalSimilarSongAnalysis?,
        candidates: List<LocalSimilarSongAnalysis>,
        limit: Int = 30,
    ): List<LocalSimilarSongRecommendation> =
        rankedRecommendations(current, candidates)
            .take(limit)

    fun selectBest(
        current: LocalSimilarSongAnalysis?,
        candidates: List<LocalSimilarSongAnalysis>,
        recentSongIds: List<String>,
        allowRecentFallback: Boolean = false,
    ): LocalSimilarSongRecommendation? {
        val ranked = rankedRecommendations(current, candidates)
        if (ranked.isEmpty()) return null

        val recent = recentSongIds.toSet()
        val immediatePrevious = recentSongIds.drop(1).firstOrNull()
        return ranked.firstOrNull { it.song.songId !in recent }
            ?: ranked.firstOrNull { allowRecentFallback && it.song.songId != immediatePrevious }
    }

    private fun rankedRecommendations(
        current: LocalSimilarSongAnalysis?,
        candidates: List<LocalSimilarSongAnalysis>,
    ): List<LocalSimilarSongRecommendation> {
        current ?: return emptyList()
        if (!current.hasCompleteEmotionVector()) return emptyList()
        val currentBpm = current.bpm?.takeIf { it > 0f } ?: return emptyList()
        val compatibleKeys = current.keyName?.compatibleCamelotKeys().orEmpty()

        return candidates.asSequence()
            .filter { it.songId != current.songId }
            .filter { it.hasCompleteEmotionVector() }
            .filter { candidate -> candidate.bpm?.let { kotlin.math.abs(it - currentBpm) <= 5f } == true }
            .filter { candidate ->
                compatibleKeys.isEmpty() ||
                    candidate.keyName?.canonicalMusicKey()?.let { it in compatibleKeys } == true
            }
            .map { candidate ->
                val distance = localEmotionDistance(current, candidate)
                LocalSimilarSongRecommendation(
                    song = candidate,
                    similarityPercent = localEmotionSimilarityPercent(distance),
                )
            }
            .sortedByDescending { it.similarityPercent }
            .toList()
    }
}

fun LocalSimilarSongAnalysis.hasCompleteEmotionVector(): Boolean =
    emotionVector().all { it != null }

fun LocalSimilarSongAnalysis.emotionVector(): List<Float?> =
    listOf(valence, energy, danceability, acousticness, instrumentalness, liveness, speechiness)

fun LocalMusicEntity.toLocalSimilarSongAnalysis(): LocalSimilarSongAnalysis =
    LocalSimilarSongAnalysis(
        songId = songId,
        bpm = bpm,
        keyName = keyName,
        valence = valence,
        energy = energy,
        danceability = danceability,
        acousticness = acousticness,
        instrumentalness = instrumentalness,
        liveness = liveness,
        speechiness = speechiness,
    )

fun normalizeLocalMusicMetric(value: Float): Float =
    if (value > 1f) (value / 100f).coerceIn(0f, 1f) else value.coerceIn(0f, 1f)

private fun localEmotionDistance(current: LocalSimilarSongAnalysis, candidate: LocalSimilarSongAnalysis): Float =
    sqrt(
        current.emotionVector()
            .zip(candidate.emotionVector())
            .sumOf { (a, b) ->
                val delta = normalizeLocalMusicMetric(a ?: 0f) - normalizeLocalMusicMetric(b ?: 0f)
                delta.toDouble().pow(2.0)
            },
    ).toFloat()

private fun localEmotionSimilarityPercent(distance: Float): Int =
    ((1f - distance / sqrt(7f)) * 100f).roundToInt().coerceIn(0, 100)

fun String.canonicalMusicKey(): String? {
    val cleaned = cleanDisplayText() ?: return null
    val normalized = cleaned
        .replace("♯", "#")
        .replace("＃", "#")
        .replace("♭", "b")
        .replace(Regex("\\s+"), " ")
        .trim()
    val lower = normalized.lowercase()
    val camelotMatch = Regex("^([ab])(\\d{1,2})$", RegexOption.IGNORE_CASE).matchEntire(normalized)
        ?: Regex("^(\\d{1,2})([ab])$", RegexOption.IGNORE_CASE).matchEntire(normalized)
    if (camelotMatch != null) {
        val first = camelotMatch.groupValues[1]
        val second = camelotMatch.groupValues[2]
        val number = first.toIntOrNull() ?: second.toIntOrNull() ?: return normalized
        val mode = if (first.toIntOrNull() == null) first.uppercase() else second.uppercase()
        return camelotToKey(number, mode)
    }
    val note = normalized.substringBefore(" ").replaceFirstChar { it.uppercase() }
    return when {
        lower.endsWith(" minor") -> normalizeMusicKey("${note}m")
        lower.endsWith(" major") -> normalizeMusicKey(note)
        lower.endsWith(" min") -> normalizeMusicKey("${note}m")
        lower.endsWith(" maj") -> normalizeMusicKey(note)
        lower.endsWith("m") && normalized.length > 1 -> normalizeMusicKey(normalized.replaceFirstChar { it.uppercase() })
        else -> normalizeMusicKey(normalized.replaceFirstChar { it.uppercase() })
    }
}

fun String.compatibleCamelotKeys(): Set<String> {
    val canonical = canonicalMusicKey() ?: return emptySet()
    val camelot = keyToCamelot(canonical) ?: return emptySet()
    val num = camelot.first
    val mode = camelot.second
    return setOf(
        camelotToKey(num, mode),
        camelotToKey(num, if (mode == "A") "B" else "A"),
        camelotToKey((num % 12) + 1, mode),
        camelotToKey(((num - 2) % 12) + 1, mode),
    ).filterNotNull().toSet()
}

private fun String.cleanDisplayText(): String? =
    replace("\uFFFD", "")
        .replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), "")
        .trim()
        .takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }

private fun normalizeMusicKey(key: String): String? =
    when (key) {
        "C", "C#" -> key
        "Db" -> "C#"
        "D", "D#" -> key
        "Eb" -> "D#"
        "E", "Fb" -> "E"
        "F", "F#" -> key
        "Gb" -> "F#"
        "G", "G#" -> key
        "Ab" -> "G#"
        "A", "A#" -> key
        "Bb" -> "A#"
        "B", "Cb" -> "B"
        "Cm", "C#m" -> key
        "Dbm" -> "C#m"
        "Dm", "D#m" -> key
        "Ebm" -> "D#m"
        "Em", "Fbm" -> "Em"
        "Fm", "F#m" -> key
        "Gbm" -> "F#m"
        "Gm", "G#m" -> key
        "Abm" -> "G#m"
        "Am", "A#m" -> key
        "Bbm" -> "A#m"
        "Bm", "Cbm" -> "Bm"
        else -> null
    }

private fun keyToCamelot(key: String): Pair<Int, String>? =
    when (key) {
        "G#m" -> 1 to "A"
        "B" -> 1 to "B"
        "D#m" -> 2 to "A"
        "F#" -> 2 to "B"
        "A#m" -> 3 to "A"
        "C#" -> 3 to "B"
        "Fm" -> 4 to "A"
        "G#" -> 4 to "B"
        "Cm" -> 5 to "A"
        "D#" -> 5 to "B"
        "Gm" -> 6 to "A"
        "A#" -> 6 to "B"
        "Dm" -> 7 to "A"
        "F" -> 7 to "B"
        "Am" -> 8 to "A"
        "C" -> 8 to "B"
        "Em" -> 9 to "A"
        "G" -> 9 to "B"
        "Bm" -> 10 to "A"
        "D" -> 10 to "B"
        "F#m" -> 11 to "A"
        "A" -> 11 to "B"
        "C#m" -> 12 to "A"
        "E" -> 12 to "B"
        else -> null
    }

private fun camelotToKey(number: Int, mode: String): String? =
    when (number to mode.uppercase()) {
        1 to "A" -> "G#m"
        1 to "B" -> "B"
        2 to "A" -> "D#m"
        2 to "B" -> "F#"
        3 to "A" -> "A#m"
        3 to "B" -> "C#"
        4 to "A" -> "Fm"
        4 to "B" -> "G#"
        5 to "A" -> "Cm"
        5 to "B" -> "D#"
        6 to "A" -> "Gm"
        6 to "B" -> "A#"
        7 to "A" -> "Dm"
        7 to "B" -> "F"
        8 to "A" -> "Am"
        8 to "B" -> "C"
        9 to "A" -> "Em"
        9 to "B" -> "G"
        10 to "A" -> "Bm"
        10 to "B" -> "D"
        11 to "A" -> "F#m"
        11 to "B" -> "A"
        12 to "A" -> "C#m"
        12 to "B" -> "E"
        else -> null
    }
