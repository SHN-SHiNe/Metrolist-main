package com.metrolist.chinamusic

import com.metrolist.chinamusic.model.MusicSource

data class ParsedPlaylistUrl(
    val source: MusicSource,
    val id: String,
)

object PlaylistUrlParser {

    /**
     * Parse a music platform playlist URL and extract source + playlist ID.
     * Supports: Netease, QQ Music, Kugou, Kuwo, Migu
     *
     * @return ParsedPlaylistUrl if successfully parsed, null otherwise
     */
    fun parse(url: String): ParsedPlaylistUrl? {
        val trimmed = url.trim()

        // Detect platform by domain first, then extract ID
        when {
            trimmed.contains("163.com") -> {
                // Netease (网易云)
                // Examples:
                //   https://music.163.com/playlist?id=73513493
                //   https://music.163.com/#/playlist?id=73513493
                //   https://music.163.com/m/playlist?id=73513493&creatorId=69276211
                //   https://y.music.163.com/m/playlist?id=73513493
                // LX Music approach: generic id= param extraction
                neteaseIdParam.find(trimmed)?.groupValues?.get(1)?.let {
                    return ParsedPlaylistUrl(MusicSource.NETEASE, it)
                }
                neteasePathId.find(trimmed)?.groupValues?.get(1)?.let {
                    return ParsedPlaylistUrl(MusicSource.NETEASE, it)
                }
            }

            trimmed.contains("y.qq.com") || trimmed.contains("qq.com/n/ryqq") -> {
                // QQ Music
                // Examples:
                //   https://y.qq.com/n/ryqq/playlist/7217720898.html
                //   https://i.y.qq.com/n2/m/share/details/taoge.html?id=7217720898
                //   https://y.qq.com/n/yqq/playsquare/7013848675.html
                qqPlaylistPath.find(trimmed)?.groupValues?.get(1)?.let {
                    return ParsedPlaylistUrl(MusicSource.QQ, it)
                }
                qqIdParam.find(trimmed)?.groupValues?.get(1)?.let {
                    return ParsedPlaylistUrl(MusicSource.QQ, it)
                }
            }

            trimmed.contains("kugou.com") -> {
                // Kugou (酷狗)
                // Examples:
                //   https://www.kugou.com/yy/special/single/1067062.html
                //   https://www.kugou.com/songlist/xxx/?uid=xxx
                kugouHtmlId.find(trimmed)?.groupValues?.get(1)?.let {
                    return ParsedPlaylistUrl(MusicSource.KUGOU, it)
                }
                kugouSonglistId.find(trimmed)?.groupValues?.get(1)?.let {
                    return ParsedPlaylistUrl(MusicSource.KUGOU, it)
                }
            }

            trimmed.contains("kuwo.cn") -> {
                // Kuwo (酷我)
                // Examples:
                //   http://www.kuwo.cn/playlist_detail/2886046289
                //   https://m.kuwo.cn/h5app/playlist/2736267853?t=qqfriend
                kuwoPlaylistPath.find(trimmed)?.groupValues?.get(1)?.let {
                    return ParsedPlaylistUrl(MusicSource.KUWO, it)
                }
            }

            trimmed.contains("migu.cn") -> {
                // Migu (咪咕)
                // Examples:
                //   https://music.migu.cn/v3/music/playlist/161044573?page=1
                //   playlist/index.html?id=xxx
                miguPlaylistPath.find(trimmed)?.groupValues?.get(1)?.let {
                    return ParsedPlaylistUrl(MusicSource.MIGU, it)
                }
                miguIdParam.find(trimmed)?.groupValues?.get(1)?.let {
                    return ParsedPlaylistUrl(MusicSource.MIGU, it)
                }
            }
        }

        return null
    }

    /**
     * Try to parse plain playlist ID with a specified source.
     * If it looks like a numeric ID, just return it directly.
     */
    fun parseWithSource(input: String, source: MusicSource): ParsedPlaylistUrl? {
        val trimmed = input.trim()
        parse(trimmed)?.let { return it }
        if (trimmed.matches(Regex("^\\d+$"))) {
            return ParsedPlaylistUrl(source, trimmed)
        }
        return null
    }

    /**
     * Generate the original platform URL for a playlist.
     */
    fun getPlaylistUrl(source: MusicSource, id: String): String = when (source) {
        MusicSource.NETEASE -> "https://music.163.com/playlist?id=$id"
        MusicSource.QQ -> "https://y.qq.com/n/ryqq/playlist/$id"
        MusicSource.KUGOU -> "https://www.kugou.com/yy/special/single/$id.html"
        MusicSource.KUWO -> "https://www.kuwo.cn/playlist_detail/$id"
        MusicSource.MIGU -> "https://music.migu.cn/v3/music/playlist/$id"
        MusicSource.ALL -> ""
    }

    /**
     * Generate the original platform URL for a song.
     */
    fun getSongUrl(source: MusicSource, songId: String): String = when (source) {
        MusicSource.NETEASE -> "https://music.163.com/song?id=$songId"
        MusicSource.QQ -> "https://y.qq.com/n/ryqq/songDetail/$songId"
        MusicSource.KUGOU -> "https://www.kugou.com/song/#hash=$songId"
        MusicSource.KUWO -> "https://www.kuwo.cn/play_detail/$songId"
        MusicSource.MIGU -> "https://music.migu.cn/v3/music/song/$songId"
        MusicSource.ALL -> ""
    }

    /**
     * Generate original playlist URL from a browseId like "china_wy_12345".
     */
    fun getPlaylistUrlFromBrowseId(browseId: String): String? {
        if (!browseId.startsWith("china_")) return null
        val parts = browseId.removePrefix("china_").split("_", limit = 2)
        if (parts.size < 2) return null
        val source = MusicSource.fromId(parts[0]) ?: return null
        return getPlaylistUrl(source, parts[1])
    }

    /**
     * Generate original song URL from a media ID like "china_wy_12345".
     */
    fun getSongUrlFromMediaId(mediaId: String): String? {
        if (!mediaId.startsWith("china_")) return null
        val parts = mediaId.removePrefix("china_").split("_", limit = 2)
        if (parts.size < 2) return null
        val source = MusicSource.fromId(parts[0]) ?: return null
        return getSongUrl(source, parts[1])
    }

    // Netease: match ?id=xxx or &id=xxx anywhere in URL
    private val neteaseIdParam = Regex("""[?&]id=(\d+)""")
    // Netease: match /playlist/xxx/xxx/... path style
    private val neteasePathId = Regex("""/playlist/(\d+)""")

    // QQ: match /playlist/xxx path
    private val qqPlaylistPath = Regex("""/playlist/(\d+)""")
    // QQ: match id=xxx parameter
    private val qqIdParam = Regex("""id=(\d+)""")

    // Kugou: match /xxx.html at end of path
    private val kugouHtmlId = Regex("""/(\d+)\.html""")
    // Kugou: match /songlist/xxx path
    private val kugouSonglistId = Regex("""/songlist/(\d+)""")

    // Kuwo: match /playlist_detail/xxx or /playlist/xxx
    private val kuwoPlaylistPath = Regex("""/playlist(?:_detail)?/(\d+)""")

    // Migu: match /playlist/xxx path
    private val miguPlaylistPath = Regex("""/playlist/(\d+)""")
    // Migu: match playlistId=xxx or id=xxx
    private val miguIdParam = Regex("""(?:playlistId|id)=(\d+)""")
}
