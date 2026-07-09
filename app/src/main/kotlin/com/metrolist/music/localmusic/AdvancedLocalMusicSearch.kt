package com.metrolist.music.localmusic

import kotlin.math.abs
import kotlin.math.roundToInt

enum class AdvancedEmotionMetric(
    val label: String,
) {
    VALENCE("愉悦度"),
    ENERGY("能量"),
    DANCEABILITY("律动"),
    ACOUSTICNESS("原声"),
    INSTRUMENTALNESS("器乐"),
    LIVENESS("现场感"),
    SPEECHINESS("人声"),
}

data class AdvancedNumericFilter(
    val enabled: Boolean = false,
    val target: Float,
    val tolerance: Float,
)

data class AdvancedKeyFilter(
    val enabled: Boolean = false,
    val number: Int = 8,
    val mode: String = "B",
    val tolerance: Int = 0,
)

data class AdvancedLocalMusicSearchCriteria(
    val text: String = "",
    val bpm: AdvancedNumericFilter = AdvancedNumericFilter(target = 120f, tolerance = 5f),
    val key: AdvancedKeyFilter = AdvancedKeyFilter(),
    val emotions: Map<AdvancedEmotionMetric, AdvancedNumericFilter> =
        AdvancedEmotionMetric.entries.associateWith {
            AdvancedNumericFilter(target = 0.5f, tolerance = 0.05f)
        },
)

