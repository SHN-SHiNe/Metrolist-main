package com.metrolist.chinamusic.source

import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import com.metrolist.chinamusic.logging.MusicLog as Timber
import java.net.URLEncoder
import kotlin.math.ceil

/**
 * Kuwo (酷我音乐) source implementation
 */
class KuwoSource : MusicSourceProvider {
    override val sourceId = "kw"
    override val sourceName = "酷我音乐"

    private val mInfoRegex = Regex("""level:(\w+),bitrate:(\d+),format:(\w+),size:([\w.]+)""")

    private fun normalizeCoverUrl(url: String?): String? {
        val raw = url?.takeIf { it.isNotBlank() } ?: return null
        return if (raw.startsWith("//")) "http:$raw" else raw
    }

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://search.kuwo.cn/r.s?client=kt&all=$encodedKeyword&pn=${page - 1}&rn=$limit&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"

        val response: HttpResponse = ChinaMusicApi.httpClient.get(url)
        val bodyText = response.bodyAsText()
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(bodyText).jsonObject

        val total = json["TOTAL"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val absList = json["abslist"]?.jsonArray ?: JsonArray(emptyList())

        val songs = absList.mapNotNull { element ->
            try {
                parseSong(element.jsonObject)
            } catch (e: Exception) {
                Timber.tag("KuwoSource").w(e, "Failed to parse song")
                null
            }
        }

        // Batch fetch cover art URLs in parallel
        val songsWithCovers = coroutineScope {
            songs.map { song ->
                async {
                    try {
                        val picUrl = "https://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=${song.songmid}"
                        val picResponse = ChinaMusicApi.httpClient.get(picUrl)
                        val body = picResponse.bodyAsText().trim()
                        normalizeCoverUrl(body)?.let { song.copy(img = it) } ?: song
                    } catch (e: Exception) {
                        song
                    }
                }
            }.awaitAll()
        }

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

    override suspend fun searchSonglist(keyword: String, page: Int, limit: Int): SonglistSearchResult {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://search.kuwo.cn/r.s?all=$encodedKeyword&pn=${page - 1}&rn=$limit&rformat=json&encoding=utf8&ver=mbox&vipver=MUSIC_8.7.7.0_BCS37&plat=pc&devid=28156413&ft=playlist&pay=0&needliveshow=0"

        val response: HttpResponse = ChinaMusicApi.httpClient.get(url)
        val bodyText = response.bodyAsText().let { text ->
            // Kuwo returns single-quoted JS object notation; convert to valid JSON
            text.replace("'", "\"")
        }
        val json = try {
            ChinaMusicApi.jsonParser.parseToJsonElement(bodyText).jsonObject
        } catch (e: Exception) {
            return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        }

        val abslist = json["abslist"]?.jsonArray ?: JsonArray(emptyList())
        val total = json["TOTAL"]?.jsonPrimitive?.content?.toIntOrNull() ?: abslist.size

        val items = abslist.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val id = item["playlistid"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SonglistItem(
                    id = id,
                    name = decodeName(item["name"]?.jsonPrimitive?.content ?: ""),
                    author = decodeName(item["nickname"]?.jsonPrimitive?.content ?: ""),
                    img = normalizeCoverUrl(item["pic"]?.jsonPrimitive?.content),
                    playCount = item["playcnt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    source = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }

        val allPage = if (limit > 0) ceil(total.toDouble() / limit).toInt() else 1
        return SonglistSearchResult(items, total, page, allPage, limit, sourceId)
    }

    override suspend fun getSonglistsByTag(sortId: String, tagId: String, page: Int, limit: Int): SonglistSearchResult {
        val parts = tagId.split("-")
        val id = parts.getOrNull(0).orEmpty().takeIf { it.toIntOrNull() != null }
        val digest = parts.getOrNull(1)
        val url = if (id != null && digest == "10000") {
            "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=$page&id=$id&rn=$limit"
        } else {
            "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=$page&rn=$limit&order=${sortId.ifBlank { "hot" }}"
        }

        val response: HttpResponse = ChinaMusicApi.httpClient.get(url)
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["code"]?.jsonPrimitive?.intOrNull != 200) {
            return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        }
        val data = json["data"]?.jsonObject ?: return SonglistSearchResult(emptyList(), 0, page, 1, limit, sourceId)
        val list = data["data"]?.jsonArray ?: JsonArray(emptyList())
        val total = data["total"]?.jsonPrimitive?.intOrNull ?: list.size
        val items = list.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val itemId = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val itemDigest = item["digest"]?.jsonPrimitive?.content ?: "8"
                SonglistItem(
                    id = "digest-${itemDigest}__${itemId}",
                    name = decodeName(item["name"]?.jsonPrimitive?.content ?: ""),
                    author = decodeName(item["uname"]?.jsonPrimitive?.content ?: ""),
                    img = normalizeCoverUrl(item["img"]?.jsonPrimitive?.content),
                    playCount = item["listencnt"]?.jsonPrimitive?.longOrNull ?: 0,
                    source = sourceId,
                )
            } catch (e: Exception) {
                null
            }
        }
        val allPage = if (limit > 0) ceil(total.toDouble() / limit).toInt() else 1
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
        val url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagList?cmd=rcm_keyword_playlist&user=0&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0&source=kwplayer_pc_9.0.5.0&loginUid=0&loginSid=0&appUid=76039576"
        val response: HttpResponse = ChinaMusicApi.httpClient.get(url)
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["code"]?.jsonPrimitive?.intOrNull != 200) return emptyList()
        val groups = json["data"]?.jsonArray ?: JsonArray(emptyList())
        return groups.filter { group ->
            predicate(group.jsonObject["name"]?.jsonPrimitive?.content.orEmpty())
        }.flatMap { group ->
            group.jsonObject["data"]?.jsonArray?.mapNotNull { item ->
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val digest = obj["digest"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SonglistTag("$id-$digest", name, sourceId)
            } ?: emptyList()
        }
    }

    override suspend fun getSonglistDetail(id: String, page: Int, limit: Int): SonglistDetail {
        var playlistId = id
        if (playlistId.startsWith("digest-")) {
            val parts = playlistId.split("__", limit = 2)
            val digest = parts.getOrNull(0)?.removePrefix("digest-").orEmpty()
            playlistId = parts.getOrNull(1).orEmpty()
            if (digest != "8" && playlistId.isNotBlank()) {
                val infoUrl = "http://qukudata.kuwo.cn/q.k?op=query&cont=ninfo&node=$playlistId&pn=0&rn=1&fmt=json&src=mbox&level=2"
                val infoJson = ChinaMusicApi.jsonParser.parseToJsonElement(ChinaMusicApi.httpClient.get(infoUrl).bodyAsText()).jsonObject
                playlistId = infoJson["child"]?.jsonArray?.firstOrNull()?.jsonObject?.get("sourceid")?.jsonPrimitive?.content ?: playlistId
            }
        } else if (playlistId.contains("/") || playlistId.contains("?") || playlistId.contains("&") || playlistId.contains(":")) {
            playlistId = Regex("""(?:playlist(?:_detail)?/|[?&]playlistId=|[?&]id=)(\d+)""").find(playlistId)?.groupValues?.getOrNull(1) ?: playlistId
        }
        val url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$playlistId&pn=${page - 1}&rn=1000&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
        val response: HttpResponse = ChinaMusicApi.httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["result"]?.jsonPrimitive?.content != "ok") {
            return SonglistDetail(id, "", songs = emptyList(), total = 0, page = page, allPage = 1, source = sourceId)
        }

        val total = json["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val musiclist = json["musiclist"]?.jsonArray ?: JsonArray(emptyList())
        val songs = musiclist.mapNotNull { element ->
            try { parseDetailSong(element.jsonObject) } catch (e: Exception) { null }
        }
        val allPage = if (limit > 0) ceil(total.toDouble() / limit).toInt() else 1

        return SonglistDetail(
            id = id,
            name = decodeName(json["title"]?.jsonPrimitive?.content ?: ""),
            author = decodeName(json["uname"]?.jsonPrimitive?.content ?: ""),
            img = normalizeCoverUrl(json["pic"]?.jsonPrimitive?.content),
            desc = decodeName(json["info"]?.jsonPrimitive?.content ?: ""),
            songs = songs,
            total = total,
            page = page,
            allPage = allPage,
            source = sourceId,
        )
    }

    private fun parseDetailSong(info: JsonObject): ChinaSong? {
        val songId = info["id"]?.jsonPrimitive?.content ?: return null
        val nMinfo = info["N_MINFO"]?.jsonPrimitive?.content ?: ""
        val types = mutableListOf<SongQualityType>()
        nMinfo.split(";").forEach { segment ->
            val match = mInfoRegex.find(segment) ?: return@forEach
            val bitrate = match.groupValues[2]
            val size = match.groupValues[4]
            when (bitrate) {
                "4000" -> types.add(SongQualityType("flac24bit", size))
                "2000" -> types.add(SongQualityType("flac", size))
                "320" -> types.add(SongQualityType("320k", size))
                "128" -> types.add(SongQualityType("128k", size))
            }
        }
        types.reverse()
        val duration = info["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return ChinaSong(
            name = decodeName(info["name"]?.jsonPrimitive?.content ?: ""),
            singer = formatSinger(decodeName(info["artist"]?.jsonPrimitive?.content ?: "")),
            source = sourceId,
            songmid = songId,
            albumName = decodeName(info["album"]?.jsonPrimitive?.content ?: ""),
            albumId = info["albumid"]?.jsonPrimitive?.content ?: "",
            interval = formatPlayTime(duration),
            durationSeconds = duration,
            types = types,
        )
    }

    override suspend fun enrichCovers(detail: SonglistDetail): SonglistDetail {
        val enriched = fetchKuwoCovers(detail.songs)
        return if (enriched != detail.songs) detail.copy(songs = enriched) else detail
    }

    private suspend fun fetchKuwoCovers(songs: List<ChinaSong>): List<ChinaSong> {
        if (songs.isEmpty()) return songs
        return coroutineScope {
            songs.map { song ->
                async {
                    try {
                        val picUrl = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=${song.songmid}"
                        val imgUrl = ChinaMusicApi.httpClient.get(picUrl).bodyAsText().trim()
                        normalizeCoverUrl(imgUrl)?.let { song.copy(img = it) } ?: song
                    } catch (e: Exception) {
                        song
                    }
                }
            }.awaitAll()
        }
    }

    private fun parseSong(info: JsonObject): ChinaSong? {
        val musicRid = info["MUSICRID"]?.jsonPrimitive?.content ?: return null
        val songId = musicRid.replace("MUSIC_", "")
        val nMinfo = info["N_MINFO"]?.jsonPrimitive?.content ?: return null

        val types = mutableListOf<SongQualityType>()
        nMinfo.split(";").forEach { segment ->
            val match = mInfoRegex.find(segment) ?: return@forEach
            val bitrate = match.groupValues[2]
            val size = match.groupValues[4]
            when (bitrate) {
                "4000" -> types.add(SongQualityType("flac24bit", size))
                "2000" -> types.add(SongQualityType("flac", size))
                "320" -> types.add(SongQualityType("320k", size))
                "128" -> types.add(SongQualityType("128k", size))
            }
        }
        types.reverse()

        val duration = info["DURATION"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        return ChinaSong(
            name = decodeName(info["SONGNAME"]?.jsonPrimitive?.content ?: ""),
            singer = formatSinger(decodeName(info["ARTIST"]?.jsonPrimitive?.content ?: "")),
            source = sourceId,
            songmid = songId,
            albumName = decodeName(info["ALBUM"]?.jsonPrimitive?.content ?: ""),
            albumId = info["ALBUMID"]?.jsonPrimitive?.content ?: "",
            interval = formatPlayTime(duration),
            durationSeconds = duration,
            types = types,
        )
    }

    private fun decodeName(name: String): String {
        return name
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
    }

    private fun formatSinger(singer: String): String {
        return singer.replace("&", "、")
    }

    private fun formatPlayTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return "%02d:%02d".format(min, sec)
    }

    override suspend fun getBoards(): List<LeaderboardItem> = listOf(
        LeaderboardItem("kw__93", "飙升榜", "93", sourceId),
        LeaderboardItem("kw__17", "新歌榜", "17", sourceId),
        LeaderboardItem("kw__16", "热歌榜", "16", sourceId),
        LeaderboardItem("kw__158", "抖音热歌榜", "158", sourceId),
        LeaderboardItem("kw__292", "铃声榜", "292", sourceId),
        LeaderboardItem("kw__284", "热评榜", "284", sourceId),
        LeaderboardItem("kw__290", "ACG新歌榜", "290", sourceId),
        LeaderboardItem("kw__286", "台湾KKBOX榜", "286", sourceId),
        LeaderboardItem("kw__279", "冬日暖心榜", "279", sourceId),
        LeaderboardItem("kw__281", "巴士随身听榜", "281", sourceId),
        LeaderboardItem("kw__255", "KTV点唱榜", "255", sourceId),
        LeaderboardItem("kw__280", "家务进行曲榜", "280", sourceId),
        LeaderboardItem("kw__282", "熬夜修仙榜", "282", sourceId),
        LeaderboardItem("kw__283", "枕边轻音乐榜", "283", sourceId),
        LeaderboardItem("kw__278", "古风音乐榜", "278", sourceId),
        LeaderboardItem("kw__264", "Vlog音乐榜", "264", sourceId),
        LeaderboardItem("kw__242", "电音榜", "242", sourceId),
        LeaderboardItem("kw__187", "流行趋势榜", "187", sourceId),
        LeaderboardItem("kw__204", "现场音乐榜", "204", sourceId),
        LeaderboardItem("kw__186", "ACG神曲榜", "186", sourceId),
        LeaderboardItem("kw__185", "最强翻唱榜", "185", sourceId),
        LeaderboardItem("kw__26", "经典怀旧榜", "26", sourceId),
        LeaderboardItem("kw__104", "华语榜", "104", sourceId),
        LeaderboardItem("kw__182", "粤语榜", "182", sourceId),
        LeaderboardItem("kw__22", "欧美榜", "22", sourceId),
        LeaderboardItem("kw__184", "韩语榜", "184", sourceId),
        LeaderboardItem("kw__183", "日语榜", "183", sourceId),
        LeaderboardItem("kw__145", "会员畅听榜", "145", sourceId),
        LeaderboardItem("kw__153", "网红新歌榜", "153", sourceId),
        LeaderboardItem("kw__64", "影视金曲榜", "64", sourceId),
        LeaderboardItem("kw__176", "DJ嗨歌榜", "176", sourceId),
        LeaderboardItem("kw__106", "真声音", "106", sourceId),
        LeaderboardItem("kw__12", "Billboard榜", "12", sourceId),
        LeaderboardItem("kw__49", "iTunes音乐榜", "49", sourceId),
        LeaderboardItem("kw__180", "beatport电音榜", "180", sourceId),
        LeaderboardItem("kw__13", "英国UK榜", "13", sourceId),
        LeaderboardItem("kw__164", "百大DJ榜", "164", sourceId),
        LeaderboardItem("kw__246", "海外音乐排行榜", "246", sourceId),
        LeaderboardItem("kw__265", "韩国Genie榜", "265", sourceId),
        LeaderboardItem("kw__14", "韩国M-net榜", "14", sourceId),
        LeaderboardItem("kw__8", "香港电台榜", "8", sourceId),
        LeaderboardItem("kw__15", "日本公信榜", "15", sourceId),
        LeaderboardItem("kw__151", "腾讯音乐人原创榜", "151", sourceId),
    )

    override suspend fun getBoardSongs(bangid: String, page: Int, limit: Int): BoardSongsResult {
        val url = "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=${page - 1}&rn=$limit&type=bang&data=content&id=$bangid&show_copyright_off=0&pcmp4=1&isbang=1"
        val response = ChinaMusicApi.httpClient.get(url)
        val bodyText = response.bodyAsText()
        val json = ChinaMusicApi.jsonParser.parseToJsonElement(bodyText).jsonObject
        val musicList = json["musiclist"]?.jsonArray ?: JsonArray(emptyList())
        val total = json["num"]?.jsonPrimitive?.content?.toIntOrNull() ?: musicList.size
        val songs = musicList.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = decodeName(item["name"]?.jsonPrimitive?.content ?: "")
                val artist = decodeName(item["artist"]?.jsonPrimitive?.content ?: "")
                val albumName = decodeName(item["album"]?.jsonPrimitive?.content ?: "")
                val albumId = item["albumid"]?.jsonPrimitive?.content ?: ""
                val duration = item["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                ChinaSong(
                    name = name,
                    singer = formatSinger(artist),
                    source = sourceId,
                    songmid = id,
                    albumName = albumName,
                    albumId = albumId,
                    interval = formatPlayTime(duration),
                    durationSeconds = duration,
                    img = normalizeCoverUrl(item["pic"]?.jsonPrimitive?.content),
                )
            } catch (e: Exception) { null }
        }
        val songsWithCovers = if (songs.any { it.img.isNullOrBlank() }) fetchKuwoCovers(songs) else songs
        return BoardSongsResult(songsWithCovers, total, page, limit, sourceId)
    }
}
