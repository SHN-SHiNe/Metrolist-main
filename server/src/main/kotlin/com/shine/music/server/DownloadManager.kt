package com.shine.music.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.extension

class DownloadManager(
    private val store: MusicStore,
    private val libraries: MusicLibraryManager,
    private val onlineCatalog: OnlineCatalog,
    private val clock: () -> Long = System::currentTimeMillis,
    maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT_DOWNLOADS,
    private val taskProcessor: (suspend (DownloadJob, DownloadRequest) -> Unit)? = null,
    private val onTrackIndexed: () -> Unit = {},
) : AutoCloseable {
    private val workerCount = maxConcurrentDownloads.also {
        require(it in 1..MAX_CONCURRENT_DOWNLOADS) { "invalid_download_worker_count" }
    }
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val wakeups = Channel<Unit>(workerCount)
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    private val closed = AtomicBoolean()

    init {
        store.failInterruptedDownloads(clock())
        repeat(workerCount) { scope.launch { runWorker() } }
    }

    fun enqueue(request: DownloadRequest): DownloadJob {
        check(!closed.get()) { "download_manager_closed" }
        require(request.url != null || request.trackId != null) { "url_or_track_id_required" }
        val now = clock()
        val job = DownloadJob(UUID.randomUUID().toString(), request.title.trim(), request.artist.trim(), "queued", createdAt = now, updatedAt = now)
        store.saveDownload(job, request)
        wakeWorkers()
        return job
    }

    fun retry(id: String): DownloadJob {
        check(!closed.get()) { "download_manager_closed" }
        val (previous, request) = store.downloadForRetry(id) ?: throw IllegalArgumentException("failed_download_not_found")
        val queued = previous.copy(
            status = "queued",
            error = null,
            updatedAt = clock(),
            downloadedBytes = 0,
            totalBytes = null,
        )
        store.saveDownload(queued)
        wakeWorkers()
        return queued
    }

    private fun wakeWorkers() {
        repeat(workerCount) { wakeups.trySend(Unit) }
    }

    private suspend fun runWorker() {
        for (ignored in wakeups) {
            while (!closed.get()) {
                val task = try {
                    store.claimQueuedDownload(clock()) ?: break
                } catch (error: Exception) {
                    logger.error("Unable to claim queued download", error)
                    break
                }
                val processor = taskProcessor
                if (processor == null) {
                    download(task.job, task.request)
                } else {
                    try {
                        processor(task.job, task.request)
                    } catch (error: Throwable) {
                        if (!closed.get()) update(task.job, "failed", error.message ?: error::class.simpleName)
                    }
                }
            }
        }
    }

    private suspend fun download(job: DownloadJob, request: DownloadRequest) {
        var temporary: java.nio.file.Path? = null
        try {
            val url = request.url ?: onlineCatalog.resolve(request.trackId!!) ?: error("unable_to_resolve_track")
            store.saveResolvedDownloadUrl(job.id, url)
            val uri = URI(url)
            require(uri.scheme in setOf("http", "https")) { "unsupported_url_scheme" }
            ensurePublicHost(uri)
            val library = libraries.downloadTarget()
            val musicDir = java.nio.file.Path.of(library.path)
            val incomingDir = musicDir.resolve(".shine-incoming")
            incomingDir.createDirectories()
            val response = http.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).header("User-Agent", "SHiNe-Music-NAS/0.1").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            currentCoroutineContext().ensureActive()
            require(response.statusCode() in 200..299) { "download_http_${response.statusCode()}" }
            val totalBytes = response.headers().firstValueAsLong("content-length").orElse(-1).takeIf { it > 0 }
            store.updateDownloadProgress(job.id, downloadedBytes = 0, totalBytes = totalBytes, now = clock())
            val contentType = response.headers().firstValue("content-type").orElse("audio/mpeg").substringBefore(';')
            val extension = extensionFor(uri.path.substringAfterLast('.', ""), contentType)
            val filename = "${safe(request.artist)} - ${safe(request.title)}.$extension"
            val part = incomingDir.resolve("${job.id}.part")
            temporary = part
            val downloadedBytes = response.body().use { input ->
                copyDownloadStream(input, part, clock) { downloaded ->
                    store.updateDownloadProgress(job.id, downloaded, totalBytes, clock())
                }
            }
            currentCoroutineContext().ensureActive()
            require(downloadedBytes > 0) { "empty_download" }
            require(totalBytes == null || downloadedBytes == totalBytes) { "incomplete_download" }
            val destination = Files.move(part, musicDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            temporary = null
            libraries.indexDownloaded(library.id, destination)
            runCatching(onTrackIndexed).onFailure { logger.warn("Downloaded track was indexed but analysis enqueue failed", it) }
            update(job, "completed")
        } catch (error: Throwable) {
            if (!closed.get()) update(job, "failed", error.message ?: error::class.simpleName)
        } finally {
            temporary?.let(Files::deleteIfExists)
        }
    }

    private fun update(job: DownloadJob, status: String, error: String? = null) {
        store.updateDownloadStatus(job.id, status, error, clock())
    }

    private fun ensurePublicHost(uri: URI) {
        val address = InetAddress.getByName(uri.host ?: error("missing_host"))
        require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress) {
            "private_network_download_blocked"
        }
    }

    private fun safe(value: String) = value.ifBlank { "未知" }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)

    private fun extensionFor(fromUrl: String, contentType: String): String = when {
        fromUrl.lowercase() in setOf("mp3", "flac", "ogg", "m4a", "aac", "wav", "opus") -> fromUrl.lowercase()
        contentType.contains("flac") -> "flac"
        contentType.contains("ogg") -> "ogg"
        contentType.contains("mp4") || contentType.contains("aac") -> "m4a"
        contentType.contains("wav") -> "wav"
        else -> "mp3"
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 3
        const val MAX_CONCURRENT_DOWNLOADS = 16
        val logger = LoggerFactory.getLogger(DownloadManager::class.java)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        wakeups.close()
        supervisor.cancel()
        http.shutdownNow()
        runBlocking { withTimeoutOrNull(5_000) { supervisor.join() } }
    }
}

/** Copies a response body while reporting at most once per interval, plus one exact final update. */
internal suspend fun copyDownloadStream(
    input: InputStream,
    destination: Path,
    clock: () -> Long = System::currentTimeMillis,
    onProgress: (Long) -> Unit,
): Long {
    var downloadedBytes = 0L
    var lastReportedBytes = 0L
    var lastReportedAt = clock()
    val buffer = ByteArray(DOWNLOAD_COPY_BUFFER_BYTES)
    Files.newOutputStream(
        destination,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    ).use { output ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            downloadedBytes += read
            val now = clock()
            if (now - lastReportedAt >= DOWNLOAD_PROGRESS_INTERVAL_MS) {
                onProgress(downloadedBytes)
                lastReportedBytes = downloadedBytes
                lastReportedAt = now
            }
        }
    }
    if (downloadedBytes != lastReportedBytes) onProgress(downloadedBytes)
    return downloadedBytes
}

private const val DOWNLOAD_COPY_BUFFER_BYTES = 64 * 1024
private const val DOWNLOAD_PROGRESS_INTERVAL_MS = 1_000L
