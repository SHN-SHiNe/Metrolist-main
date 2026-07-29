package com.shine.music.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(SqliteIntegrityResult.Healthy, SqliteIntegrity.quickCheck(result.backup))
        assertEquals(1, result.purgedTrashFiles)
        assertTrue(!oldTrash.exists())
        store.close()
    }

    @Test
    fun `failed or corrupt pending backup never replaces the latest valid candidate`() {
        val root = Files.createTempDirectory("shine-maintenance-atomic-test")
        val config = AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"))
        val store = MusicStore(config.databasePath)
        config.backupDir.toFile().mkdirs()
        val existing = config.backupDir.resolve("shine-19700101-000001.db")
        store.backupTo(existing)
        val existingBytes = existing.readBytes()

        assertFailsWith<IllegalStateException> {
            Maintenance(
                config = config,
                store = store,
                clock = { 1_000 },
                backupWriter = { pending -> pending.writeText("interrupted backup") },
            ).runOnce()
        }

        assertTrue(existing.isRegularFile())
        assertTrue(existingBytes.contentEquals(existing.readBytes()))
        assertEquals(SqliteIntegrityResult.Healthy, SqliteIntegrity.quickCheck(existing))
        val pending = Files.list(config.backupDir).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".pending") }.toList()
        }
        assertTrue(pending.isEmpty())
        store.close()
    }
}
