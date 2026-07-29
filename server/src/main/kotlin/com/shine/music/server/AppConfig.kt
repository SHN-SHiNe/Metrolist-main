package com.shine.music.server

import java.nio.file.Path
import kotlin.io.path.Path

data class AppConfig(
    val dataDir: Path,
    val musicDir: Path,
    val cacheDir: Path,
    val trashRetentionDays: Long = 30,
    val scanOnStart: Boolean = true,
    val sendspinBridgeUrl: String? = null,
    val sendspinInternalToken: String? = null,
) {
    val databasePath: Path get() = dataDir.resolve("shine-music.db")
    val backupDir: Path get() = dataDir.resolve("backups")
    val trashDir: Path get() = dataDir.resolve("trash")

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            val data = Path(env["SHINE_DATA_DIR"] ?: "./data")
            return AppConfig(
                dataDir = data,
                musicDir = Path(env["SHINE_MUSIC_DIR"] ?: "./music"),
                cacheDir = Path(env["SHINE_CACHE_DIR"] ?: data.resolve("cache").toString()),
                trashRetentionDays = env["SHINE_TRASH_RETENTION_DAYS"]?.toLongOrNull() ?: 30,
                scanOnStart = env["SHINE_SCAN_ON_START"]?.toBooleanStrictOrNull() ?: true,
                sendspinBridgeUrl = env["SHINE_SENDSPIN_BRIDGE_URL"]?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank),
                sendspinInternalToken = env["SHINE_SENDSPIN_INTERNAL_TOKEN"]?.takeIf(String::isNotBlank),
            )
        }
    }
}
