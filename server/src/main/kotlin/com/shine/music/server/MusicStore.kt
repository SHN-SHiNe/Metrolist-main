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
                        scanned_at INTEGER NOT NULL DEFAULT 0,
                        last_seen_scan TEXT NOT NULL,
                        artwork_url TEXT,
                        deleted_at INTEGER,
                        library_id TEXT NOT NULL DEFAULT 'default'
                    )
                    """.trimIndent(),
                )
                runCatching { statement.execute("ALTER TABLE tracks ADD COLUMN library_id TEXT NOT NULL DEFAULT 'default'") }
                runCatching { statement.execute("ALTER TABLE tracks ADD COLUMN artwork_url TEXT") }
                runCatching { statement.execute("ALTER TABLE tracks ADD COLUMN scanned_at INTEGER NOT NULL DEFAULT 0") }
                listOf(
                    "analysis_status TEXT NOT NULL DEFAULT 'pending'",
                    "analysis_progress REAL NOT NULL DEFAULT 0",
                    "analysis_message TEXT",
                    "bpm REAL",
                    "key_name TEXT",
                    "camelot_code TEXT",
                    "valence REAL",
                    "energy REAL",
                    "danceability REAL",
                    "acousticness REAL",
                    "instrumentalness REAL",
                    "liveness REAL",
                    "speechiness REAL",
                    "analyzed_at INTEGER",
                ).forEach { definition ->
                    runCatching { statement.execute("ALTER TABLE tracks ADD COLUMN $definition") }
                }
                statement.execute("UPDATE tracks SET scanned_at=modified_at WHERE scanned_at=0")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS online_catalog (
                        id TEXT PRIMARY KEY,
                        payload_json TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL DEFAULT '',
                        artwork_url TEXT,
                        source TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS music_libraries (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        path TEXT NOT NULL UNIQUE,
                        device_type TEXT NOT NULL,
                        read_only INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        download_target INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        last_scan_at INTEGER,
                        last_error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
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
                        error TEXT, track_id TEXT, url TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                        downloaded_bytes INTEGER NOT NULL DEFAULT 0, total_bytes INTEGER
                    )
                    """.trimIndent(),
                )
                runCatching { statement.execute("ALTER TABLE download_jobs ADD COLUMN track_id TEXT") }
                runCatching { statement.execute("ALTER TABLE download_jobs ADD COLUMN url TEXT") }
                val downloadColumns = statement.executeQuery("PRAGMA table_info(download_jobs)").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString("name")) }
                }
                if ("downloaded_bytes" !in downloadColumns) {
                    statement.execute("ALTER TABLE download_jobs ADD COLUMN downloaded_bytes INTEGER NOT NULL DEFAULT 0")
                }
                if ("total_bytes" !in downloadColumns) {
                    statement.execute("ALTER TABLE download_jobs ADD COLUMN total_bytes INTEGER")
                }
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
                        completed_at INTEGER, library_id TEXT
                    )
                    """.trimIndent(),
                )
                runCatching { statement.execute("ALTER TABLE scan_jobs ADD COLUMN library_id TEXT") }
                statement.execute("CREATE TABLE IF NOT EXISTS library_revision (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL)")
                statement.execute("INSERT OR IGNORE INTO library_revision(id,revision) VALUES(1,0)")
                statement.execute("DROP TRIGGER IF EXISTS tracks_revision_insert")
                statement.execute("DROP TRIGGER IF EXISTS tracks_revision_delete")
                statement.execute("CREATE TRIGGER tracks_revision_insert AFTER INSERT ON tracks WHEN NEW.library_id<>'$ONLINE_LIBRARY_ID' BEGIN UPDATE library_revision SET revision=revision+1 WHERE id=1; END")
                statement.execute("CREATE TRIGGER tracks_revision_delete AFTER DELETE ON tracks WHEN OLD.library_id<>'$ONLINE_LIBRARY_ID' BEGIN UPDATE library_revision SET revision=revision+1 WHERE id=1; END")
                statement.execute("DROP TRIGGER IF EXISTS tracks_revision_update")
                statement.execute(
                    """
                    CREATE TRIGGER IF NOT EXISTS tracks_revision_update
                    AFTER UPDATE OF title,artist,album,duration_ms,mime_type,file_path,file_size,modified_at,deleted_at,
                      library_id ON tracks
                    WHEN (OLD.library_id<>'$ONLINE_LIBRARY_ID' OR NEW.library_id<>'$ONLINE_LIBRARY_ID')
                      AND (OLD.title IS NOT NEW.title OR OLD.artist IS NOT NEW.artist OR OLD.album IS NOT NEW.album
                        OR OLD.duration_ms IS NOT NEW.duration_ms OR OLD.mime_type IS NOT NEW.mime_type
                        OR OLD.file_path IS NOT NEW.file_path OR OLD.file_size IS NOT NEW.file_size
                        OR OLD.modified_at IS NOT NEW.modified_at OR OLD.deleted_at IS NOT NEW.deleted_at
                        OR OLD.library_id IS NOT NEW.library_id)
                    BEGIN UPDATE library_revision SET revision=revision+1 WHERE id=1; END
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_library_sort ON tracks(deleted_at, artist COLLATE NOCASE, album COLLATE NOCASE, title COLLATE NOCASE)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_title_sort ON tracks(deleted_at, title COLLATE NOCASE, artist COLLATE NOCASE)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_album_sort ON tracks(deleted_at, album COLLATE NOCASE, artist COLLATE NOCASE, title COLLATE NOCASE)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_scan ON tracks(last_seen_scan, deleted_at)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_library ON tracks(library_id, deleted_at)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_library_title_sort ON tracks(library_id, deleted_at, title COLLATE NOCASE, artist COLLATE NOCASE, album COLLATE NOCASE, id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_library_artist_sort ON tracks(library_id, deleted_at, artist COLLATE NOCASE, album COLLATE NOCASE, title COLLATE NOCASE, id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_library_album_sort ON tracks(library_id, deleted_at, album COLLATE NOCASE, artist COLLATE NOCASE, title COLLATE NOCASE, id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_scanned_sort ON tracks(deleted_at, scanned_at, title COLLATE NOCASE, id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_library_scanned_sort ON tracks(library_id, deleted_at, scanned_at, title COLLATE NOCASE, id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_modified_sort ON tracks(deleted_at, modified_at, title COLLATE NOCASE, id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_library_modified_sort ON tracks(library_id, deleted_at, modified_at, title COLLATE NOCASE, id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_analysis_status ON tracks(deleted_at, analysis_status, modified_at)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_similarity_prefilter ON tracks(deleted_at, analysis_status, bpm, camelot_code)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tracks_similarity_key_prefilter ON tracks(deleted_at, analysis_status, camelot_code, bpm)")
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_music_libraries_download_target ON music_libraries(download_target) WHERE download_target=1")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_history_played_at ON history(played_at DESC)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_playlist_items_track ON playlist_items(track_id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_download_jobs_created_at ON download_jobs(created_at DESC)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_rooms_updated_at ON rooms(updated_at DESC)")
                    fullTextSearchEnabled = runCatching {
                        statement.execute("CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING fts5(id UNINDEXED,title,artist,album,tokenize='trigram')")
                        statement.execute("INSERT INTO tracks_fts(id,title,artist,album) SELECT id,title,artist,album FROM tracks WHERE deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' AND NOT EXISTS (SELECT 1 FROM tracks_fts LIMIT 1)")
                        statement.execute("DELETE FROM tracks_fts WHERE id IN (SELECT id FROM tracks WHERE library_id='$ONLINE_LIBRARY_ID')")
                        statement.execute("DROP TRIGGER IF EXISTS tracks_fts_insert")
                        statement.execute("DROP TRIGGER IF EXISTS tracks_fts_delete")
                        statement.execute("DROP TRIGGER IF EXISTS tracks_fts_update")
                        statement.execute("CREATE TRIGGER tracks_fts_insert AFTER INSERT ON tracks WHEN NEW.deleted_at IS NULL AND NEW.library_id<>'$ONLINE_LIBRARY_ID' BEGIN INSERT INTO tracks_fts(id,title,artist,album) VALUES(NEW.id,NEW.title,NEW.artist,NEW.album); END")
                        statement.execute("CREATE TRIGGER tracks_fts_delete AFTER DELETE ON tracks BEGIN DELETE FROM tracks_fts WHERE id=OLD.id; END")
                        statement.execute("CREATE TRIGGER tracks_fts_update AFTER UPDATE OF title,artist,album,deleted_at,library_id ON tracks BEGIN DELETE FROM tracks_fts WHERE id=OLD.id; INSERT INTO tracks_fts(id,title,artist,album) SELECT NEW.id,NEW.title,NEW.artist,NEW.album WHERE NEW.deleted_at IS NULL AND NEW.library_id<>'$ONLINE_LIBRARY_ID'; END")
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

    fun scanSession(libraryId: String = DEFAULT_LIBRARY_ID) = ScanSession(connection(), libraryId)

    inner class ScanSession internal constructor(private val db: Connection, private val libraryId: String) : AutoCloseable {
        private val fingerprint = db.prepareStatement("SELECT file_size,modified_at FROM tracks WHERE id=? AND library_id=? AND deleted_at IS NULL")

        fun fingerprint(id: String): TrackFingerprint? {
            fingerprint.setString(1, id)
            fingerprint.setString(2, libraryId)
            return fingerprint.executeQuery().use { row ->
                if (row.next()) TrackFingerprint(row.getLong("file_size"), row.getLong("modified_at")) else null
            }
        }

        fun applyBatch(scanId: String, changed: List<ScannedFile>, unchangedIds: List<String>, scannedAt: Long) {
            db.autoCommit = false
            try {
                if (changed.isNotEmpty()) {
                    db.prepareStatement(UPSERT_TRACK_SQL).use { statement ->
                        changed.forEach { file -> bindTrack(statement, scanId, file); statement.addBatch() }
                        statement.executeBatch()
                    }
                }
                if (unchangedIds.isNotEmpty()) {
                    db.prepareStatement("UPDATE tracks SET last_seen_scan=?,scanned_at=? WHERE id=? AND library_id=? AND deleted_at IS NULL").use { statement ->
                        unchangedIds.forEach { id ->
                            statement.setString(1, scanId)
                            statement.setLong(2, scannedAt)
                            statement.setString(3, id)
                            statement.setString(4, libraryId)
                            statement.addBatch()
                        }
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

    fun startScan(id: String, startedAt: Long, libraryId: String? = null) = connection().use { db ->
        db.prepareStatement("INSERT INTO scan_jobs(id,status,discovered,updated,removed,started_at,completed_at,library_id) VALUES(?,'running',0,0,0,?,NULL,?)").use {
            it.setString(1, id); it.setLong(2, startedAt); it.setString(3, libraryId); it.executeUpdate()
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
                while (rows.next()) add(ScanResult(rows.getString("id"), rows.getString("status"), rows.getInt("discovered"), rows.getInt("updated"), rows.getInt("removed"), rows.getLong("started_at"), rows.getLong("completed_at"), rows.getString("library_id")))
            } }
        }
    }

    fun finishScan(scanId: String, libraryId: String = DEFAULT_LIBRARY_ID): Int = connection().use { db ->
        db.prepareStatement("UPDATE tracks SET deleted_at = ? WHERE library_id=? AND last_seen_scan <> ? AND deleted_at IS NULL").use { statement ->
            statement.setLong(1, System.currentTimeMillis())
            statement.setString(2, libraryId)
            statement.setString(3, scanId)
            statement.executeUpdate()
        }
    }

    fun listTracks(
        query: String?,
        offset: Int,
        limit: Int,
        sort: String = "artist",
        libraryId: String? = null,
        direction: String = "asc",
    ): TrackPage = connection().use { db ->
        val filter = query?.trim().orEmpty()
        val useFullTextSearch = fullTextSearchEnabled && filter.length >= 3
        val from = if (useFullTextSearch) "tracks t JOIN tracks_fts ON tracks_fts.id=t.id" else "tracks t"
        val libraryWhere = if (libraryId == null) "" else " AND t.library_id=?"
        val where = when {
            filter.isBlank() -> "t.deleted_at IS NULL AND t.library_id<>'$ONLINE_LIBRARY_ID'$libraryWhere"
            useFullTextSearch -> "t.deleted_at IS NULL AND t.library_id<>'$ONLINE_LIBRARY_ID' AND tracks_fts MATCH ?$libraryWhere"
            else -> "t.deleted_at IS NULL AND t.library_id<>'$ONLINE_LIBRARY_ID' AND (t.title LIKE ? ESCAPE '\\' OR t.artist LIKE ? ESCAPE '\\' OR t.album LIKE ? ESCAPE '\\')$libraryWhere"
        }
        val sortKey = sort.takeIf(SUPPORTED_LIBRARY_SORTS::contains) ?: "artist"
        val sortDirection = direction.takeIf(SUPPORTED_SORT_DIRECTIONS::contains)?.uppercase() ?: "ASC"
        val orderBy = when (sortKey) {
            "title" -> "t.title COLLATE NOCASE $sortDirection, t.artist COLLATE NOCASE $sortDirection, t.album COLLATE NOCASE $sortDirection, t.id"
            "artist" -> "t.artist COLLATE NOCASE $sortDirection, t.album COLLATE NOCASE $sortDirection, t.title COLLATE NOCASE $sortDirection, t.id"
            "album" -> "t.album COLLATE NOCASE $sortDirection, t.artist COLLATE NOCASE $sortDirection, t.title COLLATE NOCASE $sortDirection, t.id"
            "modified" -> "t.modified_at $sortDirection, t.title COLLATE NOCASE $sortDirection, t.id"
            "scanned" -> "t.scanned_at $sortDirection, t.title COLLATE NOCASE $sortDirection, t.id"
            "bpm" -> "CASE WHEN t.analysis_status='completed' AND t.bpm IS NOT NULL THEN 0 ELSE 1 END, t.bpm $sortDirection, t.title COLLATE NOCASE $sortDirection, t.id"
            "energy" -> "CASE WHEN t.analysis_status='completed' AND t.energy IS NOT NULL THEN 0 ELSE 1 END, t.energy $sortDirection, t.title COLLATE NOCASE $sortDirection, t.id"
            "key" -> "CASE WHEN t.analysis_status='completed' AND t.key_name IS NOT NULL THEN 0 ELSE 1 END, t.camelot_code COLLATE NOCASE $sortDirection, t.key_name COLLATE NOCASE $sortDirection, t.title COLLATE NOCASE $sortDirection, t.id"
            else -> "CASE t.analysis_status WHEN 'running' THEN 0 WHEN 'queued' THEN 1 WHEN 'pending' THEN 2 WHEN 'failed' THEN 3 WHEN 'unavailable' THEN 4 ELSE 5 END, t.analysis_progress $sortDirection, t.title COLLATE NOCASE $sortDirection, t.id"
        }
        db.autoCommit = false
        try {
            val revision = db.createStatement().use { statement -> statement.executeQuery("SELECT revision FROM library_revision WHERE id=1").use { it.next(); it.getLong(1) } }
            val total = db.prepareStatement("SELECT COUNT(*) FROM $from WHERE $where").use { statement ->
                var index = bindSearch(statement, filter, useFullTextSearch)
                if (libraryId != null) statement.setString(index, libraryId)
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
                if (libraryId != null) statement.setString(index++, libraryId)
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

    fun saveOnlineTrack(id: String, payloadJson: String, track: OnlineTrack, now: Long) = connection().use { db ->
        db.autoCommit = false
        try {
            db.prepareStatement(
                """
                INSERT INTO online_catalog(id,payload_json,title,artist,album,artwork_url,source,duration_ms,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET payload_json=excluded.payload_json,title=excluded.title,
                  artist=excluded.artist,album=excluded.album,artwork_url=excluded.artwork_url,
                  source=excluded.source,duration_ms=excluded.duration_ms,updated_at=excluded.updated_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, payloadJson)
                statement.setString(3, track.title)
                statement.setString(4, track.artist)
                statement.setString(5, track.album)
                statement.setString(6, track.artworkUrl)
                statement.setString(7, track.source)
                statement.setLong(8, track.durationMs)
                statement.setLong(9, now)
                statement.executeUpdate()
            }
            db.prepareStatement(
                """
                INSERT INTO tracks(
                  id,title,artist,album,duration_ms,mime_type,file_path,file_size,modified_at,scanned_at,
                  last_seen_scan,library_id,deleted_at,artwork_url,analysis_status,analysis_progress,analysis_message
                )
                VALUES(?,?,?,?,?,'audio/online',?,0,?,?,'online',?,NULL,?,'unavailable',0,'在线曲目')
                ON CONFLICT(id) DO UPDATE SET title=excluded.title,artist=excluded.artist,
                  album=excluded.album,duration_ms=excluded.duration_ms,artwork_url=excluded.artwork_url,
                  modified_at=excluded.modified_at,scanned_at=excluded.scanned_at,deleted_at=NULL
                WHERE tracks.library_id=excluded.library_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, track.title)
                statement.setString(3, track.artist)
                statement.setString(4, track.album)
                statement.setLong(5, track.durationMs)
                statement.setString(6, ".shine-online/$id")
                statement.setLong(7, now)
                statement.setLong(8, now)
                statement.setString(9, ONLINE_LIBRARY_ID)
                statement.setString(10, track.artworkUrl)
                statement.executeUpdate()
            }
            val storedLibrary = db.prepareStatement("SELECT library_id FROM tracks WHERE id=?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
            }
            check(storedLibrary == ONLINE_LIBRARY_ID) { "online_track_id_collision" }
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    fun onlineTrackPayload(id: String): String? = connection().use { db ->
        db.prepareStatement("SELECT payload_json FROM online_catalog WHERE id=?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
        }
    }

    fun trackFile(id: String): Path? = connection().use { db ->
        db.prepareStatement("SELECT file_path FROM tracks WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID'").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row -> if (row.next()) Path.of(row.getString(1)) else null }
        }
    }

    fun analysisSource(id: String): AnalysisSource? = connection().use { db ->
        db.prepareStatement(
            "SELECT file_path,file_size,modified_at FROM tracks WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID'",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row ->
                if (row.next()) AnalysisSource(Path.of(row.getString("file_path")), row.getLong("file_size"), row.getLong("modified_at")) else null
            }
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

    fun tracks(ids: List<String>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val uniqueIds = ids.distinct().take(500)
        val placeholders = uniqueIds.joinToString(",") { "?" }
        return connection().use { db ->
            db.prepareStatement(
                "SELECT t.*, EXISTS(SELECT 1 FROM favorites f WHERE f.track_id=t.id) AS favorite FROM tracks t WHERE t.deleted_at IS NULL AND t.id IN ($placeholders)",
            ).use { statement ->
                uniqueIds.forEachIndexed { index, id -> statement.setString(index + 1, id) }
                val byId = statement.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString("id"), rows.toTrack()) } }
                uniqueIds.mapNotNull(byId::get)
            }
        }
    }

    fun featureVector(id: String): TrackFeatureVector? = connection().use { db ->
        db.prepareStatement("SELECT * FROM tracks WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID'").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row -> if (row.next()) row.toFeatureVector() else null }
        }
    }

    fun similarityCandidates(seed: TrackFeatureVector, limit: Int = 100): List<TrackFeatureVector> {
        val bpm = seed.bpm?.takeIf { it > 0f } ?: return emptyList()
        val emotion = seed.emotionVector().map { value -> value?.let(::normalizeMetric) ?: return emptyList() }
        val compatibleCodes = seed.keyName?.compatibleCamelotKeys().orEmpty().mapNotNull(::camelotLabel).sorted()
        val keyWhere = if (compatibleCodes.isEmpty()) "" else " AND camelot_code IN (${compatibleCodes.joinToString(",") { "?" }})"
        val dimensions = listOf("valence", "energy", "danceability", "acousticness", "instrumentalness", "liveness", "speechiness")
        val distanceOrder = dimensions.joinToString(" + ") { column -> "(($column - ?) * ($column - ?))" }
        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT * FROM tracks
                WHERE deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' AND id<>? AND analysis_status='completed'
                  AND bpm BETWEEN ? AND ?
                  AND valence IS NOT NULL AND energy IS NOT NULL AND danceability IS NOT NULL
                  AND acousticness IS NOT NULL AND instrumentalness IS NOT NULL
                  AND liveness IS NOT NULL AND speechiness IS NOT NULL
                  $keyWhere
                ORDER BY $distanceOrder, id
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, seed.trackId)
                statement.setFloat(2, bpm - 5f)
                statement.setFloat(3, bpm + 5f)
                var parameter = 4
                compatibleCodes.forEach { code -> statement.setString(parameter++, code) }
                emotion.forEach { value ->
                    statement.setFloat(parameter++, value)
                    statement.setFloat(parameter++, value)
                }
                statement.setInt(parameter, limit.coerceIn(1, 500))
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toFeatureVector()) } }
            }
        }
    }

    fun similarTracks(id: String, recentIds: Set<String> = emptySet(), limit: Int = 30): SimilarTracksResponse? {
        val seedTrack = track(id) ?: return null
        val seed = featureVector(id) ?: return SimilarTracksResponse(seedTrack, emptyList())
        val candidateLimit = (limit.coerceIn(1, 100) + recentIds.size.coerceAtMost(12)).coerceAtMost(112)
        val ranked = TrackSimilarity.rank(seed, similarityCandidates(seed, candidateLimit).filterNot { it.trackId in recentIds }, limit)
        val tracks = tracks(ranked.map(RankedTrack::trackId)).associateBy(Track::id)
        return SimilarTracksResponse(
            seed = seedTrack,
            items = ranked.mapNotNull { match ->
                tracks[match.trackId]?.let { track ->
                    SimilarTrack(track, match.similarityPercent, match.bpmDelta, match.camelotDelta)
                }
            },
        )
    }

    fun advancedSearch(request: AdvancedSearchRequest): AdvancedSearchResponse {
        AdvancedTrackSearch.validate(request)
        val clauses = mutableListOf("deleted_at IS NULL", "library_id<>'$ONLINE_LIBRARY_ID'", "analysis_status='completed'")
        val whereBinders = mutableListOf<(java.sql.PreparedStatement, Int) -> Unit>()
        val scoreExpressions = mutableListOf<String>()
        val scoreBinders = mutableListOf<(java.sql.PreparedStatement, Int) -> Unit>()
        val text = request.text.trim()
        if (text.isNotEmpty()) {
            clauses += "(title LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR album LIKE ? ESCAPE '\\')"
            val value = "%${text.replace("%", "\\%").replace("_", "\\_")}%"
            repeat(3) { whereBinders += { statement, index -> statement.setString(index, value) } }
        }
        request.bpm?.let { target ->
            val tolerance = request.bpmTolerance.coerceAtLeast(0f)
            clauses += "bpm BETWEEN ? AND ?"
            whereBinders += { statement, index -> statement.setFloat(index, target - tolerance) }
            whereBinders += { statement, index -> statement.setFloat(index, target + tolerance) }
            scoreExpressions += "(ABS(bpm - ?) / ?)"
            scoreBinders += { statement, index -> statement.setFloat(index, target) }
            scoreBinders += { statement, index -> statement.setFloat(index, tolerance.coerceAtLeast(1f)) }
        }
        val requestedKey = request.keyName?.trim()?.takeIf(String::isNotEmpty)
        val selectedKey = requestedKey?.canonicalMusicKey()?.let(::musicKeyToCamelot)
        require(requestedKey == null || selectedKey != null) { "invalid_key" }
        selectedKey?.let { selected ->
            val codes = AdvancedTrackSearch.matchingCamelot(selected, request.keyTolerance).sortedWith(compareBy({ it.first }, { it.second }))
            clauses += "camelot_code IN (${codes.joinToString(",") { "?" }})"
            codes.forEach { (number, mode) -> whereBinders += { statement, index -> statement.setString(index, "$number$mode") } }
            scoreExpressions += "(CASE camelot_code ${codes.joinToString(" ") { "WHEN ? THEN ?" }} ELSE 1000 END)"
            codes.forEach { candidate ->
                val code = "${candidate.first}${candidate.second}"
                val distance = AdvancedTrackSearch.keyDistance(candidate, selected)
                scoreBinders += { statement, index -> statement.setString(index, code) }
                scoreBinders += { statement, index -> statement.setFloat(index, distance) }
            }
        }
        val emotionFilters = listOf(
            "valence" to request.valence,
            "energy" to request.energy,
            "danceability" to request.danceability,
            "acousticness" to request.acousticness,
            "instrumentalness" to request.instrumentalness,
            "liveness" to request.liveness,
            "speechiness" to request.speechiness,
        )
        val emotionTolerance = request.emotionTolerance.coerceIn(0f, 1f)
        emotionFilters.forEach { (column, target) ->
            target ?: return@forEach
            require(target.isFinite()) { "invalid_emotion_target" }
            val normalized = target.coerceIn(0f, 1f)
            clauses += "$column BETWEEN ? AND ?"
            whereBinders += { statement, index -> statement.setFloat(index, normalized - emotionTolerance) }
            whereBinders += { statement, index -> statement.setFloat(index, normalized + emotionTolerance) }
            scoreExpressions += "(ABS($column - ?) / ?)"
            scoreBinders += { statement, index -> statement.setFloat(index, normalized) }
            scoreBinders += { statement, index -> statement.setFloat(index, emotionTolerance.coerceAtLeast(0.001f)) }
        }

        fun bind(statement: java.sql.PreparedStatement, binders: List<(java.sql.PreparedStatement, Int) -> Unit>, start: Int = 1): Int {
            var parameter = start
            binders.forEach { binder -> binder(statement, parameter++) }
            return parameter
        }

        val where = clauses.joinToString(" AND ")
        val resultLimit = request.limit.coerceIn(1, 200)
        val ordering = if (scoreExpressions.isEmpty()) "id" else "(${scoreExpressions.joinToString(" + ")}) / ${scoreExpressions.size}, id"
        val (candidates, totalCandidates) = connection().use { db ->
            db.prepareStatement("SELECT *,COUNT(*) OVER() AS total_candidates FROM tracks WHERE $where ORDER BY $ordering LIMIT ?").use { statement ->
                var parameter = bind(statement, whereBinders)
                parameter = bind(statement, scoreBinders, parameter)
                statement.setInt(parameter, resultLimit)
                statement.executeQuery().use { rows ->
                    var total = 0
                    val items = buildList {
                        while (rows.next()) {
                            if (isEmpty()) total = rows.getInt("total_candidates")
                            add(rows.toFeatureVector())
                        }
                    }
                    items to total
                }
            }
        }
        val ranked = AdvancedTrackSearch.rank(candidates, request).take(resultLimit)
        val tracks = tracks(ranked.map(AdvancedRankedTrack::trackId)).associateBy(Track::id)
        return AdvancedSearchResponse(
            items = ranked.mapNotNull { match ->
                tracks[match.trackId]?.let {
                    AdvancedSearchItem(
                        track = it,
                        similarityPercent = match.similarityPercent,
                        bpmDelta = match.bpmDelta,
                        camelotDelta = match.camelotDelta,
                        camelotModeChanged = match.camelotModeChanged,
                    )
                }
            },
            totalCandidates = totalCandidates,
        )
    }

    fun resetInterruptedAnalysis() = connection().use { db ->
        db.prepareStatement(
            "UPDATE tracks SET analysis_status='pending',analysis_progress=0,analysis_message=NULL WHERE deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' AND analysis_status IN ('queued','running')",
        ).use { it.executeUpdate() }
    }

    fun pendingAnalysisTrackIds(limit: Int = 10_000, includeFailed: Boolean = true): List<String> = connection().use { db ->
        val statuses = if (includeFailed) "('pending','failed')" else "('pending')"
        db.prepareStatement(
            "SELECT id FROM tracks WHERE deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' AND analysis_status IN $statuses ORDER BY modified_at DESC LIMIT ?",
        ).use { statement ->
            statement.setInt(1, limit.coerceIn(1, 100_000))
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    /** Atomically reserves a bounded background batch so explicit requests cannot steal a stale selection. */
    fun claimPendingAnalysisTrackIds(limit: Int = 1): List<String> = connection().use { db ->
        db.prepareStatement(
            """
            UPDATE tracks
            SET analysis_status='queued',analysis_progress=0.05,analysis_message='等待分析'
            WHERE id IN (
                SELECT id FROM tracks
                WHERE deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' AND analysis_status='pending'
                ORDER BY modified_at DESC
                LIMIT ?
            )
              AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID'
              AND analysis_status='pending'
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, limit.coerceIn(1, MAX_ANALYSIS_CLAIM_BATCH_SIZE))
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    fun setAnalysisQueued(id: String, force: Boolean = false): Boolean = connection().use { db ->
        val condition = if (force) "analysis_status NOT IN ('queued','running')" else "analysis_status IN ('pending','failed')"
        db.prepareStatement(
            "UPDATE tracks SET analysis_status='queued',analysis_progress=0.05,analysis_message='等待分析' WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' AND $condition",
        ).use { statement -> statement.setString(1, id); statement.executeUpdate() > 0 }
    }

    /** Atomically starts the exact source version that was queued and returns its analysis lease. */
    fun startAnalysis(id: String): AnalysisSource? = connection().use { db ->
        db.prepareStatement(
            """
            UPDATE tracks
            SET analysis_status='running',analysis_progress=0.1,analysis_message='准备分析'
            WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' AND analysis_status='queued'
            RETURNING file_path,file_size,modified_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row ->
                if (row.next()) AnalysisSource(Path.of(row.getString("file_path")), row.getLong("file_size"), row.getLong("modified_at")) else null
            }
        }
    }

    /** Writes state only while the caller still owns the running lease for this source version. */
    fun updateRunningAnalysisState(
        id: String,
        expectedSource: AnalysisSource,
        status: String,
        progress: Float,
        message: String?,
    ): Boolean = connection().use { db ->
        db.prepareStatement(
            """
            UPDATE tracks SET analysis_status=?,analysis_progress=?,analysis_message=?
            WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' AND analysis_status='running'
              AND file_path=? AND file_size=? AND modified_at=?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status)
            statement.setFloat(2, progress.coerceIn(0f, 1f))
            statement.setString(3, message)
            statement.setString(4, id)
            statement.setString(5, expectedSource.path.toString())
            statement.setLong(6, expectedSource.size)
            statement.setLong(7, expectedSource.modifiedAt)
            statement.executeUpdate() > 0
        }
    }

    fun resetQueuedAnalysis(id: String): Boolean = connection().use { db ->
        db.prepareStatement(
            "UPDATE tracks SET analysis_status='pending',analysis_progress=0,analysis_message=NULL WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' AND analysis_status='queued'",
        ).use { statement -> statement.setString(1, id); statement.executeUpdate() > 0 }
    }

    fun updateAnalysisState(id: String, status: String, progress: Float, message: String?) = connection().use { db ->
        db.prepareStatement(
            "UPDATE tracks SET analysis_status=?,analysis_progress=?,analysis_message=? WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID'",
        ).use { statement ->
            statement.setString(1, status)
            statement.setFloat(2, progress.coerceIn(0f, 1f))
            statement.setString(3, message)
            statement.setString(4, id)
            statement.executeUpdate() > 0
        }
    }

    fun saveAnalysis(id: String, result: AudioAnalysisResult, now: Long, expectedSource: AnalysisSource? = null) = connection().use { db ->
        val sourceGuard = if (expectedSource == null) "" else " AND analysis_status='running' AND file_path=? AND file_size=? AND modified_at=?"
        db.prepareStatement(
            """
            UPDATE tracks SET analysis_status='completed',analysis_progress=1,analysis_message='分析完成',
              bpm=?,key_name=?,camelot_code=?,valence=?,energy=?,danceability=?,acousticness=?,instrumentalness=?,liveness=?,speechiness=?,analyzed_at=?
            WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID'$sourceGuard
            """.trimIndent(),
        ).use { statement ->
            statement.setFloat(1, result.bpm)
            statement.setString(2, result.keyName)
            statement.setString(3, camelotLabel(result.keyName))
            statement.setFloat(4, normalizeMetric(result.valence))
            statement.setFloat(5, normalizeMetric(result.energy))
            statement.setFloat(6, normalizeMetric(result.danceability))
            statement.setFloat(7, normalizeMetric(result.acousticness))
            statement.setFloat(8, normalizeMetric(result.instrumentalness))
            statement.setFloat(9, normalizeMetric(result.liveness))
            statement.setFloat(10, normalizeMetric(result.speechiness))
            statement.setLong(11, now)
            statement.setString(12, id)
            if (expectedSource != null) {
                statement.setString(13, expectedSource.path.toString())
                statement.setLong(14, expectedSource.size)
                statement.setLong(15, expectedSource.modifiedAt)
            }
            statement.executeUpdate() > 0
        }
    }

    fun analysisSummary(available: Boolean, implementation: String, unavailableReason: String? = null): AnalysisSummary = connection().use { db ->
        val counts = mutableMapOf<String, Int>()
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT analysis_status,COUNT(*) count FROM tracks WHERE deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID' GROUP BY analysis_status").use { rows ->
                while (rows.next()) counts[rows.getString("analysis_status")] = rows.getInt("count")
            }
        }
        AnalysisSummary(
            available = available,
            implementation = implementation,
            unavailableReason = unavailableReason,
            total = counts.values.sum(),
            pending = counts["pending"] ?: 0,
            queued = counts["queued"] ?: 0,
            running = counts["running"] ?: 0,
            completed = counts["completed"] ?: 0,
            failed = counts["failed"] ?: 0,
        )
    }

    fun markTrackDeleted(id: String, now: Long): Boolean = connection().use { db ->
        db.prepareStatement("UPDATE tracks SET deleted_at=? WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID'").use {
            it.setLong(1, now); it.setString(2, id); it.executeUpdate() > 0
        }
    }

    fun ensureDefaultLibrary(path: Path, now: Long) = connection().use { db ->
        db.prepareStatement(
            """
            INSERT OR IGNORE INTO music_libraries(
              id,name,path,device_type,read_only,enabled,download_target,status,last_scan_at,last_error,created_at,updated_at
            ) VALUES(?, 'NAS 音乐', ?, 'local', 0, 1, 1, 'unknown', NULL, NULL, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, DEFAULT_LIBRARY_ID)
            statement.setString(2, path.toAbsolutePath().normalize().toString())
            statement.setLong(3, now)
            statement.setLong(4, now)
            statement.executeUpdate()
        }
    }

    fun createLibrary(request: MusicLibraryRequest, status: String, now: Long): MusicLibraryView {
        val id = UUID.randomUUID().toString()
        connection().use { db ->
            db.autoCommit = false
            try {
                if (request.downloadTarget) db.createStatement().use { it.executeUpdate("UPDATE music_libraries SET download_target=0") }
                db.prepareStatement(
                    """
                    INSERT INTO music_libraries(
                      id,name,path,device_type,read_only,enabled,download_target,status,last_scan_at,last_error,created_at,updated_at
                    ) VALUES(?,?,?,?,?,?,?,?,NULL,NULL,?,?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, id)
                    statement.setString(2, request.name)
                    statement.setString(3, request.path)
                    statement.setString(4, request.deviceType)
                    statement.setBoolean(5, request.readOnly)
                    statement.setBoolean(6, request.enabled)
                    statement.setBoolean(7, request.downloadTarget)
                    statement.setString(8, status)
                    statement.setLong(9, now)
                    statement.setLong(10, now)
                    statement.executeUpdate()
                }
                db.commit()
            } catch (error: Throwable) {
                db.rollback()
                throw error
            } finally {
                db.autoCommit = true
            }
        }
        return requireNotNull(library(id))
    }

    fun updateLibrary(id: String, request: MusicLibraryRequest, status: String, now: Long): MusicLibraryView? {
        if (library(id) == null) return null
        connection().use { db ->
            db.autoCommit = false
            try {
                if (request.downloadTarget) db.createStatement().use { it.executeUpdate("UPDATE music_libraries SET download_target=0") }
                db.prepareStatement(
                    """
                    UPDATE music_libraries SET name=?,device_type=?,read_only=?,enabled=?,download_target=?,status=?,
                      last_error=NULL,updated_at=? WHERE id=?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, request.name)
                    statement.setString(2, request.deviceType)
                    statement.setBoolean(3, request.readOnly)
                    statement.setBoolean(4, request.enabled)
                    statement.setBoolean(5, request.downloadTarget)
                    statement.setString(6, status)
                    statement.setLong(7, now)
                    statement.setString(8, id)
                    statement.executeUpdate()
                }
                db.commit()
            } catch (error: Throwable) {
                db.rollback()
                throw error
            } finally {
                db.autoCommit = true
            }
        }
        return library(id)
    }

    fun libraries(): List<MusicLibraryView> = connection().use { db ->
        db.createStatement().use { statement ->
            statement.executeQuery(LIBRARY_SELECT + " ORDER BY l.created_at, l.name COLLATE NOCASE").use { rows ->
                buildList { while (rows.next()) add(rows.toMusicLibrary()) }
            }
        }
    }

    fun library(id: String): MusicLibraryView? = connection().use { db ->
        db.prepareStatement(LIBRARY_SELECT + " WHERE l.id=?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row -> if (row.next()) row.toMusicLibrary() else null }
        }
    }

    fun setLibraryStatus(id: String, status: String, lastScanAt: Long?, error: String?, now: Long) = connection().use { db ->
        db.prepareStatement("UPDATE music_libraries SET status=?,last_scan_at=?,last_error=?,updated_at=? WHERE id=?").use { statement ->
            statement.setString(1, status)
            if (lastScanAt == null) statement.setNull(2, java.sql.Types.BIGINT) else statement.setLong(2, lastScanAt)
            statement.setString(3, error)
            statement.setLong(4, now)
            statement.setString(5, id)
            statement.executeUpdate()
        }
    }

    fun activeTrackCount(libraryId: String): Int = connection().use { db ->
        db.prepareStatement("SELECT COUNT(*) FROM tracks WHERE library_id=? AND deleted_at IS NULL").use { statement ->
            statement.setString(1, libraryId)
            statement.executeQuery().use { row -> row.next(); row.getInt(1) }
        }
    }

    fun trackLocation(id: String): StoredTrackLocation? = connection().use { db ->
        db.prepareStatement("SELECT file_path,library_id FROM tracks WHERE id=? AND deleted_at IS NULL AND library_id<>'$ONLINE_LIBRARY_ID'").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row ->
                if (row.next()) StoredTrackLocation(Path.of(row.getString("file_path")), row.getString("library_id")) else null
            }
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
        db.prepareStatement("INSERT INTO download_jobs(id,title,artist,status,error,track_id,url,created_at,updated_at,downloaded_bytes,total_bytes) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,error=excluded.error,updated_at=excluded.updated_at,downloaded_bytes=excluded.downloaded_bytes,total_bytes=excluded.total_bytes").use {
            it.setString(1, job.id); it.setString(2, job.title); it.setString(3, job.artist); it.setString(4, job.status); it.setString(5, job.error)
            it.setString(6, request?.trackId); it.setString(7, request?.url); it.setLong(8, job.createdAt); it.setLong(9, job.updatedAt)
            it.setLong(10, job.downloadedBytes)
            if (job.totalBytes == null) it.setNull(11, java.sql.Types.BIGINT) else it.setLong(11, job.totalBytes)
            it.executeUpdate()
        }
    }

    fun downloadForRetry(id: String): Pair<DownloadJob, DownloadRequest>? = connection().use { db ->
        db.prepareStatement("SELECT * FROM download_jobs WHERE id=? AND status='failed'").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { row ->
                if (!row.next()) return@use null
                val job = row.toDownloadJob()
                job to DownloadRequest(row.getString("track_id"), row.getString("url"), job.title, job.artist)
            }
        }
    }

    /** Atomically claims one queued download so a fixed worker pool never executes it twice. */
    fun claimQueuedDownload(now: Long): QueuedDownload? = connection().use { db ->
        db.prepareStatement(
            """
            UPDATE download_jobs
            SET status='downloading',error=NULL,updated_at=?
            WHERE id=(
                SELECT id FROM download_jobs
                WHERE status='queued'
                ORDER BY created_at,id
                LIMIT 1
            ) AND status='queued'
            RETURNING *
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, now)
            statement.executeQuery().use { row ->
                if (!row.next()) return@use null
                val job = row.toDownloadJob()
                QueuedDownload(
                    job,
                    DownloadRequest(row.getString("track_id"), row.getString("url"), job.title, job.artist),
                )
            }
        }
    }

    fun saveResolvedDownloadUrl(id: String, url: String) = connection().use { db ->
        db.prepareStatement("UPDATE download_jobs SET url=? WHERE id=?").use {
            it.setString(1, url); it.setString(2, id); it.executeUpdate()
        }
    }

    fun updateDownloadProgress(id: String, downloadedBytes: Long, totalBytes: Long?, now: Long) = connection().use { db ->
        db.prepareStatement(
            "UPDATE download_jobs SET downloaded_bytes=?,total_bytes=?,updated_at=? WHERE id=? AND status='downloading'",
        ).use { statement ->
            statement.setLong(1, downloadedBytes)
            if (totalBytes == null) statement.setNull(2, java.sql.Types.BIGINT) else statement.setLong(2, totalBytes)
            statement.setLong(3, now)
            statement.setString(4, id)
            statement.executeUpdate()
        }
    }

    fun updateDownloadStatus(id: String, status: String, error: String?, now: Long) = connection().use { db ->
        db.prepareStatement("UPDATE download_jobs SET status=?,error=?,updated_at=? WHERE id=?").use { statement ->
            statement.setString(1, status)
            statement.setString(2, error)
            statement.setLong(3, now)
            statement.setString(4, id)
            statement.executeUpdate()
        }
    }

    fun downloads(): List<DownloadJob> = connection().use { db ->
        db.createStatement().use { statement -> statement.executeQuery("SELECT * FROM download_jobs ORDER BY created_at DESC").use { rows -> buildList {
            while (rows.next()) add(rows.toDownloadJob())
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

    private fun ResultSet.toDownloadJob() = DownloadJob(
        id = getString("id"),
        title = getString("title"),
        artist = getString("artist"),
        status = getString("status"),
        error = getString("error"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        downloadedBytes = getLong("downloaded_bytes"),
        totalBytes = getLong("total_bytes").takeUnless { wasNull() },
    )

    private fun ResultSet.toMusicLibrary() = MusicLibraryView(
        id = getString("id"),
        name = getString("name"),
        path = getString("path"),
        deviceType = getString("device_type"),
        readOnly = getBoolean("read_only"),
        enabled = getBoolean("enabled"),
        downloadTarget = getBoolean("download_target"),
        status = getString("status"),
        trackCount = getInt("track_count"),
        lastScanAt = getLong("last_scan_at").takeUnless { wasNull() },
        lastError = getString("last_error"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )

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
        statement.setLong(10, file.scannedAt)
        statement.setString(11, scanId)
        statement.setString(12, file.libraryId)
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
        scannedAt = getLong("scanned_at"),
        artworkUrl = getString("artwork_url") ?: "/api/media/${getString("id")}/artwork",
        favorite = getBoolean("favorite"),
        libraryId = getString("library_id"),
        analysis = TrackAnalysis(
            status = getString("analysis_status") ?: "pending",
            progress = getFloat("analysis_progress"),
            message = getString("analysis_message"),
            bpm = nullableFloat("bpm"),
            keyName = getString("key_name"),
            camelot = getString("camelot_code") ?: camelotLabel(getString("key_name")),
            valence = nullableFloat("valence"),
            energy = nullableFloat("energy"),
            danceability = nullableFloat("danceability"),
            acousticness = nullableFloat("acousticness"),
            instrumentalness = nullableFloat("instrumentalness"),
            liveness = nullableFloat("liveness"),
            speechiness = nullableFloat("speechiness"),
            analyzedAt = getLong("analyzed_at").takeUnless { wasNull() },
        ),
    )

    private fun ResultSet.toFeatureVector() = TrackFeatureVector(
        trackId = getString("id"),
        bpm = nullableFloat("bpm"),
        keyName = getString("key_name"),
        valence = nullableFloat("valence"),
        energy = nullableFloat("energy"),
        danceability = nullableFloat("danceability"),
        acousticness = nullableFloat("acousticness"),
        instrumentalness = nullableFloat("instrumentalness"),
        liveness = nullableFloat("liveness"),
        speechiness = nullableFloat("speechiness"),
    )

    private fun ResultSet.nullableFloat(column: String): Float? = getFloat(column).let { value ->
        if (wasNull()) null else value
    }

    companion object {
        internal const val ONLINE_LIBRARY_ID = "__online__"
        val SUPPORTED_LIBRARY_SORTS = setOf("title", "artist", "album", "modified", "scanned", "bpm", "energy", "key", "analysis")
        val SUPPORTED_SORT_DIRECTIONS = setOf("asc", "desc")
        private const val MAX_ANALYSIS_CLAIM_BATCH_SIZE = 256
        private const val LIBRARY_SELECT =
            "SELECT l.*, (SELECT COUNT(*) FROM tracks t WHERE t.library_id=l.id AND t.deleted_at IS NULL) track_count FROM music_libraries l"
        private val UPSERT_TRACK_SQL =
            """
            INSERT INTO tracks(id,title,artist,album,duration_ms,mime_type,file_path,file_size,modified_at,scanned_at,last_seen_scan,library_id,deleted_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL)
            ON CONFLICT(id) DO UPDATE SET title=excluded.title, artist=excluded.artist, album=excluded.album,
              duration_ms=excluded.duration_ms, mime_type=excluded.mime_type, file_path=excluded.file_path,
              file_size=excluded.file_size, modified_at=excluded.modified_at, scanned_at=excluded.scanned_at, last_seen_scan=excluded.last_seen_scan,
              library_id=excluded.library_id, deleted_at=NULL,
              analysis_status='pending', analysis_progress=0, analysis_message=NULL,
              bpm=NULL, key_name=NULL, camelot_code=NULL, valence=NULL, energy=NULL, danceability=NULL,
              acousticness=NULL, instrumentalness=NULL, liveness=NULL, speechiness=NULL, analyzed_at=NULL
            """.trimIndent()
    }
}

data class TrackFingerprint(val size: Long, val modifiedAt: Long)

data class AnalysisSource(val path: Path, val size: Long, val modifiedAt: Long)

data class StoredTrackLocation(val path: Path, val libraryId: String)

data class QueuedDownload(val job: DownloadJob, val request: DownloadRequest)

data class StoredRoom(val id: String, val name: String, val stateJson: String, val version: Long, val updatedAt: Long)

class PlaylistVersionConflict : RuntimeException("playlist_version_conflict")

enum class ScanChange { DISCOVERED, UPDATED, UNCHANGED }
