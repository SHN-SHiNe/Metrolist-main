package com.metrolist.music.localmusic

object LocalMusicScanFilter {
    fun shouldSkip(displayName: String, documentId: String): Boolean {
        val name = displayName.trim()
        if (name.isHiddenOrIgnoredSegment()) return true

        return documentId
            .replace('\\', '/')
            .substringAfter(':', documentId)
            .split('/')
            .any { it.trim().isHiddenOrIgnoredSegment() }
    }

    fun isSupportedPlayableAudio(displayName: String, mimeType: String?): Boolean {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension in unsupportedExtensions) return false
        if (extension in supportedExtensions) return true

        val normalizedMimeType = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        return normalizedMimeType in supportedMimeTypes
    }

    private fun String.isHiddenOrIgnoredSegment(): Boolean {
        if (isEmpty()) return false
        if (startsWith(".")) return true
        return lowercase() in ignoredDirectoryNames
    }

    private val ignoredDirectoryNames =
        setOf(
            "@eadir",
            "#recycle",
            "\$recycle.bin",
            "system volume information",
        )

    private val supportedExtensions =
        setOf(
            "mp3",
            "m4a",
            "mp4",
            "flac",
            "wav",
            "ogg",
            "opus",
            "aac",
            "amr",
            "ac3",
            "eac3",
            "ac4",
        )

    private val unsupportedExtensions =
        setOf(
            "aif",
            "aiff",
            "aifc",
            "wma",
        )

    private val supportedMimeTypes =
        setOf(
            "audio/mpeg",
            "audio/mp3",
            "audio/mp4",
            "audio/m4a",
            "audio/x-m4a",
            "video/mp4",
            "audio/flac",
            "audio/x-flac",
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/vnd.wave",
            "audio/ogg",
            "application/ogg",
            "audio/opus",
            "audio/aac",
            "audio/aacp",
            "audio/3gpp",
            "audio/3gpp2",
            "audio/amr",
            "audio/amr-wb",
            "audio/ac3",
            "audio/eac3",
            "audio/ac4",
        )
}
