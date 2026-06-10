package com.metrolist.chinamusic.source

import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import timber.log.Timber
import java.security.MessageDigest

/**
 * Migu (咪咕音乐) search implementation
 */
class MiguSource : MusicSourceProvider {
    override val sourceId = "mg"
    override val sourceName = "咪咕音乐"

    companion object {
        private const val TAG = "MiguSource"
        private const val DEVICE_ID = "963B7AA0D21511ED807EE5846EC87D20"
        private const val SIGNATURE_MD5 = "6cdc72a439cef99a3418d2a78aa28c73"
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun createSignature(time: String, keyword: String): String {
        return md5("${keyword}${SIGNATURE_MD5}yyapp2d16148780a1dcc7408e06336b98cfd50${DEVICE_ID}${time}")
    }

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        val time = System.currentTimeMillis().toString()
        val sign = createSignature(time, keyword)

        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll" +
            "?isCorrect=0&isCopyright=1" +
            "&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0%2C%22lyricSong%22%3A0%7D" +
            "&pageSize=$limit&text=$encodedKeyword&pageNo=$page&sort=0&sid=USS"

        Timber.tag(TAG).d("Searching: $keyword, page=$page")

        val response: HttpResponse = ChinaMusicApi.httpClient.get(url) {
            header("uiVersion", "A_music_3.6.1")
            header("deviceId", DEVICE_ID)
            header("timestamp", time)
            header("sign", sign)
            header("channel", "0146921")
            header("User-Agent", "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30")
        }

        val body = response.bodyAsText()
        val jsonObj = ChinaMusicApi.jsonParser.parseToJsonElement(body).jsonObject

        val code = jsonObj["code"]?.jsonPrimitive?.content
        if (code != "000000") {
            val info = jsonObj["info"]?.jsonPrimitive?.content ?: "搜索失败"
            throw Exception(info)
        }

        val songResultData = jsonObj["songResultData"]?.jsonObject
        val resultList = songResultData?.get("resultList")?.jsonArray ?: JsonArray(emptyList())
        val totalCount = songResultData?.get("totalCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val ids = mutableSetOf<String>()
        val songs = mutableListOf<ChinaSong>()

        for (group in resultList) {
            val items = group.jsonArray
            for (element in items) {
                val item = element.jsonObject
                val songId = item["songId"]?.jsonPrimitive?.content ?: continue
                val copyrightId = item["copyrightId"]?.jsonPrimitive?.content ?: continue

                if (ids.contains(copyrightId)) continue
                ids.add(copyrightId)

                val name = item["name"]?.jsonPrimitive?.content ?: ""
                val singerList = item["singerList"]?.jsonArray
                val singer = singerList?.joinToString("、") {
                    it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                } ?: ""

                val albumName = item["album"]?.jsonPrimitive?.content ?: ""
                val albumId = item["albumId"]?.jsonPrimitive?.content ?: ""
                val duration = item["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

                // Image
                var img = item["img3"]?.jsonPrimitive?.content
                    ?: item["img2"]?.jsonPrimitive?.content
                    ?: item["img1"]?.jsonPrimitive?.content
                if (img != null && !img.startsWith("http")) {
                    img = "http://d.musicapp.migu.cn$img"
                }

                // Quality types
                val types = mutableListOf<SongQualityType>()
                val audioFormats = item["audioFormats"]?.jsonArray
                audioFormats?.forEach { format ->
                    val formatObj = format.jsonObject
                    val formatType = formatObj["formatType"]?.jsonPrimitive?.content ?: return@forEach
                    val size = formatObj["asize"]?.jsonPrimitive?.content
                        ?: formatObj["isize"]?.jsonPrimitive?.content
                    when (formatType) {
                        "PQ" -> types.add(SongQualityType("128k", size))
                        "HQ" -> types.add(SongQualityType("320k", size))
                        "SQ" -> types.add(SongQualityType("flac", size))
                        "ZQ24" -> types.add(SongQualityType("flac24bit", size))
                    }
                }

                songs.add(
                    ChinaSong(
                        name = name,
                        singer = singer,
                        source = "mg",
                        songmid = songId,
                        albumName = albumName,
                        albumId = albumId,
                        durationSeconds = duration,
                        img = img,
                        copyrightId = copyrightId,
                        types = types,
                    )
                )
            }
        }

        val allPage = if (totalCount > 0) ((totalCount + limit - 1) / limit) else 1

        return SearchResult(
            list = songs,
            total = totalCount,
            page = page,
            allPage = allPage,
            limit = limit,
            source = "mg",
        )
    }

    override suspend fun searchSonglist(keyword: String, page: Int, limit: Int): SonglistSearchResult {
        val time = System.currentTimeMillis().toString()
        val sign = createSignature(time, keyword)
        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")

        val searchSwitch = java.net.URLEncoder.encode(
            """{"song":0,"album":0,"singer":0,"tagSong":0,"mvSong":0,"bestShow":0,"songlist":1,"lyricSong":0}""",
            "UTF-8"
        )
        val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll?isCorrect=1&isCopyright=1" +
            "&searchSwitch=$searchSwitch&pageSize=$limit&text=$encodedKeyword&pageNo=$page&sort=0&sid=USS"

        val response: HttpResponse = ChinaMusicApi.httpClient.get(url) {
            header("uiVersion", "A_music_3.6.1")
            header("deviceId", DEVICE_ID)
            header("timestamp", time)
            header("sign", sign)
            header("channel", "0146921")
            header("User-Agent", "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30")
        }

        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val songListData = json["songListResultData"]?.jsonObject
            ?: return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)

        val resultList = songListData["result"]?.jsonArray ?: JsonArray(emptyList())
        val total = songListData["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val items = resultList.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                var img = item["musicListPicUrl"]?.jsonPrimitive?.content
                if (img != null && !img.startsWith("http")) img = "http://d.musicapp.migu.cn$img"
                SonglistItem(
                    id = id,
                    name = item["name"]?.jsonPrimitive?.content ?: "",
                    author = item["userName"]?.jsonPrimitive?.content ?: "",
                    img = img,
                    playCount = item["playNum"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
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
        val miguHeaders: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
            header("Referer", "https://m.music.migu.cn/")
        }
        val url = if (tagId.toLongOrNull() != null) {
            "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-listbytag/release?pageNumber=$page&templateVersion=2&tagId=$tagId"
        } else {
            "https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0?templateVersion=2&pageNo=$page"
        }
        val response: HttpResponse = ChinaMusicApi.httpClient.get(url, miguHeaders)
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["code"]?.jsonPrimitive?.content != "000000") {
            return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        }
        val data = json["data"]?.jsonObject ?: return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        val items = mutableListOf<SonglistItem>()
        fun collectContents(array: JsonArray) {
            array.forEach { element ->
                val item = element.jsonObject
                val children = item["contents"]?.jsonArray
                if (children != null) {
                    collectContents(children)
                    return@forEach
                }
                val resId = item["resId"]?.jsonPrimitive?.content ?: return@forEach
                if (item["resType"]?.jsonPrimitive?.content == "2021") {
                    items.add(
                        SonglistItem(
                            id = resId,
                            name = item["txt"]?.jsonPrimitive?.content ?: "",
                            author = "",
                            img = item["img"]?.jsonPrimitive?.content,
                            playCount = 0,
                            source = sourceId,
                        )
                    )
                }
            }
        }
        val contents = data["contents"]?.jsonArray
        if (contents != null) {
            collectContents(contents)
        } else {
            val contentList = data["contentItemList"]?.jsonArray
            val itemList = contentList?.getOrNull(1)?.jsonObject?.get("itemList")?.jsonArray ?: JsonArray(emptyList())
            itemList.forEach { element ->
                val item = element.jsonObject
                val id = item["logEvent"]?.jsonObject?.get("contentId")?.jsonPrimitive?.content ?: return@forEach
                items.add(
                    SonglistItem(
                        id = id,
                        name = item["title"]?.jsonPrimitive?.content ?: "",
                        author = "",
                        img = item["imageUrl"]?.jsonPrimitive?.content,
                        playCount = 0,
                        source = sourceId,
                    )
                )
            }
        }
        val total = if (items.isNotEmpty()) 99999 else 0
        return SonglistSearchResult(items, total, page, 99999, limit, sourceId)
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
        val headers: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
            header("Referer", "https://m.music.migu.cn/")
        }
        val response: HttpResponse = ChinaMusicApi.httpClient.get("https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-taglist/release", headers)
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["code"]?.jsonPrimitive?.content != "000000") return emptyList()
        val groups = json["data"]?.jsonArray ?: JsonArray(emptyList())
        return groups.filter { group ->
            predicate(group.jsonObject["header"]?.jsonObject?.get("title")?.jsonPrimitive?.content.orEmpty())
        }.flatMap { group ->
            group.jsonObject["content"]?.jsonArray?.mapNotNull { item ->
                val texts = item.jsonObject["texts"]?.jsonArray ?: return@mapNotNull null
                val name = texts.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null
                val id = texts.getOrNull(1)?.jsonPrimitive?.content ?: return@mapNotNull null
                SonglistTag(id, name, sourceId)
            } ?: emptyList()
        }
    }

