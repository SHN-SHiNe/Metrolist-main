package com.shine.music.server

import kotlin.math.abs
import kotlin.math.roundToInt

data class AdvancedRankedTrack(
    val trackId: String,
    val distance: Float,
    val similarityPercent: Int,
    val bpmDelta: Float?,
    val camelotDelta: Int?,
    val camelotModeChanged: Boolean?,
)

private data class AdvancedDistance(
    val score: Float,
    val bpmDelta: Float?,
    val camelotDelta: Int?,
    val camelotModeChanged: Boolean?,
)

/** Exact JVM port of the Android advanced local-music filter and scorer. */
object AdvancedTrackSearch {
    fun rank(candidates: List<TrackFeatureVector>, criteria: AdvancedSearchRequest): List<AdvancedRankedTrack> {
        validate(criteria)
        return candidates.mapNotNull { candidate ->
            distance(candidate, criteria)?.let { match ->
                AdvancedRankedTrack(
                    candidate.trackId,
                    match.score,
                    ((1f - match.score.coerceIn(0f, 1f)) * 100f).roundToInt().coerceIn(0, 100),
                    match.bpmDelta,
                    match.camelotDelta,
                    match.camelotModeChanged,
                )
            }
        }.sortedBy(AdvancedRankedTrack::distance)
    }

    fun validate(criteria: AdvancedSearchRequest) {
        criteria.bpm?.let { require(it.isFinite() && it in 40f..220f) { "invalid_bpm" } }
        require(criteria.bpmTolerance.isFinite() && criteria.bpmTolerance >= 0f) { "invalid_bpm_tolerance" }
        require(criteria.keyTolerance in 0..5) { "invalid_key_tolerance" }
        require(criteria.emotionTolerance.isFinite() && criteria.emotionTolerance in 0f..1f) { "invalid_emotion_tolerance" }
        listOf(
            criteria.valence,
            criteria.energy,
            criteria.danceability,
            criteria.acousticness,
            criteria.instrumentalness,
            criteria.liveness,
            criteria.speechiness,
        ).filterNotNull().forEach { require(it.isFinite() && it in 0f..1f) { "invalid_emotion_target" } }
    }

    private fun distance(candidate: TrackFeatureVector, criteria: AdvancedSearchRequest): AdvancedDistance? {
        var distance = 0f
        var activeFilters = 0
        var bpmDelta: Float? = null
        var camelotDelta: Int? = null
        var camelotModeChanged: Boolean? = null

        criteria.bpm?.let { target ->
            val value = candidate.bpm?.takeIf { it > 0f } ?: return null
            val tolerance = criteria.bpmTolerance.coerceAtLeast(0f)
            bpmDelta = value - target
            val absoluteDelta = abs(bpmDelta!!)
            if (absoluteDelta > tolerance) return null
            distance += absoluteDelta / tolerance.coerceAtLeast(1f)
            activeFilters++
        }

        criteria.keyName?.canonicalMusicKey()?.let { selectedKey ->
            val selected = musicKeyToCamelot(selectedKey) ?: return null
            val value = candidate.keyName?.canonicalMusicKey()?.let(::musicKeyToCamelot) ?: return null
            val matching = matchingCamelot(selected, criteria.keyTolerance)
            if (value !in matching) return null
            camelotDelta = signedCamelotDelta(value, selected)
            camelotModeChanged = value.second != selected.second
            distance += keyDistance(value, selected)
            activeFilters++
        }

        val tolerance = criteria.emotionTolerance.coerceIn(0f, 1f)
        listOf(
            criteria.valence to candidate.valence,
            criteria.energy to candidate.energy,
            criteria.danceability to candidate.danceability,
            criteria.acousticness to candidate.acousticness,
            criteria.instrumentalness to candidate.instrumentalness,
            criteria.liveness to candidate.liveness,
            criteria.speechiness to candidate.speechiness,
        ).forEach { (target, rawValue) ->
            if (target == null) return@forEach
            val value = rawValue?.let(::normalizeMetric) ?: return null
            val delta = abs(value - target.coerceIn(0f, 1f))
            if (delta > tolerance) return null
            distance += delta / tolerance.coerceAtLeast(0.001f)
            activeFilters++
        }

        return AdvancedDistance(
            score = if (activeFilters == 0) 0f else distance / activeFilters,
            bpmDelta = bpmDelta,
            camelotDelta = camelotDelta,
            camelotModeChanged = camelotModeChanged,
        )
    }

    fun matchingCamelot(selected: Pair<Int, String>, tolerance: Int): Set<Pair<Int, String>> {
        val number = normalizeCamelotNumber(selected.first)
        val mode = selected.second.uppercase()
        val ring = (-tolerance.coerceIn(0, 11)..tolerance.coerceIn(0, 11)).map { offset ->
            normalizeCamelotNumber(number + offset) to mode
        }
        return (ring + (number to if (mode == "A") "B" else "A")).toSet()
    }

    fun keyDistance(candidate: Pair<Int, String>, selected: Pair<Int, String>): Float {
        if (candidate == selected) return 0f
        if (candidate.first == selected.first && candidate.second != selected.second) return 0.5f
        val delta = abs(candidate.first - selected.first)
        return minOf(delta, 12 - delta).coerceAtLeast(1).toFloat()
    }

    fun signedCamelotDelta(candidate: Pair<Int, String>, selected: Pair<Int, String>): Int {
        val clockwise = (normalizeCamelotNumber(candidate.first) - normalizeCamelotNumber(selected.first)).mod(12)
        return if (clockwise > 6) clockwise - 12 else clockwise
    }

    private fun normalizeCamelotNumber(number: Int): Int = ((number - 1).mod(12)) + 1
}
