package com.metrolist.chinamusic.source

import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import com.metrolist.chinamusic.logging.MusicLog as Timber
import java.net.URLEncoder

/**
 * Kugou (酷狗音乐) source implementation
 */
class KugouSource : MusicSourceProvider {
    override val sourceId = "kg"
    override val sourceName = "酷狗音乐"

    private fun normalizeCoverUrl(url: String?, size: Int = 400): String? {
        val raw = url?.takeIf { it.isNotBlank() } ?: return null
        val sized = raw.replace("{size}", size.toString())
        return if (sized.startsWith("http://")) sized.replaceFirst("http://", "https://") else sized
    }

    private fun parseCoverUrl(item: JsonObject, size: Int = 400): String? {
        val transParam = item["trans_param"]?.jsonObject
        return normalizeCoverUrl(
            item["album_sizable_cover"]?.jsonPrimitive?.content
                ?: transParam?.get("union_cover")?.jsonPrimitive?.content
                ?: item["img"]?.jsonPrimitive?.content
                ?: item["imgurl"]?.jsonPrimitive?.content,
            size,
        )
    }

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=$encodedKeyword&page=$page&pagesize=$limit&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1"

        val response: HttpResponse = ChinaMusicApi.httpClient.get(url)
        val bodyText = response.bodyAsText()
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(bodyText).jsonObject

        val errorCode = json["error_code"]?.jsonPrimitive?.intOrNull ?: -1
        if (errorCode != 0) {
            throw Exception("Kugou search error: code=$errorCode")
        }

        val data = json["data"]?.jsonObject ?: throw Exception("No data in response")
        val total = data["total"]?.jsonPrimitive?.intOrNull ?: 0
        val lists = data["lists"]?.jsonArray ?: JsonArray(emptyList())

        val seen = mutableSetOf<String>()
        val songs = mutableListOf<ChinaSong>()

