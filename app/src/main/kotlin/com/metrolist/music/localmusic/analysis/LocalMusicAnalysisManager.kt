/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import android.net.Uri
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LocalMusicEntity
import com.metrolist.music.db.entities.LocalSong
import com.metrolist.music.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMusicAnalysisManager
@Inject
constructor(
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val database: MusicDatabase,
    private val audioPreprocessor: AudioPreprocessor,
    private val vibenetAnalyzer: VibenetOnDeviceAnalyzer,
    private val tempoKeyAnalyzer: TempoKeyAnalyzer,
    private val tagWriter: LocalMusicTagWriter,
) {
    private val mutex = Mutex()
    private val _states = MutableStateFlow<Map<String, LocalMusicAnalysisState>>(emptyMap())
    val states: StateFlow<Map<String, LocalMusicAnalysisState>> = _states.asStateFlow()

    fun analyze(localSong: LocalSong) {
        analyze(localSong.localMusic)
    }

    fun analyze(localMusic: LocalMusicEntity) {
        if (localMusic.songId.isBlank() || localMusic.contentUri.isBlank()) return
        val current = _states.value[localMusic.songId]?.status
        if (current == LocalMusicAnalysisStatus.Queued || current == LocalMusicAnalysisStatus.Running) return
        setState(
            songId = localMusic.songId,
            status = LocalMusicAnalysisStatus.Queued,
            message = "等待分析",
            progress = 0.05f,
        )
        applicationScope.launch(Dispatchers.Default) {
            mutex.withLock {
                runAnalysis(localMusic)
            }
        }
    }

    private suspend fun runAnalysis(localMusic: LocalMusicEntity) {
        setState(localMusic.songId, LocalMusicAnalysisStatus.Running, "解码音频", 0.12f)
        runCatching {
            val uri = Uri.parse(localMusic.contentUri)
            val decoded = audioPreprocessor.decodeMono16k(uri)
            setState(localMusic.songId, LocalMusicAnalysisStatus.Running, "提取特征", 0.35f)
            val mel = audioPreprocessor.extractVibenetMel(decoded.samples)
            setState(localMusic.songId, LocalMusicAnalysisStatus.Running, "情绪分析", 0.57f)
            val emotions = vibenetAnalyzer.analyze(mel)
            setState(localMusic.songId, LocalMusicAnalysisStatus.Running, "BPM/调性", 0.74f)
            val bpm = localMusic.bpm?.takeIf { it > 0f } ?: tempoKeyAnalyzer.estimateBpm(decoded.samples, decoded.sampleRate)
            val keyName = localMusic.keyName?.takeIf { it.isNotBlank() } ?: tempoKeyAnalyzer.estimateKey(decoded.samples, decoded.sampleRate)
            LocalMusicAnalysisResult(
                bpm = bpm,
                keyName = keyName,
                valence = emotions.valence,
                energy = emotions.energy,
                danceability = emotions.danceability,
                acousticness = emotions.acousticness,
                instrumentalness = emotions.instrumentalness,
                liveness = emotions.liveness,
                speechiness = emotions.speechiness,
            ).normalized()
        }.onSuccess { result ->
            setState(localMusic.songId, LocalMusicAnalysisStatus.Running, "保存结果", 0.86f)
            persistResult(localMusic.songId, result)
            setState(localMusic.songId, LocalMusicAnalysisStatus.Running, "写入标签", 0.94f)
            when (val writeResult = tagWriter.writeMp3Tags(Uri.parse(localMusic.contentUri), result)) {
                LocalMusicTagWriteResult.Written -> {
                    Timber.tag(TAG).i("Wrote local analysis tags for %s", localMusic.songId)
                }
                is LocalMusicTagWriteResult.Skipped -> {
                    Timber.tag(TAG).i("Skipped local analysis tag write for %s: %s", localMusic.songId, writeResult.reason)
                }
                is LocalMusicTagWriteResult.Failed -> {
                    Timber.tag(TAG).w(writeResult.throwable, "Failed to write local analysis tags for %s: %s", localMusic.songId, writeResult.reason)
                }
            }
            setState(localMusic.songId, LocalMusicAnalysisStatus.Complete, "分析完成", 1f)
            clearStateLater(localMusic.songId)
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Local music analysis failed for %s", localMusic.songId)
            setState(
                songId = localMusic.songId,
                status = LocalMusicAnalysisStatus.Failed,
                message = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun persistResult(songId: String, result: LocalMusicAnalysisResult) {
        withContext(Dispatchers.IO) {
            database.updateLocalMusicAnalysis(
                songId = songId,
                bpm = result.bpm,
                keyName = result.keyName,
                valence = result.valence,
                energy = result.energy,
                danceability = result.danceability,
                acousticness = result.acousticness,
                instrumentalness = result.instrumentalness,
                liveness = result.liveness,
                speechiness = result.speechiness,
                moodSummary = result.moodSummary(),
            )
        }
    }

    private fun setState(
        songId: String,
        status: LocalMusicAnalysisStatus,
        message: String? = null,
        progress: Float? = null,
    ) {
        _states.value = _states.value + (songId to LocalMusicAnalysisState(status, message, progress))
    }

    private fun clearStateLater(songId: String) {
        applicationScope.launch {
            delay(2_500)
            _states.value = _states.value - songId
        }
    }

    private companion object {
        const val TAG = "LocalMusicAnalysis"
    }
}