    override suspend fun getSonglistDetail(id: String, page: Int, limit: Int): SonglistDetail {
        val miguHeaders: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
            header("Referer", "https://m.music.migu.cn/")
        }
        // Fetch playlist info
        val infoUrl = "https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0?playlistId=$id"
        val infoResponse: HttpResponse = ChinaMusicApi.httpClient.get(infoUrl, miguHeaders)
        val infoJson = ChinaMusicApi.jsonParser.parseToJsonElement(infoResponse.bodyAsText()).jsonObject
        val infoData = if (infoJson["code"]?.jsonPrimitive?.content == "000000") infoJson["data"]?.jsonObject else null

        // Fetch playlist songs
        val songsUrl = "https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0?pageNo=$page&pageSize=$limit&playlistId=$id"
        val songsResponse: HttpResponse = ChinaMusicApi.httpClient.get(songsUrl, miguHeaders)
        val songsJson = ChinaMusicApi.jsonParser.parseToJsonElement(songsResponse.bodyAsText()).jsonObject
        val songsData = if (songsJson["code"]?.jsonPrimitive?.content == "000000") songsJson["data"]?.jsonObject else null
        val songArray = songsData?.get("songList")?.jsonArray ?: JsonArray(emptyList())
        val total = songsData?.get("totalCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val songs = songArray.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val songId = item["songId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val copyrightId = item["copyrightId"]?.jsonPrimitive?.content ?: songId
                val duration = item["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val singerList = item["singerList"]?.jsonArray
                val singer = singerList?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }?.joinToString("、") ?: ""
                var img = item["img3"]?.jsonPrimitive?.content
                    ?: item["img2"]?.jsonPrimitive?.content
                    ?: item["img1"]?.jsonPrimitive?.content
                if (img != null && !img.startsWith("http")) img = "http://d.musicapp.migu.cn$img"
                ChinaSong(
                    name = item["songName"]?.jsonPrimitive?.content ?: "",
                    singer = singer,
                    source = sourceId,
                    songmid = copyrightId,
                    albumName = item["album"]?.jsonPrimitive?.content ?: "",
                    albumId = item["albumId"]?.jsonPrimitive?.content ?: "",
                    interval = formatPlayTime(duration),
                    durationSeconds = duration,
                    img = img,
                    copyrightId = copyrightId,
                )
            } catch (e: Exception) { null }
        }

        val coverImg = infoData?.get("imgItem")?.jsonObject?.get("img")?.jsonPrimitive?.content
        val allPage = if (limit > 0 && total > 0) Math.ceil(total.toDouble() / limit).toInt() else 1
        return SonglistDetail(
            id = id,
            name = infoData?.get("title")?.jsonPrimitive?.content ?: "",
            author = infoData?.get("ownerName")?.jsonPrimitive?.content ?: "",
            img = coverImg,
            desc = infoData?.get("summary")?.jsonPrimitive?.content ?: "",
            songs = songs,
            total = total,
            page = page,
            allPage = allPage,
            source = sourceId,
        )
    }

    private fun formatPlayTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return "%02d:%02d".format(min, sec)
    }

    override suspend fun getBoards(): List<LeaderboardItem> = listOf(
        LeaderboardItem("mg__27553319", "新歌榜", "27553319", sourceId),
        LeaderboardItem("mg__27186466", "热歌榜", "27186466", sourceId),
        LeaderboardItem("mg__27553408", "原创榜", "27553408", sourceId),
        LeaderboardItem("mg__75959118", "音乐风向榜", "75959118", sourceId),
        LeaderboardItem("mg__76557036", "彩铃分贝榜", "76557036", sourceId),
        LeaderboardItem("mg__76557745", "会员臻爱榜", "76557745", sourceId),
        LeaderboardItem("mg__23189800", "港台榜", "23189800", sourceId),
        LeaderboardItem("mg__23189399", "内地榜", "23189399", sourceId),
        LeaderboardItem("mg__19190036", "欧美榜", "19190036", sourceId),
        LeaderboardItem("mg__83176390", "国风金曲榜", "83176390", sourceId),
        LeaderboardItem("mg__23189813", "日韩榜", "23189813", sourceId),
        LeaderboardItem("mg__23190126", "彩铃榜", "23190126", sourceId),
        LeaderboardItem("mg__15140045", "KTV榜", "15140045", sourceId),
        LeaderboardItem("mg__15140034", "网络榜", "15140034", sourceId),
        LeaderboardItem("mg__23217754", "MV榜", "23217754", sourceId),
        LeaderboardItem("mg__23218151", "新专辑榜", "23218151", sourceId),
        LeaderboardItem("mg__21958042", "iTunes榜", "21958042", sourceId),
        LeaderboardItem("mg__21975570", "Billboard榜", "21975570", sourceId),
        LeaderboardItem("mg__22272815", "台湾Hito中文榜", "22272815", sourceId),
        LeaderboardItem("mg__22272904", "中国TOP排行榜", "22272904", sourceId),
        LeaderboardItem("mg__22272943", "韩国Melon榜", "22272943", sourceId),
        LeaderboardItem("mg__22273437", "英国UK榜", "22273437", sourceId),
    )

    override suspend fun getBoardSongs(bangid: String, page: Int, limit: Int): BoardSongsResult {
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do?columnId=$bangid&needAll=0"
        val response = ChinaMusicApi.httpClient.get(url) {
            header("Referer", "https://app.c.nf.migu.cn/")
            header("User-Agent", "Mozilla/5.0 (Linux; Android 5.1.1) AppleWebKit/537.36 Chrome/59.0.3071.115 Mobile Safari/537.36")
            header("channel", "0146921")
        }
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["code"]?.jsonPrimitive?.content != "000000") {
            return BoardSongsResult(emptyList(), 0, page, limit, sourceId)
        }
        val contents = json["columnInfo"]?.jsonObject?.get("contents")?.jsonArray ?: JsonArray(emptyList())
        val songs = contents.mapNotNull { element ->
            try {
                val objectInfo = element.jsonObject["objectInfo"]?.jsonObject ?: return@mapNotNull null
                val copyrightId = objectInfo["copyrightId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val songName = objectInfo["songName"]?.jsonPrimitive?.content ?: ""
                val singer = objectInfo["singer"]?.jsonPrimitive?.content ?: ""
                val albumName = objectInfo["album"]?.jsonPrimitive?.content ?: ""
                val albumId = objectInfo["albumId"]?.jsonPrimitive?.content ?: ""
                val img = objectInfo["albumImgs"]?.jsonArray?.firstOrNull()?.jsonObject?.get("img")?.jsonPrimitive?.content
                ChinaSong(
                    name = songName,
                    singer = singer,
                    source = sourceId,
                    songmid = copyrightId,
                    albumName = albumName,
                    albumId = albumId,
                    img = img,
                    copyrightId = copyrightId,
                )
            } catch (e: Exception) { null }
        }
        return BoardSongsResult(songs, songs.size, page, limit, sourceId)
    }
}
