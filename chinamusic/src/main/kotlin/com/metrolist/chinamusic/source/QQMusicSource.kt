package com.metrolist.chinamusic.source

import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import timber.log.Timber
import java.security.MessageDigest
import android.util.Base64
import java.net.URLEncoder

/**
 * QQ Music (QQ音乐) source implementation
 */
class QQMusicSource : MusicSourceProvider {
    override val sourceId = "tx"
    override val sourceName = "QQ音乐"

    private val PART_1_INDEXES = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
    private val PART_2_INDEXES = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
    private val SCRAMBLE_VALUES = intArrayOf(89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179)

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult =
        searchFast(keyword, page, limit, retryNum = 0)

    private suspend fun searchFast(keyword: String, page: Int, limit: Int, retryNum: Int): SearchResult {
        val requestData = buildJsonObject {
            putJsonObject("comm") {
                put("ct", "11")
                put("cv", "14090508")
                put("v", "14090508")
                put("tmeAppID", "qqmusic")
                put("phonetype", "EBG-AN10")
                put("deviceScore", "553.47")
                put("devicelevel", "50")
                put("newdevicelevel", "20")
                put("rom", "HuaWei/EMOTION/EmotionUI_14.2.0")
                put("os_ver", "12")
                put("OpenUDID", "0")
                put("OpenUDID2", "0")
                put("QIMEI36", "0")
                put("udid", "0")
                put("chid", "0")
                put("aid", "0")
                put("oaid", "0")
                put("taid", "0")
                put("tid", "0")
                put("wid", "0")
                put("uid", "0")
                put("sid", "0")
                put("modeSwitch", "6")
                put("teenMode", "0")
                put("ui_mode", "2")
                put("nettype", "1020")
                put("v4ip", "")
            }
            putJsonObject("req") {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicMobile")
                putJsonObject("param") {
                    put("search_type", 0)
                    put("searchid", Math.random().toString().substring(2))
                    put("query", keyword)
                    put("page_num", page)
                    put("num_per_page", limit)
                    put("highlight", 0)
                    put("nqc_flag", 0)
                    put("multi_zhida", 0)
                    put("cat", 2)
                    put("grp", 1)
                    put("sin", 0)
                    put("sem", 0)
                }
            }
        }

        val jsonStr = requestData.toString()
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg"

        val response: HttpResponse = ChinaMusicApi.httpClient.post(url) {
            header("User-Agent", "QQMusic 14090508(android 12)")
            contentType(ContentType.Application.Json)
            setBody(jsonStr)
        }

        val bodyText = response.bodyAsText()
        Timber.tag("QQMusicSource").d("Response (first 500): ${bodyText.take(500)}")
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(bodyText).jsonObject

        val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            if (retryNum < 1) return searchFast(keyword, page, limit, retryNum + 1)
            Timber.tag("QQMusicSource").w("QQ Music search error: code=$code")
            return emptySearchResult(page, limit)
        }

        val req = json["req"]?.jsonObject ?: throw Exception("No req in response")
        val reqCode = req["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (reqCode != 0) {
            if (retryNum < 1) return searchFast(keyword, page, limit, retryNum + 1)
            Timber.tag("QQMusicSource").w("QQ Music search req error: code=$reqCode")
            return emptySearchResult(page, limit)
        }

        val data = req["data"]?.jsonObject ?: throw Exception("No data in response")
        val body = data["body"]?.jsonObject ?: throw Exception("No body in response")
        val meta = data["meta"]?.jsonObject

        val total = meta?.get("estimate_sum")?.jsonPrimitive?.intOrNull ?: 0
        val itemSong = body["item_song"]?.jsonArray ?: JsonArray(emptyList())

        val songs = itemSong.mapNotNull { element ->
            try {
                parseSong(element.jsonObject)
            } catch (e: Exception) {
                Timber.tag("QQMusicSource").w(e, "Failed to parse song")
                null
            }
        }

        val allPage = if (limit > 0) Math.ceil(total.toDouble() / limit).toInt() else 1

