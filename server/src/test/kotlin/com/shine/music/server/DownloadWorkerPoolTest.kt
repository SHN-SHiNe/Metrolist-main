package com.shine.music.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadWorkerPoolTest {
    @Test
    fun `large download backlog uses only the configured fixed workers`() = runBlocking {
        val root = Files.createTempDirectory("shine-download-workers")
        Files.createDirectories(root.resolve("music"))
        val config = AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), scanOnStart = false)
        MusicStore(config.databasePath).use { store ->
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val finished = AtomicInteger()
            val started = Channel<Unit>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            val allFinished = CompletableDeferred<Unit>()
            DownloadManager(
                store = store,
                libraries = MusicLibraryManager(config, store),
                onlineCatalog = OnlineCatalog(store),
                maxConcurrentDownloads = 3,
                taskProcessor = { job, _ ->
                    val current = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, current) }
                    started.send(Unit)
                    try {
                        release.await()
                        store.saveDownload(job.copy(status = "completed", updatedAt = System.currentTimeMillis()))
                    } finally {
                        active.decrementAndGet()
                        if (finished.incrementAndGet() == 20) allFinished.complete(Unit)
                    }
                },
            ).use { downloads ->
                repeat(20) { index ->
                    downloads.enqueue(
                        DownloadRequest(url = "https://example.com/$index.mp3", title = "Song $index", artist = "Artist"),
                    )
                }

                repeat(3) { withTimeout(5_000) { started.receive() } }
                delay(100)
                assertEquals(3, peak.get())
                assertEquals(3, store.downloads().count { it.status == "downloading" })
                assertEquals(17, store.downloads().count { it.status == "queued" })

                release.complete(Unit)
                withTimeout(5_000) { allFinished.await() }
                assertEquals(3, peak.get())
                assertEquals(20, store.downloads().count { it.status == "completed" })
            }
        }
    }
}
