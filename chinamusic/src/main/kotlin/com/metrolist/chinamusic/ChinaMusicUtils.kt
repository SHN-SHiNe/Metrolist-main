package com.metrolist.chinamusic

import com.metrolist.chinamusic.model.AudioQuality
import com.metrolist.chinamusic.model.ChinaSong
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.chinamusic.model.SongComment
import io.ktor.client.request.get
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Utility functions for Chinese music source integration
 */
object ChinaMusicUtils {
    private const val CHINA_PREFIX = "china_"
    private val qqSongIdCache = ConcurrentHashMap<String, String>()
    private val kugouHashCache = ConcurrentHashMap<String, String>()

    /**
     * Create a unique media ID for a Chinese source song.
     * Format: china_{source}_{songmid}
     * e.g., china_kw_12345, china_kg_abcdef
     */
    fun createMediaId(song: ChinaSong): String {
        return "${CHINA_PREFIX}${song.source}_${song.songmid}"
    }

    /**
     * Check if a media ID belongs to a Chinese music source
     */
    fun isChinaMediaId(mediaId: String): Boolean {
        return mediaId.startsWith(CHINA_PREFIX)
    }

    /**
     * Parse a Chinese media ID to extract source and song ID
     * Returns Triple(mediaId, source, songId) or null if not a valid China media ID
     */
    fun parseMediaId(mediaId: String): Triple<String, String, String>? {
        if (!isChinaMediaId(mediaId)) return null
        val parts = mediaId.removePrefix(CHINA_PREFIX).split("_", limit = 2)
        if (parts.size != 2) return null
        return Triple(mediaId, parts[0], parts[1])
    }

    /**
     * Extract source from a China media ID (e.g. "china_kw_12345" → "kw")
     */
    fun extractSource(mediaId: String): String? = parseMediaId(mediaId)?.second

    /**
     * Extract song ID from a China media ID (e.g. "china_kw_12345" → "12345")
     */
    fun extractSongId(mediaId: String): String? = parseMediaId(mediaId)?.third

    /**
     * Get the music URL for a Chinese source media ID, with cross-source fallback.
     * @param title Song title for fallback search (optional but recommended)
     * @param artist Song artist for fallback search (optional)
     * @param durationSeconds Song duration for fallback matching (0 = any)
     */
    suspend fun getMusicUrl(
        mediaId: String,
        quality: AudioQuality = AudioQuality.HIGH,
        title: String = "",
        artist: String = "",
        durationSeconds: Int = 0,
    ): Result<String> {
        val parsed = parseMediaId(mediaId)
            ?: return Result.failure(Exception("Not a Chinese source media ID: $mediaId"))
        val (_, source, songId) = parsed

        val chinaSong = ChinaSong(
            name = title,
            singer = artist,
            source = source,
            songmid = songId,
            durationSeconds = durationSeconds,
            hash = if (source == "kg") songId else null,
        )

        val primaryResult = ChinaMusicApi.getMusicUrlWithFallback(chinaSong, quality)
        if (primaryResult.isSuccess) return primaryResult

        if (title.isNotBlank()) {
            return ChinaMusicApi.findMusicFromOtherSources(
                name = title,
                singer = artist,
                durationSeconds = durationSeconds,
                excludeSourceId = source,
                quality = quality,
            )
        }
        return primaryResult
    }

