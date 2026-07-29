package com.shine.music.server

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

internal sealed interface SqliteIntegrityResult {
    data object Healthy : SqliteIntegrityResult
    data class Corrupt(val reason: String) : SqliteIntegrityResult
    data class Unavailable(val cause: Throwable) : SqliteIntegrityResult
}

internal object SqliteIntegrity {
    fun quickCheck(database: Path): SqliteIntegrityResult {
        if (!Files.isRegularFile(database)) {
            return SqliteIntegrityResult.Unavailable(
                IllegalStateException("sqlite_database_missing:${database.toAbsolutePath()}"),
            )
        }
        return try {
            Class.forName("org.sqlite.JDBC")
            val sqliteConfig = SQLiteConfig().apply {
                setReadOnly(true)
                setBusyTimeout(2_000)
            }
            val dataSource = SQLiteDataSource(sqliteConfig).apply {
                url = "jdbc:sqlite:${database.toAbsolutePath()}"
            }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check").use { rows ->
                        val failures = buildList {
                            while (rows.next()) {
                                rows.getString(1)?.takeUnless { it.equals("ok", ignoreCase = true) }?.let(::add)
                            }
                        }
                        if (failures.isEmpty()) {
                            SqliteIntegrityResult.Healthy
                        } else {
                            SqliteIntegrityResult.Corrupt(failures.joinToString("; ").take(1_024))
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (isRecognizedCorruption(error)) {
                SqliteIntegrityResult.Corrupt(error.message ?: "sqlite_corrupt")
            } else {
                SqliteIntegrityResult.Unavailable(error)
            }
        }
    }

    fun requireHealthy(database: Path) {
        when (val result = quickCheck(database)) {
            SqliteIntegrityResult.Healthy -> Unit
            is SqliteIntegrityResult.Corrupt -> error("sqlite_quick_check_failed:${result.reason}")
            is SqliteIntegrityResult.Unavailable -> throw IllegalStateException("sqlite_quick_check_unavailable", result.cause)
        }
    }

    fun isRecognizedCorruption(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .filterIsInstance<SQLException>()
        .any { sqlError ->
            val primaryCode = sqlError.errorCode and 0xff
            primaryCode == SQLITE_CORRUPT || primaryCode == SQLITE_NOTADB ||
                CORRUPTION_MARKERS.any { marker -> sqlError.message.orEmpty().contains(marker, ignoreCase = true) }
        }

    private const val SQLITE_CORRUPT = 11
    private const val SQLITE_NOTADB = 26
    private val CORRUPTION_MARKERS = listOf(
        "SQLITE_CORRUPT",
        "SQLITE_NOTADB",
        "database disk image is malformed",
        "file is not a database",
    )
}
