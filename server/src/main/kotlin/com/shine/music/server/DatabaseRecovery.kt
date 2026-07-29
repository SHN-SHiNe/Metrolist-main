package com.shine.music.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

internal object DatabaseRecovery {
    fun shouldAttempt(database: Path, startupError: Throwable): Boolean {
        if (SqliteIntegrity.isRecognizedCorruption(startupError)) return true
        return SqliteIntegrity.quickCheck(database) is SqliteIntegrityResult.Corrupt
    }

    fun latestHealthyBackup(backupDir: Path): Path? {
        if (!Files.isDirectory(backupDir)) return null
        return Files.list(backupDir).use { paths ->
            paths.filter { it.isRegularFile() && it.fileName.toString().endsWith(".db") }
                .sorted(compareByDescending<Path> { it.getLastModifiedTime().toMillis() })
                .filter { SqliteIntegrity.quickCheck(it) == SqliteIntegrityResult.Healthy }
                .findFirst()
                .orElse(null)
        }
    }

    fun restore(database: Path, backup: Path, now: Long): Path? {
        val staged = database.parent.resolve(".${database.fileName}.restore-${UUID.randomUUID()}.pending")
        val damaged = database.parent.resolve("shine-music-corrupt-$now.db")
        val databaseWal = Path.of("$database-wal")
        val databaseShm = Path.of("$database-shm")
        val damagedWal = Path.of("$damaged-wal")
        val damagedShm = Path.of("$damaged-shm")
        var databaseMoved = false
        var walMoved = false
        var shmMoved = false
        try {
            Files.copy(backup, staged, StandardCopyOption.REPLACE_EXISTING)
            SqliteIntegrity.requireHealthy(staged)
            if (Files.exists(database)) {
                moveAtomically(database, damaged)
                databaseMoved = true
            }
            if (Files.exists(databaseWal)) {
                moveAtomically(databaseWal, damagedWal)
                walMoved = true
            }
            if (Files.exists(databaseShm)) {
                moveAtomically(databaseShm, damagedShm)
                shmMoved = true
            }
            moveAtomically(staged, database)
            return damaged.takeIf { databaseMoved }
        } catch (restoreError: Throwable) {
            runCatching {
                if (databaseMoved && !Files.exists(database) && Files.exists(damaged)) {
                    moveAtomically(damaged, database)
                }
                if (walMoved && !Files.exists(databaseWal) && Files.exists(damagedWal)) {
                    moveAtomically(damagedWal, databaseWal)
                }
                if (shmMoved && !Files.exists(databaseShm) && Files.exists(damagedShm)) {
                    moveAtomically(damagedShm, databaseShm)
                }
            }.onFailure(restoreError::addSuppressed)
            throw restoreError
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
