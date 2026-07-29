package com.shine.music.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadRetryApiTest {
    @Test
    fun `failed download keeps its request and can be retried`() = testApplication {
        val root = Files.createTempDirectory("shine-download-retry")
        val config = AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), scanOnStart = false)
        val failed = DownloadJob(
            "job-1",
            "测试歌曲",
            "测试歌手",
            "failed",
            "network",
            1,
            2,
            downloadedBytes = 12_345,
            totalBytes = 98_765,
        )
        MusicStore(config.databasePath).use { store ->
            store.saveDownload(
                failed,
                DownloadRequest(url = "http://127.0.0.1/not-allowed", title = failed.title, artist = failed.artist),
            )
        }
        application { shineModule(config) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/downloads/${failed.id}/retry")

        assertEquals(HttpStatusCode.Accepted, response.status)
        val retried = response.body<DownloadJob>()
        assertEquals("queued", retried.status)
        assertEquals(0L, retried.downloadedBytes)
        assertEquals(null, retried.totalBytes)
        val persisted = client.get("/api/downloads").body<List<DownloadJob>>().single()
        assertEquals(0L, persisted.downloadedBytes)
        assertEquals(null, persisted.totalBytes)
    }
}
