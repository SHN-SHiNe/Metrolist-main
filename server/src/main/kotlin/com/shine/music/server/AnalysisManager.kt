package com.shine.music.server

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes expensive native analysis so a large library cannot exhaust NAS CPU or memory. */
class AnalysisManager(
    private val store: MusicStore,
    modelPath: Path = VibenetModelLocator.resolve(),
    architecture: String = System.getProperty("os.arch").orEmpty(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val runtime = AudioAnalysisRuntime.create(modelPath, architecture)
    private val closed = AtomicBoolean()
    private val drainLock = Any()
    private var drainRequested = false
    private var drainScheduled = false
    private var drainBatchSize = DEFAULT_DRAIN_BATCH_SIZE
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shine-audio-analysis").apply { isDaemon = true }
    }

    val available: Boolean get() = runtime.available
    val implementation: String get() = runtime.implementation
    val unavailableReason: String? get() = runtime.unavailableReason

    init {
        store.resetInterruptedAnalysis()
        if (!available) logger.warn("Audio analysis unavailable: {}", unavailableReason)
    }

    fun summary(): AnalysisSummary = store.analysisSummary(available, implementation, unavailableReason)

    fun enqueue(trackId: String, force: Boolean = false): Boolean {
        check(!closed.get()) { "analysis_manager_closed" }
        if (!available || !store.setAnalysisQueued(trackId, force)) return false
        return try {
            executor.execute { analyze(trackId) }
            true
        } catch (_: RejectedExecutionException) {
            store.updateAnalysisState(trackId, "pending", 0f, null)
            false
        }
    }

    fun enqueue(trackIds: Iterable<String>, force: Boolean = false): Int = trackIds.count { enqueue(it, force) }

    fun enqueueMissing(limit: Int = DEFAULT_DRAIN_BATCH_SIZE): Int {
        if (!available || closed.get()) return 0
        return synchronized(drainLock) {
            drainBatchSize = limit.coerceIn(1, MAX_DRAIN_BATCH_SIZE)
            drainRequested = true
            scheduleNextDrainBatchLocked()
        }
    }

    /** Backwards-compatible descriptive alias for callers that already use this name. */
    fun enqueuePending(limit: Int = DEFAULT_DRAIN_BATCH_SIZE): Int = enqueueMissing(limit)

    /**
     * Marks only one bounded batch as queued. When that batch finishes, another is appended to
     * the executor tail. Explicit single-track requests submitted in between therefore run before
     * the next background batch, while the drain continues until no pending tracks remain.
     */
    private fun scheduleNextDrainBatchLocked(): Int {
        if (closed.get() || !drainRequested || drainScheduled) return 0
        drainScheduled = true
        val queuedIds = try {
            store.pendingAnalysisTrackIds(drainBatchSize, includeFailed = false)
                .filter { store.setAnalysisQueued(it) }
        } catch (error: Exception) {
            drainRequested = false
            drainScheduled = false
            logger.error("Unable to load the next audio-analysis batch", error)
            return 0
        }
        if (queuedIds.isEmpty()) {
            drainRequested = false
            drainScheduled = false
            return 0
        }
        return try {
            executor.execute {
                try {
                    queuedIds.forEach { trackId ->
                        if (closed.get()) {
                            store.updateAnalysisState(trackId, "pending", 0f, null)
                        } else {
                            analyze(trackId)
                        }
                    }
                } finally {
                    synchronized(drainLock) {
                        drainScheduled = false
                        if (!closed.get() && drainRequested) scheduleNextDrainBatchLocked()
                    }
                }
            }
            queuedIds.size
        } catch (_: RejectedExecutionException) {
            queuedIds.forEach { store.updateAnalysisState(it, "pending", 0f, null) }
            drainRequested = false
            drainScheduled = false
            0
        }
    }

    private fun analyze(trackId: String) {
        try {
            val analyzer = checkNotNull(runtime.analyzer) { "analysis_runtime_unavailable" }
            val source = store.analysisSource(trackId) ?: error("音乐文件不存在或已从曲库移除")
            val path = source.path
            store.updateAnalysisState(trackId, "running", 0.10f, "准备分析")
            val result = analyzer.analyze(path) { progress, message ->
                if (!closed.get()) store.updateAnalysisState(trackId, "running", progress, message)
            }
            if (closed.get()) return
            val sourceStillMatches = Files.isRegularFile(path) &&
                Files.size(path) == source.size &&
                Files.getLastModifiedTime(path).toMillis() == source.modifiedAt
            if (!sourceStillMatches || !store.saveAnalysis(trackId, result, clock(), source)) {
                logger.info("Discarded stale audio analysis for track {} because its source changed", trackId)
                if (!closed.get()) store.updateAnalysisState(trackId, "pending", 0f, null)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!closed.get()) store.updateAnalysisState(trackId, "pending", 0f, null)
        } catch (error: Exception) {
            fail(trackId, error)
        } catch (error: LinkageError) {
            fail(trackId, error)
        }
    }

    private fun fail(trackId: String, error: Throwable) {
        if (closed.get()) return
        val message = safeMessage(error)
        logger.error("Audio analysis failed for track {}: {}", trackId, message, error)
        store.updateAnalysisState(trackId, "failed", 0f, message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(drainLock) { drainRequested = false }
        executor.shutdownNow()
        val terminated = try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (terminated) {
            store.resetInterruptedAnalysis()
            runtime.close()
        } else {
            // Closing an ORT session underneath native inference is unsafe. The worker is a
            // daemon, so process shutdown will reclaim it and startup recovery resets its row.
            logger.warn("Audio analysis worker did not stop within {} seconds", SHUTDOWN_TIMEOUT_SECONDS)
        }
    }

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "分析失败").replace(Regex("[\\r\\n]+"), " ").take(500)

    private companion object {
        const val DEFAULT_DRAIN_BATCH_SIZE = 1
        const val MAX_DRAIN_BATCH_SIZE = 256
        const val SHUTDOWN_TIMEOUT_SECONDS = 30L
        val logger = LoggerFactory.getLogger(AnalysisManager::class.java)
    }
}
