package com.shine.music.server

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadProgressTest {
    @Test
    fun `legacy download rows migrate with compatible empty progress`() {
        val root = Files.createTempDirectory("shine-download-progress-migration")
        val database = root.resolve("shine-music.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE download_jobs (
                        id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, status TEXT NOT NULL,
                        error TEXT, track_id TEXT, url TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute("INSERT INTO download_jobs VALUES('legacy','旧任务','歌手','failed','network',NULL,'https://example.com/old.mp3',1,2)")
            }
        }

        MusicStore(database).use { store ->
            val migrated = store.downloads().single()
            assertEquals(0L, migrated.downloadedBytes)
            assertNull(migrated.totalBytes)

            store.saveDownload(
                migrated.copy(
                    status = "downloading",
                    downloadedBytes = 4_096,
                    totalBytes = 8_192,
                    updatedAt = 3,
                ),
            )
            val persisted = store.downloads().single()
            assertEquals(4_096, persisted.downloadedBytes)
            assertEquals(8_192, persisted.totalBytes)
        }
    }

    @Test
    fun `stream copy throttles intermediate writes and always reports exact final bytes`() = runBlocking {
        val root = Files.createTempDirectory("shine-download-progress-copy")
        val destination = root.resolve("track.part")
        val source = ByteArray(7 * 64 * 1024) { (it % 251).toByte() }
        var now = 0L
        val progress = mutableListOf<Long>()

        val copied = copyDownloadStream(
            input = ByteArrayInputStream(source),
            destination = destination,
            clock = { now += 250; now },
            onProgress = progress::add,
        )

        assertEquals(source.size.toLong(), copied)
        assertEquals(listOf(4L * 64 * 1024, source.size.toLong()), progress)
        assertContentEquals(source, Files.readAllBytes(destination))
    }
}
