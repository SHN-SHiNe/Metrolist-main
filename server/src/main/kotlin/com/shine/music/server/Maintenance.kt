package com.shine.music.server

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

class Maintenance(
    private val config: AppConfig,
    private val store: MusicStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun runOnce(): MaintenanceResult {
        val now = clock()
        config.backupDir.createDirectories()
        config.trashDir.createDirectories()
        val backup = config.backupDir.resolve("shine-${BACKUP_FORMAT.format(Instant.ofEpochMilli(now))}.db")
        store.backupTo(backup)

        val backups = Files.list(config.backupDir).use { paths ->
            paths.filter { it.isRegularFile() && it.fileName.toString().endsWith(".db") }
                .sorted(compareByDescending<Path> { it.getLastModifiedTime().toMillis() })
                .toList()
        }
        backups.drop(7).forEach(Files::deleteIfExists)

        val trashCutoff = now - config.trashRetentionDays * 24 * 60 * 60 * 1000
        var purged = 0
        Files.list(config.trashDir).use { paths ->
            paths.filter { it.isRegularFile() && it.getLastModifiedTime().toMillis() < trashCutoff }.forEach {
                if (Files.deleteIfExists(it)) purged++
            }
        }
        return MaintenanceResult(backup, purged, backups.size.coerceAtMost(7))
    }

    companion object {
        private val BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}

data class MaintenanceResult(val backup: Path, val purgedTrashFiles: Int, val retainedBackups: Int)
