/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.search

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalIsPlayerExpanded
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.constants.SearchSource
import com.metrolist.music.constants.SearchSourceKey
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.ui.component.HideOnScrollFAB
import com.metrolist.music.ui.screens.search.ChinaSearchInline
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    pureBlack: Boolean,
    savedStateHandle: SavedStateHandle,
    initialQuery: String? = null,
    initialSource: String? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isPlayerExpanded = LocalIsPlayerExpanded.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lazyListState = rememberLazyListState()
    var isHandlingScrollToTop by remember { mutableStateOf(false) }

    val scrollToTopCount by savedStateHandle.getStateFlow("scrollToTopCount", 0).collectAsStateWithLifecycle(initialValue = 0)

    var lastHandledCount by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scrollToTopCount) {
        if (scrollToTopCount > lastHandledCount) {
            lastHandledCount = scrollToTopCount
            isHandlingScrollToTop = true
            kotlinx.coroutines.delay(500)
            isHandlingScrollToTop = false
        }
    }

    val activeSearchSource by remember(context) {
        context.dataStore.data
            .map { SearchSource.fromPreference(it[SearchSourceKey]) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(SearchSource.fromPreference(context.dataStore[SearchSourceKey]))

    fun updateSearchSource(source: SearchSource) {
        coroutineScope.launch {
            context.dataStore.edit {
                it[SearchSourceKey] = source.name
            }
        }
    }

    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialQuery ?: ""))
    }

    // Set initial source if provided
    LaunchedEffect(initialSource) {
        if (initialSource != null) {
            updateSearchSource(SearchSource.fromPreference(initialSource))
        }
    }
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)

    fun recordSearch(searchQuery: String) {
        if (searchQuery.isEmpty()) {
            return
        }

        focusManager.clearFocus()

        if (!pauseSearchHistory) {
            coroutineScope.launch(Dispatchers.IO) {
                database.query {
                    insert(SearchHistory(query = searchQuery))
                }
            }
        }
    }

    val onSearch: (String) -> Unit = { searchQuery ->
        recordSearch(searchQuery)
    }

    val onSearchFromSuggestion: (String) -> Unit = { searchQuery ->
        recordSearch(searchQuery)
    }

    Scaffold(
        topBar = {
            val topBarColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer
            Column(modifier = Modifier.background(topBarColor)) {
                TopAppBar(
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
                                        .padding(start = 14.dp, end = 4.dp),
                            ) {
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .focusRequester(focusRequester),
                                    textStyle =
                                        TextStyle(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 16.sp,
                                        ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    singleLine = true,
                                    decorationBox = { innerTextField ->
                                        if (query.text.isEmpty()) {
                                            Text(
                                                text =
                                                    stringResource(
                                                        when (activeSearchSource) {
                                                            SearchSource.LIBRARY -> R.string.search_library
                                                            SearchSource.ONLINE -> R.string.search_china_music
                                                            SearchSource.CHINA -> R.string.search_china_music
                                                            SearchSource.CHINA_SONGLIST -> R.string.search_china_songlist
                                                            SearchSource.ADVANCED -> R.string.search_advanced_local_music
                                                        },
                                                    ),
                                                style =
                                                    TextStyle(
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                        fontSize = 16.sp,
                                                    ),
                                            )
                                        }
                                        innerTextField()
                                    },
                                    keyboardOptions =
                                        KeyboardOptions(
                                            imeAction = ImeAction.Search,
                                        ),
                                    keyboardActions =
                                        KeyboardActions(
                                            onSearch = { onSearch(query.text) },
                                        ),
                                )

                                if (query.text.isNotEmpty()) {
                                    IconButton(
                                        onClick = { query = TextFieldValue("") },
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.close),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_back),
                                contentDescription = stringResource(R.string.dismiss),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = topBarColor,
                        ),
                )
                SearchSourceTabs(
                    activeSearchSource = activeSearchSource,
                    onSearchSourceChange = ::updateSearchSource,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
        containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

        Box(
            modifier =
                Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(bottom = bottomPadding)
                        .fillMaxSize(),
            ) {
                when (activeSearchSource) {
                    SearchSource.LIBRARY -> {
                        LocalSearchScreen(
                            query = query.text,
                            navController = navController,
                            onDismiss = { navController.navigateUp() },
                            pureBlack = pureBlack,
                        )
                    }

                    SearchSource.ONLINE -> {
                        // Redirect to CHINA search
                        ChinaSearchInline(
                            query = query.text,
                            navController = navController,
                            onSearch = { searchQuery ->
                                if (!pauseSearchHistory && searchQuery.isNotEmpty()) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        database.query {
                                            insert(SearchHistory(query = searchQuery))
                                        }
                                    }
                                }
                                query = TextFieldValue(searchQuery, TextRange(searchQuery.length))
                            },
                        )
                    }

                    SearchSource.CHINA -> {
                        ChinaSearchInline(
                            query = query.text,
                            navController = navController,
                            onSearch = { searchQuery ->
                                if (!pauseSearchHistory && searchQuery.isNotEmpty()) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        database.query {
                                            insert(SearchHistory(query = searchQuery))
                                        }
                                    }
                                }
                                query = TextFieldValue(searchQuery, TextRange(searchQuery.length))
                            },
                        )
                    }

                    SearchSource.CHINA_SONGLIST -> {
                        ChinaSonglistInline(
                            query = query.text,
                            onNavigateToSonglist = { source, id, name ->
                                navController.navigate("china_songlist/$source/${URLEncoder.encode(id, "UTF-8")}?name=${URLEncoder.encode(name, "UTF-8")}")
                            },
                            onSearch = { searchQuery ->
                                if (!pauseSearchHistory && searchQuery.isNotEmpty()) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        database.query {
                                            insert(SearchHistory(query = searchQuery))
                                        }
                                    }
                                }
                                query = TextFieldValue(searchQuery, TextRange(searchQuery.length))
                            },
                        )
                    }

                    SearchSource.ADVANCED -> {
                        AdvancedLocalMusicSearchScreen(
                            query = query.text,
                            navController = navController,
                            pureBlack = pureBlack,
                        )
                    }
                }
            }

            HideOnScrollFAB(
                lazyListState = lazyListState,
                icon = R.drawable.mic,
                onClick = { navController.navigate("recognition") },
            )
        }
    }

    // Handle lifecycle events to manage keyboard visibility
    DisposableEffect(lifecycleOwner, isPlayerExpanded) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        if (isHandlingScrollToTop) return@LifecycleEventObserver
                        // Always hide keyboard when resuming if player is expanded
                        if (isPlayerExpanded) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        if (isHandlingScrollToTop) return@LifecycleEventObserver
                        // Clear focus when pausing to prevent keyboard from showing on resume
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Initial check - hide keyboard if player is expanded
        if (isPlayerExpanded) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
