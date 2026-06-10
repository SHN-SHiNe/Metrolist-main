package com.metrolist.chinamusic.source

import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import timber.log.Timber
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Netease Cloud Music (网易云音乐) source implementation
 */
class NeteaseSource : MusicSourceProvider {
    override val sourceId = "wy"
    override val sourceName = "网易云音乐"

    private val eapiKey = "e82ckenh8dichen8".toByteArray()

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        val url = "/api/search/song/list/page"
        val data = buildJsonObject {
            put("keyword", keyword)
            put("needCorrect", "1")
            put("channel", "typing")
            put("offset", limit * (page - 1))
            put("scene", "normal")
            put("total", page == 1)
            put("limit", limit)
        }

        val encryptedParams = eapiEncrypt(url, data.toString())

        val response: HttpResponse = ChinaMusicApi.httpClient.post("http://interface.music.163.com/eapi/batch") {
            header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36")
            header("origin", "https://music.163.com")
            setBody(FormDataContent(Parameters.build {
                append("params", encryptedParams)
            }))
        }

        val bodyText = response.bodyAsText()
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(bodyText).jsonObject

        val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 200) {
            throw Exception("Netease search error: code=$code")
        }

        val resultData = json["data"]?.jsonObject ?: throw Exception("No data in response")
        val totalCount = resultData["totalCount"]?.jsonPrimitive?.intOrNull ?: 0
        val resources = resultData["resources"]?.jsonArray ?: JsonArray(emptyList())

        val songs = resources.mapNotNull { element ->
            try {
                parseSong(element.jsonObject)
            } catch (e: Exception) {
                Timber.tag("NeteaseSource").w(e, "Failed to parse song")
                null
            }
        }

        val allPage = if (limit > 0) Math.ceil(totalCount.toDouble() / limit).toInt() else 1

        return SearchResult(
            list = songs,
            total = totalCount,
            page = page,
            allPage = allPage,
            limit = limit,
            source = sourceId,
        )
    }

    override suspend fun searchSonglist(keyword: String, page: Int, limit: Int): SonglistSearchResult {
        val url = "/api/cloudsearch/pc"
        val data = buildJsonObject {
            put("s", keyword)
            put("type", 1000)
            put("limit", limit)
            put("total", page == 1)
            put("offset", limit * (page - 1))
        }

        val encryptedParams = eapiEncrypt(url, data.toString())

        val response: HttpResponse = ChinaMusicApi.httpClient.post("http://interface.music.163.com/eapi/batch") {
            header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36")
            header("origin", "https://music.163.com")
            setBody(FormDataContent(Parameters.build { append("params", encryptedParams) }))
        }

        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["code"]?.jsonPrimitive?.intOrNull != 200) {
            return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        }

        val result = json["result"]?.jsonObject ?: return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        val playlists = result["playlists"]?.jsonArray ?: JsonArray(emptyList())
        val total = result["playlistCount"]?.jsonPrimitive?.intOrNull ?: 0

        val items = playlists.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SonglistItem(
                    id = id,
                    name = item["name"]?.jsonPrimitive?.content ?: "",
                    author = item["creator"]?.jsonObject?.get("nickname")?.jsonPrimitive?.content ?: "",
                    img = item["coverImgUrl"]?.jsonPrimitive?.content,
                    playCount = item["playCount"]?.jsonPrimitive?.longOrNull ?: 0,
                    source = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }

        val allPage = if (limit > 0) Math.ceil(total.toDouble() / limit).toInt() else 1
        return SonglistSearchResult(items, total, page, allPage, limit, sourceId)
    }

    override suspend fun getSonglistsByTag(sortId: String, tagId: String, page: Int, limit: Int): SonglistSearchResult {
        val category = tagId.ifBlank { "全部" }
        val response: HttpResponse = ChinaMusicApi.httpClient.get("https://music.163.com/api/playlist/list") {
            parameter("cat", category)
            parameter("order", sortId.ifBlank { "hot" })
            parameter("limit", limit)
            parameter("offset", limit * (page - 1))
            parameter("total", page == 1)
            header("Referer", "https://music.163.com")
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }

        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["code"]?.jsonPrimitive?.intOrNull != 200) {
            return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        }

        val playlists = json["playlists"]?.jsonArray ?: JsonArray(emptyList())
        val total = json["total"]?.jsonPrimitive?.intOrNull ?: playlists.size
        val items = playlists.mapNotNull { element ->
            try {
                val item = element.jsonObject
                SonglistItem(
                    id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    name = item["name"]?.jsonPrimitive?.content ?: "",
                    author = item["creator"]?.jsonObject?.get("nickname")?.jsonPrimitive?.content ?: "",
                    img = item["coverImgUrl"]?.jsonPrimitive?.content,
                    playCount = item["playCount"]?.jsonPrimitive?.longOrNull ?: 0,
                    source = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }
        val allPage = if (limit > 0) Math.ceil(total.toDouble() / limit).toInt() else 1
        return SonglistSearchResult(items, total, page, allPage, limit, sourceId)
    }

    override suspend fun getSonglistTags(): List<SonglistTag> = listOf(
        "清晨", "夜晚", "学习", "工作", "午休", "下午茶", "地铁", "驾车", "运动", "旅行",
        "散步", "酒吧"
    ).map { SonglistTag(it, it, sourceId) }

    override suspend fun getSonglistGenreTags(): List<SonglistTag> = listOf(
        "华语", "欧美", "日语", "韩语", "粤语", "流行", "摇滚", "民谣", "电子", "舞曲",
        "说唱", "轻音乐", "爵士", "乡村", "R&B/Soul", "古典", "民族", "英伦", "金属", "蓝调"
    ).map { SonglistTag(it, it, sourceId) }

    private fun parseSong(resource: JsonObject): ChinaSong? {
        val baseInfo = resource["baseInfo"]?.jsonObject ?: return null
        val item = baseInfo["simpleSongData"]?.jsonObject ?: return null

        val id = item["id"]?.jsonPrimitive?.content ?: return null
        val name = item["name"]?.jsonPrimitive?.content ?: ""

        val artists = item["ar"]?.jsonArray ?: JsonArray(emptyList())
        val singer = artists.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.joinToString("、")

        val album = item["al"]?.jsonObject
        val albumName = album?.get("name")?.jsonPrimitive?.content ?: ""
        val albumId = album?.get("id")?.jsonPrimitive?.content ?: ""
        val img = album?.get("picUrl")?.jsonPrimitive?.content

        val dt = item["dt"]?.jsonPrimitive?.longOrNull ?: 0
        val durationSeconds = (dt / 1000).toInt()

        val privilege = item["privilege"]?.jsonObject
        val maxBr = privilege?.get("maxbr")?.jsonPrimitive?.intOrNull ?: 0
        val maxBrLevel = privilege?.get("maxBrLevel")?.jsonPrimitive?.content ?: ""

        val types = mutableListOf<SongQualityType>()
        if (maxBrLevel == "hires") {
            val hrSize = item["hr"]?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull
            types.add(SongQualityType("flac24bit", hrSize?.let { formatSize(it) }))
        }
        if (maxBr >= 999000) {
            val sqSize = item["sq"]?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull
            types.add(SongQualityType("flac", sqSize?.let { formatSize(it) }))
        }
        if (maxBr >= 320000) {
            val hSize = item["h"]?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull
            types.add(SongQualityType("320k", hSize?.let { formatSize(it) }))
        }
        if (maxBr >= 128000) {
            val lSize = item["l"]?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull
            types.add(SongQualityType("128k", lSize?.let { formatSize(it) }))
        }

        return ChinaSong(
            name = name,
            singer = singer,
            source = sourceId,
            songmid = id,
            albumName = albumName,
            albumId = albumId,
            interval = formatPlayTime(durationSeconds),
            durationSeconds = durationSeconds,
            img = img,
            types = types,
        )
    }

    override suspend fun getSonglistDetail(id: String, page: Int, limit: Int): SonglistDetail {
        val apiUrl = "/api/v6/playlist/detail"
        val data = buildJsonObject {
            put("id", id)
            put("n", 1000)
            put("s", 8)
        }
        val encryptedParams = eapiEncrypt(apiUrl, data.toString())
        val response = ChinaMusicApi.httpClient.post("https://interface.music.163.com/eapi/v6/playlist/detail") {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 9; PCT-AL10) AppleWebKit/537.36")
            header("Referer", "https://music.163.com")
            header("Cookie", "os=android; appver=8.9.20")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("params=$encryptedParams")
        }
        val body = response.bodyAsText()
        val json = try {
            ChinaMusicApi.jsonParser.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return SonglistDetail(id, "", songs = emptyList(), total = 0, page = page, allPage = 1, source = sourceId)
        }
        if (json["code"]?.jsonPrimitive?.intOrNull != 200) {
            return SonglistDetail(id, "", songs = emptyList(), total = 0, page = page, allPage = 1, source = sourceId)
        }
        val playlist = json["playlist"]?.jsonObject
            ?: return SonglistDetail(id, "", songs = emptyList(), total = 0, page = page, allPage = 1, source = sourceId)
        val tracks = playlist["tracks"]?.jsonArray ?: JsonArray(emptyList())
        val songs = tracks.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val songId = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val artists = item["ar"]?.jsonArray ?: JsonArray(emptyList())
                val singer = artists.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.joinToString("、")
                val album = item["al"]?.jsonObject
                val dt = item["dt"]?.jsonPrimitive?.longOrNull ?: 0
                val durationSeconds = (dt / 1000).toInt()
                ChinaSong(
                    name = item["name"]?.jsonPrimitive?.content ?: "",
                    singer = singer,
                    source = sourceId,
                    songmid = songId,
                    albumName = album?.get("name")?.jsonPrimitive?.content ?: "",
                    albumId = album?.get("id")?.jsonPrimitive?.content ?: "",
                    interval = formatPlayTime(durationSeconds),
                    durationSeconds = durationSeconds,
                    img = album?.get("picUrl")?.jsonPrimitive?.content,
                )
            } catch (e: Exception) { null }
        }
        val trackIds = playlist["trackIds"]?.jsonArray
        val total = trackIds?.size ?: songs.size

        // If trackIds has more songs than tracks (API limit 1000), fetch remaining
        val allSongs = if (trackIds != null && trackIds.size > songs.size) {
            val remainingIds = trackIds.drop(songs.size).mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            val extraSongs = fetchSongsByIds(remainingIds)
            songs + extraSongs
        } else {
            songs
        }

        return SonglistDetail(
            id = id,
            name = playlist["name"]?.jsonPrimitive?.content ?: "",
            author = playlist["creator"]?.jsonObject?.get("nickname")?.jsonPrimitive?.content ?: "",
            img = playlist["coverImgUrl"]?.jsonPrimitive?.content,
            desc = playlist["description"]?.jsonPrimitive?.content ?: "",
            songs = allSongs,
            total = total,
            page = page,
            allPage = 1,
            source = sourceId,
        )
    }

    override suspend fun getSongsByIds(ids: List<String>): List<ChinaSong> = fetchSongsByIds(ids)

    private suspend fun fetchSongsByIds(ids: List<String>): List<ChinaSong> {
        val result = mutableListOf<ChinaSong>()
        // Fetch in batches of 500
        for (batch in ids.chunked(500)) {
            try {
                val c = batch.joinToString(",") { """{"id":$it}""" }
                val idsStr = batch.joinToString(",")
                val data = buildJsonObject {
                    put("c", "[$c]")
                    put("ids", "[$idsStr]")
                }
                val encryptedParams = eapiEncrypt("/api/v3/song/detail", data.toString())
                val response = ChinaMusicApi.httpClient.post("https://interface.music.163.com/eapi/v3/song/detail") {
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 9; PCT-AL10) AppleWebKit/537.36")
                    header("Referer", "https://music.163.com")
                    header("Cookie", "os=android; appver=8.9.20")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("params=$encryptedParams")
                }
                val body = response.bodyAsText()
                val json = ChinaMusicApi.jsonParser.parseToJsonElement(body).jsonObject
                val songs = json["songs"]?.jsonArray ?: continue
                songs.mapNotNull { element ->
                    try {
                        val item = element.jsonObject
                        val songId = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val artists = item["ar"]?.jsonArray ?: JsonArray(emptyList())
                        val singer = artists.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.joinToString("、")
                        val album = item["al"]?.jsonObject
                        val dt = item["dt"]?.jsonPrimitive?.longOrNull ?: 0
                        val durationSeconds = (dt / 1000).toInt()
                        ChinaSong(
                            name = item["name"]?.jsonPrimitive?.content ?: "",
                            singer = singer,
                            source = sourceId,
                            songmid = songId,
                            albumName = album?.get("name")?.jsonPrimitive?.content ?: "",
                            albumId = album?.get("id")?.jsonPrimitive?.content ?: "",
                            interval = formatPlayTime(durationSeconds),
                            durationSeconds = durationSeconds,
                            img = album?.get("picUrl")?.jsonPrimitive?.content,
                        )
                    } catch (e: Exception) { null }
                }.also { result.addAll(it) }
            } catch (e: Exception) {
                Timber.tag("NeteaseSource").w(e, "Failed to fetch batch song details")
            }
        }
        return result
    }

    override suspend fun getBoards(): List<LeaderboardItem> = listOf(
        LeaderboardItem("wy__19723756", "飙升榜", "19723756", sourceId),
        LeaderboardItem("wy__3779629", "新歌榜", "3779629", sourceId),
        LeaderboardItem("wy__2884035", "原创榜", "2884035", sourceId),
        LeaderboardItem("wy__3778678", "热歌榜", "3778678", sourceId),
        LeaderboardItem("wy__991319590", "说唱榜", "991319590", sourceId),
        LeaderboardItem("wy__71384707", "古典榜", "71384707", sourceId),
        LeaderboardItem("wy__1978921795", "电音榜", "1978921795", sourceId),
        LeaderboardItem("wy__5453912201", "黑胶VIP爱听榜", "5453912201", sourceId),
        LeaderboardItem("wy__71385702", "ACG榜", "71385702", sourceId),
        LeaderboardItem("wy__745956260", "韩语榜", "745956260", sourceId),
        LeaderboardItem("wy__10520166", "国电榜", "10520166", sourceId),
        LeaderboardItem("wy__180106", "UK排行榜周榜", "180106", sourceId),
        LeaderboardItem("wy__60198", "美国Billboard榜", "60198", sourceId),
        LeaderboardItem("wy__3812895", "Beatport全球电子舞曲榜", "3812895", sourceId),
        LeaderboardItem("wy__21845217", "KTV唛榜", "21845217", sourceId),
        LeaderboardItem("wy__60131", "日本Oricon榜", "60131", sourceId),
        LeaderboardItem("wy__2809513713", "欧美热歌榜", "2809513713", sourceId),
        LeaderboardItem("wy__2809577409", "欧美新歌榜", "2809577409", sourceId),
        LeaderboardItem("wy__27135204", "法国 NRJ Vos Hits 周榜", "27135204", sourceId),
        LeaderboardItem("wy__3001835560", "ACG动画榜", "3001835560", sourceId),
        LeaderboardItem("wy__3001795926", "ACG游戏榜", "3001795926", sourceId),
        LeaderboardItem("wy__3001890046", "ACG VOCALOID榜", "3001890046", sourceId),
        LeaderboardItem("wy__3112516681", "中国新乡村音乐排行榜", "3112516681", sourceId),
        LeaderboardItem("wy__5059644681", "日语榜", "5059644681", sourceId),
        LeaderboardItem("wy__5059633707", "摇滚榜", "5059633707", sourceId),
        LeaderboardItem("wy__5059642708", "国风榜", "5059642708", sourceId),
        LeaderboardItem("wy__5338990334", "潜力爆款榜", "5338990334", sourceId),
        LeaderboardItem("wy__5059661515", "民谣榜", "5059661515", sourceId),
        LeaderboardItem("wy__6688069460", "听歌识曲榜", "6688069460", sourceId),
        LeaderboardItem("wy__6723173524", "网络热歌榜", "6723173524", sourceId),
        LeaderboardItem("wy__6732051320", "俄语榜", "6732051320", sourceId),
        LeaderboardItem("wy__6732014811", "越南语榜", "6732014811", sourceId),
        LeaderboardItem("wy__6886768100", "中文DJ榜", "6886768100", sourceId),
        LeaderboardItem("wy__6939992364", "俄罗斯top hit流行音乐榜", "6939992364", sourceId),
        LeaderboardItem("wy__7095271308", "泰语榜", "7095271308", sourceId),
        LeaderboardItem("wy__7356827205", "BEAT排行榜", "7356827205", sourceId),
        LeaderboardItem("wy__7603212484", "LOOK直播歌曲榜", "7603212484", sourceId),
        LeaderboardItem("wy__7775163417", "赏音榜", "7775163417", sourceId),
        LeaderboardItem("wy__7785123708", "黑胶VIP新歌榜", "7785123708", sourceId),
        LeaderboardItem("wy__7785066739", "黑胶VIP热歌榜", "7785066739", sourceId),
        LeaderboardItem("wy__7785091694", "黑胶VIP爱搜榜", "7785091694", sourceId),
    )

    override suspend fun getBoardSongs(bangid: String, page: Int, limit: Int): BoardSongsResult {
        val detail = getSonglistDetail(bangid, page, limit)
        return BoardSongsResult(
            list = detail.songs,
            total = detail.total,
            page = page,
            limit = limit,
            source = sourceId,
        )
    }

    private fun eapiEncrypt(apiUrl: String, text: String): String {
        val message = "nobody${apiUrl}use${text}md5forencrypt"
        val digest = md5Hex(message)
        val data = "${apiUrl}-36cd479b6b5-${text}-36cd479b6b5-${digest}"

        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(eapiKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = cipher.doFinal(data.toByteArray())
        return encrypted.joinToString("") { "%02X".format(it) }
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun formatPlayTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return "%02d:%02d".format(min, sec)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.2fMB".format(bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> "%.2fKB".format(bytes.toDouble() / 1024)
            else -> "${bytes}B"
        }
    }
}
