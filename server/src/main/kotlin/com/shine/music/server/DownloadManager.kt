package com.shine.music.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    private val onTrackIndexed: () -> Unit = {},
) : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    private val closed = AtomicBoolean()

    init {
        store.failInterruptedDownloads(clock())
    }

    fun enqueue(request: DownloadRequest): DownloadJob {
        check(!closed.get()) { "download_manager_closed" }
        require(request.url != null || request.trackId != null) { "url_or_track_id_required" }
        val now = clock()
        val job = DownloadJob(UUID.randomUUID().toString(), request.title.trim(), request.artist.trim(), "queued", createdAt = now, updatedAt = now)
        store.saveDownload(job, request)
        scope.launch { download(job, request) }
        return job
    }

    fun retry(id: String): DownloadJob {
        check(!closed.get()) { "download_manager_closed" }
        val (previous, request) = store.downloadForRetry(id) ?: throw IllegalArgumentException("failed_download_not_found")
        val queued = previous.copy(status = "queued", error = null, updatedAt = clock())
        store.saveDownload(queued)
        scope.launch { download(queued, request) }
        return queued
    }

    private suspend fun download(job: DownloadJob, request: DownloadRequest) {
        update(job, "downloading")
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
            val contentType = response.headers().firstValue("content-type").orElse("audio/mpeg").substringBefore(';')
            val extension = extensionFor(uri.path.substringAfterLast('.', ""), contentType)
            val filename = "${safe(request.artist)} - ${safe(request.title)}.$extension"
            val part = incomingDir.resolve("${job.id}.part")
            temporary = part
            response.body().use { input -> Files.copy(input, part, StandardCopyOption.REPLACE_EXISTING) }
            currentCoroutineContext().ensureActive()
            require(Files.size(part) > 0) { "empty_download" }
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
        store.saveDownload(job.copy(status = status, error = error, updatedAt = clock()))
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
        val logger = LoggerFactory.getLogger(DownloadManager::class.java)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        supervisor.cancel()
        http.shutdownNow()
        runBlocking { withTimeoutOrNull(5_000) { supervisor.join() } }
    }
}
