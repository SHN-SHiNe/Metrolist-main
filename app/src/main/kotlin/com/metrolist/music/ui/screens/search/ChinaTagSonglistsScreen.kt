package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.chinamusic.model.SonglistItem
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.viewmodels.ChinaTagSonglistsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChinaTagSonglistsScreen(
    navController: NavController,
    viewModel: ChinaTagSonglistsViewModel = hiltViewModel(),
) {
    val songlists by viewModel.songlists.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val lazyGridState = rememberLazyGridState()
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(lazyGridState) {
        snapshotFlow {
            val layoutInfo = lazyGridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 6
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) viewModel.loadMore()
        }
    }

    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = isLoading && songlists.isNotEmpty(),
        onRefresh = viewModel::refresh,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            state = lazyGridState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "${viewModel.source.displayName}  ${viewModel.tagName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }

            items(songlists, key = { "${it.source}_${it.id}" }) { songlist ->
                ChinaSonglistGridCard(
                    songlist = songlist,
                    onClick = {
                        navController.navigate(
                            "china_songlist/${songlist.source}/${URLEncoder.encode(songlist.id, "UTF-8")}?name=${URLEncoder.encode(songlist.name, "UTF-8")}",
                        )
                    },
                    modifier = Modifier.padding(8.dp),
                )
            }

            if (isLoading) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (error != null && songlists.isEmpty()) {
                item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = viewModel::retry) { Text("重试") }
                    }
                }
            }
        }
    }

    TopAppBar(
        title = { Text(viewModel.tagName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

@Composable
private fun ChinaSonglistGridCard(
    songlist: SonglistItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = chinaHdCoverUrl(songlist.img),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium),
        )
        Text(
            text = songlist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (songlist.author.isNotBlank()) {
            Text(
                text = songlist.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun chinaHdCoverUrl(url: String?, size: Int = 400): String? {
    if (url.isNullOrBlank()) return null
    val sized = url.replace("{size}", size.toString())
    val absolute = if (sized.startsWith("//")) "http:$sized" else sized
    return if (absolute.contains("music.126.net")) {
        "${absolute.split("?")[0]}?param=${size}y${size}"
    } else {
        absolute
    }
}
