package com.shine.music.server

import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

fun Application.shineModule(
    config: AppConfig = AppConfig.fromEnvironment(),
    clock: () -> Long = System::currentTimeMillis,
    sendspinBridge: SendspinBridge = config.sendspinBridgeUrl?.let(::HttpSendspinBridge) ?: InMemorySendspinBridge(),
) {
    config.dataDir.createDirectories()
    config.musicDir.createDirectories()
    config.libraryDir.createDirectories()
    config.cacheDir.createDirectories()
    config.trashDir.createDirectories()

    val store = openStoreWithRecovery(config, clock())
    val libraries = MusicLibraryManager(config, store, clock)
    val catalog = OnlineCatalog(store)
    val analyses = AnalysisManager(store, clock = clock)
    val downloads = DownloadManager(store, libraries, catalog, clock) {
        if (config.analysisOnScan) analyses.enqueueMissing()
    }
    val rooms = RoomHub(store, catalog, sendspinBridge, clock)
    val maintenance = Maintenance(config, store, clock)
    val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    install(CallLogging)
    install(ContentNegotiation) { json(jsonCodec) }
    install(PartialContent)
    install(WebSockets)
    install(StatusPages) {
        exception<PlaylistVersionConflict> { call, _ ->
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "playlist_version_conflict"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad_request")))
        }
        exception<NoSuchElementException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (cause.message ?: "not_found")))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.environment.log.error("Unhandled request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error"))
        }
    }
    monitor.subscribe(ApplicationStopped) {
        downloads.close()
        analyses.close()
        store.close()
    }

    if (config.scanOnStart) {
        launch(Dispatchers.IO) {
            runCatching { libraries.scanAll() }
                .onSuccess { if (config.analysisOnScan) analyses.enqueueMissing() }
                .onFailure { environment.log.error("Startup library scan failed", it) }
        }
    } else if (config.analysisOnScan) {
        launch(Dispatchers.IO) { analyses.enqueueMissing() }
    }
    launch(Dispatchers.IO) {
        while (isActive) {
            val restored = runCatching { rooms.restore() }
            if (restored.isSuccess) break
            environment.log.error("Sendspin room restore failed; retrying", restored.exceptionOrNull())
            delay(2_000)
        }
    }
    launch(Dispatchers.IO) {
        while (isActive) {
            runCatching { maintenance.runOnce() }.onFailure { environment.log.error("Maintenance failed", it) }
            delay(24 * 60 * 60 * 1000L)
        }
    }

    routing {
        get("/api/health") {
            call.respond(HealthResponse("ok", BuildInfo.VERSION, clock()))
        }

        get("/api/library") {
            val query = call.request.queryParameters["q"]
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val sort = call.request.queryParameters["sort"]?.takeIf { it in setOf("title", "artist", "album") } ?: "artist"
            val libraryId = call.request.queryParameters["libraryId"]?.takeIf(String::isNotBlank)
            call.respond(store.listTracks(query, offset, limit, sort, libraryId))
        }
        get("/api/tracks") {
            val ids = call.request.queryParameters["ids"].orEmpty().split(',').map(String::trim).filter(String::isNotBlank).distinct()
            require(ids.isNotEmpty() && ids.size <= 100) { "track_ids_required" }
            call.respond(store.tracks(ids))
        }
        get("/api/library/{trackId}/similar") {
            val id = call.parameters["trackId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 30
            val recent = call.request.queryParameters.getAll("recent").orEmpty()
                .flatMap { value -> value.split(',') }
                .map(String::trim).filter(String::isNotBlank).take(12).toSet()
            call.respond(store.similarTracks(id, recent, limit) ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        post("/api/library/advanced-search") {
            val request = call.receive<AdvancedSearchRequest>()
            call.respond(withContext(Dispatchers.IO) { store.advancedSearch(request) })
        }
        get("/api/analysis") {
            call.respond(analyses.summary())
        }
        post("/api/analysis") {
            if (!analyses.available) {
                return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to (analyses.unavailableReason ?: "analysis_unavailable")),
                )
            }
            val request = call.receive<AnalysisEnqueueRequest>()
            val trackIds = request.trackIds.map(String::trim).filter(String::isNotBlank).distinct()
            require(trackIds.size <= 500) { "too_many_track_ids" }
            require(request.missingOnly || trackIds.isNotEmpty()) { "track_ids_required_for_force_analysis" }
            if (trackIds.isNotEmpty() && store.tracks(trackIds).size != trackIds.size) {
                throw NoSuchElementException("track_not_in_library")
            }
            val queued = if (trackIds.isEmpty()) {
                analyses.enqueueMissing()
            } else {
                analyses.enqueue(trackIds, force = !request.missingOnly)
            }
            call.respond(HttpStatusCode.Accepted, AnalysisEnqueueResponse(queued, draining = trackIds.isEmpty()))
        }
        post("/api/radio/next") {
            val request = call.receive<RadioNextRequest>()
            val recent = request.recentTrackIds.take(12)
            var next = store.similarTracks(request.currentTrackId, recent.toSet(), 1)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            if (next.items.isEmpty() && recent.size > 1) {
                next = store.similarTracks(request.currentTrackId, setOf(recent[1]), 1)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
            }
            val item = next.items.firstOrNull() ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "no_similar_track"))
            call.respond(item)
        }
        get("/api/scans") { call.respond(store.scans()) }
        post("/api/scans") {
            val result = withContext(Dispatchers.IO) { libraries.scanAll() }
            if (config.analysisOnScan) analyses.enqueueMissing()
            call.respond(result)
        }
        get("/api/libraries") { call.respond(libraries.list()) }
        post("/api/libraries") {
            call.respond(HttpStatusCode.Created, libraries.create(call.receive()))
        }
        put("/api/libraries/{libraryId}") {
            val id = call.parameters["libraryId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            call.respond(libraries.update(id, call.receive()))
        }
        post("/api/libraries/{libraryId}/scan") {
            val id = call.parameters["libraryId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val allowEmpty = call.request.queryParameters["allowEmpty"]?.toBooleanStrictOrNull() ?: false
            val result = withContext(Dispatchers.IO) { libraries.scan(id, allowEmpty) }
            if (config.analysisOnScan) analyses.enqueueMissing()
            call.respond(result)
        }
        delete("/api/library/{trackId}") {
            val id = call.parameters["trackId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val moved = withContext(Dispatchers.IO) { libraries.moveTrackToTrash(id) }
            if (!moved) return@delete call.respond(HttpStatusCode.NotFound)
            call.respond(MessageResponse("moved_to_trash"))
        }

        get("/api/media/{trackId}/stream") {
            val id = call.parameters["trackId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val file = store.trackFile(id)?.toFile()
            if (file?.isFile == true) {
                call.response.header(HttpHeaders.CacheControl, CacheControl.MaxAge(maxAgeSeconds = 3600).toString())
                return@get call.respondFile(file)
            }
            val url = catalog.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondRedirect(url)
        }
        get("/api/media/{trackId}/artwork") {
            val id = call.parameters["trackId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val cover = listOf("jpg", "png").map { config.cacheDir.resolve("covers/$id.$it") }.firstOrNull(Files::isRegularFile)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, CacheControl.MaxAge(maxAgeSeconds = 604800).toString())
            call.respondFile(cover.toFile())
        }

        get("/api/search") {
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            require(query.isNotBlank()) { "query_required" }
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 30
            val source = call.request.queryParameters["source"] ?: "all"
            call.respond(catalog.search(query, page, limit, source))
        }

        get("/api/favorites") { call.respond(store.favorites()) }
        put("/api/favorites/{trackId}") {
            val id = call.parameters["trackId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            store.setFavorite(id, true)
            call.respond(MessageResponse("favorite_added"))
        }
        delete("/api/favorites/{trackId}") {
            val id = call.parameters["trackId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            store.setFavorite(id, false)
            call.respond(MessageResponse("favorite_removed"))
        }

        get("/api/playlists") { call.respond(store.playlists()) }
        post("/api/playlists") {
            val request = call.receive<CreatePlaylistRequest>()
            require(request.name.isNotBlank()) { "playlist_name_required" }
            call.respond(HttpStatusCode.Created, store.createPlaylist(request.name.trim(), clock()))
        }
        get("/api/playlists/{playlistId}") {
            val id = call.parameters["playlistId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val playlist = store.playlist(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(playlist)
        }
        put("/api/playlists/{playlistId}") {
            val id = call.parameters["playlistId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val updated = store.updatePlaylist(id, call.receive(), clock()) ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(updated)
        }
        delete("/api/playlists/{playlistId}") {
            val id = call.parameters["playlistId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (store.deletePlaylist(id)) call.respond(MessageResponse("playlist_deleted")) else call.respond(HttpStatusCode.NotFound)
        }

        get("/api/history") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            call.respond(store.history(limit))
        }
        post("/api/history") {
            val request = call.receive<HistoryRequest>()
            store.recordHistory(request.trackId, request.playedAt ?: clock())
            call.respond(HttpStatusCode.Created, MessageResponse("history_recorded"))
        }

        get("/api/settings/sources") { call.respond(store.sources()) }
        post("/api/settings/sources") {
            val request = call.receive<SourceConfigRequest>()
            require(request.apiUrl.startsWith("http://") || request.apiUrl.startsWith("https://")) { "invalid_source_url" }
            call.respond(HttpStatusCode.Created, store.saveSource(null, request, clock()))
        }
        put("/api/settings/sources/{sourceId}") {
            val id = call.parameters["sourceId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            call.respond(store.saveSource(id, call.receive(), clock()))
        }

        get("/api/downloads") { call.respond(store.downloads()) }
        post("/api/downloads") { call.respond(HttpStatusCode.Accepted, downloads.enqueue(call.receive())) }
        post("/api/downloads/{downloadId}/retry") {
            val id = call.parameters["downloadId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.respond(HttpStatusCode.Accepted, downloads.retry(id))
        }

        get("/api/rooms") { call.respond(rooms.list()) }
        post("/api/rooms") {
            val request = call.receive<CreateRoomRequest>()
            require(request.name.isNotBlank()) { "room_name_required" }
            request.id?.let { require(runCatching { java.util.UUID.fromString(it) }.isSuccess) { "invalid_room_id" } }
            call.respond(HttpStatusCode.Created, rooms.create(request.name, request.id))
        }
        post("/internal/sendspin/events") {
            if (config.sendspinInternalToken == null || call.request.headers["X-SHiNe-Internal-Token"] != config.sendspinInternalToken) {
                return@post call.respond(HttpStatusCode.NotFound)
            }
            rooms.reconcile(call.receive())
            call.respond(MessageResponse("room_reconciled"))
        }
        get("/api/rooms/{roomId}") {
            val id = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(rooms.detail(id) ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        put("/api/rooms/{roomId}/state") {
            val id = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            call.respond(rooms.update(id, call.receive()) ?: return@put call.respond(HttpStatusCode.NotFound))
        }
        delete("/api/rooms/{roomId}") {
            val id = call.parameters["roomId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (!rooms.delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.NoContent)
        }
        webSocket("/api/rooms/{roomId}/sendspin") {
            val id = call.parameters["roomId"] ?: return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "room_required"))
            val upstreamUrl = rooms.websocketUrl(id)
                ?: return@webSocket close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "sendspin_unavailable"))
            val proxy = HttpClient(CIO) { install(ClientWebSockets) }
            val upstream = runCatching { proxy.webSocketSession { url(upstreamUrl) } }.getOrElse {
                proxy.close()
                return@webSocket close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "sendspin_unavailable"))
            }
            try {
                coroutineScope {
                    val toUpstream = launch {
                        incoming.consumeEach { frame ->
                            when (frame) {
                                is Frame.Text -> upstream.send(Frame.Text(frame.readText()))
                                is Frame.Binary -> upstream.send(Frame.Binary(frame.fin, frame.data))
                                else -> Unit
                            }
                        }
                    }
                    val toBrowser = launch {
                        upstream.incoming.consumeEach { frame ->
                            when (frame) {
                                is Frame.Text -> send(Frame.Text(frame.readText()))
                                is Frame.Binary -> send(Frame.Binary(frame.fin, frame.data))
                                else -> Unit
                            }
                        }
                    }
                    toUpstream.invokeOnCompletion { toBrowser.cancel() }
                    toBrowser.invokeOnCompletion { toUpstream.cancel() }
                    joinAll(toUpstream, toBrowser)
                }
            } finally {
                upstream.close()
                proxy.close()
            }
        }

        staticResources("/", "web", index = "index.html") {
            modify { resource, call ->
                val immutable = resource.path.contains("/assets/")
                call.response.header(
                    HttpHeaders.CacheControl,
                    if (immutable) "public, max-age=31536000, immutable" else "no-cache, must-revalidate",
                )
            }
        }
    }
}

internal object BuildInfo {
    const val VERSION = "0.1.0"
}

private fun openStoreWithRecovery(config: AppConfig, now: Long): MusicStore = try {
    MusicStore(config.databasePath)
} catch (initial: Throwable) {
    val backup = if (Files.isDirectory(config.backupDir)) Files.list(config.backupDir).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".db") }
            .max(Comparator.comparingLong<Path> { Files.getLastModifiedTime(it).toMillis() })
            .orElse(null)
    } else null
    if (backup == null) throw initial
    val damaged = config.dataDir.resolve("shine-music-corrupt-$now.db")
    if (Files.exists(config.databasePath)) Files.move(config.databasePath, damaged, StandardCopyOption.REPLACE_EXISTING)
    Files.deleteIfExists(Path.of("${config.databasePath}-wal"))
    Files.deleteIfExists(Path.of("${config.databasePath}-shm"))
    Files.copy(backup, config.databasePath, StandardCopyOption.REPLACE_EXISTING)
    MusicStore(config.databasePath)
}
