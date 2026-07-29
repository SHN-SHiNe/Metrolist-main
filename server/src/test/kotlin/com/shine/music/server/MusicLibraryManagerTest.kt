package com.shine.music.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MusicLibraryManagerTest {
    @Test
    fun `new writable download target replaces the previous target`() {
        val root = Files.createTempDirectory("shine-download-library-test")
        val config = AppConfig(
            root.resolve("data"),
            root.resolve("music"),
            root.resolve("cache"),
            scanOnStart = false,
            libraryDir = root.resolve("libraries"),
        )
        Files.createDirectories(config.musicDir)
        val first = config.libraryDir.resolve("first")
        val second = config.libraryDir.resolve("second")
        Files.createDirectories(first)
        Files.createDirectories(second)

        MusicStore(config.databasePath).use { store ->
            val manager = MusicLibraryManager(config, store)
            val firstLibrary = manager.create(MusicLibraryRequest("下载一", first.toString(), readOnly = false, downloadTarget = true))
            val secondLibrary = manager.create(MusicLibraryRequest("下载二", second.toString(), readOnly = false, downloadTarget = true))

            assertEquals(secondLibrary.id, manager.downloadTarget().id)
            assertEquals(false, manager.list().single { it.id == firstLibrary.id }.downloadTarget)
            assertEquals(1, manager.list().count(MusicLibraryView::downloadTarget))
        }
    }

    @Test
    fun `library path is unique and disabled library cannot receive downloads`() {
        val root = Files.createTempDirectory("shine-library-validation-test")
        val config = AppConfig(
            root.resolve("data"), root.resolve("music"), root.resolve("cache"),
            scanOnStart = false, libraryDir = root.resolve("libraries"),
        )
        Files.createDirectories(config.musicDir)
        val mounted = config.libraryDir.resolve("mounted")
        Files.createDirectories(mounted)

        MusicStore(config.databasePath).use { store ->
            val manager = MusicLibraryManager(config, store)
            manager.create(MusicLibraryRequest("移动盘", mounted.toString()))

            assertEquals(
                "library_path_overlaps_existing_library",
                assertFailsWith<IllegalArgumentException> {
                    manager.create(MusicLibraryRequest("重复盘", mounted.toString()))
                }.message,
            )
            assertEquals(
                "library_path_overlaps_existing_library",
                assertFailsWith<IllegalArgumentException> {
                    manager.create(MusicLibraryRequest("子目录", mounted.resolve("nested").toString()))
                }.message,
            )
            assertEquals(
                "download_library_must_be_enabled_and_writable",
                assertFailsWith<IllegalArgumentException> {
                    manager.create(MusicLibraryRequest("停用下载盘", config.libraryDir.resolve("disabled").toString(), readOnly = false, enabled = false, downloadTarget = true))
                }.message,
            )
            assertEquals(
                "download_library_must_be_online_and_writable",
                assertFailsWith<IllegalArgumentException> {
                    manager.create(MusicLibraryRequest("离线下载盘", config.libraryDir.resolve("offline").toString(), readOnly = false, downloadTarget = true))
                }.message,
            )
        }
    }
}
