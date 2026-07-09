package com.metrolist.music.localmusic

data class LocalMusicScanSource(
    val uri: String,
    val kind: LocalMusicScanSourceKind,
)

enum class LocalMusicScanSourceKind {
    USER,
    DOWNLOAD,
}

object LocalMusicScanSources {
    fun effectiveSources(
        legacyFolderUri: String?,
        userFolderUris: String?,
        fileDownloadDirectoryUri: String?,
    ): List<LocalMusicScanSource> {
        val sources = linkedMapOf<String, LocalMusicScanSource>()

        fun add(uri: String?, kind: LocalMusicScanSourceKind) {
            val normalized = uri?.trim().orEmpty()
            if (normalized.isBlank()) return
            sources.putIfAbsent(normalized, LocalMusicScanSource(normalized, kind))
        }

        add(legacyFolderUri, LocalMusicScanSourceKind.USER)
        parseUserFolders(userFolderUris).forEach { add(it, LocalMusicScanSourceKind.USER) }
        add(fileDownloadDirectoryUri, LocalMusicScanSourceKind.DOWNLOAD)

        return sources.values.toList()
    }

    fun addUserFolder(
        userFolderUris: String?,
        uri: String,
    ): String = serializeUserFolders(parseUserFolders(userFolderUris) + uri)

    fun removeUserFolder(
        userFolderUris: String?,
        uri: String,
    ): String = serializeUserFolders(parseUserFolders(userFolderUris).filterNot { it == uri })

    fun parseUserFolders(userFolderUris: String?): List<String> =
        userFolderUris
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            .orEmpty()

    fun serializeUserFolders(uris: List<String>): String =
        uris
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
}