data class AdvancedLocalMusicSearchCandidate(
    val songId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val displayName: String,
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

data class AdvancedLocalMusicSearchResult(
    val songId: String,
    val distance: Float,
    val similarityPercent: Int,
)

object AdvancedLocalMusicSearch {
    val camelotKeys: List<CamelotKey> =
        (1..12).flatMap { number ->
            listOf(
                CamelotKey(number, "A", advancedCamelotShortName(number, "A") ?: ""),
                CamelotKey(number, "B", advancedCamelotShortName(number, "B") ?: ""),
            )
        }.filter { it.key.isNotBlank() }

    fun rank(
        candidates: List<AdvancedLocalMusicSearchCandidate>,
        criteria: AdvancedLocalMusicSearchCriteria,
    ): List<AdvancedLocalMusicSearchResult> =
        candidates
            .mapNotNull { candidate ->
                distance(candidate, criteria)?.let {
                    AdvancedLocalMusicSearchResult(candidate.songId, it, similarityPercent(it))
                }
            }
            .sortedBy { it.distance }

    fun similarityPercent(distance: Float): Int =
        ((1f - distance.coerceIn(0f, 1f)) * 100f).roundToInt().coerceIn(0, 100)

    fun withEmotionTolerance(
        filters: Map<AdvancedEmotionMetric, AdvancedNumericFilter>,
        tolerance: Float,
    ): Map<AdvancedEmotionMetric, AdvancedNumericFilter> {
        val normalizedTolerance = tolerance.coerceIn(0f, 1f)
        return AdvancedEmotionMetric.entries.associateWith { metric ->
            (
                filters[metric]
                    ?: AdvancedNumericFilter(target = 0.5f, tolerance = normalizedTolerance)
            ).copy(tolerance = normalizedTolerance)
        }
    }

    fun matchingKeyNumbers(filter: AdvancedKeyFilter): Set<Pair<Int, String>> {
        if (!filter.enabled) return emptySet()
        val number = filter.number.coerceIn(1, 12)
        val mode = filter.mode.uppercase()
        val sameMode =
            (-filter.tolerance..filter.tolerance).map { offset ->
                advancedNormalizeCamelotNumber(number + offset) to mode
            }
        return (sameMode + (number to if (mode == "A") "B" else "A")).toSet()
    }

    fun camelotLabel(keyName: String?): String? {
        val camelot = keyName?.canonicalMusicKey()?.let(::advancedKeyToCamelot) ?: return null
        return "${camelot.first}${camelot.second}"
    }

    fun keyLabel(number: Int, mode: String): String =
        advancedCamelotShortName(number, mode)?.let { "$number${mode.uppercase()} / $it" }.orEmpty()

    fun keyShortLabel(number: Int, mode: String): String =
        advancedCamelotShortName(number, mode)?.let { "$number${mode.uppercase()}\n$it" }.orEmpty()

    private fun distance(
        candidate: AdvancedLocalMusicSearchCandidate,
        criteria: AdvancedLocalMusicSearchCriteria,
    ): Float? {
        if (!matchesText(candidate, criteria.text)) return null
        var distance = 0f
        var activeFilters = 0

        criteria.bpm.takeIf { it.enabled }?.let { filter ->
            val bpm = candidate.bpm?.takeIf { it > 0f } ?: return null
            val delta = abs(bpm - filter.target)
            if (delta > filter.tolerance) return null
            distance += delta / filter.tolerance.coerceAtLeast(1f)
            activeFilters++
        }

        criteria.key.takeIf { it.enabled }?.let { filter ->
            val candidateCamelot = candidate.keyName?.canonicalMusicKey()?.let(::advancedKeyToCamelot) ?: return null
            val matched = matchingKeyNumbers(filter)
            if (candidateCamelot !in matched) return null
            distance += keyDistance(candidateCamelot, filter)
            activeFilters++
        }

        criteria.emotions.forEach { (metric, filter) ->
            if (!filter.enabled) return@forEach
            val value = candidate.emotion(metric)?.let(::normalizeLocalMusicMetric) ?: return null
            val delta = abs(value - filter.target)
            if (delta > filter.tolerance) return null
            distance += delta / filter.tolerance.coerceAtLeast(0.001f)
            activeFilters++
        }

        return if (activeFilters == 0) 0f else distance / activeFilters
    }

    private fun matchesText(candidate: AdvancedLocalMusicSearchCandidate, text: String): Boolean {
        val query = text.trim().lowercase()
        if (query.isBlank()) return true
        return listOf(candidate.title, candidate.artists, candidate.album.orEmpty(), candidate.displayName, candidate.keyName.orEmpty())
            .any { it.lowercase().contains(query) }
    }

    private fun AdvancedLocalMusicSearchCandidate.emotion(metric: AdvancedEmotionMetric): Float? =
        when (metric) {
            AdvancedEmotionMetric.VALENCE -> valence
            AdvancedEmotionMetric.ENERGY -> energy
            AdvancedEmotionMetric.DANCEABILITY -> danceability
            AdvancedEmotionMetric.ACOUSTICNESS -> acousticness
            AdvancedEmotionMetric.INSTRUMENTALNESS -> instrumentalness
            AdvancedEmotionMetric.LIVENESS -> liveness
            AdvancedEmotionMetric.SPEECHINESS -> speechiness
        }

    private fun keyDistance(candidate: Pair<Int, String>, filter: AdvancedKeyFilter): Float {
        val selected = filter.number.coerceIn(1, 12) to filter.mode.uppercase()
        if (candidate == selected) return 0f
        if (candidate.first == selected.first && candidate.second != selected.second) return 0.5f
        val ringDistance = circularDistance(candidate.first, selected.first).toFloat()
        return ringDistance.coerceAtLeast(1f)
    }
}

data class CamelotKey(
    val number: Int,
    val mode: String,
    val key: String,
)

fun advancedKeyToCamelot(key: String): Pair<Int, String>? =
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

fun advancedCamelotDisplayName(number: Int, mode: String): String? =
    when (advancedNormalizeCamelotNumber(number) to mode.uppercase()) {
        1 to "A" -> "A-Flat Minor"
        1 to "B" -> "B Major"
        2 to "A" -> "E-Flat Minor"
        2 to "B" -> "F-Sharp Major"
        3 to "A" -> "B-Flat Minor"
        3 to "B" -> "D-Flat Major"
        4 to "A" -> "F Minor"
        4 to "B" -> "A-Flat Major"
        5 to "A" -> "C Minor"
        5 to "B" -> "E-Flat Major"
        6 to "A" -> "G Minor"
        6 to "B" -> "B-Flat Major"
        7 to "A" -> "D Minor"
        7 to "B" -> "F Major"
        8 to "A" -> "A Minor"
        8 to "B" -> "C Major"
        9 to "A" -> "E Minor"
        9 to "B" -> "G Major"
        10 to "A" -> "B Minor"
        10 to "B" -> "D Major"
        11 to "A" -> "F-Sharp Minor"
        11 to "B" -> "A Major"
        12 to "A" -> "D-Flat Minor"
        12 to "B" -> "E Major"
        else -> null
    }

fun advancedCamelotShortName(number: Int, mode: String): String? =
    when (advancedNormalizeCamelotNumber(number) to mode.uppercase()) {
        1 to "A" -> "Abm"
        1 to "B" -> "B"
        2 to "A" -> "Ebm"
        2 to "B" -> "F#"
        3 to "A" -> "Bbm"
        3 to "B" -> "Db"
        4 to "A" -> "Fm"
        4 to "B" -> "Ab"
        5 to "A" -> "Cm"
        5 to "B" -> "Eb"
        6 to "A" -> "Gm"
        6 to "B" -> "Bb"
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
        12 to "A" -> "Dbm"
        12 to "B" -> "E"
        else -> null
    }

fun advancedKeyDisplayName(keyName: String?): String? {
    val canonical = keyName?.canonicalMusicKey() ?: return null
    val camelot = advancedKeyToCamelot(canonical) ?: return null
    return advancedCamelotShortName(camelot.first, camelot.second)
}

fun advancedCamelotToKey(number: Int, mode: String): String? =
    when (advancedNormalizeCamelotNumber(number) to mode.uppercase()) {
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

fun advancedNormalizeCamelotNumber(number: Int): Int =
    ((number - 1).floorMod(12)) + 1

private fun circularDistance(a: Int, b: Int): Int {
    val delta = abs(advancedNormalizeCamelotNumber(a) - advancedNormalizeCamelotNumber(b))
    return minOf(delta, 12 - delta)
}

private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
