package com.metrolist.music.utils

object ArtistNameSplitter {
    private val delimiterRegex = Regex("""\s*(?:[;,/、，；])\s*""")
    private val whitespaceRegex = Regex("""\s+""")

    fun split(name: String?): List<String> =
        name
            ?.split(delimiterRegex)
            ?.map { it.replace(whitespaceRegex, " ").trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinctBy { it.lowercase() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(UNKNOWN_ARTIST)

    fun isComposite(name: String): Boolean = split(name).size > 1

    const val UNKNOWN_ARTIST = "Unknown Artist"
}
