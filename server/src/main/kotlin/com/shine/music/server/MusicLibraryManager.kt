package com.shine.music.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MusicLibraryManager(
    private val config: AppConfig,
    private val store: MusicStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val operationLock = ReentrantLock()

    init {
        store.ensureDefaultLibrary(config.musicDir, clock())
    }

    fun list(): List<MusicLibraryView> = store.libraries()

    fun create(request: MusicLibraryRequest): MusicLibraryView = operationLock.withLock {
        val normalized = validatePath(request.path)
        val resolved = resolveWithinAllowedRoot(normalized)
        require(request.name.isNotBlank()) { "library_name_required" }
        require(request.deviceType in DEVICE_TYPES) { "invalid_library_device_type" }
        require(store.libraries().none {
            val existing = Path.of(it.path).toAbsolutePath().normalize()
            val overlapsLexically = normalized.startsWith(existing) || existing.startsWith(normalized)
            val overlapsPhysically = if (it.id == DEFAULT_LIBRARY_ID) false else {
                val existingResolved = resolveWithinAllowedRoot(existing)
                resolved.startsWith(existingResolved) || existingResolved.startsWith(resolved)
            }
            overlapsLexically || overlapsPhysically
        }) {
            "library_path_overlaps_existing_library"
        }
        val normalizedRequest = request.copy(name = request.name.trim(), path = normalized.toString())
        val status = when {
            !request.enabled -> "disabled"
            isAvailable(normalized) -> "online"
            else -> "offline"
        }
        validateDownloadTarget(request, normalized, status)
        store.createLibrary(normalizedRequest, status, clock())
    }

    fun update(id: String, request: MusicLibraryRequest): MusicLibraryView = operationLock.withLock {
        val existing = store.library(id) ?: throw NoSuchElementException("library_not_found")
        val normalized = Path.of(request.path).toAbsolutePath().normalize()
        require(normalized.toString() == existing.path) { "library_path_immutable" }
        require(request.name.isNotBlank()) { "library_name_required" }
        require(request.deviceType in DEVICE_TYPES) { "invalid_library_device_type" }
        val normalizedRequest = request.copy(name = request.name.trim(), path = normalized.toString())
        val status = when {
            !request.enabled -> "disabled"
            isAvailable(normalized) -> "online"
            else -> "offline"
        }
        validateDownloadTarget(request, normalized, status)
        requireNotNull(store.updateLibrary(id, normalizedRequest, status, clock()))
    }

    fun downloadTarget(): MusicLibraryView {
        val target = store.libraries().singleOrNull(MusicLibraryView::downloadTarget)
            ?: throw IllegalStateException("download_library_required")
        require(target.enabled) { "download_library_disabled" }
        require(!target.readOnly) { "download_library_must_be_writable" }
        require(isAvailable(Path.of(target.path)) && Files.isWritable(Path.of(target.path))) { "download_library_offline" }
        return target
    }

    fun indexDownloaded(libraryId: String, path: Path) = operationLock.withLock {
        val library = store.library(libraryId) ?: throw NoSuchElementException("library_not_found")
        LibraryScanner(
            root = Path.of(library.path),
            store = store,
            cacheRoot = config.cacheDir,
            clock = clock,
            libraryId = library.id,
        ).index(path)
    }

    fun scanAll(): ScanResult = operationLock.withLock {
        val startedAt = clock()
        val results = store.libraries().filter(MusicLibraryView::enabled).map(::scanUnlocked)
        ScanResult(
            id = UUID.randomUUID().toString(),
            status = if (results.any { it.status != "completed" }) "completed_with_offline" else "completed",
            discovered = results.sumOf(ScanResult::discovered),
            updated = results.sumOf(ScanResult::updated),
            removed = results.sumOf(ScanResult::removed),
            startedAt = startedAt,
            completedAt = clock(),
        )
    }

    fun scan(id: String, allowEmpty: Boolean = false): ScanResult = operationLock.withLock {
        val library = store.library(id) ?: throw NoSuchElementException("library_not_found")
        scanUnlocked(library, allowEmpty)
    }

    fun moveTrackToTrash(trackId: String): Boolean = operationLock.withLock {
        val location = store.trackLocation(trackId) ?: return false
        val library = store.library(location.libraryId) ?: throw NoSuchElementException("library_not_found")
        require(!library.readOnly) { "library_read_only" }
        require(library.enabled) { "library_disabled" }
        val root = operationalRoot(library)
        require(isAvailable(root)) { "library_offline" }
        LibraryScanner(
            root = root,
            store = store,
            cacheRoot = config.cacheDir,
            clock = clock,
            libraryId = library.id,
        ).moveToTrash(trackId, config.trashDir, clock())
    }

    private fun scanUnlocked(library: MusicLibraryView, allowEmpty: Boolean = false): ScanResult {
        val startedAt = clock()
        if (!library.enabled) return ScanResult(
            UUID.randomUUID().toString(), "disabled", 0, 0, 0, startedAt, clock(), library.id,
        )
        val root = runCatching { operationalRoot(library) }.getOrElse { error ->
            store.setLibraryStatus(library.id, "offline", library.lastScanAt, error.message ?: "invalid_library_path", clock())
            return ScanResult(UUID.randomUUID().toString(), "offline", 0, 0, 0, startedAt, clock(), library.id)
        }
        if (!isAvailable(root)) {
            store.setLibraryStatus(library.id, "offline", library.lastScanAt, "path_unavailable", clock())
            return ScanResult(UUID.randomUUID().toString(), "offline", 0, 0, 0, startedAt, clock(), library.id)
        }
        return runCatching {
            LibraryScanner(
                root = root,
                store = store,
                cacheRoot = config.cacheDir,
                clock = clock,
                libraryId = library.id,
            ).scan(allowEmpty)
        }.onSuccess { result ->
            if (result.status == "completed") {
                store.setLibraryStatus(library.id, "online", result.completedAt, null, clock())
            } else {
                store.setLibraryStatus(library.id, "offline", library.lastScanAt, "empty_mount", clock())
            }
        }.getOrElse { error ->
            store.setLibraryStatus(library.id, "offline", library.lastScanAt, error.message ?: "scan_failed", clock())
            ScanResult(UUID.randomUUID().toString(), "offline", 0, 0, 0, startedAt, clock(), library.id)
        }
    }

    private fun validatePath(value: String): Path {
        require(value.isNotBlank()) { "library_path_required" }
        val path = Path.of(value).toAbsolutePath().normalize()
        val allowedRoot = config.libraryDir.toAbsolutePath().normalize()
        require(path.startsWith(allowedRoot) && path != allowedRoot) { "library_path_outside_allowed_root" }
        resolveWithinAllowedRoot(path)
        return path
    }

    private fun resolveWithinAllowedRoot(path: Path): Path {
        val allowedRoot = config.libraryDir.toAbsolutePath().normalize()
        val existing = generateSequence(path) { it.parent }.firstOrNull(Files::exists)
            ?: throw IllegalArgumentException("library_path_outside_allowed_root")
        val realAllowedRoot = allowedRoot.toRealPath()
        val resolved = existing.toRealPath().resolve(existing.relativize(path)).normalize()
        require(resolved.startsWith(realAllowedRoot) && resolved != realAllowedRoot) {
            "library_path_outside_allowed_root"
        }
        return resolved
    }

    private fun operationalRoot(library: MusicLibraryView): Path = if (library.id == DEFAULT_LIBRARY_ID) {
        Path.of(library.path).toAbsolutePath().normalize()
    } else validatePath(library.path)

    private fun isAvailable(path: Path): Boolean = Files.isDirectory(path) && Files.isReadable(path)

    private fun validateDownloadTarget(request: MusicLibraryRequest, path: Path, status: String) {
        if (!request.downloadTarget) return
        require(!request.readOnly && request.enabled) { "download_library_must_be_enabled_and_writable" }
        require(status == "online" && Files.isWritable(path)) { "download_library_must_be_online_and_writable" }
    }

    companion object {
        private val DEVICE_TYPES = setOf("local", "usb", "network", "cloud")
    }
}
