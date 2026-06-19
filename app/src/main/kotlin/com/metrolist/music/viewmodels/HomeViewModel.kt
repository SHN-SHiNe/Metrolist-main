/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.model.MusicSource
import com.metrolist.chinamusic.model.SonglistItem
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.flow.combine
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.ExplorePage
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.utils.completed
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.ChinaHomeLastBoardIdKey
import com.metrolist.music.constants.ChinaHomeLastBoardNameKey
import com.metrolist.music.constants.ChinaHomeLastGenreTagIdKey
import com.metrolist.music.constants.ChinaHomeLastGenreTagNameKey
import com.metrolist.music.constants.ChinaHomeLastTagIdKey
import com.metrolist.music.constants.ChinaHomeLastTagNameKey
import com.metrolist.music.constants.ChinaHomeSourceIdKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.QuickPicks
import com.metrolist.music.constants.QuickPicksKey
import com.metrolist.music.constants.ShowWrappedCardKey
import com.metrolist.music.constants.WrappedSeenKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.extensions.filterVideoSongs
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.SimilarRecommendation
import com.metrolist.music.ui.screens.wrapped.WrappedAudioService
import com.metrolist.music.ui.screens.wrapped.WrappedManager
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import kotlin.random.Random

data class DailyDiscoverItem(
    val seed: Song,
    val recommendation: YTItem,
    val relatedEndpoint: BrowseEndpoint?
)

data class CommunityPlaylistItem(
    val playlist: PlaylistItem,
    val songs: List<SongItem>
)

