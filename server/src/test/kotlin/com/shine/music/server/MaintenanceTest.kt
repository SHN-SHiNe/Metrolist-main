package com.shine.music.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaintenanceTest {
    @Test
    fun `maintenance creates a consistent database backup and expires old trash`() {
        val root = Files.createTempDirectory("shine-maintenance-test")
        val config = AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), trashRetentionDays = 1)
        val store = MusicStore(config.databasePath)
        Files.createDirectories(config.trashDir)
        val oldTrash = config.trashDir.resolve("old.mp3")
        oldTrash.writeText("old")
        Files.setLastModifiedTime(oldTrash, FileTime.fromMillis(0))

        val result = Maintenance(config, store, clock = { 172_800_000 }).runOnce()

        assertTrue(result.backup.exists())
        assertEquals(1, result.purgedTrashFiles)
        assertTrue(!oldTrash.exists())
    }
}
