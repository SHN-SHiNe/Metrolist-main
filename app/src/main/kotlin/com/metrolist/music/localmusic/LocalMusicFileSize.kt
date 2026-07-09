package com.metrolist.music.localmusic

import com.metrolist.music.db.entities.LocalMusicEntity
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object LocalMusicFileSize {
    fun totalBytes(files: List<LocalMusicEntity>): Long {
        val candidates =
            files
                .filter { it.missingSince == null }
                .filter { !it.documentId.contains("/.") }
                .filter { (it.fileSize ?: 0L) > 0L }
        val contentOnlyFallbackKeys =
            candidates
                .filter { normalizedDocumentId(it.documentId) == null && normalizedDocumentId(it.contentUri) == null }
                .mapNotNull { fallbackKey(it) }
                .toSet()

        return candidates
            .asSequence()
            .mapNotNull { file ->
                val size = file.fileSize?.takeIf { it > 0L } ?: return@mapNotNull null
                dedupeKey(file, contentOnlyFallbackKeys)?.let { key -> key to size }
            }
            .groupBy({ it.first }, { it.second })
            .values
            .sumOf { sizes -> sizes.maxOrNull() ?: 0L }
    }

    internal fun dedupeKey(
        file: LocalMusicEntity,
        contentOnlyFallbackKeys: Set<String> = emptySet(),
    ): String? {
        val fallbackKey = fallbackKey(file)
        val normalizedDocumentId = normalizedDocumentId(file.documentId)
        if (fallbackKey != null && fallbackKey in contentOnlyFallbackKeys) return fallbackKey
        if (normalizedDocumentId != null) return normalizedDocumentId

        val normalizedContentDocumentId = normalizedDocumentId(file.contentUri)
        if (normalizedContentDocumentId != null) return normalizedContentDocumentId

        return fallbackKey
    }

    private fun normalizedDocumentId(value: String): String? {
        val raw =
            when {
                "/document/" in value -> value.substringAfterLast("/document/")
                value.startsWith("content://") -> return null
                else -> value
            }.trim()
        return raw
            .takeIf { it.isNotBlank() }
            ?.urlDecode()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun fallbackKey(file: LocalMusicEntity): String? {
        val size = file.fileSize?.takeIf { it > 0L } ?: return null
        val name = file.displayName.trim().takeIf { it.isNotBlank() } ?: return null
        return "$name|$size"
    }

    private fun String.urlDecode(): String =
        runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrDefault(this)
}