data class ChinaHomeTag(
    val id: String,
    val name: String,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val syncUtils: SyncUtils,
    val wrappedManager: WrappedManager,
    private val wrappedAudioService: WrappedAudioService,
) : ViewModel() {
    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)
    val isRandomizing = MutableStateFlow(false)

    private val quickPicksEnum = context.dataStore.data.map {
        it[QuickPicksKey].toEnum(QuickPicks.QUICK_PICKS)
    }.distinctUntilChanged()

    val quickPicks = MutableStateFlow<List<Song>?>(null)
    val dailyDiscover = MutableStateFlow<List<DailyDiscoverItem>?>(null)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val communityPlaylists = MutableStateFlow<List<CommunityPlaylistItem>?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)
    private val previousHomePage = MutableStateFlow<HomePage?>(null)
    val chinaHomeSource = MutableStateFlow(MusicSource.NETEASE)
    val chinaHomeTags = MutableStateFlow<List<ChinaHomeTag>>(emptyList())
    val chinaHomeGenreTags = MutableStateFlow<List<ChinaHomeTag>>(emptyList())
    val selectedChinaHomeTag = MutableStateFlow<ChinaHomeTag?>(null)
    val chinaHomeSonglists = MutableStateFlow<List<SonglistItem>>(emptyList())
    val isChinaHomeLoading = MutableStateFlow(false)
    val selectedChinaHomeGenreTag = MutableStateFlow<ChinaHomeTag?>(null)
    val chinaHomeGenreSonglists = MutableStateFlow<List<SonglistItem>>(emptyList())
    val isChinaHomeGenreLoading = MutableStateFlow(false)
    val chinaHomeBoards = MutableStateFlow<List<com.metrolist.chinamusic.model.LeaderboardItem>>(emptyList())
    val selectedChinaHomeBoard = MutableStateFlow<com.metrolist.chinamusic.model.LeaderboardItem?>(null)
    val chinaHomeBoardSongs = MutableStateFlow<List<com.metrolist.chinamusic.model.ChinaSong>>(emptyList())
    val isChinaHomeBoardLoading = MutableStateFlow(false)
    val chinaArtistRecommendations = MutableStateFlow<List<Pair<String, List<com.metrolist.chinamusic.model.ChinaSong>>>>(emptyList())

    // Official API data for podcast sections
    val savedPodcastShows = MutableStateFlow<List<com.metrolist.innertube.models.PodcastItem>>(emptyList())
    val episodesForLater = MutableStateFlow<List<SongItem>>(emptyList())

    val allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allYtItems = MutableStateFlow<List<YTItem>>(emptyList())

    private val chinaHomeFallbackTagsBySource = mapOf(
        MusicSource.KUWO to listOf(ChinaHomeTag("", "推荐")),
        MusicSource.KUGOU to listOf(ChinaHomeTag("", "推荐")),
        MusicSource.QQ to listOf(ChinaHomeTag("", "推荐")),
        MusicSource.NETEASE to listOf(ChinaHomeTag("全部", "推荐")),
        MusicSource.MIGU to listOf(ChinaHomeTag("", "推荐")),
    )

    val pinnedSpeedDialItems: StateFlow<List<SpeedDialItem>> =
        database.speedDialDao.getAll()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val speedDialItems: StateFlow<List<YTItem>> =
        combine(
            database.speedDialDao.getAll(),
            keepListening,
            quickPicks
        ) { pinned, keepListening, quick ->
            val pinnedItems = pinned.map { it.toYTItem() }
            val filled = pinnedItems.toMutableList()
            val targetSize = 27

            if (filled.size < targetSize) {
                // Keep Listening (History/Heavy Rotation)
                keepListening?.let { k ->
                    val needed = targetSize - filled.size
                    val available = k.filter { item ->
                        filled.none { p -> p.id == item.id }
                    }.mapNotNull { item ->
                        when (item) {
                            is Song -> SongItem(
                                id = item.id,
                                title = item.title,
                                artists = item.artists.map { Artist(name = it.name, id = it.id) },
                                thumbnail = item.thumbnailUrl ?: "",
                                explicit = false
                            )
                            is Album -> AlbumItem(
                                browseId = item.id,
                                playlistId = item.album.playlistId ?: "",
                                title = item.title,
                                artists = item.artists.map { Artist(name = it.name, id = it.id) },
                                year = item.album.year,
                                thumbnail = item.thumbnailUrl ?: ""
                            )
                            is com.metrolist.music.db.entities.Artist -> ArtistItem(
                                id = item.id,
                                title = item.title,
                                thumbnail = item.thumbnailUrl,
                                shuffleEndpoint = null,
                                radioEndpoint = null
                            )
                            else -> null
                        }
                    }
                    filled.addAll(available.take(needed))
                }
            }

            if (filled.size < targetSize) {
                // Quick Picks
                quick?.let { q ->
                    val needed = targetSize - filled.size
                    val available = q.filter { song ->
                        filled.none { p -> p.id == song.id }
                    }.map { song ->
                        SongItem(
                            id = song.id,
                            title = song.title,
                            artists = song.artists.map { Artist(name = it.name, id = it.id) },
                            thumbnail = song.thumbnailUrl ?: "",
                            explicit = false
                        )
                    }
                    filled.addAll(available.take(needed))
                }
            }
            
            filled.take(targetSize)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    suspend fun getRandomItem(): YTItem? {
        try {
            isRandomizing.value = true
            // Visual feedback for the animation
            kotlinx.coroutines.delay(1000)

            val userSongs = mutableListOf<YTItem>()
            val otherSources = mutableListOf<YTItem>()

            quickPicks.value?.let { songs ->
                userSongs.addAll(songs.map { song ->
                    SongItem(
                        id = song.id,
                        title = song.title,
                        artists = song.artists.map { Artist(name = it.name, id = it.id) },
                        thumbnail = song.thumbnailUrl ?: "",
                        explicit = false
                    )
                })
            }

            keepListening.value?.let { items ->
                items.forEach { item ->
                    when (item) {
                        is Song -> userSongs.add(SongItem(
                            id = item.id,
                            title = item.title,
                            artists = item.artists.map { Artist(name = it.name, id = it.id) },
                            thumbnail = item.thumbnailUrl ?: "",
                            explicit = false
                        ))
                        is Album -> otherSources.add(AlbumItem(
                            browseId = item.id,
                            playlistId = item.album.playlistId ?: "",
                            title = item.title,
                            artists = item.artists.map { Artist(name = it.name, id = it.id) },
                            year = item.album.year,
                            thumbnail = item.thumbnailUrl ?: ""
                        ))
                        is com.metrolist.music.db.entities.Artist -> otherSources.add(ArtistItem(
                            id = item.id,
                            title = item.title,
                            thumbnail = item.thumbnailUrl,
                            shuffleEndpoint = null,
                            radioEndpoint = null
                        ))
                        else -> {}
                    }
                }
            }

            otherSources.addAll(allYtItems.value)

            // Probability: 80% User Songs, 20% Other Sources
            val item = if (userSongs.isNotEmpty() && (otherSources.isEmpty() || Random.nextFloat() < 0.8f)) {
                userSongs.distinctBy { it.id }.shuffled().firstOrNull()
            } else {
                otherSources.distinctBy { it.id }.shuffled().firstOrNull()
            } ?: userSongs.firstOrNull() ?: otherSources.firstOrNull()

            return item
        } finally {
            isRandomizing.value = false
        }
    }

    val accountName = MutableStateFlow("Guest")
    val accountImageUrl = MutableStateFlow<String?>(null)

	val showWrappedCard: StateFlow<Boolean> = context.dataStore.data.map { prefs ->
        val showWrappedPref = prefs[ShowWrappedCardKey] ?: false
        val seen = prefs[WrappedSeenKey] ?: false
        val isBeforeDate = LocalDate.now().isBefore(LocalDate.of(2026, 2, 1))

        isBeforeDate && (!seen || showWrappedPref)
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val wrappedSeen: StateFlow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[WrappedSeenKey] ?: false
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun togglePin(item: YTItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val speedDialItem = SpeedDialItem.fromYTItem(item)
            val isPinned = database.speedDialDao.isPinned(speedDialItem.id).first()
            if (isPinned) {
                database.speedDialDao.delete(speedDialItem.id)
            } else {
                database.speedDialDao.insert(speedDialItem)
            }
        }
    }

    fun markWrappedAsSeen() {
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit {
                it[WrappedSeenKey] = true
            }
        }
    }
    // Track last processed cookie to avoid unnecessary updates
    private var lastProcessedCookie: String? = null
    // Track if we're currently processing account data
    private var isProcessingAccountData = false

    private suspend fun getDailyDiscover() {
        // No-op: YouTube-based daily discover removed
    }

    private suspend fun getQuickPicks() {
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        when (quickPicksEnum.first()) {
            QuickPicks.QUICK_PICKS -> {
                val relatedSongs = database.quickPicks().first().filterVideoSongs(hideVideoSongs)
                val forgotten = database.forgottenFavorites().first().filterVideoSongs(hideVideoSongs).take(8)

                val combined = (relatedSongs + forgotten)
                    .distinctBy { it.id }
                    .shuffled()
                    .take(20)

                quickPicks.value = combined.ifEmpty { relatedSongs.shuffled().take(20) }
            }
            QuickPicks.LAST_LISTEN -> {
                val song = database.events().first().firstOrNull()?.song
                if (song != null && database.hasRelatedSongs(song.id)) {
                    quickPicks.value = database.getRelatedSongs(song.id).first().filterVideoSongs(hideVideoSongs).shuffled().take(20)
                }
            }
        }
    }

    private suspend fun getCommunityPlaylists() {
        // No-op: YouTube-based community playlists removed
    }

    private suspend fun load() {
        isLoading.value = true
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val fromTimeStamp = System.currentTimeMillis() - 86400000L * 7 * 2

        coroutineScope {
            launch(Dispatchers.IO) { getQuickPicks() }

            launch(Dispatchers.IO) {
                forgottenFavorites.value = database.forgottenFavorites().first()
                    .filterVideoSongs(hideVideoSongs).shuffled().take(20)
            }

            launch(Dispatchers.IO) {
                val songs = database.mostPlayedSongs(fromTimeStamp, limit = 15, offset = 5).first()
                    .filterVideoSongs(hideVideoSongs).shuffled().take(10)
                val albums = database.mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2).first()
                    .filter { it.album.thumbnailUrl != null }.shuffled().take(5)
                val artists = database.mostPlayedArtists(fromTimeStamp).first()
                    .filter { it.artist.thumbnailUrl != null }.shuffled().take(5)
                keepListening.value = (songs + albums + artists).shuffled()
            }
        }

        allLocalItems.value = (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
            .filter { it is Song || it is Album }
        isLoading.value = false
    }

    private val _isLoadingMore = MutableStateFlow(false)
    fun loadMoreYouTubeItems(continuation: String?) {
        // No-op: YouTube removed
    }

    fun toggleChip(chip: HomePage.Chip?) {
        // No-op: YouTube removed
    }

    private suspend fun fetchPodcastData() {
        // Fetch saved podcast shows from official API
        YouTube.savedPodcastShows().onSuccess { shows ->
            savedPodcastShows.value = shows
        }.onFailure {
            reportException(it)
        }

        // Fetch episodes for later from official API
        YouTube.episodesForLater().onSuccess { episodes ->
            episodesForLater.value = episodes
        }.onFailure {
            reportException(it)
        }
    }

    private suspend fun loadAccountPlaylists() {
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
        YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
            accountPlaylists.value = it.items.filterIsInstance<PlaylistItem>()
                .filterNot { it.id == "SE" }
                .filterYoutubeShorts(hideYoutubeShorts)
        }.onFailure {
            reportException(it)
        }
    }

    fun refresh() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            load()
            loadChinaHomeSonglists()
            loadChinaArtistRecommendations()
            isRefreshing.value = false
        }
    }

    fun selectChinaHomeSource(source: MusicSource) {
        if (chinaHomeSource.value == source) return
        chinaHomeSource.value = source
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[ChinaHomeSourceIdKey] = source.id
            }
            loadChinaHomeTags(source)
        }
    }

    fun switchToNextChinaHomeSource() {
        val sources = listOf(MusicSource.NETEASE, MusicSource.KUWO, MusicSource.KUGOU, MusicSource.QQ, MusicSource.MIGU)
        val currentIndex = sources.indexOf(chinaHomeSource.value).takeIf { it >= 0 } ?: 0
        selectChinaHomeSource(sources[(currentIndex + 1) % sources.size])
    }

    fun selectChinaHomeTag(tag: ChinaHomeTag) {
        if (selectedChinaHomeTag.value == tag) return
        selectedChinaHomeTag.value = tag
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[ChinaHomeLastTagIdKey] = tag.id
                prefs[ChinaHomeLastTagNameKey] = tag.name
            }
            loadChinaHomeSonglists()
        }
    }

    private suspend fun loadChinaHomeSonglists() {
        isChinaHomeLoading.value = true
        val source = chinaHomeSource.value
        val tagId = selectedChinaHomeTag.value?.id.orEmpty()
        ChinaMusicApi.getSonglistsByTag(tagId = tagId, page = 1, limit = 30, source = source)
            .onSuccess { chinaHomeSonglists.value = it.list }
            .onFailure {
                chinaHomeSonglists.value = emptyList()
                reportException(it)
            }
        isChinaHomeLoading.value = false
    }

    fun selectChinaHomeGenreTag(tag: ChinaHomeTag) {
        if (selectedChinaHomeGenreTag.value == tag) return
        selectedChinaHomeGenreTag.value = tag
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[ChinaHomeLastGenreTagIdKey] = tag.id
                prefs[ChinaHomeLastGenreTagNameKey] = tag.name
            }
            loadChinaHomeGenreSonglists()
        }
    }

    private suspend fun loadChinaHomeGenreSonglists() {
        isChinaHomeGenreLoading.value = true
        val source = chinaHomeSource.value
        val tagId = selectedChinaHomeGenreTag.value?.id.orEmpty()
        ChinaMusicApi.getSonglistsByTag(tagId = tagId, page = 1, limit = 30, source = source)
            .onSuccess { chinaHomeGenreSonglists.value = it.list }
            .onFailure {
                chinaHomeGenreSonglists.value = emptyList()
                reportException(it)
            }
        isChinaHomeGenreLoading.value = false
    }

    fun selectChinaHomeBoard(board: com.metrolist.chinamusic.model.LeaderboardItem) {
        if (selectedChinaHomeBoard.value == board) return
        selectedChinaHomeBoard.value = board
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[ChinaHomeLastBoardIdKey] = board.bangid
                prefs[ChinaHomeLastBoardNameKey] = board.name
            }
            loadChinaHomeBoardSongs()
        }
    }

    private suspend fun loadChinaHomeBoardSongs() {
        isChinaHomeBoardLoading.value = true
        val source = chinaHomeSource.value
        val bangid = selectedChinaHomeBoard.value?.bangid.orEmpty()
        ChinaMusicApi.getBoardSongs(bangid = bangid, page = 1, limit = 30, source = source)
            .onSuccess { chinaHomeBoardSongs.value = it.list }
            .onFailure {
                chinaHomeBoardSongs.value = emptyList()
                reportException(it)
            }
        isChinaHomeBoardLoading.value = false
    }

    private suspend fun loadChinaHomeTags(source: MusicSource = chinaHomeSource.value) {
        val fallback = chinaHomeFallbackTagsBySource[source].orEmpty()
        val prefs = context.dataStore.data.first()
        val lastTagId = prefs[ChinaHomeLastTagIdKey]
        val lastTagName = prefs[ChinaHomeLastTagNameKey]
        ChinaMusicApi.getSonglistTags(source)
            .onSuccess { tags ->
                val homeTags = (fallback + tags.map { ChinaHomeTag(it.id, it.name) })
                    .distinctBy { it.id.ifBlank { it.name } }
                    .take(30)
                chinaHomeTags.value = homeTags
                selectedChinaHomeTag.value = if (lastTagId != null && lastTagName != null) {
                    homeTags.find { it.id == lastTagId } ?: homeTags.randomOrNull()
                } else {
                    homeTags.randomOrNull()
                }
                loadChinaHomeSonglists()
            }
            .onFailure {
                chinaHomeTags.value = fallback
                selectedChinaHomeTag.value = if (lastTagId != null && lastTagName != null) {
                    fallback.find { it.id == lastTagId } ?: fallback.randomOrNull()
                } else {
                    fallback.randomOrNull()
                }
                loadChinaHomeSonglists()
                reportException(it)
            }
        val lastGenreTagId = prefs[ChinaHomeLastGenreTagIdKey]
        val lastGenreTagName = prefs[ChinaHomeLastGenreTagNameKey]
        ChinaMusicApi.getSonglistGenreTags(source)
            .onSuccess { tags ->
                val genreTags = tags.map { ChinaHomeTag(it.id, it.name) }
                    .distinctBy { tag -> tag.id.ifBlank { tag.name } }
                    .take(60)
                chinaHomeGenreTags.value = genreTags
                selectedChinaHomeGenreTag.value = if (lastGenreTagId != null && lastGenreTagName != null) {
                    genreTags.find { it.id == lastGenreTagId } ?: genreTags.randomOrNull()
                } else {
                    genreTags.randomOrNull()
                }
                loadChinaHomeGenreSonglists()
            }
            .onFailure {
                chinaHomeGenreTags.value = emptyList()
                reportException(it)
            }
        val lastBoardId = prefs[ChinaHomeLastBoardIdKey]
        val lastBoardName = prefs[ChinaHomeLastBoardNameKey]
        ChinaMusicApi.getBoards(source)
            .onSuccess { boards ->
                chinaHomeBoards.value = boards
                selectedChinaHomeBoard.value = if (lastBoardId != null && lastBoardName != null) {
                    boards.find { it.bangid == lastBoardId } ?: boards.randomOrNull()
                } else {
                    boards.randomOrNull()
                }
                loadChinaHomeBoardSongs()
            }
            .onFailure {
                chinaHomeBoards.value = emptyList()
                reportException(it)
            }
        loadChinaArtistRecommendations(source)
    }

    private fun getChinaHomeSource(sourceId: String?): MusicSource =
        MusicSource.fromId(sourceId.orEmpty())
            ?.takeIf { it in MusicSource.realSources }
            ?: MusicSource.NETEASE

    private suspend fun loadChinaArtistRecommendations(source: MusicSource = chinaHomeSource.value) {
        try {
            val events = database.events().first()
            if (events.isEmpty()) {
                chinaArtistRecommendations.value = emptyList()
                return
            }
            val recentEvents = events.take(50)
            val total = recentEvents.size.toFloat()
            val weightedCounts = mutableMapOf<String, Float>()
            recentEvents.forEachIndexed { index, event ->
                val weight = 1.0f - (index / total) * 0.7f
                event.song.artists.forEach { artist ->
                    if (artist.name.isNotBlank()) {
                        weightedCounts[artist.name] = (weightedCounts[artist.name] ?: 0f) + weight
                    }
                }
            }
            val artistNames = weightedCounts.entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(2)
            if (artistNames.isEmpty()) {
                chinaArtistRecommendations.value = emptyList()
                return
            }
            val results = mutableListOf<Pair<String, List<com.metrolist.chinamusic.model.ChinaSong>>>()
            for (name in artistNames) {
                ChinaMusicApi.search(keyword = name, page = 1, limit = 20, source = source)
                    .onSuccess { searchResult ->
                        if (searchResult.list.isNotEmpty()) {
                            results.add(name to searchResult.list)
                        }
                    }
            }
            chinaArtistRecommendations.value = results
        } catch (e: Exception) {
            reportException(e)
            chinaArtistRecommendations.value = emptyList()
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val source = getChinaHomeSource(context.dataStore.data.first()[ChinaHomeSourceIdKey])
            chinaHomeSource.value = source
            loadChinaHomeTags(source)
            load()
        }

        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { prefs -> getChinaHomeSource(prefs[ChinaHomeSourceIdKey]) }
                .distinctUntilChanged()
                .collect { source ->
                    if (chinaHomeSource.value != source) {
                        chinaHomeSource.value = source
                        loadChinaHomeTags(source)
                    }
                }
        }

        // Reactively update artist recommendations when listening history changes
        viewModelScope.launch(Dispatchers.IO) {
            database.events()
                .map { events ->
                    val recent = events.take(50)
                    val t = recent.size.toFloat()
                    val wc = mutableMapOf<String, Float>()
                    recent.forEachIndexed { idx, ev ->
                        val w = 1.0f - (idx / t) * 0.7f
                        ev.song.artists.forEach { a ->
                            if (a.name.isNotBlank()) {
                                wc[a.name] = (wc[a.name] ?: 0f) + w
                            }
                        }
                    }
                    wc.entries.sortedByDescending { it.value }.map { it.key }.take(2)
                }
                .distinctUntilChanged()
                .collect {
                    loadChinaArtistRecommendations()
                }
        }
    }
}
