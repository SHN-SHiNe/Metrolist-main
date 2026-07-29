package com.shine.music.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import kotlin.io.path.createDirectories

class MusicStore(private val databasePath: Path) : AutoCloseable {
    private val dataSource: HikariDataSource
    private var fullTextSearchEnabled = false

    init {
        databasePath.parent.createDirectories()
        Class.forName("org.sqlite.JDBC")
        val sqliteConfig = SQLiteConfig().apply {
            setBusyTimeout(10_000)
            enforceForeignKeys(true)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
        }
        val sqliteDataSource = SQLiteDataSource(sqliteConfig).apply {
            url = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
        }
        dataSource = HikariDataSource(HikariConfig().apply {
            dataSource = sqliteDataSource
            poolName = "shine-sqlite-${databasePath.toAbsolutePath().toString().hashCode().toUInt()}"
            maximumPoolSize = 4
            minimumIdle = 1
            connectionTimeout = 10_000
            validationTimeout = 3_000
            connectionTestQuery = "SELECT 1"
        })
        try {
            connection().use { db ->
                db.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA busy_timeout=10000")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tracks (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL DEFAULT '',
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        mime_type TEXT NOT NULL,
                        file_path TEXT NOT NULL UNIQUE,
                        file_size INTEGER NOT NULL,
                        modified_at INTEGER NOT NULL,
                        last_seen_scan TEXT NOT NULL,
                        deleted_at INTEGER
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS playlists (
                        id TEXT PRIMARY KEY, name TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_items (
                        playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                        track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(playlist_id, position)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE, played_at INTEGER NOT NULL)",
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS source_configs (
                        id TEXT PRIMARY KEY, name TEXT NOT NULL, api_url TEXT NOT NULL, api_key TEXT NOT NULL,
                        enabled INTEGER NOT NULL, updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS download_jobs (
                        id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, status TEXT NOT NULL,
                        error TEXT, track_id TEXT, url TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                runCatching { statement.execute("ALTER TABLE download_jobs ADD COLUMN track_id TEXT") }
                runCatching { statement.execute("ALTER TABLE download_jobs ADD COLUMN url TEXT") }
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS rooms (
                        id TEXT PRIMARY KEY, name TEXT NOT NULL, state_json TEXT NOT NULL,
                        version INTEGER NOT NULL, updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS favorites (track_id TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE)",
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS scan_jobs (
                        id TEXT PRIMARY KEY, status TEXT NOT NULL, discovered INTEGER NOT NULL,
                        updated INTEGER NOT NULL, removed INTEGER NOT NULL, started_at INTEGER NOT NULL,
                        completed_at INTEGER
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE TABLE IF NOT EXISTS library_revision (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL)")
                statement.execute("INSERT OR IGNORE INTO library_revision(id,revision) VALUES(1,0)")
                statement.execute("CREATE TRIGGER IF NOT EXISTS tracks_revision_insert AFTER INSERT ON tracks BEGIN UPDATE library_revision SET revision=revision+1 WHERE id=1; END")
                statement.execute("CREATE TRIGGER IF NOT EXISTS tracks_revision_delete AFTER DELETE ON tracks BEGIN UPDATE library_revision SET revision=revision+1 WHERE id=1; END")
                statement.execute(
                    """
                    CREATE TRIGGER IF NOT EXISTS tracks_revision_update
                    AFTER UPDATE OF title,artist,album,duration_ms,mime_type,file_path,file_size,modified_at,deleted_at ON tracks
                    WHEN OLD.title IS NOT NEW.title OR OLD.artist IS NOT NEW.artist OR OLD.album IS NOT NEW.album
                      OR OLD.duration_ms IS NOT NEW.duration_ms OR OLD.mime_type IS NOT NEW.mime_type
                      OR OLD.file_path IS NOT NEW.file_path OR OLD.file_size IS NOT NEW.file_size
                      OR OLD.modified_at IS NOT NEW.modified_at OR OLD.deleted_at IS NOT NEW.deleted_at
                    BEGIN UPDATE library_revision SET revision=revision+1 WHERE id=1; END
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_library_sort ON tracks(deleted_at, artist COLLATE NOCASE, album COLLATE NOCASE, title COLLATE NOCASE)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_title_sort ON tracks(deleted_at, title COLLATE NOCASE, artist COLLATE NOCASE)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_album_sort ON tracks(deleted_at, album COLLATE NOCASE, artist COLLATE NOCASE, title COLLATE NOCASE)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_scan ON tracks(last_seen_scan, deleted_at)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_history_played_at ON history(played_at DESC)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_playlist_items_track ON playlist_items(track_id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_download_jobs_created_at ON download_jobs(created_at DESC)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_rooms_updated_at ON rooms(updated_at DESC)")
                    fullTextSearchEnabled = runCatching {
                        statement.execute("CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING fts5(id UNINDEXED,title,artist,album,tokenize='trigram')")
                        statement.execute("INSERT INTO tracks_fts(id,title,artist,album) SELECT id,title,artist,album FROM tracks WHERE deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM tracks_fts LIMIT 1)")
                        statement.execute("CREATE TRIGGER IF NOT EXISTS tracks_fts_insert AFTER INSERT ON tracks WHEN NEW.deleted_at IS NULL BEGIN INSERT INTO tracks_fts(id,title,artist,album) VALUES(NEW.id,NEW.title,NEW.artist,NEW.album); END")
                        statement.execute("CREATE TRIGGER IF NOT EXISTS tracks_fts_delete AFTER DELETE ON tracks BEGIN DELETE FROM tracks_fts WHERE id=OLD.id; END")
                        statement.execute("CREATE TRIGGER IF NOT EXISTS tracks_fts_update AFTER UPDATE OF title,artist,album,deleted_at ON tracks BEGIN DELETE FROM tracks_fts WHERE id=OLD.id; INSERT INTO tracks_fts(id,title,artist,album) SELECT NEW.id,NEW.title,NEW.artist,NEW.album WHERE NEW.deleted_at IS NULL; END")
                        true
                    }.getOrDefault(false)
                }
            }
        } catch (error: Throwable) {
            dataSource.close()
            throw error
        }
    }

    private fun connection(): Connection = dataSource.connection

    override fun close() = dataSource.close()

    fun upsertTrack(scanId: String, file: ScannedFile): ScanChange = connection().use { db ->
        val previous = db.prepareStatement("SELECT modified_at, file_size FROM tracks WHERE id = ?").use { query ->
            query.setString(1, file.id)
            query.executeQuery().use { row -> if (row.next()) row.getLong(1) to row.getLong(2) else null }
        }
        db.prepareStatement(UPSERT_TRACK_SQL).use { statement ->
            bindTrack(statement, scanId, file)
            statement.executeUpdate()
        }
        when {
            previous == null -> ScanChange.DISCOVERED
            previous.first != file.modifiedAt || previous.second != file.size -> ScanChange.UPDATED
            else -> ScanChange.UNCHANGED
        }
    }

    fun scanSession() = ScanSession(connection())

    inner class ScanSession internal constructor(private val db: Connection) : AutoCloseable {
        private val fingerprint = db.prepareStatement("SELECT file_size,modified_at FROM tracks WHERE id=? AND deleted_at IS NULL")

        fun fingerprint(id: String): TrackFingerprint? {
            fingerprint.setString(1, id)
            return fingerprint.executeQuery().use { row ->
                if (row.next()) TrackFingerprint(row.getLong("file_size"), row.getLong("modified_at")) else null
            }
        }

        fun applyBatch(scanId: String, changed: List<ScannedFile>, unchangedIds: List<String>) {
            db.autoCommit = false
            try {
                if (changed.isNotEmpty()) {
                    db.prepareStatement(UPSERT_TRACK_SQL).use { statement ->
                        changed.forEach { file -> bindTrack(statement, scanId, file); statement.addBatch() }
                        statement.executeBatch()
                    }
                }
                if (unchangedIds.isNotEmpty()) {
                    db.prepareStatement("UPDATE tracks SET last_seen_scan=? WHERE id=? AND deleted_at IS NULL").use { statement ->
                        unchangedIds.forEach { id -> statement.setString(1, scanId); statement.setString(2, id); statement.addBatch() }
                        statement.executeBatch()
                    }
                }
                db.commit()
            } catch (error: Throwable) {
                db.rollback()
                throw error
            } finally {
                db.autoCommit = true
            }
        }

        override fun close() {
            fingerprint.close()
            db.close()
        }
    }

    fun startScan(id: String, startedAt: Long) = connection().use { db ->
        db.prepareStatement("INSERT INTO scan_jobs(id,status,discovered,updated,removed,started_at,completed_at) VALUES(?,'running',0,0,0,?,NULL)").use {
            it.setString(1, id); it.setLong(2, startedAt); it.executeUpdate()
        }
    }

    fun finishScanJob(result: ScanResult) = connection().use { db ->
        db.prepareStatement("UPDATE scan_jobs SET status=?,discovered=?,updated=?,removed=?,completed_at=? WHERE id=?").use {
            it.setString(1, result.status); it.setInt(2, result.discovered); it.setInt(3, result.updated)
            it.setInt(4, result.removed); it.setLong(5, result.completedAt); it.setString(6, result.id); it.executeUpdate()
        }
    }

    fun scans(limit: Int = 20): List<ScanResult> = connection().use { db ->
        db.prepareStatement("SELECT * FROM scan_jobs ORDER BY started_at DESC LIMIT ?").use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(ScanResult(rows.getString("id"), rows.getString("status"), rows.getInt("discovered"), rows.getInt("updated"), rows.getInt("removed"), rows.getLong("started_at"), rows.getLong("completed_at")))
            } }
        }
    }

    fun finishScan(scanId: String): Int = connection().use { db ->
        db.prepareStatement("UPDATE tracks SET deleted_at = ? WHERE last_seen_scan <> ? AND deleted_at IS NULL").use { statement ->
            statement.setLong(1, System.currentTimeMillis())
            statement.setString(2, scanId)
            statement.executeUpdate()
        }
    }

    fun listTracks(query: String?, offset: Int, limit: Int, sort: String = "artist"): TrackPage = connection().use { db ->
        val filter = query?.trim().orEmpty()
        val useFullTextSearch = fullTextSearchEnabled && filter.length >= 3
        val from = if (useFullTextSearch) "tracks t JOIN tracks_fts ON tracks_fts.id=t.id" else "tracks t"
        val where = when {
            filter.isBlank() -> "t.deleted_at IS NULL"
            useFullTextSearch -> "t.deleted_at IS NULL AND tracks_fts MATCH ?"
            else -> "t.deleted_at IS NULL AND (t.title LIKE ? ESCAPE '\\' OR t.artist LIKE ? ESCAPE '\\' OR t.album LIKE ? ESCAPE '\\')"
        }
        val orderBy = when (sort) {
            "title" -> "t.title COLLATE NOCASE, t.artist COLLATE NOCASE, t.album COLLATE NOCASE, t.id"
            "album" -> "t.album COLLATE NOCASE, t.artist COLLATE NOCASE, t.title COLLATE NOCASE, t.id"
            else -> "t.artist COLLATE NOCASE, t.album COLLATE NOCASE, t.title COLLATE NOCASE, t.id"
        }
        db.autoCommit = false
        try {
            val revision = db.createStatement().use { statement -> statement.executeQuery("SELECT revision FROM library_revision WHERE id=1").use { it.next(); it.getLong(1) } }
            val total = db.prepareStatement("SELECT COUNT(*) FROM $from WHERE $where").use { statement ->
                bindSearch(statement, filter, useFullTextSearch)
                statement.executeQuery().use { it.next(); it.getInt(1) }
            }
            val items = db.prepareStatement(
                """
                SELECT t.*, EXISTS(SELECT 1 FROM favorites f WHERE f.track_id=t.id) AS favorite
                FROM $from WHERE $where ORDER BY $orderBy
                LIMIT ? OFFSET ?
                """.trimIndent(),
            ).use { statement ->
                var index = bindSearch(statement, filter, useFullTextSearch)
                statement.setInt(index++, limit)
                statement.setInt(index, offset)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toTrack()) } }
            }
            db.commit()
            TrackPage(items, total, offset, limit, revision)
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    fun trackFile(id: String): Path? = connection().use { db ->
        db.prepareStatement("SELECT file_path FROM tracks WHERE id=? AND deleted_at IS NULL").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row -> if (row.next()) Path.of(row.getString(1)) else null }
        }
    }

    fun track(id: String): Track? = connection().use { db ->
        db.prepareStatement(
            "SELECT t.*, EXISTS(SELECT 1 FROM favorites f WHERE f.track_id=t.id) AS favorite FROM tracks t WHERE t.id=? AND t.deleted_at IS NULL",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row -> if (row.next()) row.toTrack() else null }
        }
    }

    fun markTrackDeleted(id: String, now: Long): Boolean = connection().use { db ->
        db.prepareStatement("UPDATE tracks SET deleted_at=? WHERE id=? AND deleted_at IS NULL").use {
            it.setLong(1, now); it.setString(2, id); it.executeUpdate() > 0
        }
    }

    fun setFavorite(trackId: String, favorite: Boolean) = connection().use { db ->
        if (favorite) {
            db.prepareStatement("INSERT OR IGNORE INTO favorites(track_id) VALUES(?)").use { it.setString(1, trackId); it.executeUpdate() }
        } else {
            db.prepareStatement("DELETE FROM favorites WHERE track_id=?").use { it.setString(1, trackId); it.executeUpdate() }
        }
    }

    fun favorites(): List<Track> = connection().use { db ->
        db.prepareStatement(
            "SELECT t.*, 1 AS favorite FROM tracks t JOIN favorites f ON f.track_id=t.id WHERE t.deleted_at IS NULL ORDER BY t.title COLLATE NOCASE",
        ).use { statement -> statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toTrack()) } } }
    }

    fun createPlaylist(name: String, now: Long): PlaylistSummary {
        val id = UUID.randomUUID().toString()
        connection().use { db ->
            db.prepareStatement("INSERT INTO playlists(id,name,version,created_at,updated_at) VALUES(?,?,1,?,?)").use {
                it.setString(1, id); it.setString(2, name); it.setLong(3, now); it.setLong(4, now); it.executeUpdate()
            }
        }
        return PlaylistSummary(id, name, 1, 0, now)
    }

    fun playlists(): List<PlaylistSummary> = connection().use { db ->
        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT p.*, COUNT(i.track_id) track_count FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id=p.id GROUP BY p.id ORDER BY p.updated_at DESC",
            ).use { rows -> buildList { while (rows.next()) add(rows.toPlaylistSummary()) } }
        }
    }

    fun playlist(id: String): PlaylistDetail? = connection().use { db ->
        val summary = db.prepareStatement("SELECT p.*, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id=p.id) track_count FROM playlists p WHERE id=?").use {
            it.setString(1, id); it.executeQuery().use { row -> if (row.next()) row.toPlaylistSummary() else null }
        } ?: return@use null
        val tracks = db.prepareStatement(
            "SELECT t.*, EXISTS(SELECT 1 FROM favorites f WHERE f.track_id=t.id) favorite FROM playlist_items i JOIN tracks t ON t.id=i.track_id WHERE i.playlist_id=? AND t.deleted_at IS NULL ORDER BY i.position",
        ).use { statement -> statement.setString(1, id); statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toTrack()) } } }
        PlaylistDetail(summary.id, summary.name, summary.version, tracks, summary.updatedAt)
    }

    fun updatePlaylist(id: String, request: UpdatePlaylistRequest, now: Long): PlaylistDetail? = connection().use { db ->
        db.autoCommit = false
        try {
            val current = db.prepareStatement("SELECT version,name FROM playlists WHERE id=?").use {
                it.setString(1, id); it.executeQuery().use { row -> if (row.next()) row.getLong(1) to row.getString(2) else null }
            } ?: return@use null
            if (request.expectedVersion != null && request.expectedVersion != current.first) throw PlaylistVersionConflict()
            db.prepareStatement("UPDATE playlists SET name=?, version=version+1, updated_at=? WHERE id=? AND version=?").use {
                it.setString(1, request.name?.trim()?.ifBlank { current.second } ?: current.second)
                it.setLong(2, now)
                it.setString(3, id)
                it.setLong(4, current.first)
                if (it.executeUpdate() != 1) throw PlaylistVersionConflict()
            }
            request.trackIds?.let { ids ->
                db.prepareStatement("DELETE FROM playlist_items WHERE playlist_id=?").use { it.setString(1, id); it.executeUpdate() }
                db.prepareStatement("INSERT INTO playlist_items(playlist_id,track_id,position) VALUES(?,?,?)").use { insert ->
                    ids.distinct().forEachIndexed { index, trackId ->
                        insert.setString(1, id); insert.setString(2, trackId); insert.setInt(3, index); insert.addBatch()
                    }
                    insert.executeBatch()
                }
            }
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
        playlist(id)
    }

    fun deletePlaylist(id: String): Boolean = connection().use { db ->
        db.prepareStatement("DELETE FROM playlists WHERE id=?").use { it.setString(1, id); it.executeUpdate() > 0 }
    }

    fun recordHistory(trackId: String, playedAt: Long) = connection().use { db ->
        db.prepareStatement("INSERT INTO history(track_id,played_at) VALUES(?,?)").use {
            it.setString(1, trackId); it.setLong(2, playedAt); it.executeUpdate()
        }
    }

    fun history(limit: Int): List<HistoryEntry> = connection().use { db ->
        db.prepareStatement(
            "SELECT h.id history_id,h.played_at,t.*,EXISTS(SELECT 1 FROM favorites f WHERE f.track_id=t.id) favorite FROM history h JOIN tracks t ON t.id=h.track_id ORDER BY h.played_at DESC LIMIT ?",
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(HistoryEntry(rows.getLong("history_id"), rows.toTrack(), rows.getLong("played_at"))) } }
        }
    }

    fun saveSource(id: String?, request: SourceConfigRequest, now: Long): SourceConfigView {
        val sourceId = id ?: UUID.randomUUID().toString()
        connection().use { db ->
            db.prepareStatement(
                "INSERT INTO source_configs(id,name,api_url,api_key,enabled,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,api_url=excluded.api_url,api_key=excluded.api_key,enabled=excluded.enabled,updated_at=excluded.updated_at",
            ).use {
                it.setString(1, sourceId); it.setString(2, request.name); it.setString(3, request.apiUrl); it.setString(4, request.apiKey)
                it.setBoolean(5, request.enabled); it.setLong(6, now); it.executeUpdate()
            }
        }
        return SourceConfigView(sourceId, request.name, request.apiUrl, maskSecret(request.apiKey), request.enabled, now)
    }

    fun sources(): List<SourceConfigView> = connection().use { db ->
        db.createStatement().use { statement -> statement.executeQuery("SELECT * FROM source_configs ORDER BY updated_at DESC").use { rows ->
            buildList { while (rows.next()) add(SourceConfigView(rows.getString("id"), rows.getString("name"), rows.getString("api_url"), maskSecret(rows.getString("api_key")), rows.getBoolean("enabled"), rows.getLong("updated_at"))) }
        } }
    }

    fun activeSourceSecret(): Triple<String, String, String>? = connection().use { db ->
        db.createStatement().use { statement -> statement.executeQuery("SELECT name,api_url,api_key FROM source_configs WHERE enabled=1 ORDER BY updated_at DESC LIMIT 1").use { row ->
            if (row.next()) Triple(row.getString(1), row.getString(2), row.getString(3)) else null
        } }
    }

    fun saveDownload(job: DownloadJob, request: DownloadRequest? = null) = connection().use { db ->
        db.prepareStatement("INSERT INTO download_jobs(id,title,artist,status,error,track_id,url,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,error=excluded.error,updated_at=excluded.updated_at").use {
            it.setString(1, job.id); it.setString(2, job.title); it.setString(3, job.artist); it.setString(4, job.status); it.setString(5, job.error)
            it.setString(6, request?.trackId); it.setString(7, request?.url); it.setLong(8, job.createdAt); it.setLong(9, job.updatedAt); it.executeUpdate()
        }
    }

    fun downloadForRetry(id: String): Pair<DownloadJob, DownloadRequest>? = connection().use { db ->
        db.prepareStatement("SELECT * FROM download_jobs WHERE id=? AND status='failed'").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row ->
                if (!row.next()) return@use null
                val job = DownloadJob(row.getString("id"), row.getString("title"), row.getString("artist"), row.getString("status"), row.getString("error"), row.getLong("created_at"), row.getLong("updated_at"))
                job to DownloadRequest(row.getString("track_id"), row.getString("url"), job.title, job.artist)
            }
        }
    }

    fun saveResolvedDownloadUrl(id: String, url: String) = connection().use { db ->
        db.prepareStatement("UPDATE download_jobs SET url=? WHERE id=?").use {
            it.setString(1, url); it.setString(2, id); it.executeUpdate()
        }
    }

    fun downloads(): List<DownloadJob> = connection().use { db ->
        db.createStatement().use { statement -> statement.executeQuery("SELECT * FROM download_jobs ORDER BY created_at DESC").use { rows -> buildList {
            while (rows.next()) add(DownloadJob(rows.getString("id"), rows.getString("title"), rows.getString("artist"), rows.getString("status"), rows.getString("error"), rows.getLong("created_at"), rows.getLong("updated_at")))
        } } }
    }

    fun failInterruptedDownloads(now: Long) = connection().use { db ->
        db.prepareStatement("UPDATE download_jobs SET status='failed',error='server_restarted',updated_at=? WHERE status IN ('queued','downloading')").use {
            it.setLong(1, now)
            it.executeUpdate()
        }
    }

    fun createRoom(name: String, stateJson: String, now: Long, requestedId: String? = null): RoomSummary {
        val id = requestedId ?: UUID.randomUUID().toString()
        connection().use { db -> db.prepareStatement("INSERT INTO rooms(id,name,state_json,version,updated_at) VALUES(?,?,?,0,?)").use {
            it.setString(1, id); it.setString(2, name); it.setString(3, stateJson); it.setLong(4, now); it.executeUpdate()
        } }
        return RoomSummary(id, name, 0, 0, now)
    }

    fun deleteRoom(id: String): Boolean = connection().use { db ->
        db.prepareStatement("DELETE FROM rooms WHERE id=?").use { statement ->
            statement.setString(1, id)
            statement.executeUpdate() > 0
        }
    }

    fun rooms(): List<StoredRoom> = connection().use { db ->
        db.createStatement().use { statement -> statement.executeQuery("SELECT * FROM rooms ORDER BY updated_at DESC").use { rows -> buildList {
            while (rows.next()) add(StoredRoom(rows.getString("id"), rows.getString("name"), rows.getString("state_json"), rows.getLong("version"), rows.getLong("updated_at")))
        } } }
    }

    fun saveRoom(id: String, stateJson: String, version: Long, now: Long) = connection().use { db ->
        db.prepareStatement("UPDATE rooms SET state_json=?,version=?,updated_at=? WHERE id=?").use {
            it.setString(1, stateJson); it.setLong(2, version); it.setLong(3, now); it.setString(4, id); it.executeUpdate()
        }
    }

    fun backupTo(target: Path) {
        target.parent.createDirectories()
        connection().use { db ->
            db.prepareStatement("VACUUM INTO ?").use { statement ->
                statement.setString(1, target.toAbsolutePath().toString())
                statement.execute()
            }
        }
    }

    private fun ResultSet.toPlaylistSummary() = PlaylistSummary(getString("id"), getString("name"), getLong("version"), getInt("track_count"), getLong("updated_at"))

    private fun bindTrack(statement: java.sql.PreparedStatement, scanId: String, file: ScannedFile) {
        statement.setString(1, file.id)
        statement.setString(2, file.title)
        statement.setString(3, file.artist)
        statement.setString(4, file.album)
        statement.setLong(5, file.durationMs)
        statement.setString(6, file.mimeType)
        statement.setString(7, file.path.toAbsolutePath().normalize().toString())
        statement.setLong(8, file.size)
        statement.setLong(9, file.modifiedAt)
        statement.setString(10, scanId)
    }
    private fun maskSecret(secret: String) = when {
        secret.isBlank() -> ""
        secret.length <= 4 -> "••••"
        else -> "••••${secret.takeLast(4)}"
    }

    private fun bindSearch(statement: java.sql.PreparedStatement, filter: String, fullText: Boolean): Int {
        if (filter.isBlank()) return 1
        if (fullText) {
            statement.setString(1, "\"${filter.replace("\"", "\"\"")}\"")
            return 2
        }
        val value = "%${filter.replace("%", "\\%").replace("_", "\\_")}%"
        statement.setString(1, value)
        statement.setString(2, value)
        statement.setString(3, value)
        return 4
    }

    private fun ResultSet.toTrack() = Track(
        id = getString("id"),
        title = getString("title"),
        artist = getString("artist"),
        album = getString("album"),
        durationMs = getLong("duration_ms"),
        mimeType = getString("mime_type"),
        size = getLong("file_size"),
        modifiedAt = getLong("modified_at"),
        artworkUrl = "/api/media/${getString("id")}/artwork",
        favorite = getBoolean("favorite"),
    )

    companion object {
        private val UPSERT_TRACK_SQL =
            """
            INSERT INTO tracks(id,title,artist,album,duration_ms,mime_type,file_path,file_size,modified_at,last_seen_scan,deleted_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,NULL)
            ON CONFLICT(id) DO UPDATE SET title=excluded.title, artist=excluded.artist, album=excluded.album,
              duration_ms=excluded.duration_ms, mime_type=excluded.mime_type, file_path=excluded.file_path,
              file_size=excluded.file_size, modified_at=excluded.modified_at, last_seen_scan=excluded.last_seen_scan,
              deleted_at=NULL
            """.trimIndent()
    }
}

data class TrackFingerprint(val size: Long, val modifiedAt: Long)

data class StoredRoom(val id: String, val name: String, val stateJson: String, val version: Long, val updatedAt: Long)

class PlaylistVersionConflict : RuntimeException("playlist_version_conflict")

enum class ScanChange { DISCOVERED, UPDATED, UNCHANGED }