        for (element in lists) {
            val item = element.jsonObject
            try {
                val song = parseSong(item)
                if (song != null) {
                    val key = "${song.songmid}${song.hash}"
                    if (key !in seen) {
                        seen.add(key)
                        songs.add(song)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("KugouSource").w(e, "Failed to parse song")
            }
        }

        // Batch fetch cover art from Kugou privilege API
        val songsWithCovers = fetchCovers(songs)

        val allPage = if (limit > 0) Math.ceil(total.toDouble() / limit).toInt() else 1

        return SearchResult(
            list = songsWithCovers,
            total = total,
            page = page,
            allPage = allPage,
            limit = limit,
            source = sourceId,
        )
    }

    private fun parseSong(item: JsonObject): ChinaSong? {
        val types = mutableListOf<SongQualityType>()

        val fileSize = item["FileSize"]?.jsonPrimitive?.longOrNull ?: 0
        val fileHash = item["FileHash"]?.jsonPrimitive?.content ?: ""
        if (fileSize > 0) {
            types.add(SongQualityType("128k", formatSize(fileSize), fileHash))
        }

        val hqFileSize = item["HQFileSize"]?.jsonPrimitive?.longOrNull ?: 0
        val hqFileHash = item["HQFileHash"]?.jsonPrimitive?.content ?: ""
        if (hqFileSize > 0) {
            types.add(SongQualityType("320k", formatSize(hqFileSize), hqFileHash))
        }

        val sqFileSize = item["SQFileSize"]?.jsonPrimitive?.longOrNull ?: 0
        val sqFileHash = item["SQFileHash"]?.jsonPrimitive?.content ?: ""
        if (sqFileSize > 0) {
            types.add(SongQualityType("flac", formatSize(sqFileSize), sqFileHash))
        }

        val resFileSize = item["ResFileSize"]?.jsonPrimitive?.longOrNull ?: 0
        val resFileHash = item["ResFileHash"]?.jsonPrimitive?.content ?: ""
        if (resFileSize > 0) {
            types.add(SongQualityType("flac24bit", formatSize(resFileSize), resFileHash))
        }

        val duration = item["Duration"]?.jsonPrimitive?.intOrNull ?: 0
        val audioId = item["Audioid"]?.jsonPrimitive?.content
            ?: item["AudioID"]?.jsonPrimitive?.content ?: return null

        return ChinaSong(
            name = decodeName(item["SongName"]?.jsonPrimitive?.content ?: ""),
            singer = decodeName(formatSingers(item["Singers"]?.jsonArray)),
            source = sourceId,
            songmid = audioId,
            albumName = decodeName(item["AlbumName"]?.jsonPrimitive?.content ?: ""),
            albumId = item["AlbumID"]?.jsonPrimitive?.content ?: "",
            interval = formatPlayTime(duration),
            durationSeconds = duration,
            hash = fileHash.ifEmpty { hqFileHash },
            types = types,
        )
    }

    override suspend fun searchSonglist(keyword: String, page: Int, limit: Int): SonglistSearchResult {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://msearchretry.kugou.com/api/v3/search/special?keyword=$encodedKeyword&page=$page&pagesize=$limit&showtype=10&filter=0&version=7910&sver=2"

        val response: HttpResponse = ChinaMusicApi.httpClient.get(url)
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject

        if (json["errcode"]?.jsonPrimitive?.intOrNull != 0) {
            return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        }

        val data = json["data"]?.jsonObject ?: return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        val infoArray = data["info"]?.jsonArray ?: JsonArray(emptyList())
        val total = data["total"]?.jsonPrimitive?.intOrNull ?: 0

        val items = infoArray.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val id = "id_" + (item["specialid"]?.jsonPrimitive?.content ?: return@mapNotNull null)
                SonglistItem(
                    id = id,
                    name = decodeName(item["specialname"]?.jsonPrimitive?.content ?: ""),
                    author = item["nickname"]?.jsonPrimitive?.content ?: "",
                    img = normalizeCoverUrl(item["imgurl"]?.jsonPrimitive?.content, 240),
                    playCount = item["playcount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
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
        val sort = sortId.toIntOrNull() ?: 5
        val url = "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_ajax=1&cdn=cdn&t=$sort&c=${categoryId ?: ""}&p=$page"
        val response: HttpResponse = ChinaMusicApi.httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            header("Referer", "https://www.kugou.com/")
        }
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = json["special_db"]?.jsonArray
            ?: json["data"]?.jsonArray
            ?: json["info"]?.jsonArray
            ?: JsonArray(emptyList())
        val items = data.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val id = item["specialid"]?.jsonPrimitive?.content
                    ?: item["special_id"]?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                SonglistItem(
                    id = "id_$id",
                    name = decodeName(item["specialname"]?.jsonPrimitive?.content ?: item["name"]?.jsonPrimitive?.content ?: ""),
                    author = item["nickname"]?.jsonPrimitive?.content ?: item["username"]?.jsonPrimitive?.content ?: "",
                    img = parseCoverUrl(item, 240),
                    playCount = item["playcount"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: item["play_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    source = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }
        val total = json["total"]?.jsonPrimitive?.intOrNull
            ?: json["count"]?.jsonPrimitive?.intOrNull
            ?: if (items.isNotEmpty()) 99999 else 0
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
        val response: HttpResponse = ChinaMusicApi.httpClient.get("http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_smarty=1&")
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["status"]?.jsonPrimitive?.intOrNull != 1) return emptyList()
        val tagids = json["data"]?.jsonObject?.get("tagids")?.jsonObject ?: return emptyList()
        return tagids.entries.filter { entry ->
            predicate(entry.key) || predicate(entry.value.jsonObject["pname"]?.jsonPrimitive?.content.orEmpty())
        }.flatMap { entry ->
            val group = entry.value
            group.jsonObject["data"]?.jsonArray?.mapNotNull { item ->
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SonglistTag(id, name, sourceId)
            } ?: emptyList()
        }
    }

    override suspend fun getSonglistDetail(id: String, page: Int, limit: Int): SonglistDetail {
        val specialId = id.removePrefix("id_")
        // Fetch info and songs in parallel
        val infoUrl = "http://mobilecdn.kugou.com/api/v3/special/info?specialid=$specialId&version=8830&appid=1005&area_code=1&encode_album_audio_id=1&userid=0"
        val songsUrl = "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$specialId&page=$page&pagesize=$limit&version=8830&appid=1005&area_code=1&encode_album_audio_id=1&userid=0"
        val headers: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("User-Agent", "Android712-AndroidPhone-11451-376-0-FeeCacheUpdate-wifi")
        }
        val infoResponse: HttpResponse = ChinaMusicApi.httpClient.get(infoUrl, headers)
        val songsResponse: HttpResponse = ChinaMusicApi.httpClient.get(songsUrl, headers)

        val infoJson = ChinaMusicApi.jsonParser.parseToJsonElement(infoResponse.bodyAsText()).jsonObject
        val songsJson = ChinaMusicApi.jsonParser.parseToJsonElement(songsResponse.bodyAsText()).jsonObject

        val infoData = infoJson["data"]?.jsonObject
        val songsData = songsJson["data"]?.jsonObject
        val songsArray = songsData?.get("info")?.jsonArray ?: JsonArray(emptyList())
        val total = songsData?.get("count")?.jsonPrimitive?.intOrNull ?: songsArray.size

        val songs = songsArray.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val hash = item["hash"]?.jsonPrimitive?.content
                if (hash.isNullOrBlank() || hash == "0") return@mapNotNull null
                val audioId = item["audio_id"]?.jsonPrimitive?.content
                    ?: item["encode_album_audio_id"]?.jsonPrimitive?.content
                    ?: hash
                val duration = item["duration"]?.jsonPrimitive?.intOrNull ?: 0

                // Parse filename "artist - title" format when separate fields missing
                val filename = item["filename"]?.jsonPrimitive?.content ?: ""
                val songname = item["songname"]?.jsonPrimitive?.content
                val singername = item["singername"]?.jsonPrimitive?.content
                val (parsedName, parsedSinger) = if (!songname.isNullOrBlank()) {
                    songname to (singername ?: "")
                } else if (filename.contains(" - ")) {
                    val parts = filename.split(" - ", limit = 2)
                    parts[1] to parts[0]
                } else {
                    filename to ""
                }

                ChinaSong(
                    name = decodeName(parsedName),
                    singer = decodeName(parsedSinger),
                    source = sourceId,
                    songmid = audioId,
                    albumName = item["album_name"]?.jsonPrimitive?.content ?: "",
                    albumId = item["album_id"]?.jsonPrimitive?.content ?: "",
                    interval = formatPlayTime(duration),
                    durationSeconds = duration,
                    hash = hash,
                )
            } catch (e: Exception) { null }
        }

        val coverImg = normalizeCoverUrl(
            infoData?.get("img")?.jsonPrimitive?.content
                ?: infoData?.get("imgurl")?.jsonPrimitive?.content,
            480,
        )
        val allPage = if (limit > 0 && total > 0) Math.ceil(total.toDouble() / limit).toInt() else 1

        return SonglistDetail(
            id = id,
            name = decodeName(infoData?.get("specialname")?.jsonPrimitive?.content ?: ""),
            author = infoData?.get("username")?.jsonPrimitive?.content
                ?: infoData?.get("nickname")?.jsonPrimitive?.content ?: "",
            img = coverImg,
            desc = infoData?.get("intro")?.jsonPrimitive?.content ?: "",
            songs = songs,
            total = total,
            page = page,
            allPage = allPage,
            source = sourceId,
        )
    }

    private fun formatSingers(singers: JsonArray?): String {
        if (singers == null) return ""
        return singers.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.joinToString("、")
    }

    private fun decodeName(name: String): String {
        return name
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#039;", "'")
            .replace("<em>", "")
            .replace("</em>", "")
    }

    override suspend fun enrichCovers(detail: SonglistDetail): SonglistDetail {
        val enriched = fetchCovers(detail.songs)
        return if (enriched != detail.songs) detail.copy(songs = enriched) else detail
    }

    private suspend fun fetchCovers(songs: List<ChinaSong>): List<ChinaSong> {
        if (songs.isEmpty()) return songs
        try {
            val resourceArray = buildJsonArray {
                songs.forEach { song ->
                    addJsonObject {
                        put("album_audio_id", song.songmid)
                        put("album_id", song.albumId)
                        put("hash", song.hash ?: "")
                        put("id", 0)
                        put("name", "${song.singer} - ${song.name}.mp3")
                        put("type", "audio")
                    }
                }
            }
            val requestBody = buildJsonObject {
                put("appid", 1001)
                put("area_code", "1")
                put("behavior", "play")
                put("clientver", "9020")
                put("need_hash_offset", 1)
                put("relate", 1)
                put("resource", resourceArray)
                put("token", "")
                put("userid", 2626431536)
                put("vip", 1)
            }
            val response = ChinaMusicApi.httpClient.post("http://media.store.kugou.com/v1/get_res_privilege") {
                header("KG-RC", "1")
                header("KG-THash", "expand_search_manager.cpp:852736169:451")
                header("User-Agent", "KuGou2012-9020-ExpandSearchManager")
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }
            val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
            if (json["error_code"]?.jsonPrimitive?.intOrNull != 0) return songs
            val dataArray = json["data"]?.jsonArray ?: return songs

            return songs.mapIndexed { index, song ->
                try {
                    val info = dataArray.getOrNull(index)?.jsonObject?.get("info")?.jsonObject
                    val imgSizes = info?.get("imgsize")?.jsonArray
                    val imageTemplate = info?.get("image")?.jsonPrimitive?.content
                    val img = if (imageTemplate != null && imgSizes != null && imgSizes.isNotEmpty()) {
                        imageTemplate.replace("{size}", imgSizes[0].jsonPrimitive.content)
                    } else imageTemplate
                    normalizeCoverUrl(img)?.let { song.copy(img = it) } ?: song
                } catch (e: Exception) {
                    song
                }
            }
        } catch (e: Exception) {
            Timber.tag("KugouSource").w(e, "Failed to fetch covers")
            return songs
        }
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
        LeaderboardItem("kg__8888", "TOP500", "8888", sourceId),
        LeaderboardItem("kg__6666", "飙升榜", "6666", sourceId),
        LeaderboardItem("kg__59703", "蜂鸟流行音乐榜", "59703", sourceId),
        LeaderboardItem("kg__52144", "抖音热歌榜", "52144", sourceId),
        LeaderboardItem("kg__52767", "快手热歌榜", "52767", sourceId),
        LeaderboardItem("kg__24971", "DJ热歌榜", "24971", sourceId),
        LeaderboardItem("kg__23784", "网络红歌榜", "23784", sourceId),
        LeaderboardItem("kg__44412", "说唱先锋榜", "44412", sourceId),
        LeaderboardItem("kg__31308", "内地榜", "31308", sourceId),
        LeaderboardItem("kg__33160", "电音榜", "33160", sourceId),
        LeaderboardItem("kg__31313", "香港地区榜", "31313", sourceId),
        LeaderboardItem("kg__51341", "民谣榜", "51341", sourceId),
        LeaderboardItem("kg__54848", "台湾地区榜", "54848", sourceId),
        LeaderboardItem("kg__31310", "欧美榜", "31310", sourceId),
        LeaderboardItem("kg__33162", "ACG新歌榜", "33162", sourceId),
        LeaderboardItem("kg__31311", "韩国榜", "31311", sourceId),
        LeaderboardItem("kg__31312", "日本榜", "31312", sourceId),
        LeaderboardItem("kg__49225", "80后热歌榜", "49225", sourceId),
        LeaderboardItem("kg__49223", "90后热歌榜", "49223", sourceId),
        LeaderboardItem("kg__49224", "00后热歌榜", "49224", sourceId),
        LeaderboardItem("kg__33165", "粤语金曲榜", "33165", sourceId),
        LeaderboardItem("kg__33166", "欧美金曲榜", "33166", sourceId),
        LeaderboardItem("kg__33163", "影视金曲榜", "33163", sourceId),
        LeaderboardItem("kg__51340", "伤感榜", "51340", sourceId),
        LeaderboardItem("kg__35811", "会员专享榜", "35811", sourceId),
        LeaderboardItem("kg__37361", "雷达榜", "37361", sourceId),
        LeaderboardItem("kg__21101", "分享榜", "21101", sourceId),
        LeaderboardItem("kg__46910", "综艺新歌榜", "46910", sourceId),
        LeaderboardItem("kg__30972", "酷狗音乐人原创榜", "30972", sourceId),
        LeaderboardItem("kg__60170", "闽南语榜", "60170", sourceId),
        LeaderboardItem("kg__65234", "儿歌榜", "65234", sourceId),
        LeaderboardItem("kg__4681", "美国BillBoard榜", "4681", sourceId),
        LeaderboardItem("kg__25028", "Beatport电子舞曲榜", "25028", sourceId),
        LeaderboardItem("kg__4680", "英国单曲榜", "4680", sourceId),
        LeaderboardItem("kg__38623", "韩国Melon音乐榜", "38623", sourceId),
        LeaderboardItem("kg__42807", "joox本地热歌榜", "42807", sourceId),
        LeaderboardItem("kg__36107", "小语种热歌榜", "36107", sourceId),
        LeaderboardItem("kg__4673", "日本公信榜", "4673", sourceId),
        LeaderboardItem("kg__46868", "日本SPACE SHOWER榜", "46868", sourceId),
        LeaderboardItem("kg__42808", "KKBOX风云榜", "42808", sourceId),
        LeaderboardItem("kg__60171", "越南语榜", "60171", sourceId),
        LeaderboardItem("kg__60172", "泰语榜", "60172", sourceId),
        LeaderboardItem("kg__59895", "R&B榜", "59895", sourceId),
        LeaderboardItem("kg__59896", "摇滚榜", "59896", sourceId),
        LeaderboardItem("kg__59897", "爵士榜", "59897", sourceId),
        LeaderboardItem("kg__59898", "乡村音乐榜", "59898", sourceId),
        LeaderboardItem("kg__59900", "纯音乐榜", "59900", sourceId),
        LeaderboardItem("kg__59899", "古典榜", "59899", sourceId),
        LeaderboardItem("kg__22603", "5sing音乐榜", "22603", sourceId),
        LeaderboardItem("kg__21335", "繁星音乐榜", "21335", sourceId),
        LeaderboardItem("kg__33161", "古风新歌榜", "33161", sourceId),
    )

    override suspend fun getBoardSongs(bangid: String, page: Int, limit: Int): BoardSongsResult {
        val url = "http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=1&plat=0&pagesize=$limit&area_code=1&page=$page&rankid=$bangid&with_res_tag=0"
        val response = ChinaMusicApi.httpClient.get(url)
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = json["data"]?.jsonObject ?: return BoardSongsResult(emptyList(), 0, page, limit, sourceId)
        val total = data["total"]?.jsonPrimitive?.intOrNull ?: 0
        val info = data["info"]?.jsonArray ?: JsonArray(emptyList())
        val songs = info.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val audioId = item["audio_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val songName = item["songname"]?.jsonPrimitive?.content ?: ""
                val authors = item["authors"]?.jsonArray
                val singer = authors?.mapNotNull { it.jsonObject["author_name"]?.jsonPrimitive?.content }?.joinToString("、") ?: ""
                val albumName = item["remark"]?.jsonPrimitive?.content ?: ""
                val albumId = item["album_id"]?.jsonPrimitive?.content ?: ""
                val duration = item["duration"]?.jsonPrimitive?.intOrNull ?: 0
                val hash = item["hash"]?.jsonPrimitive?.content
                ChinaSong(
                    name = songName,
                    singer = singer,
                    source = sourceId,
                    songmid = audioId,
                    albumName = albumName,
                    albumId = albumId,
                    interval = formatPlayTime(duration),
                    durationSeconds = duration,
                    hash = hash,
                    img = parseCoverUrl(item),
                )
            } catch (e: Exception) { null }
        }
        val songsWithCovers = if (songs.any { it.img.isNullOrBlank() }) fetchCovers(songs) else songs
        return BoardSongsResult(songsWithCovers, total, page, limit, sourceId)
    }
}
