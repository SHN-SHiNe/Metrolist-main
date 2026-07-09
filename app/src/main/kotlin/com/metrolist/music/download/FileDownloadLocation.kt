package com.metrolist.music.download

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class FileDownloadLocation(
    val directoryUri: String,
    val displayPath: String,
    val isDefault: Boolean,
) {
    companion object {
        const val DEFAULT_DISPLAY_PATH = "Music/SHiNe MUSIC"

        fun fromDirectoryUri(directoryUri: String): FileDownloadLocation {
            if (directoryUri.isBlank()) {
                return FileDownloadLocation(
                    directoryUri = "",
                    displayPath = DEFAULT_DISPLAY_PATH,
                    isDefault = true,
                )
            }

            val decodedTreePath =
                directoryUri
                    .substringAfter("/tree/", directoryUri)
                    .substringBefore("?")
                    .urlDecode()
                    .trim('/')
                    .ifBlank { directoryUri.urlDecode() }

            val displayPath =
                if (decodedTreePath.startsWith("primary:")) {
                    "内部存储/" + decodedTreePath.removePrefix("primary:").trim('/')
                } else {
                    decodedTreePath
                }

            return FileDownloadLocation(
                directoryUri = directoryUri,
                displayPath = displayPath,
                isDefault = false,
            )
        }
    }
}

private fun String.urlDecode(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())
