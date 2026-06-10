package com.metrolist.music.ui.screens.comments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.chinamusic.ChinaMusicUtils
import com.metrolist.chinamusic.model.SongComment
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongCommentsScreen(
    navController: NavController,
    mediaId: String,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "歌曲评论") },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        SongCommentsContent(
            mediaId = mediaId,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

@Composable
fun SongCommentsContent(
    mediaId: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    var showHotComments by remember(mediaId) { mutableStateOf(true) }
    var loading by remember(mediaId, showHotComments) { mutableStateOf(true) }
    var error by remember(mediaId, showHotComments) { mutableStateOf<String?>(null) }
    var comments by remember(mediaId, showHotComments) { mutableStateOf<List<SongComment>>(emptyList()) }
    val database = LocalDatabase.current

    LaunchedEffect(mediaId, showHotComments) {
        Timber.tag("SongComments").d("open mediaId=$mediaId hot=$showHotComments")
        loading = true
        error = null
        val song = database.song(mediaId).firstOrNull()
        ChinaMusicUtils.getComments(
            mediaId = mediaId,
            hot = showHotComments,
            title = song?.song?.title.orEmpty(),
            artist = song?.artists?.joinToString(" ") { it.name }.orEmpty(),
            durationSeconds = song?.song?.duration ?: 0,
        )
            .onSuccess {
                Timber.tag("SongComments").d("loaded comments size=${it.size}")
                comments = it
            }
            .onFailure {
                Timber.tag("SongComments").e(it, "load failed mediaId=$mediaId")
                error = it.message ?: "评论加载失败"
            }
        loading = false
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let {
                IconButton(onClick = it) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            }
            Text(
                text = "歌曲评论",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = showHotComments,
                onClick = { showHotComments = true },
                label = { Text(text = "热门评论") },
            )
            FilterChip(
                selected = !showHotComments,
                onClick = { showHotComments = false },
                label = { Text(text = "最新评论") },
            )
        }

        when {
            loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(comments, key = { it.id }) { comment ->
                        ListItem(
                            headlineContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = comment.userName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (comment.likedCount > 0) {
                                        Text(text = "♥ ${comment.likedCount}")
                                    }
                                }
                            },
                            supportingContent = {
                                Column {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = comment.content)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
