/**
 * Metrolist Project (C) 2026
 * Lyrics provider for Chinese music sources - fetches directly from source APIs
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.ChinaMusicUtils
import com.metrolist.kugou.KuGou
import timber.log.Timber

object ChinaMusicLyricsProvider : LyricsProvider {
    override val name = "ChinaMusic"

    override fun isEnabled(context: Context): Boolean = true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        if (!ChinaMusicUtils.isChinaMediaId(id)) {
            return Result.failure(Exception("Not a China source song"))
        }
        val source = ChinaMusicUtils.extractSource(id) ?: return Result.failure(Exception("Unknown source"))
        val songId = ChinaMusicUtils.extractSongId(id) ?: return Result.failure(Exception("Unknown song ID"))
        Timber.tag("ChinaMusicLyrics").d("Fetching lyrics: source=$source, songId=$songId for '$title'")

        // Try direct source API first
        val result = ChinaMusicApi.getLyric(source, songId)
        if (result.isSuccess) {
            Timber.tag("ChinaMusicLyrics").i("Got lyrics for '$title' (${result.getOrNull()?.length} chars)")
            return result
        }
        Timber.tag("ChinaMusicLyrics").w("Direct API failed: ${result.exceptionOrNull()?.message}, trying KuGou search")

        // Fallback: search via built-in KuGou (by title+artist, faster than letting other providers timeout)
        val kugouResult = try {
            KuGou.getLyrics(title, artist, duration, album)
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (kugouResult.isSuccess) {
            Timber.tag("ChinaMusicLyrics").i("Got lyrics from KuGou fallback for '$title'")
            return kugouResult
        }
        Timber.tag("ChinaMusicLyrics").w("KuGou fallback also failed for '$title'")
        return kugouResult
    }
}
