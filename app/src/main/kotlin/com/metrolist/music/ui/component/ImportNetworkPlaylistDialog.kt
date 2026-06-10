package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.PlaylistUrlParser
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@Composable
fun ImportNetworkPlaylistDialog(
    onDismiss: () -> Unit,
    onPlaylistImported: ((String) -> Unit)? = null,
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }

    TextFieldDialog(
        icon = { Icon(painter = painterResource(R.drawable.language), contentDescription = null) },
        title = { Text(text = "导入网络歌单") },
        initialTextFieldValue = TextFieldValue(""),
        onDismiss = onDismiss,
        autoDismiss = false,
        onDone = { urlInput ->
            if (isLoading) return@TextFieldDialog
            val parsed = PlaylistUrlParser.parse(urlInput)
            if (parsed == null) {
                Toast.makeText(context, "无法识别此链接，请输入网易云/QQ/酷狗/酷我/咪咕歌单链接", Toast.LENGTH_LONG).show()
                return@TextFieldDialog
            }

            isLoading = true
            coroutineScope.launch(Dispatchers.IO) {
                ChinaMusicApi.getSonglistDetail(
                    id = parsed.id,
                    page = 1,
                    limit = 10000,
                    source = parsed.source,
                ).onSuccess { detail ->
                    val chinaBrowseId = "china_${parsed.source.id}_${parsed.id}"
                    val existingPlaylist = database.playlistByBrowseId(chinaBrowseId).first()
                    if (existingPlaylist != null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "该歌单已在媒体库中", Toast.LENGTH_SHORT).show()
                            isLoading = false
                            onPlaylistImported?.invoke(existingPlaylist.id)
                            onDismiss()
                        }
                        return@onSuccess
                    }

                    val playlistEntity = PlaylistEntity(
                        name = detail.name,
                        browseId = chinaBrowseId,
                        isEditable = false,
                        thumbnailUrl = detail.img,
                        remoteSongCount = detail.songs.size,
                        bookmarkedAt = LocalDateTime.now(),
                    )
                    val songMetadataList = detail.songs.map { it.toMediaMetadata() }
                    database.transaction {
                        insert(playlistEntity)
                        songMetadataList.forEach { insert(it) }
                        val songIds = songMetadataList.map { it.id to null as String? }
                        val createdPlaylist = database.playlistBlocking(playlistEntity.id)
                            ?: return@transaction
                        database.addSongsToPlaylist(createdPlaylist, songIds, setInLibrary = false)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已导入「${detail.name}」", Toast.LENGTH_SHORT).show()
                        isLoading = false
                        onPlaylistImported?.invoke(playlistEntity.id)
                        onDismiss()
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                        isLoading = false
                    }
                }
            }
        },
        extraContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Text(
                    text = "请输入歌单链接（支持网易云、QQ音乐、酷狗、酷我、咪咕）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isLoading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        },
    )
}
