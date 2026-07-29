package com.shine.music.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
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
    fun `unchanged files keep their indexed metadata without being parsed again`() {
        val root = Files.createTempDirectory("shine-incremental-scan")
        val music = root.resolve("music")
        Files.createDirectories(music)
        val song = music.resolve("song.mp3")
        song.writeBytes(byteArrayOf(1, 2, 3))
        val store = MusicStore(root.resolve("data/library.db"))
        var reads = 0
        var title = "第一次解析"
        val scanner = LibraryScanner(music, store, root.resolve("cache")) {
            reads++
            AudioMetadata(title, "歌手", "专辑", 1_000)
        }

        scanner.scan()
        title = "不应覆盖"
        val unchanged = scanner.scan()

        assertEquals(1, reads)
        assertEquals(0, unchanged.updated)
        assertEquals("第一次解析", store.listTracks(null, 0, 10).items.single().title)

        Files.setLastModifiedTime(song, FileTime.fromMillis(song.toFile().lastModified() + 2_000))
        val updated = scanner.scan()

        assertEquals(2, reads)
        assertEquals(1, updated.updated)
        assertEquals("不应覆盖", store.listTracks(null, 0, 10).items.single().title)
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
        val scanner = LibraryScanner(music, store, root.resolve("cache")) { path ->
            reads++
            AudioMetadata(path.fileName.toString(), "歌手", "", 1_000)
        }
        scanner.scan()
        val second = music.resolve("second.mp3")
        second.writeBytes(byteArrayOf(2))

        val change = scanner.index(second)

        assertEquals(ScanChange.DISCOVERED, change)
        assertEquals(2, reads)
        assertEquals(2, store.listTracks(null, 0, 10).total)
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
