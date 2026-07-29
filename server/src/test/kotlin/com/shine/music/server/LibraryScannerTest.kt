package com.shine.music.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LibraryScannerTest {
    @Test
    fun `existing databases backfill scan time from file modification time`() {
        val root = Files.createTempDirectory("shine-scanned-at-migration")
        val database = root.resolve("data/library.db")
        Files.createDirectories(database.parent)
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { db ->
            db.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE tracks (
                      id TEXT PRIMARY KEY,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL DEFAULT '',
                      duration_ms INTEGER NOT NULL DEFAULT 0,mime_type TEXT NOT NULL,file_path TEXT NOT NULL UNIQUE,
                      file_size INTEGER NOT NULL,modified_at INTEGER NOT NULL,last_seen_scan TEXT NOT NULL,
                      deleted_at INTEGER,library_id TEXT NOT NULL DEFAULT 'default'
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "INSERT INTO tracks(id,title,artist,mime_type,file_path,file_size,modified_at,last_seen_scan) " +
                        "VALUES('legacy','旧曲目','歌手','audio/mpeg','legacy.mp3',1,12345,'legacy-scan')",
                )
            }
        }

        MusicStore(database).use { store ->
            assertEquals(12_345, store.track("legacy")?.scannedAt)
        }
    }

    @Test
    fun `unchanged files keep their indexed metadata without being parsed again`() {
        val root = Files.createTempDirectory("shine-incremental-scan")
        val music = root.resolve("music")
        Files.createDirectories(music)
        val song = music.resolve("song.mp3")
        song.writeBytes(byteArrayOf(1, 2, 3))
        val store = MusicStore(root.resolve("data/library.db"))
        var reads = 0
        var title = "第一次解析"
        var now = 1_000L
        val scanner = LibraryScanner(music, store, root.resolve("cache"), clock = { now }) {
            reads++
            AudioMetadata(title, "歌手", "专辑", 1_000)
        }

        scanner.scan()
        val initial = store.listTracks(null, 0, 10).items.single()
        assertEquals(1_000, initial.scannedAt)
        store.updateAnalysisState(initial.id, "failed", .5f, "保留分析状态")
        val revisionBeforeRescan = store.listTracks(null, 0, 10).revision
        now = 2_000
        title = "不应覆盖"
        val unchanged = scanner.scan()

        assertEquals(1, reads)
        assertEquals(0, unchanged.updated)
        val rescanned = store.listTracks(null, 0, 10).items.single()
        assertEquals("第一次解析", rescanned.title)
        assertEquals(2_000, rescanned.scannedAt)
        assertEquals("failed", rescanned.analysis.status)
        assertEquals(revisionBeforeRescan, store.listTracks(null, 0, 10).revision)

        now = 3_000
        Files.setLastModifiedTime(song, FileTime.fromMillis(song.toFile().lastModified() + 2_000))
        val updated = scanner.scan()

        assertEquals(2, reads)
        assertEquals(1, updated.updated)
        val changed = store.listTracks(null, 0, 10).items.single()
        assertEquals("不应覆盖", changed.title)
        assertEquals(3_000, changed.scannedAt)
        assertEquals("pending", changed.analysis.status)
        store.close()
    }

    @Test
    fun `indexing one downloaded file does not rescan or remove the rest of the library`() {
        val root = Files.createTempDirectory("shine-single-file-index")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("first.mp3").writeBytes(byteArrayOf(1))
        val store = MusicStore(root.resolve("data/library.db"))
        var reads = 0
        var now = 1_000L
        val scanner = LibraryScanner(music, store, root.resolve("cache"), clock = { now }) { path ->
            reads++
            AudioMetadata(path.fileName.toString(), "歌手", "", 1_000)
        }
        scanner.scan()
        now = 4_000
        val second = music.resolve("second.mp3")
        second.writeBytes(byteArrayOf(2))

        val change = scanner.index(second)

        assertEquals(ScanChange.DISCOVERED, change)
        assertEquals(2, reads)
        assertEquals(2, store.listTracks(null, 0, 10).total)
        assertEquals(4_000, store.listTracks("second", 0, 1).items.single().scannedAt)
        store.close()
    }

    @Test
    fun `overlapping scan requests are serialized and parse each unchanged file once`() {
        val root = Files.createTempDirectory("shine-serialized-scan")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("song.mp3").writeBytes(byteArrayOf(1))
        val activeReaders = AtomicInteger()
        val maximumReaders = AtomicInteger()
        val reads = AtomicInteger()
        val store = MusicStore(root.resolve("data/library.db"))
        val scanner = LibraryScanner(music, store, root.resolve("cache")) {
            val active = activeReaders.incrementAndGet()
            maximumReaders.accumulateAndGet(active, ::maxOf)
            reads.incrementAndGet()
            Thread.sleep(80)
            activeReaders.decrementAndGet()
            AudioMetadata("歌", "歌手", "", 1_000)
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val scans = List(2) { executor.submit<ScanResult> { start.await(); scanner.scan() } }
        start.countDown()
        scans.forEach { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, maximumReaders.get())
        assertEquals(1, reads.get())
        store.close()
    }

    @Test
    fun `deleting during a scan cannot resurrect a track whose file moved to trash`() {
        val root = Files.createTempDirectory("shine-scan-delete")
        val music = root.resolve("music")
        Files.createDirectories(music)
        val song = music.resolve("song.mp3")
        song.writeBytes(byteArrayOf(1))
        val store = MusicStore(root.resolve("data/library.db"))
        val initial = LibraryScanner(music, store, root.resolve("cache")) { AudioMetadata("歌", "歌手", "", 1_000) }
        initial.scan()
        Files.setLastModifiedTime(song, FileTime.fromMillis(song.toFile().lastModified() + 2_000))
        val readerStarted = CountDownLatch(1)
        val allowReader = CountDownLatch(1)
        val scanner = LibraryScanner(music, store, root.resolve("cache")) {
            readerStarted.countDown()
            allowReader.await()
            AudioMetadata("歌", "歌手", "", 1_000)
        }
        val trackId = store.listTracks(null, 0, 1).items.single().id
        val executor = Executors.newFixedThreadPool(2)

        val scan = executor.submit<ScanResult> { scanner.scan() }
        readerStarted.await(2, TimeUnit.SECONDS)
        val deletion = executor.submit<Boolean> { scanner.moveToTrash(trackId, root.resolve("trash"), 10) }
        Thread.sleep(40)
        assertFalse(deletion.isDone)
        allowReader.countDown()
        scan.get(5, TimeUnit.SECONDS)
        assertEquals(true, deletion.get(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(0, store.listTracks(null, 0, 10).total)
        store.close()
    }
}