    suspend fun getNeteaseComments(
        songId: String,
        limit: Int = 50,
        hot: Boolean = true,
    ): Result<List<SongComment>> = runCatching {
        val url = if (hot) {
            "https://music.163.com/api/v1/resource/hotcomments/R_SO_4_$songId?limit=$limit&offset=0"
        } else {
            "https://music.163.com/api/v1/resource/comments/R_SO_4_$songId?limit=$limit&offset=0"
        }
        val response = ChinaMusicApi.httpClient.get(url) {
            headers.append("Referer", "https://music.163.com")
            headers.append("User-Agent", "Mozilla/5.0")
        }
        val root = ChinaMusicApi.jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val list = if (hot) {
            root["hotComments"]?.jsonArray.orEmpty()
        } else {
            root["comments"]?.jsonArray.orEmpty()
        }
        list.mapNotNull { element ->
            val item = element.jsonObject
            val user = item["user"]?.jsonObject ?: return@mapNotNull null
            SongComment(
                id = item["commentId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                userName = user["nickname"]?.jsonPrimitive?.content ?: "",
                avatarUrl = user["avatarUrl"]?.jsonPrimitive?.content,
                content = item["content"]?.jsonPrimitive?.content ?: "",
                time = item["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                likedCount = item["likedCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
    }

    suspend fun getComments(
        mediaId: String,
        hot: Boolean = true,
        limit: Int = 50,
        title: String = "",
        artist: String = "",
        durationSeconds: Int = 0,
    ): Result<List<SongComment>> = runCatching {
        val (_, source, songId) = parseMediaId(mediaId)
            ?: throw IllegalArgumentException("Not a Chinese source media ID: $mediaId")
        when (source) {
            "wy" -> getNeteaseComments(songId, limit, hot).getOrThrow()
            "kw" -> getKuwoComments(songId, limit, hot)
            "kg" -> getKugouComments(resolveKugouHash(songId, title, artist, durationSeconds), limit, hot)
            "mg" -> getMiguComments(songId, limit, hot)
            "tx" -> getQQComments(resolveQQSongId(songId, title, artist, durationSeconds), limit, hot)
            else -> throw IllegalArgumentException("Unsupported comment source: $source")
        }
    }

    private suspend fun resolveQQSongId(songMid: String, title: String, artist: String, durationSeconds: Int): String {
        if (songMid.all { it.isDigit() }) return songMid
        qqSongIdCache[songMid]?.let { return it }
        if (title.isBlank()) return songMid
        val result = ChinaMusicApi.search(
            keyword = "$title $artist".trim(),
            page = 1,
            limit = 10,
            source = MusicSource.QQ,
        ).getOrNull()
        val resolved = result?.list
            ?.minByOrNull { candidate ->
                val durationScore = if (durationSeconds > 0) kotlin.math.abs(candidate.durationSeconds - durationSeconds) else 0
                val titlePenalty = if (candidate.name.equals(title, ignoreCase = true)) 0 else 1000
                durationScore + titlePenalty
            }
            ?.copyrightId
            ?.takeIf { it.isNotBlank() }
            ?: songMid
        Timber.tag("SongComments").d("resolve QQ songMid=$songMid title=$title resolved=$resolved")
        if (resolved.all { it.isDigit() }) {
            qqSongIdCache[songMid] = resolved
        }
        return resolved
    }

    private suspend fun resolveKugouHash(songIdOrHash: String, title: String, artist: String, durationSeconds: Int): String {
        if (songIdOrHash.length == 32) return songIdOrHash
        kugouHashCache[songIdOrHash]?.let { return it }
        if (title.isBlank()) return songIdOrHash
        val result = ChinaMusicApi.search(
            keyword = "$title $artist".trim(),
            page = 1,
            limit = 10,
            source = MusicSource.KUGOU,
        ).getOrNull()
        val resolved = result?.list
            ?.minByOrNull { candidate ->
                val durationScore = if (durationSeconds > 0) kotlin.math.abs(candidate.durationSeconds - durationSeconds) else 0
                val titlePenalty = if (candidate.name.equals(title, ignoreCase = true)) 0 else 1000
                durationScore + titlePenalty
            }
            ?.hash
            ?.takeIf { it.isNotBlank() }
            ?: songIdOrHash
        Timber.tag("SongComments").d("resolve Kugou idOrHash=$songIdOrHash title=$title resolved=$resolved")
        if (resolved.length == 32) {
            kugouHashCache[songIdOrHash] = resolved
        }
        return resolved
    }

    private suspend fun getKuwoComments(songId: String, limit: Int, hot: Boolean): List<SongComment> {
        val type = if (hot) "get_rec_comment" else "get_comment"
        val url = "http://ncomment.kuwo.cn/com.s?f=web&type=$type&aapiver=1&prod=kwplayer_ar_10.5.2.0&digest=15&sid=$songId&start=0&msgflag=1&count=$limit&newver=3&uid=0"
        val root = ChinaMusicApi.jsonParser.parseToJsonElement(
            ChinaMusicApi.httpClient.get(url) {
                headers.append("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 9;)")
            }.bodyAsText()
        ).jsonObject
        val code = root["code"]?.jsonPrimitive?.contentOrNull
        if (code != "200") throw IllegalStateException("获取评论失败")
        val list = if (hot) root["hot_comments"]?.jsonArray else root["comments"]?.jsonArray
        return list.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            SongComment(
                id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                userName = item["u_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                avatarUrl = item["u_pic"]?.jsonPrimitive?.contentOrNull,
                content = item["msg"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                time = item["time"]?.jsonPrimitive?.longOrNull ?: 0,
                likedCount = item["like_num"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    private suspend fun getMiguComments(songId: String, limit: Int, hot: Boolean): List<SongComment> {
        val queryType = if (hot) 2 else 1
        val extra = if (hot) "&hotCommentStart=0" else "&commentId="
        val url = "https://app.c.nf.migu.cn/MIGUM3.0/user/comment/stack/v1.0?pageSize=$limit&queryType=$queryType&resourceId=$songId&resourceType=2$extra"
        val root = ChinaMusicApi.jsonParser.parseToJsonElement(
            ChinaMusicApi.httpClient.get(url) {
                headers.append("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
            }.bodyAsText()
        ).jsonObject
        if (root["code"]?.jsonPrimitive?.contentOrNull != "000000") throw IllegalStateException("获取评论失败")
        val data = root["data"]?.jsonObject ?: return emptyList()
        val list = if (hot) data["hotComments"]?.jsonArray else data["comments"]?.jsonArray
        return list.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val user = item["user"]?.jsonObject
            SongComment(
                id = item["commentId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                userName = user?.get("nickName")?.jsonPrimitive?.contentOrNull.orEmpty(),
                avatarUrl = user?.get("middleIcon")?.jsonPrimitive?.contentOrNull
                    ?: user?.get("bigIcon")?.jsonPrimitive?.contentOrNull
                    ?: user?.get("smallIcon")?.jsonPrimitive?.contentOrNull,
                content = item["commentInfo"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                time = item["commentTime"]?.jsonPrimitive?.longOrNull ?: 0,
                likedCount = item["opNumItem"]?.jsonObject?.get("thumbNum")?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    private suspend fun getKugouComments(songId: String, limit: Int, hot: Boolean): List<SongComment> {
        val timestamp = System.currentTimeMillis()
        val params = "dfid=0&mid=16249512204336365674023395779019&clienttime=$timestamp&uuid=0&extdata=$songId&appid=1005&code=fc4be23b4e972707f36b8a828a93ba8a&schash=$songId&clientver=11409&p=1&clienttoken=&pagesize=$limit&ver=10&kugouid=0"
        val endpoint = if (hot) "topliked" else "newest"
        val signature = kugouSignature(params)
        val url = "http://m.comment.service.kugou.com/r/v1/rank/$endpoint?$params&signature=$signature"
        val root = ChinaMusicApi.jsonParser.parseToJsonElement(
            ChinaMusicApi.httpClient.get(url) {
                headers.append("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36")
            }.bodyAsText()
        ).jsonObject
        if (root["err_code"]?.jsonPrimitive?.intOrNull != 0) throw IllegalStateException("获取评论失败")
        return root["list"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            SongComment(
                id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                userName = item["user_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                avatarUrl = item["user_pic"]?.jsonPrimitive?.contentOrNull,
                content = item["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                time = item["addtime"]?.jsonPrimitive?.longOrNull ?: 0,
                likedCount = item["like"]?.jsonObject?.get("likenum")?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    private suspend fun getQQComments(songId: String, limit: Int, hot: Boolean): List<SongComment> {
        return if (hot) {
            if (!songId.all { it.isDigit() }) {
                Timber.tag("SongComments").w("QQ hot requires numeric songId, got=$songId")
                return emptyList()
            }
            val body = buildJsonObject {
                put("comm", buildJsonObject {
                    put("cv", 4747474)
                    put("ct", 24)
                    put("format", "json")
                    put("inCharset", "utf-8")
                    put("outCharset", "utf-8")
                    put("notice", 0)
                    put("platform", "yqq.json")
                    put("needNewCode", 1)
                    put("uin", 0)
                })
                put("req", buildJsonObject {
                    put("module", "music.globalComment.CommentRead")
                    put("method", "GetHotCommentList")
                    put("param", buildJsonObject {
                        put("BizType", 1)
                        put("BizId", songId)
                        put("LastCommentSeqNo", "")
                        put("PageSize", limit)
                        put("PageNum", 0)
                        put("HotType", 1)
                        put("WithAirborne", 0)
                        put("PicEnable", 1)
                    })
                })
            }
            val responseText = ChinaMusicApi.httpClient.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                    contentType(ContentType.Application.Json)
                    headers.append("referer", "https://y.qq.com/")
                    headers.append("origin", "https://y.qq.com")
                    setBody(body.toString())
                }.bodyAsText()
            Timber.tag("SongComments").d("QQ hot raw=${responseText.take(1000)}")
            val root = ChinaMusicApi.jsonParser.parseToJsonElement(responseText).jsonObject
            Timber.tag("SongComments").d("QQ hot code=${root["code"]?.jsonPrimitive?.contentOrNull} reqCode=${root["req"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull}")
            val comments = root["req"]?.jsonObject?.get("data")?.jsonObject
                ?.get("CommentList")?.jsonObject?.get("Comments")?.jsonArray
            Timber.tag("SongComments").d("QQ hot commentsCount=${comments?.size ?: -1}")
            val parsed = comments.orEmpty().mapNotNull { element ->
                val item = element.jsonObject
                SongComment(
                    id = item["CmId"]?.jsonPrimitive?.contentOrNull
                        ?: item["SeqNo"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null,
                    userName = item["Nick"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    avatarUrl = item["Avatar"]?.jsonPrimitive?.contentOrNull,
                    content = item["Content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    time = item["PubTime"]?.jsonPrimitive?.longOrNull ?: 0,
                    likedCount = item["PraiseNum"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
            parsed.ifEmpty { getQQLegacyHotComments(songId, limit) }
        } else {
            val root = ChinaMusicApi.jsonParser.parseToJsonElement(
                ChinaMusicApi.httpClient.post("http://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg") {
                    headers.append("User-Agent", "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")
                    setBody(FormDataContent(Parameters.build {
                        append("uin", "0")
                        append("format", "json")
                        append("cid", "205360772")
                        append("reqtype", "2")
                        append("biztype", "1")
                        append("topid", songId)
                        append("cmd", "8")
                        append("needmusiccrit", "1")
                        append("pagenum", "0")
                        append("pagesize", limit.toString())
                    }))
                }.bodyAsText()
            ).jsonObject
            val commentListElement = root["comment"]?.jsonObject?.get("commentlist")
            val comments = if (commentListElement == null || commentListElement is JsonNull) {
                JsonArray(emptyList())
            } else {
                commentListElement.jsonArray
            }
            comments.orEmpty().mapNotNull { element ->
                val item = element.jsonObject
                SongComment(
                    id = item["commentid"]?.jsonPrimitive?.contentOrNull ?: item["rootcommentid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    userName = item["rootcommentnick"]?.jsonPrimitive?.contentOrNull?.removePrefix("@").orEmpty(),
                    avatarUrl = item["avatarurl"]?.jsonPrimitive?.contentOrNull,
                    content = item["rootcommentcontent"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    time = item["time"]?.jsonPrimitive?.longOrNull ?: 0,
                    likedCount = item["praisenum"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
        }
    }

    private suspend fun getQQLegacyHotComments(songId: String, limit: Int): List<SongComment> {
        val root = ChinaMusicApi.jsonParser.parseToJsonElement(
            ChinaMusicApi.httpClient.post("http://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg") {
                headers.append("User-Agent", "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")
                setBody(FormDataContent(Parameters.build {
                    append("uin", "0")
                    append("format", "json")
                    append("cid", "205360772")
                    append("reqtype", "2")
                    append("biztype", "1")
                    append("topid", songId)
                    append("cmd", "9")
                    append("needmusiccrit", "1")
                    append("pagenum", "0")
                    append("pagesize", limit.toString())
                }))
            }.bodyAsText()
        ).jsonObject
        Timber.tag("SongComments").d("QQ legacy hot code=${root["code"]?.jsonPrimitive?.contentOrNull}")
        val commentListElement = root["comment"]?.jsonObject?.get("commentlist")
        val comments = if (commentListElement == null || commentListElement is JsonNull) {
            JsonArray(emptyList())
        } else {
            commentListElement.jsonArray
        }
        Timber.tag("SongComments").d("QQ legacy hot commentsCount=${comments.size}")
        return comments.mapNotNull { element ->
            val item = element.jsonObject
            SongComment(
                id = item["commentid"]?.jsonPrimitive?.contentOrNull
                    ?: item["rootcommentid"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null,
                userName = item["rootcommentnick"]?.jsonPrimitive?.contentOrNull?.removePrefix("@").orEmpty(),
                avatarUrl = item["avatarurl"]?.jsonPrimitive?.contentOrNull,
                content = item["rootcommentcontent"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                time = item["time"]?.jsonPrimitive?.longOrNull ?: 0,
                likedCount = item["praisenum"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    private fun kugouSignature(params: String): String {
        val key = "OIlwieks28dk2k092lksi2UIkp"
        val sorted = params.split("&").sorted().joinToString("")
        return md5("$key$sorted$key")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
