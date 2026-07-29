package com.shine.music.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.extension

class DownloadManager(
    private val config: AppConfig,
    private val store: MusicStore,
    private val scanner: LibraryScanner,
    private val onlineCatalog: OnlineCatalog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()

    fun enqueue(request: DownloadRequest): DownloadJob {
        require(request.url != null || request.trackId != null) { "url_or_track_id_required" }
        val now = clock()
        val job = DownloadJob(UUID.randomUUID().toString(), request.title.trim(), request.artist.trim(), "queued", createdAt = now, updatedAt = now)
        store.saveDownload(job, request)
        scope.launch { download(job, request) }
        return job
    }

    fun retry(id: String): DownloadJob {
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
            config.musicDir.createDirectories()
            val incomingDir = config.musicDir.resolve(".shine-incoming")
            incomingDir.createDirectories()
            val response = http.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).header("User-Agent", "SHiNe-Music-NAS/0.1").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            require(response.statusCode() in 200..299) { "download_http_${response.statusCode()}" }
            val contentType = response.headers().firstValue("content-type").orElse("audio/mpeg").substringBefore(';')
            val extension = extensionFor(uri.path.substringAfterLast('.', ""), contentType)
            val filename = "${safe(request.artist)} - ${safe(request.title)}.$extension"
            val part = incomingDir.resolve("${job.id}.part")
            temporary = part
            response.body().use { input -> Files.copy(input, part, StandardCopyOption.REPLACE_EXISTING) }
            require(Files.size(part) > 0) { "empty_download" }
            Files.move(part, config.musicDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            scanner.scan()
            update(job, "completed")
        } catch (error: Throwable) {
            update(job, "failed", error.message ?: error::class.simpleName)
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
}
