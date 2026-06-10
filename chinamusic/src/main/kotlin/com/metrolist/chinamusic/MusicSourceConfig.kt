package com.metrolist.chinamusic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class MusicSourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val apiUrl: String,
    val apiKey: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun listFromJson(jsonString: String): List<MusicSourceConfig> {
            if (jsonString.isBlank()) return listOf(DEFAULT_MUSIC_SOURCE)
            return try {
                json.decodeFromString<List<MusicSourceConfig>>(jsonString)
            } catch (e: Exception) {
                listOf(DEFAULT_MUSIC_SOURCE)
            }
        }
    }
}

val DEFAULT_MUSIC_SOURCE = MusicSourceConfig(
    id = "default",
    name = "默认音乐源",
    apiUrl = "https://source.shiqianjiang.cn/api/music",
    apiKey = "CERU_KEY-C999C589-89F0-416F-80A2-CCDD5BAB6EC1",
)