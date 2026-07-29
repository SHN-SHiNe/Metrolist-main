package com.shine.music.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.sql.SQLException
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseRecoveryTest {
    @Test
    fun `non corruption startup errors preserve the primary database and fail fast`() {
        val root = Files.createTempDirectory("shine-recovery-fail-fast-test")
        val config = AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"))
        val store = MusicStore(config.databasePath)
        config.backupDir.toFile().mkdirs()
        store.backupTo(config.backupDir.resolve("shine-valid.db"))
        store.close()
        val original = config.databasePath.readBytes()
        val startupError = IllegalStateException("schema_migration_bug")

        val thrown = assertFailsWith<IllegalStateException> {
            openStoreWithRecovery(config, now = 123) { throw startupError }
        }

        assertTrue(thrown === startupError)
        assertTrue(original.contentEquals(config.databasePath.readBytes()))
        assertTrue(!config.dataDir.resolve("shine-music-corrupt-123.db").exists())
    }

    @Test
    fun `failed quick check restores newest healthy backup and preserves corrupt primary`() {
        val root = Files.createTempDirectory("shine-recovery-corrupt-test")
        val config = AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"))
        val store = MusicStore(config.databasePath)
        config.backupDir.toFile().mkdirs()
        val healthyBackup = config.backupDir.resolve("shine-healthy.db")
        store.backupTo(healthyBackup)
        store.close()
        Files.setLastModifiedTime(healthyBackup, FileTime.fromMillis(1_000))
        val invalidNewerBackup = config.backupDir.resolve("shine-newer-but-invalid.db")
        invalidNewerBackup.writeText("not a sqlite backup")
        Files.setLastModifiedTime(invalidNewerBackup, FileTime.fromMillis(2_000))
        config.databasePath.writeText("corrupt primary")
        var openAttempts = 0

        val recovered = openStoreWithRecovery(config, now = 456) { database ->
            if (openAttempts++ == 0) throw IllegalStateException("startup failed before reporting sqlite code")
            MusicStore(database)
        }

        recovered.close()
        assertEquals(2, openAttempts)
        assertEquals(SqliteIntegrityResult.Healthy, SqliteIntegrity.quickCheck(config.databasePath))
        assertEquals("corrupt primary", config.dataDir.resolve("shine-music-corrupt-456.db").readText())
        assertTrue(invalidNewerBackup.exists())
    }

    @Test
    fun `only SQLite corruption result codes are recognized for direct recovery`() {
        assertTrue(SqliteIntegrity.isRecognizedCorruption(SQLException("broken", "", 11)))
        assertTrue(SqliteIntegrity.isRecognizedCorruption(SQLException("not a database", "", 26)))
        assertTrue(!SqliteIntegrity.isRecognizedCorruption(SQLException("database is locked", "", 5)))
    }
}