        return SearchResult(
            list = songs,
            total = total,
            page = page,
            allPage = allPage,
            limit = limit,
            source = sourceId,
        )
    }

    private fun emptySearchResult(page: Int, limit: Int): SearchResult {
        return SearchResult(
            list = emptyList(),
            total = 0,
            page = page,
            allPage = 1,
            limit = limit,
            source = sourceId,
        )
    }

    private suspend fun searchLegacy(keyword: String, page: Int, limit: Int): SearchResult {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?ct=24&qqmusic_ver=1298&new_json=1&remoteplace=txt.yqq.song&searchid=${Math.random().toString().substring(2)}&t=0&aggr=1&cr=1&catZhida=1&lossless=0&flag_qc=0&p=$page&n=$limit&w=$encodedKeyword&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        val response = ChinaMusicApi.httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            header("Referer", "https://y.qq.com/portal/search.html")
        }
        val bodyText = response.bodyAsText()
        Timber.tag("QQMusicSource").d("Legacy response (first 500): ${bodyText.take(500)}")
        if (bodyText.isBlank()) {
            return SearchResult(
                list = emptyList(),
                total = 0,
                page = page,
                allPage = 1,
                limit = limit,
                source = sourceId,
            )
        }
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(bodyText).jsonObject
        if (json["code"]?.jsonPrimitive?.intOrNull != 0) {
            throw Exception("QQ Music search legacy error: body=${bodyText.take(200)}")
        }
        val data = json["data"]?.jsonObject ?: throw Exception("No legacy data in response")
        val song = data["song"]?.jsonObject ?: throw Exception("No legacy song in response")
        val list = song["list"]?.jsonArray ?: JsonArray(emptyList())
        val total = song["totalnum"]?.jsonPrimitive?.intOrNull ?: 0
        val songs = list.mapNotNull { element ->
            try {
                parseSong(element.jsonObject)
            } catch (e: Exception) {
                Timber.tag("QQMusicSource").w(e, "Failed to parse legacy song")
                null
            }
        }
        val allPage = if (limit > 0) Math.ceil(total.toDouble() / limit).toInt() else 1
        return SearchResult(
            list = songs,
            total = total,
            page = page,
            allPage = allPage,
            limit = limit,
            source = sourceId,
        )
    }

    override suspend fun searchSonglist(keyword: String, page: Int, limit: Int): SonglistSearchResult {
        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        val url = "http://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist?page_no=${page - 1}&num_per_page=$limit&format=json&query=$encodedKeyword&remoteplace=txt.yqq.playlist&inCharset=utf8&outCharset=utf-8"

        val response: HttpResponse = ChinaMusicApi.httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")
            header("Referer", "http://y.qq.com/portal/search.html")
        }

        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["code"]?.jsonPrimitive?.intOrNull != 0) {
            return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        }

        val data = json["data"]?.jsonObject ?: return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        val listArray = data["list"]?.jsonArray ?: JsonArray(emptyList())
        val total = data["sum"]?.jsonPrimitive?.intOrNull ?: 0

        val items = listArray.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val id = item["dissid"]?.jsonPrimitive?.content?.let { String.format("%s", it) } ?: return@mapNotNull null
                SonglistItem(
                    id = id,
                    name = decodeName(item["dissname"]?.jsonPrimitive?.content ?: ""),
                    author = decodeName(item["creator"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""),
                    img = item["imgurl"]?.jsonPrimitive?.content,
                    playCount = item["listennum"]?.jsonPrimitive?.longOrNull ?: 0,
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
        val categoryId = tagId.toIntOrNull()
        val payload = buildJsonObject {
            putJsonObject("comm") {
                put("cv", 1602)
                put("ct", 20)
            }
            putJsonObject("playlist") {
                put("module", if (categoryId != null) "playlist.PlayListCategoryServer" else "playlist.PlayListPlazaServer")
                put("method", if (categoryId != null) "get_category_content" else "get_playlist_by_tag")
                putJsonObject("param") {
                    if (categoryId != null) {
                        put("titleid", categoryId)
                        put("caller", "0")
                        put("category_id", categoryId)
                        put("size", limit)
                        put("page", page - 1)
                        put("use_page", 1)
                    } else {
                        put("id", 10000000)
                        put("sin", limit * (page - 1))
                        put("size", limit)
                        put("order", sortId.toIntOrNull() ?: 5)
                        put("cur_page", page)
                    }
                }
            }
        }
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=${URLEncoder.encode(payload.toString(), "UTF-8")}"
        val response: HttpResponse = ChinaMusicApi.httpClient.get(url)
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["code"]?.jsonPrimitive?.intOrNull != 0) {
            return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        }
        val playlistData = json["playlist"]?.jsonObject?.get("data")?.jsonObject
            ?: return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        val content = playlistData["content"]?.jsonObject
        val list = if (categoryId != null) {
            content?.get("v_item")?.jsonArray ?: JsonArray(emptyList())
        } else {
            playlistData["v_playlist"]?.jsonArray ?: JsonArray(emptyList())
        }
        val total = if (categoryId != null) {
            content?.get("total_cnt")?.jsonPrimitive?.intOrNull ?: list.size
        } else {
            playlistData["total"]?.jsonPrimitive?.intOrNull ?: list.size
        }
        val items = list.mapNotNull { element ->
            try {
                val basic = if (categoryId != null) element.jsonObject["basic"]?.jsonObject else element.jsonObject
                if (basic == null) return@mapNotNull null
                SonglistItem(
                    id = basic["tid"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    name = decodeName(basic["title"]?.jsonPrimitive?.content ?: ""),
                    author = basic["creator"]?.jsonObject?.get("nick")?.jsonPrimitive?.content
                        ?: basic["creator_info"]?.jsonObject?.get("nick")?.jsonPrimitive?.content ?: "",
                    img = basic["cover"]?.jsonObject?.get("medium_url")?.jsonPrimitive?.content
                        ?: basic["cover"]?.jsonObject?.get("default_url")?.jsonPrimitive?.content
                        ?: basic["cover_url_medium"]?.jsonPrimitive?.content,
                    playCount = basic["play_cnt"]?.jsonPrimitive?.longOrNull
                        ?: basic["access_num"]?.jsonPrimitive?.longOrNull ?: 0,
                    source = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }
        val allPage = if (limit > 0) Math.ceil(total.toDouble() / limit).toInt() else 1
        return SonglistSearchResult(items, total, page, allPage, limit, sourceId)
    }

    override suspend fun getSonglistTags(): List<SonglistTag> {
        return getTagsByGroupName { it.contains("场景") }
    }

    override suspend fun getSonglistGenreTags(): List<SonglistTag> {
        return getTagsByGroupName { name ->
            name.contains("语种") || name.contains("流派") || name.contains("风格")
        }
    }

    private suspend fun getTagsByGroupName(predicate: (String) -> Boolean): List<SonglistTag> {
        val payload = buildJsonObject {
            putJsonObject("tags") {
                put("method", "get_all_categories")
                put("module", "playlist.PlaylistAllCategoriesServer")
                putJsonObject("param") {
                    put("qq", "")
                }
            }
        }
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=${URLEncoder.encode(payload.toString(), "UTF-8")}"
        val response: HttpResponse = ChinaMusicApi.httpClient.get(url)
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val groups = json["tags"]?.jsonObject?.get("data")?.jsonObject?.get("v_group")?.jsonArray ?: JsonArray(emptyList())
        return groups.filter { group ->
            predicate(group.jsonObject["group_name"]?.jsonPrimitive?.content.orEmpty())
        }.flatMap { group ->
            group.jsonObject["v_item"]?.jsonArray?.mapNotNull { item ->
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SonglistTag(id, name, sourceId)
            } ?: emptyList()
        }
    }

    private fun decodeName(name: String): String = name
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#039;", "'")

    private fun parseSong(item: JsonObject): ChinaSong? {
        val file = item["file"]?.jsonObject ?: return null
        val mediaMid = file["media_mid"]?.jsonPrimitive?.content
        if (mediaMid.isNullOrEmpty()) return null

        val mid = item["mid"]?.jsonPrimitive?.content ?: return null
        val songId = item["id"]?.jsonPrimitive?.content
        val title = item["title"]?.jsonPrimitive?.content ?: ""

        val singers = item["singer"]?.jsonArray ?: JsonArray(emptyList())
        val singer = singers.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.joinToString("、")

        val album = item["album"]?.jsonObject
        val albumName = album?.get("name")?.jsonPrimitive?.content ?: ""
        val albumMid = album?.get("mid")?.jsonPrimitive?.content ?: ""

        val interval = item["interval"]?.jsonPrimitive?.intOrNull ?: 0

        val types = mutableListOf<SongQualityType>()
        val size128 = file["size_128mp3"]?.jsonPrimitive?.longOrNull ?: 0
        if (size128 > 0) types.add(SongQualityType("128k", formatSize(size128)))
        val size320 = file["size_320mp3"]?.jsonPrimitive?.longOrNull ?: 0
        if (size320 > 0) types.add(SongQualityType("320k", formatSize(size320)))
        val sizeFlac = file["size_flac"]?.jsonPrimitive?.longOrNull ?: 0
        if (sizeFlac > 0) types.add(SongQualityType("flac", formatSize(sizeFlac)))
        val sizeHires = file["size_hires"]?.jsonPrimitive?.longOrNull ?: 0
        if (sizeHires > 0) types.add(SongQualityType("flac24bit", formatSize(sizeHires)))

        val img = when {
            albumMid.isNotEmpty() && albumMid != "空" ->
                "https://y.gtimg.cn/music/photo_new/T002R500x500M000${albumMid}.jpg"
            singers.isNotEmpty() -> {
                val singerMid = singers.firstOrNull()?.jsonObject?.get("mid")?.jsonPrimitive?.content
                if (singerMid != null) "https://y.gtimg.cn/music/photo_new/T001R500x500M000${singerMid}.jpg" else null
            }
            else -> null
        }

        return ChinaSong(
            name = title,
            singer = singer,
            source = sourceId,
            songmid = mid,
            albumName = albumName,
            albumId = albumMid,
            interval = formatPlayTime(interval),
            durationSeconds = interval,
            img = img,
            copyrightId = songId,
            types = types,
        )
    }

    override suspend fun getSonglistDetail(id: String, page: Int, limit: Int): SonglistDetail {
        val parts = id.split("|", limit = 2)
        val dissId = parts[0]
        val encHostUin = parts.getOrNull(1).orEmpty()
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$dissId&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq.json&needNewCode=0"
        val response = ChinaMusicApi.httpClient.get(url) {
            header("Origin", "https://y.qq.com")
            header("Referer", "https://y.qq.com/n/yqq/playsquare/$dissId.html")
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }
        val json = try {
            ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: Exception) {
            return getMobileShareSonglistDetail(dissId, encHostUin, page, limit)
        }
        if (json["code"]?.jsonPrimitive?.intOrNull != 0) {
            return getMobileShareSonglistDetail(dissId, encHostUin, page, limit)
        }
        val cdlist = json["cdlist"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return getMobileShareSonglistDetail(dissId, encHostUin, page, limit)
        val songlist = cdlist["songlist"]?.jsonArray ?: JsonArray(emptyList())
        val songs = songlist.mapNotNull { element ->
            try { parseSong(element.jsonObject) } catch (e: Exception) { null }
        }
        if (songs.isEmpty()) return getMobileShareSonglistDetail(dissId, encHostUin, page, limit)
        return SonglistDetail(
            id = dissId,
            name = decodeName(cdlist["dissname"]?.jsonPrimitive?.content ?: ""),
            author = cdlist["nickname"]?.jsonPrimitive?.content ?: "",
            img = cdlist["logo"]?.jsonPrimitive?.content,
            desc = decodeName(cdlist["desc"]?.jsonPrimitive?.content ?: ""),
            songs = songs,
            total = songs.size,
            page = page,
            allPage = 1,
            source = sourceId,
        )
    }

    private suspend fun getMobileShareSonglistDetail(
        id: String,
        encHostUin: String,
        page: Int,
        limit: Int,
    ): SonglistDetail {
        if (encHostUin.isBlank()) {
            return SonglistDetail(id, "", songs = emptyList(), total = 0, page = page, allPage = 1, source = sourceId)
        }
        val requestData = buildJsonObject {
            putJsonObject("comm") {
                put("format", "json")
                put("inCharset", "utf-8")
                put("outCharset", "utf-8")
                put("platform", "yqq.json")
                put("needNewCode", 1)
                put("cv", 20040108)
            }
            putJsonObject("req_0") {
                put("module", "music.srfDissInfo.aiDissInfo")
                put("method", "uniform_get_Dissinfo")
                putJsonObject("param") {
                    put("disstid", id.toLongOrNull() ?: 0L)
                    put("enc_host_uin", encHostUin)
                    put("tag", 1)
                    put("userinfo", 1)
                    put("song_begin", (page - 1) * limit)
                    put("song_num", limit)
                    put("orderlist", 1)
                }
            }
        }
        val response = ChinaMusicApi.httpClient.get("https://u.y.qq.com/cgi-bin/musicu.fcg?data=${URLEncoder.encode(requestData.toString(), "UTF-8")}") {
            header("Referer", "https://i2.y.qq.com/n3/other/pages/details/playlist.html?hosteuin=$encHostUin&id=$id")
            header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Mobile Safari/537.36")
        }
        val root = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = root["req_0"]?.jsonObject?.get("data")?.jsonObject
            ?: return SonglistDetail(id, "", songs = emptyList(), total = 0, page = page, allPage = 1, source = sourceId)
        val songs = data["songlist"]?.jsonArray.orEmpty().mapNotNull { element ->
            try { parseSong(element.jsonObject) } catch (e: Exception) { null }
        }
        val dirInfo = data["dirinfo"]?.jsonObject
        val total = data["total_song_num"]?.jsonPrimitive?.intOrNull ?: songs.size
        return SonglistDetail(
            id = id,
            name = decodeName(dirInfo?.get("title")?.jsonPrimitive?.content ?: ""),
            author = dirInfo?.get("host_nick")?.jsonPrimitive?.content ?: "",
            img = dirInfo?.get("picurl")?.jsonPrimitive?.content,
            desc = decodeName(dirInfo?.get("desc")?.jsonPrimitive?.content ?: ""),
            songs = songs,
            total = total,
            page = page,
            allPage = if (limit > 0) ((total + limit - 1) / limit).coerceAtLeast(1) else 1,
            source = sourceId,
        )
    }

    private fun zzcSign(text: String): String {
        val hash = sha1Hex(text)

        val part1 = PART_1_INDEXES.map { idx ->
            if (idx < hash.length) hash[idx] else '0'
        }.joinToString("")

        val part2 = PART_2_INDEXES.map { idx ->
            if (idx < hash.length) hash[idx] else '0'
        }.joinToString("")

        val part3 = ByteArray(SCRAMBLE_VALUES.size) { i ->
            val hexByte = if (i * 2 + 2 <= hash.length) {
                hash.substring(i * 2, i * 2 + 2).toInt(16)
            } else 0
            (SCRAMBLE_VALUES[i] xor hexByte).toByte()
        }

        val b64Part = Base64.encodeToString(part3, Base64.NO_WRAP)
            .replace(Regex("[/+=\\\\]"), "")

        return "zzc${part1}${b64Part}${part2}".lowercase()
    }

    private fun sha1Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
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

    override suspend fun getBoards(): List<LeaderboardItem> = listOf(
        LeaderboardItem("tx__4", "流行指数榜", "4", sourceId),
        LeaderboardItem("tx__26", "热歌榜", "26", sourceId),
        LeaderboardItem("tx__27", "新歌榜", "27", sourceId),
        LeaderboardItem("tx__62", "飙升榜", "62", sourceId),
        LeaderboardItem("tx__58", "说唱榜", "58", sourceId),
        LeaderboardItem("tx__57", "喜力电音榜", "57", sourceId),
        LeaderboardItem("tx__28", "网络歌曲榜", "28", sourceId),
        LeaderboardItem("tx__5", "内地榜", "5", sourceId),
        LeaderboardItem("tx__3", "欧美榜", "3", sourceId),
        LeaderboardItem("tx__59", "香港地区榜", "59", sourceId),
        LeaderboardItem("tx__16", "韩国榜", "16", sourceId),
        LeaderboardItem("tx__60", "抖快榜", "60", sourceId),
        LeaderboardItem("tx__29", "影视金曲榜", "29", sourceId),
        LeaderboardItem("tx__17", "日本榜", "17", sourceId),
        LeaderboardItem("tx__52", "腾讯音乐人原创榜", "52", sourceId),
        LeaderboardItem("tx__36", "K歌金曲榜", "36", sourceId),
        LeaderboardItem("tx__61", "台湾地区榜", "61", sourceId),
        LeaderboardItem("tx__63", "DJ舞曲榜", "63", sourceId),
        LeaderboardItem("tx__64", "综艺新歌榜", "64", sourceId),
        LeaderboardItem("tx__65", "国风热歌榜", "65", sourceId),
        LeaderboardItem("tx__67", "听歌识曲榜", "67", sourceId),
        LeaderboardItem("tx__72", "动漫音乐榜", "72", sourceId),
        LeaderboardItem("tx__73", "游戏音乐榜", "73", sourceId),
        LeaderboardItem("tx__75", "有声榜", "75", sourceId),
        LeaderboardItem("tx__131", "校园音乐人排行榜", "131", sourceId),
    )

    override suspend fun getBoardSongs(bangid: String, page: Int, limit: Int): BoardSongsResult {
        val requestBody = buildJsonObject {
            putJsonObject("toplist") {
                put("module", "musicToplist.ToplistInfoServer")
                put("method", "GetDetail")
                putJsonObject("param") {
                    put("topid", bangid.toIntOrNull() ?: 0)
                    put("num", limit)
                    put("period", "")
                }
            }
            putJsonObject("comm") {
                put("uin", 0)
                put("format", "json")
                put("ct", 20)
                put("cv", 1859)
            }
        }
        val response = ChinaMusicApi.httpClient.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
            header("User-Agent", "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = json["toplist"]?.jsonObject?.get("data")?.jsonObject
            ?: return BoardSongsResult(emptyList(), 0, page, limit, sourceId)
        val songInfoList = data["songInfoList"]?.jsonArray ?: JsonArray(emptyList())
        val total = data["totalNum"]?.jsonPrimitive?.intOrNull ?: songInfoList.size
        val songs = songInfoList.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val mid = item["mid"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = item["name"]?.jsonPrimitive?.content ?: ""
                val singers = item["singer"]?.jsonArray
                val singer = singers?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }?.joinToString("、") ?: ""
                val album = item["album"]?.jsonObject
                val albumName = album?.get("name")?.jsonPrimitive?.content ?: ""
                val albumMid = album?.get("mid")?.jsonPrimitive?.content ?: ""
                val interval = item["interval"]?.jsonPrimitive?.intOrNull ?: 0
                val img = if (albumMid.isNotBlank()) "https://y.gtimg.cn/music/photo_new/T002R500x500M000${albumMid}.jpg" else null
                ChinaSong(
                    name = name,
                    singer = singer,
                    source = sourceId,
                    songmid = mid,
                    albumName = albumName,
                    albumId = albumMid,
                    interval = formatPlayTime(interval),
                    durationSeconds = interval,
                    img = img,
                )
            } catch (e: Exception) { null }
        }
        return BoardSongsResult(songs, total, page, limit, sourceId)
    }

    override suspend fun getSongsByIds(ids: List<String>): List<ChinaSong> {
        if (ids.isEmpty()) return emptyList()
        val requestBody = buildJsonObject {
            putJsonObject("songinfo") {
                put("module", "music.trackInfo.UniformRuleCtrl")
                put("method", "CgiGetTrackInfo")
                putJsonObject("param") {
                    putJsonArray("mids") { ids.forEach { add(it) } }
                    putJsonArray("ids") { }
                    putJsonArray("types") { ids.forEach { add(0) } }
                    put("ctx", 1)
                }
            }
            putJsonObject("comm") {
                put("uin", 0)
                put("format", "json")
                put("ct", 20)
                put("cv", 1859)
            }
        }
        val response = ChinaMusicApi.httpClient.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val tracks = json["songinfo"]?.jsonObject?.get("data")?.jsonObject?.get("tracks")?.jsonArray
            ?: return emptyList()
        return tracks.mapNotNull { element ->
            try { parseSong(element.jsonObject) } catch (e: Exception) { null }
        }
    }
}