private fun SearchSourceTabs(
    activeSearchSource: SearchSource,
    onSearchSourceChange: (SearchSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs =
        listOf(
            SearchTab(SearchSource.CHINA, R.drawable.music_note, stringResource(R.string.filter_songs)),
            SearchTab(SearchSource.CHINA_SONGLIST, R.drawable.queue_music, stringResource(R.string.filter_playlists)),
            SearchTab(SearchSource.LIBRARY, R.drawable.library_music, stringResource(R.string.search_source_library)),
            SearchTab(SearchSource.ADVANCED, R.drawable.advanced_search, stringResource(R.string.search_source_advanced)),
        )
    val activeIndex = tabs.indexOfFirst { it.source == activeSearchSource }.coerceAtLeast(0)

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(44.dp),
    ) {
        val groupGap = 8.dp
        val groupWidth = (maxWidth - groupGap) / 2
        val tabWidth = groupWidth / 2
        val indicatorTarget =
            if (activeIndex < 2) {
                tabWidth * activeIndex.toFloat()
            } else {
                groupWidth + groupGap + tabWidth * (activeIndex - 2).toFloat()
            }
        val indicatorOffset by animateDpAsState(
            targetValue = indicatorTarget,
            label = "searchSourceTabOffset",
        )

        Row(modifier = Modifier.fillMaxSize()) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                modifier = Modifier.width(groupWidth).fillMaxHeight(),
            ) {}
            Spacer(Modifier.width(groupGap))
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                modifier = Modifier.width(groupWidth).fillMaxHeight(),
            ) {}
        }

        Box(
            modifier =
                Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary),
        )

        Row(modifier = Modifier.fillMaxSize()) {
            tabs.take(2).forEach { tab ->
                SearchSourceTabIcon(tab, activeSearchSource, onSearchSourceChange, Modifier.width(tabWidth))
            }
            Spacer(Modifier.width(groupGap))
            tabs.drop(2).forEach { tab ->
                SearchSourceTabIcon(tab, activeSearchSource, onSearchSourceChange, Modifier.width(tabWidth))
            }
        }
    }
}

private data class SearchTab(
    val source: SearchSource,
    val icon: Int,
    val contentDescription: String,
)

@Composable
private fun SearchSourceTabIcon(
    tab: SearchTab,
    activeSearchSource: SearchSource,
    onSearchSourceChange: (SearchSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = tab.source == activeSearchSource
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .clickable { onSearchSourceChange(tab.source) }
                .padding(horizontal = 8.dp),
    ) {
        Icon(
            painter = painterResource(tab.icon),
            contentDescription = tab.contentDescription,
            tint =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(22.dp),
        )
    }
}
