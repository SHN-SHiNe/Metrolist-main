package com.metrolist.chinamusic

import com.metrolist.chinamusic.model.*
import com.metrolist.chinamusic.source.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.metrolist.chinamusic.logging.MusicLog as Timber

/**
 * Main entry point for Chinese music source APIs.
 * Provides search, songlist, leaderboard, and music URL resolution.
 */
object ChinaMusicApi {
    private const val TAG = "ChinaMusicApi"
    private const val AGGREGATE_SEARCH_TIMEOUT_MS = 2500L
    private const val AGGREGATE_SONGLIST_TIMEOUT_MS = 3000L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    internal val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
        }
        defaultRequest {
            header("User-Agent", "lx-music-request/2.0.0")
        }
    }

    internal val jsonParser = json

    // Music URL provider configuration - source-aware
    private var activeSource: MusicSourceConfig = DEFAULT_MUSIC_SOURCE
    private val apiUrl get() = activeSource.apiUrl
    private val apiKey get() = activeSource.apiKey

    /** Get the currently active music source config */
    val currentSource: MusicSourceConfig get() = activeSource

    /**
     * Configure using a MusicSourceConfig directly
     */
    fun configure(source: MusicSourceConfig) {
        this.activeSource = source
    }

    /**
     * Configure the custom music URL API endpoint (backward compatible)
     */
    fun configure(apiUrl: String, apiKey: String) {
        this.activeSource = MusicSourceConfig(
            id = "legacy",
            name = "自定义源",
            apiUrl = apiUrl,
            apiKey = apiKey,
        )
    }

    // Source implementations
    private val kuwoSource = KuwoSource()
    private val neteaseSource = NeteaseSource()
    private val kugouSource = KugouSource()
    private val qqSource = QQMusicSource()
    private val miguSource = MiguSource()

    private fun getSource(source: MusicSource): MusicSourceProvider = when (source) {
        MusicSource.KUWO -> kuwoSource
        MusicSource.NETEASE -> neteaseSource
        MusicSource.KUGOU -> kugouSource
        MusicSource.QQ -> qqSource
        MusicSource.MIGU -> miguSource
        MusicSource.ALL -> throw IllegalArgumentException("Use aggregate search for ALL source")
    }

    private fun normalizeForMatch(s: String): String =
        s.lowercase().replace(Regex("[\\s\\p{P}]"), "")

    private fun similarityScore(keyword: String, title: String, singer: String): Int {
        val query = keyword.lowercase()
        val combined = "$title $singer".lowercase()
        if (combined == query) return 100
        if (combined.startsWith(query)) return 90
        if (combined.contains(query)) return 70
        val words = query.split(" ").filter { it.isNotBlank() }
        val matchCount = words.count { combined.contains(it) }
        return if (words.isNotEmpty()) (matchCount * 60) / words.size else 0
    }

    /**
     * Search for songs across a specific source (or all sources if source == ALL)
     */
    suspend fun search(
        keyword: String,
        page: Int = 1,
        limit: Int = 30,
        source: MusicSource = MusicSource.KUWO,
    ): Result<SearchResult> = runCatching {
        Timber.tag(TAG).d("Searching '$keyword' on ${source.displayName}, page=$page")
        if (source.isAggregate) {
            coroutineScope {
                val results = MusicSource.realSources.map { src ->
                    async {
                        withTimeoutOrNull(AGGREGATE_SEARCH_TIMEOUT_MS) {
                            runCatching { getSource(src).search(keyword, page, limit) }
                                .onFailure { Timber.tag(TAG).w(it, "Search failed source=${src.id}") }
                                .getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
                val merged = results.flatMap { it.list }
                    .distinctBy { "${it.source}_${it.songmid}" }
                    .sortedByDescending { similarityScore(keyword, it.name, it.singer) }
                val total = results.sumOf { it.total }
                val allPage = results.maxOfOrNull { it.allPage } ?: 1
                SearchResult(list = merged, total = total, page = page, allPage = allPage, limit = limit, source = "all")
            }
        } else {
            getSource(source).search(keyword, page, limit)
        }
    }

    /**
     * Search for playlists/songlists on a specific source
     */
    suspend fun searchSonglist(
        keyword: String,
        page: Int = 1,
        limit: Int = 20,
        source: MusicSource = MusicSource.NETEASE,
    ): Result<SonglistSearchResult> = runCatching {
        Timber.tag(TAG).d("Searching songlist '$keyword' on ${source.displayName}, page=$page")
        if (source.isAggregate) {
            coroutineScope {
                val results = MusicSource.realSources.map { src ->
                    async {
                        withTimeoutOrNull(AGGREGATE_SONGLIST_TIMEOUT_MS) {
                            runCatching { getSource(src).searchSonglist(keyword, page, limit) }
                                .onFailure { Timber.tag(TAG).w(it, "Songlist search failed source=${src.id}") }
                                .getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
                val merged = results.flatMap { it.list }
                    .distinctBy { "${it.source}_${it.id}" }
                    .sortedByDescending { similarityScore(keyword, it.name, it.author) }
                val total = results.sumOf { it.total }
                val allPage = results.maxOfOrNull { it.allPage } ?: 1
                SonglistSearchResult(list = merged, total = total, page = page, allPage = allPage, limit = limit, source = "all")
            }
        } else {
            getSource(source).searchSonglist(keyword, page, limit)
        }
    }

    suspend fun getSonglistsByTag(
        sortId: String = "hot",
        tagId: String = "",
        page: Int = 1,
        limit: Int = 30,
        source: MusicSource = MusicSource.NETEASE,
    ): Result<SonglistSearchResult> = runCatching {
        Timber.tag(TAG).d("Getting songlists by tag source=${source.displayName}, sort=$sortId, tag=$tagId, page=$page")
        if (source.isAggregate) {
            throw IllegalArgumentException("Use a concrete source for tag songlists")
        }
        getSource(source).getSonglistsByTag(sortId, tagId, page, limit)
    }

    suspend fun getSonglistTags(
        source: MusicSource = MusicSource.NETEASE,
    ): Result<List<SonglistTag>> = runCatching {
        if (source.isAggregate) {
            throw IllegalArgumentException("Use a concrete source for songlist tags")
        }
        getSource(source).getSonglistTags()
    }

    suspend fun getSonglistGenreTags(
        source: MusicSource = MusicSource.NETEASE,
    ): Result<List<SonglistTag>> = runCatching {
        if (source.isAggregate) {
            throw IllegalArgumentException("Use a concrete source for songlist genre tags")
        }
        getSource(source).getSonglistGenreTags()
    }

    /**
     * Get playlist/songlist detail with songs from a specific source
     */
    suspend fun getSonglistDetail(
        id: String,
        page: Int = 1,
        limit: Int = 50,
        source: MusicSource = MusicSource.NETEASE,
    ): Result<SonglistDetail> = runCatching {
        Timber.tag(TAG).d("Getting songlist detail id='$id' on ${source.displayName}, page=$page")
        getSource(source).getSonglistDetail(id, page, limit)
    }

    /**
     * Enrich songlist with individual song covers in the background (source-specific)
     */
    suspend fun enrichSonglistCovers(
        detail: SonglistDetail,
        source: MusicSource = MusicSource.NETEASE,
    ): Result<SonglistDetail> = runCatching {
        getSource(source).enrichCovers(detail)
    }

    /**
     * Get leaderboard/chart list for a specific source
     */
    suspend fun getBoards(
        source: MusicSource = MusicSource.NETEASE,
    ): Result<List<LeaderboardItem>> = runCatching {
        if (source.isAggregate) {
            throw IllegalArgumentException("Use a concrete source for leaderboards")
        }
        getSource(source).getBoards()
    }

    /**
     * Get songs from a leaderboard
     */
    suspend fun getBoardSongs(
        bangid: String,
        page: Int = 1,
        limit: Int = 100,
        source: MusicSource = MusicSource.NETEASE,
    ): Result<BoardSongsResult> = runCatching {
        if (source.isAggregate) {
            throw IllegalArgumentException("Use a concrete source for board songs")
        }
        getSource(source).getBoardSongs(bangid, page, limit)
    }

    suspend fun getSongsByIds(
        ids: List<String>,
        source: MusicSource = MusicSource.NETEASE,
    ): Result<List<ChinaSong>> = runCatching {
        if (source.isAggregate) {
            throw IllegalArgumentException("Use a concrete source for song detail")
        }
        getSource(source).getSongsByIds(ids)
    }

    /**
     * Search for songs across all sources
     */
    suspend fun searchAll(
        keyword: String,
        page: Int = 1,
        limit: Int = 30,
    ): Map<MusicSource, Result<SearchResult>> {
        return coroutineScope {
            MusicSource.realSources.map { source ->
                async {
                    source to runCatching { getSource(source).search(keyword, page, limit) }
                }
            }.awaitAll().toMap()
        }
    }

    /**
     * Get the playback URL for a song using the custom API
     */
    suspend fun getMusicUrl(
        song: ChinaSong,
        quality: AudioQuality = AudioQuality.HIGH,
    ): Result<String> = runCatching {
        val songId = song.hash ?: song.songmid
        val requestUrl = "$apiUrl/url?source=${song.source}&songId=$songId&quality=${quality.id}"
        Timber.tag(TAG).d("Getting music URL: source=${song.source}, songId=$songId, quality=${quality.id}")

        val response: HttpResponse = httpClient.get(requestUrl) {
            header("Content-Type", "application/json")
            header("X-API-Key", apiKey)
        }
        val body = jsonParser.decodeFromString<MusicUrlResponse>(response.bodyAsText())

        when (body.code) {
            200 -> {
                val url = body.url ?: throw Exception("URL is null")
                Timber.tag(TAG).d("Got music URL: $url")
                url
            }
            403 -> throw Exception("权限不足或Key失效")
            429 -> throw Exception("请求过速，请稍后再试")
            else -> throw Exception(body.message ?: "未知错误 (code: ${body.code})")
        }
    }

    /**
     * Get lyrics for a song. Tries backend API first, falls back to direct source APIs.
     */
    suspend fun getLyric(source: String, songId: String): Result<String> {
        Timber.tag(TAG).d("Getting lyrics: source=$source, songId=$songId")

        // 1. Try backend API
        try {
            val requestUrl = "$apiUrl/lyric?source=$source&songId=$songId"
            val response: HttpResponse = httpClient.get(requestUrl) {
                header("X-API-Key", apiKey)
            }
            val text = response.bodyAsText()
            val body = jsonParser.decodeFromString<LyricsResponse>(text)
            if (body.code == 200) {
                val lrc = body.lxlyric?.takeIf { it.isNotBlank() }
                    ?: body.lyric?.takeIf { it.isNotBlank() }
                if (lrc != null) {
                    Timber.tag(TAG).i("Got lyrics from backend API ($source)")
                    return Result.success(lrc)
                }
            }
            Timber.tag(TAG).d("Backend lyrics empty or failed (code=${body.code}), trying direct source")
        } catch (e: Exception) {
            Timber.tag(TAG).d("Backend lyric API failed: ${e.message}, trying direct source")
        }

        // 2. Fallback to direct source APIs
        return when (source) {
            "kw" -> getKuwoLyricDirect(songId)
            "kg" -> getKugouLyricDirect(songId)
            "wy" -> getNeteaseLyricDirect(songId)
            else -> Result.failure(Exception("No lyrics API for source: $source"))
        }
    }

    /**
     * Kuwo direct lyrics: simple JSON API
     */
    private suspend fun getKuwoLyricDirect(musicId: String): Result<String> = runCatching {
        Timber.tag(TAG).d("Kuwo direct lyrics for musicId=$musicId")
        val response = httpClient.get("http://m.kuwo.cn/newh5/singles/songinfoandlrc") {
            parameter("musicId", musicId)
        }
        val root = jsonParser.parseToJsonElement(response.bodyAsText())
        val dataElement = root.jsonObject["data"]
        if (dataElement == null || dataElement is kotlinx.serialization.json.JsonNull) {
            throw Exception("Kuwo API returned null data for musicId=$musicId")
        }
        val data = dataElement.jsonObject
        val lrcListElement = data["lrclist"]
        if (lrcListElement == null || lrcListElement is kotlinx.serialization.json.JsonNull) {
            throw Exception("No lrclist for musicId=$musicId")
        }
        val lrcList = lrcListElement.jsonArray
        if (lrcList.isEmpty()) throw Exception("Empty lrclist")
        val lrc = buildString {
            for (item in lrcList) {
                val obj = item.jsonObject
                val timeStr = obj["time"]?.jsonPrimitive?.content ?: continue
                val text = obj["lineLyric"]?.jsonPrimitive?.content ?: ""
                val totalSeconds = timeStr.toDoubleOrNull() ?: continue
                val min = (totalSeconds / 60).toInt()
                val sec = totalSeconds % 60
                append("[%02d:%05.2f]%s\n".format(min, sec, text))
            }
        }.trimEnd()
        if (lrc.isBlank()) throw Exception("Parsed lyrics empty")
        Timber.tag(TAG).i("Got Kuwo direct lyrics (${lrc.length} chars)")
        lrc
    }

    /**
     * Kugou direct lyrics: search by hash → download
     */
    private suspend fun getKugouLyricDirect(hash: String): Result<String> = runCatching {
        Timber.tag(TAG).d("Kugou direct lyrics for hash=$hash")
        // Step 1: search by hash
        val searchResp = httpClient.get("https://lyrics.kugou.com/search") {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            parameter("hash", hash)
        }
        val searchJson = jsonParser.parseToJsonElement(searchResp.bodyAsText()).jsonObject
        val candidates = searchJson["candidates"]?.jsonArray
        if (candidates.isNullOrEmpty()) throw Exception("No lyrics candidates for hash")
        val first = candidates[0].jsonObject
        val id = first["id"]?.jsonPrimitive?.content ?: throw Exception("No candidate id")
        val accessKey = first["accesskey"]?.jsonPrimitive?.content ?: throw Exception("No accesskey")

        // Step 2: download lyrics
        val dlResp = httpClient.get("https://lyrics.kugou.com/download") {
            parameter("ver", 1)
            parameter("client", "pc")
            parameter("id", id)
            parameter("accesskey", accessKey)
            parameter("fmt", "lrc")
            parameter("charset", "utf8")
        }
        val dlJson = jsonParser.parseToJsonElement(dlResp.bodyAsText()).jsonObject
        val content = dlJson["content"]?.jsonPrimitive?.content ?: throw Exception("No content")
        val decoded = java.util.Base64.getDecoder().decode(content).toString(Charsets.UTF_8)
        if (decoded.isBlank()) throw Exception("Decoded lyrics empty")
        Timber.tag(TAG).i("Got Kugou direct lyrics (${decoded.length} chars)")
        decoded
    }

    /**
     * Netease direct lyrics via public API
     */
    private suspend fun getNeteaseLyricDirect(songId: String): Result<String> = runCatching {
        Timber.tag(TAG).d("Netease direct lyrics for songId=$songId")
        val response = httpClient.get("https://music.163.com/api/song/lyric") {
            parameter("id", songId)
            parameter("lv", -1)
            parameter("tv", -1)
            header("Referer", "https://music.163.com")
        }
        val json = jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val lrc = json["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.content
        val tlyric = json["tlyric"]?.jsonObject?.get("lyric")?.jsonPrimitive?.content
        val result = buildString {
            if (!lrc.isNullOrBlank()) append(lrc)
            if (!tlyric.isNullOrBlank()) {
                append("\n")
                append(tlyric)
            }
        }.trim()
        if (result.isBlank()) throw Exception("Netease lyrics empty")
        Timber.tag(TAG).i("Got Netease direct lyrics (${result.length} chars)")
        result
    }

    /**
     * Get the playback URL with quality fallback
     */
    suspend fun getMusicUrlWithFallback(
        song: ChinaSong,
        preferredQuality: AudioQuality = AudioQuality.HIGH,
    ): Result<String> {
        val qualities = listOf(
            preferredQuality,
            AudioQuality.HIGH,
            AudioQuality.LOW,
            AudioQuality.LOSSLESS,
        ).distinct()

        for (quality in qualities) {
            val result = getMusicUrl(song, quality)
            if (result.isSuccess) return result
            Timber.tag(TAG).d("Quality ${quality.id} failed, trying next...")
        }
        return Result.failure(Exception("所有音质均获取失败"))
    }

    /**
     * Find and play a song from another source when the primary source fails.
     * Searches all other sources by name+singer, matches by duration, and tries each candidate URL.
     */
    suspend fun findMusicFromOtherSources(
        name: String,
        singer: String,
        durationSeconds: Int,
        excludeSourceId: String,
        quality: AudioQuality = AudioQuality.HIGH,
    ): Result<String> {
        if (name.isBlank()) return Result.failure(Exception("无歌曲名称，无法搜索其他源"))
        val query = if (singer.isNotBlank()) "$name $singer" else name
        Timber.tag(TAG).d("Finding alternative source for: $query (excluding $excludeSourceId)")

        val candidates = coroutineScope {
            MusicSource.realSources
                .filter { it.id != excludeSourceId }
                .map { src ->
                    async {
                        runCatching { getSource(src).search(query, 1, 20) }.getOrNull()
                    }
                }.awaitAll()
                .filterNotNull()
                .flatMap { it.list }
                .filter { song ->
                    if (song.durationSeconds in 1..29) return@filter false
                    val nameNorm = normalizeForMatch(name)
                    val songNorm = normalizeForMatch(song.name)
                    val nameMatch = nameNorm == songNorm ||
                        (nameNorm.length >= 4 && songNorm.contains(nameNorm)) ||
                        (songNorm.length >= 4 && nameNorm.contains(songNorm))
                    val durationMatch = durationSeconds <= 0 ||
                        song.durationSeconds <= 0 ||
                        Math.abs(song.durationSeconds - durationSeconds) <= 10
                    nameMatch && durationMatch
                }
                .sortedWith(
                    compareByDescending<ChinaSong> { song ->
                        val singerNorm = normalizeForMatch(singer)
                        val songSingerNorm = normalizeForMatch(song.singer)
                        songSingerNorm.contains(singerNorm) || singerNorm.contains(songSingerNorm)
                    }.thenBy {
                        if (durationSeconds > 0 && it.durationSeconds > 0)
                            Math.abs(it.durationSeconds - durationSeconds) else 0
                    }
                )
        }

        Timber.tag(TAG).d("Found ${candidates.size} alternative candidates for: $name")
        for (candidate in candidates.take(5)) {
            Timber.tag(TAG).d("Trying alternative: ${candidate.source} - ${candidate.name} by ${candidate.singer}")
            val url = getMusicUrlWithFallback(candidate, quality)
            if (url.isSuccess) {
                Timber.tag(TAG).i("Alternative source found: ${candidate.source} for $name")
                return url
            }
        }
        return Result.failure(Exception("所有备用音源均获取失败: $name"))
    }
}
