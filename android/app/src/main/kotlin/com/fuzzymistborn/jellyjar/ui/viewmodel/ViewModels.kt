package com.fuzzymistborn.jellyjar.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuzzymistborn.jellyjar.api.JellyfinImageHelper
import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import com.fuzzymistborn.jellyjar.data.local.FavoriteEntity
import com.fuzzymistborn.jellyjar.data.repository.*
import com.fuzzymistborn.jellyjar.model.*
import com.fuzzymistborn.jellyjar.model.SortOrder
import com.fuzzymistborn.jellyjar.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── Library ViewModel ────────────────────────────────────────────────────────

data class LibraryState(
    val items: List<JellyfinItem> = emptyList(),
    val libraries: List<JellyfinLibrary> = emptyList(),
    val selectedLibrary: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val totalCount: Int = 0,
    val isOnline: Boolean = true,
    val jellyfinAvailable: Boolean = true,
    val showingDownloads: Boolean = false,
    val featuredItem: JellyfinItem? = null,
    val jellyfinUrl: String = "",
    val jellyfinToken: String = "",
    val downloadStatuses: Map<String, String> = emptyMap(),
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.DEFAULT,
    val showContinueWatching: Boolean = true,
    val showRecentlyAdded: Boolean = true,
    val showMyList: Boolean = true,
    val globalSearchActive: Boolean = false,
    val globalSearchQuery: String = "",
    val globalSearchResults: List<JellyfinItem> = emptyList(),
    val isGlobalSearching: Boolean = false,
    val resumeItems: List<JellyfinItem> = emptyList(),
    val recentlyAdded: List<JellyfinItem> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val favoriteItems: List<JellyfinItem> = emptyList(),
) {
    val hasMore: Boolean get() = items.size < totalCount

    val displayItems: List<JellyfinItem> get() {
        var result = items
        if (searchQuery.isNotBlank()) {
            result = result.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        result = when (sortOrder) {
            SortOrder.DEFAULT -> result
            SortOrder.ALPHABETICAL -> result.sortedBy { it.name.lowercase() }
            SortOrder.YEAR_ASC -> result.sortedBy { it.year ?: 0 }
            SortOrder.YEAR_DESC -> result.sortedByDescending { it.year ?: 0 }
            SortOrder.RATING_DESC -> result.sortedByDescending { it.communityRating ?: 0f }
            SortOrder.UNWATCHED_FIRST -> result.sortedBy { it.userData?.played == true }
        }
        return result
    }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val jellyfinRepo: JellyfinRepository,
    private val downloadRepo: DownloadRepository,
    private val settings: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
    private val favoriteRepo: com.fuzzymistborn.jellyjar.data.repository.FavoriteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                _state.update { it.copy(isOnline = online) }
                if (online && !_state.value.showingDownloads) loadLibrary()
                else if (!online) buildOfflineLibraries()
            }
        }
        // Retry Jellyfin with backoff when online but server is unreachable; stops once available
        viewModelScope.launch {
            var delayMs = 30_000L
            while (!_state.value.jellyfinAvailable) {
                kotlinx.coroutines.delay(delayMs)
                if (_state.value.isOnline && !_state.value.jellyfinAvailable && !_state.value.showingDownloads) {
                    loadLibrary()
                    delayMs = (delayMs * 2).coerceAtMost(300_000L)
                }
            }
        }
        viewModelScope.launch {
            settings.settings.collect { s ->
                val prevToken = _state.value.jellyfinToken
                val prevUrl = _state.value.jellyfinUrl
                _state.update { it.copy(jellyfinUrl = s.jellyfinUrl, jellyfinToken = s.jellyfinToken, showContinueWatching = s.showContinueWatching, showRecentlyAdded = s.showRecentlyAdded, showMyList = s.showMyList) }
                val tokenBecameAvailable = prevToken.isBlank() && s.jellyfinToken.isNotBlank()
                val urlChanged = prevUrl != s.jellyfinUrl && s.jellyfinToken.isNotBlank()
                if ((tokenBecameAvailable || urlChanged) && _state.value.isOnline && !_state.value.showingDownloads) {
                    loadLibrary()
                }
            }
        }
        viewModelScope.launch {
            downloadRepo.downloads.collect { downloads ->
                _state.update {
                    it.copy(downloadStatuses = downloads.associateBy({ it.jellyfinId }, { it.status }))
                }
            }
        }
        viewModelScope.launch {
            jellyfinRepo.getResumeItems().onSuccess { items ->
                _state.update { it.copy(resumeItems = items) }
            }
        }
        viewModelScope.launch {
            jellyfinRepo.getRecentlyAdded().onSuccess { items ->
                _state.update { it.copy(recentlyAdded = items) }
            }
        }
        viewModelScope.launch {
            favoriteRepo.favorites.collect { faves ->
                _state.update { s ->
                    s.copy(
                        favoriteIds = faves.map { it.jellyfinId }.toSet(),
                        favoriteItems = faves.map { fe ->
                            JellyfinItem(
                                id = fe.jellyfinId, name = fe.title, type = fe.type,
                                overview = null, year = null, communityRating = null,
                                runTimeTicks = null, seriesName = null, seasonName = null,
                                indexNumber = null, parentIndexNumber = null,
                                mediaSources = null, imageTags = null,
                                backdropImageTags = null, userData = null,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun loadLibrary(isRefresh: Boolean = false) = viewModelScope.launch {
        if (!isRefresh) {
            _state.update { s -> s.copy(isLoading = true, items = emptyList(), totalCount = 0) }
        }
        jellyfinRepo.getLibraries()
            .onSuccess { libs ->
                _state.update { s -> s.copy(libraries = libs, jellyfinAvailable = true) }
            }
            .onFailure {
                _state.update { s -> s.copy(isLoading = false, isRefreshing = false, jellyfinAvailable = false) }
                buildOfflineLibraries() // show downloaded content whenever Jellyfin is unreachable
                return@launch
            }
        val selectedLib = _state.value.libraries
            .find { lib -> lib.name == _state.value.selectedLibrary }
        val parentId = selectedLib?.id
        val types = when (selectedLib?.collectionType) {
            "movies" -> "Movie"
            "tvshows" -> "Series"
            else -> "Movie,Series"
        }
        jellyfinRepo.getItems(parentId = parentId, types = types, startIndex = 0)
            .onSuccess { response ->
                _state.update { s ->
                    s.copy(
                        items = response.Items,
                        totalCount = response.TotalRecordCount,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
            }
            .onFailure {
                _state.update { s -> s.copy(isLoading = false, isRefreshing = false, jellyfinAvailable = false) }
            }
    }

    fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        loadLibrary(isRefresh = true)
    }

    fun loadMore() {
        if (_state.value.isLoadingMore || !_state.value.hasMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            val selectedLib = _state.value.libraries
                .find { lib -> lib.name == _state.value.selectedLibrary }
            val parentId = selectedLib?.id
            val types = when (selectedLib?.collectionType) {
                "movies" -> "Movie"
                "tvshows" -> "Series"
                else -> "Movie,Series"
            }
            jellyfinRepo.getItems(
                parentId = parentId,
                types = types,
                startIndex = _state.value.items.size,
            ).onSuccess { response ->
                _state.update { s ->
                    s.copy(
                        items = s.items + response.Items,
                        totalCount = response.TotalRecordCount,
                        isLoadingMore = false,
                    )
                }
            }.onFailure {
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    private fun loadOffline() = viewModelScope.launch {
        downloadRepo.completedDownloads.collect { downloads ->
            val items = downloads.map { it.toJellyfinItem() }
            _state.update { s -> s.copy(items = items, isLoading = false) }
        }
    }

    private fun buildOfflineLibraries() = viewModelScope.launch {
        val downloads = downloadRepo.completedDownloads.first()
        val syntheticLibraries = buildList {
            if (downloads.any { it.type == "Movie" })
                add(JellyfinLibrary(id = "offline_movies", name = "Movies", collectionType = "movies"))
            if (downloads.any { it.type == "Episode" })
                add(JellyfinLibrary(id = "offline_tv", name = "TV Shows", collectionType = "tvshows"))
        }
        _state.update { s -> s.copy(libraries = syntheticLibraries) }
    }

    private fun loadOfflineLibrary(libraryName: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }
        val downloads = downloadRepo.completedDownloads.first()
        val collectionType = _state.value.libraries.find { it.name == libraryName }?.collectionType
        val items = when (collectionType) {
            "movies" -> downloads.filter { it.type == "Movie" }.map { it.toJellyfinItem() }
            "tvshows" -> {
                // Group by series name and look up each series from the cache so we navigate
                // to the real series DetailScreen (with seasons/episodes) rather than a flat list.
                downloads
                    .filter { it.type == "Episode" && it.seriesName != null }
                    .map { it.seriesName!! }
                    .distinct()
                    .mapNotNull { seriesName -> jellyfinRepo.getCachedSeriesByName(seriesName) }
            }
            else -> downloads.map { it.toJellyfinItem() }
        }
        _state.update { s -> s.copy(items = items, totalCount = items.size, isLoading = false) }
    }

    fun goHome() {
        _state.update { it.copy(selectedLibrary = null, showingDownloads = false, items = emptyList(), totalCount = 0) }
    }

    fun showDownloads() {
        _state.update { it.copy(showingDownloads = true, selectedLibrary = null) }
        loadOffline()
    }

    fun toggleTab() {
        val showDownloads = !_state.value.showingDownloads
        _state.update { it.copy(showingDownloads = showDownloads, selectedLibrary = null) }
        if (showDownloads) loadOffline()
    }

    fun selectLibrary(name: String?) {
        _state.update { it.copy(selectedLibrary = name, items = emptyList(), totalCount = 0) }
        if (name != null) {
            if (_state.value.isOnline && _state.value.jellyfinAvailable) loadLibrary()
            else loadOfflineLibrary(name)
        }
    }

    fun setFeatured(item: JellyfinItem) {
        _state.update { it.copy(featuredItem = item) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setSortOrder(order: SortOrder) {
        _state.update { it.copy(sortOrder = order) }
    }

    fun openGlobalSearch() {
        _state.update { it.copy(globalSearchActive = true, globalSearchQuery = "", globalSearchResults = emptyList()) }
    }

    fun closeGlobalSearch() {
        globalSearchJob?.cancel()
        _state.update { it.copy(globalSearchActive = false, globalSearchQuery = "", globalSearchResults = emptyList(), isGlobalSearching = false) }
    }

    private var globalSearchJob: Job? = null

    fun setGlobalSearchQuery(query: String) {
        _state.update { it.copy(globalSearchQuery = query) }
        globalSearchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(globalSearchResults = emptyList(), isGlobalSearching = false) }
            return
        }
        globalSearchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(isGlobalSearching = true) }
            jellyfinRepo.getItems(
                types = "Movie,Series,Episode",
                searchTerm = query,
                limit = 60,
            ).onSuccess { response ->
                _state.update { it.copy(globalSearchResults = response.Items, isGlobalSearching = false) }
            }.onFailure {
                _state.update { it.copy(isGlobalSearching = false) }
            }
        }
    }

    fun posterUrl(itemId: String) =
        JellyfinImageHelper.primaryImageUrl(_state.value.jellyfinUrl, itemId)

    fun backdropUrl(itemId: String) =
        JellyfinImageHelper.backdropImageUrl(_state.value.jellyfinUrl, itemId)

    fun libraryBackdropUrl(libraryId: String) =
        // Libraries use Primary image; backdrop index 0 as fallback
        "${_state.value.jellyfinUrl}/Items/$libraryId/Images/Primary?fillWidth=600&quality=85"

    private fun DownloadEntity.toJellyfinItem() = JellyfinItem(
        id = jellyfinId, name = title, type = type,
        overview = overview, year = year, communityRating = null,
        runTimeTicks = runtimeMinutes?.let { it.toLong() * 600_000_000L },
        seriesName = seriesName, seasonName = null,
        indexNumber = null, parentIndexNumber = null,
        mediaSources = if (localPath.isNotBlank()) listOf(
            MediaSource(id = jellyfinId, path = localPath, size = sizeBytes,
                        container = "mp4", videoStreams = null, audioStreams = null)
        ) else null,
        imageTags = null,
        backdropImageTags = null, userData = null,
    )
}

// ─── Detail ViewModel ─────────────────────────────────────────────────────────

data class DetailState(
    val item: JellyfinItem? = null,
    val download: DownloadEntity? = null,
    val episodeDownloads: Map<String, DownloadEntity> = emptyMap(),
    val seasonDownloadProgress: Map<String, Float> = emptyMap(),
    val seasonEpisodeIds: Map<String, List<String>> = emptyMap(),
    val seasons: List<com.fuzzymistborn.jellyjar.model.JellyfinItem> = emptyList(),
    val selectedSeasonIndex: Int? = null,
    val episodes: List<com.fuzzymistborn.jellyjar.model.JellyfinItem> = emptyList(),
    val isLoadingEpisodes: Boolean = false,
    val isLoading: Boolean = false,
    val isOnline: Boolean = true,
    val error: String? = null,
    val downloadError: String? = null,
    val jellyfinUrl: String = "",
    val jellyfinToken: String = "",
    val episodeViewGrid: Boolean = true,
    val streamPositionMs: Long = 0L,
    val isFavorite: Boolean = false,
    val isPlayed: Boolean = false,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val jellyfinRepo: JellyfinRepository,
    private val downloadRepo: DownloadRepository,
    private val settings: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
    private val favoriteRepo: com.fuzzymistborn.jellyjar.data.repository.FavoriteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                _state.update { it.copy(isOnline = online) }
            }
        }
        viewModelScope.launch {
            favoriteRepo.favoriteIds.collect { ids ->
                _state.update { it.copy(isFavorite = it.item?.id in ids) }
            }
        }
    }

    fun loadItem(itemId: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }
        settings.settings.first().also { s ->
            _state.update { it.copy(jellyfinUrl = s.jellyfinUrl, jellyfinToken = s.jellyfinToken, episodeViewGrid = s.episodeViewGrid) }
        }
        val savedPositionMs = downloadRepo.getPlaybackPosition(itemId)
        _state.update { it.copy(streamPositionMs = savedPositionMs) }
        jellyfinRepo.getItem(itemId)
            .onSuccess { item ->
                val isFav = favoriteRepo.isFavorite(item.id)
                _state.update { it.copy(item = item, isLoading = false, isFavorite = isFav, isPlayed = item.userData?.played == true) }
                if (item.type == "Series") loadSeasons(itemId)
            }
            .onFailure {
                // Offline fallback: load from local cache
                val cached = jellyfinRepo.getCachedItem(itemId)
                if (cached != null) {
                    _state.update { it.copy(item = cached, isLoading = false, error = null) }
                    if (cached.type == "Series") loadOfflineSeasons(cached.name)
                } else {
                    _state.update { it.copy(isLoading = false, error = "Unavailable offline") }
                }
            }
        // Watch all download statuses (movie + episodes)
        downloadRepo.downloads
            .collect { allDownloads ->
                val thisItem = allDownloads.find { it.jellyfinId == itemId }
                val epMap = allDownloads
                    .filter { it.jellyfinId != itemId }
                    .associateBy { it.jellyfinId }
                _state.update { it.copy(download = thisItem, episodeDownloads = epMap) }
            }
    }

    fun refreshStreamPosition(itemId: String) = viewModelScope.launch {
        _state.update { it.copy(streamPositionMs = downloadRepo.getPlaybackPosition(itemId)) }
    }

    fun queueEpisodeDownload(episode: JellyfinItem, preset: String) = viewModelScope.launch {
        val mediaPath = episode.mediaSources?.firstOrNull()?.path ?: return@launch
        downloadRepo.queueTranscode(episode, preset, mediaPath).onFailure {
            _state.update { s -> s.copy(downloadError = "Couldn't start download: ${it.message ?: "unknown error"}") }
        }
    }

    fun queueSeasonDownload(seasonId: String, preset: String) = viewModelScope.launch {
        // Show indeterminate progress while fetching episode list
        _state.update { it.copy(seasonDownloadProgress = it.seasonDownloadProgress + (seasonId to 0f)) }
        jellyfinRepo.getItems(parentId = seasonId, types = "Episode").onSuccess { response ->
            val episodes = response.Items
            var failCount = 0
            episodes.forEachIndexed { idx, episode ->
                val mediaPath = episode.mediaSources?.firstOrNull()?.path ?: return@forEachIndexed
                downloadRepo.queueTranscode(episode, preset, mediaPath).onFailure { failCount++ }
                val progress = ((idx + 1).toFloat() / episodes.size) * 100f
                _state.update { it.copy(seasonDownloadProgress = it.seasonDownloadProgress + (seasonId to progress)) }
            }
            // Clear progress indicator once all queued
            _state.update {
                it.copy(
                    seasonDownloadProgress = it.seasonDownloadProgress - seasonId,
                    downloadError = if (failCount > 0) "$failCount episode(s) failed to queue" else it.downloadError,
                )
            }
        }.onFailure {
            _state.update { it.copy(seasonDownloadProgress = it.seasonDownloadProgress - seasonId) }
        }
    }

    private fun loadSeasons(seriesId: String) = viewModelScope.launch {
        jellyfinRepo.getItems(parentId = seriesId, types = "Season")
            .onSuccess { response ->
                _state.update { it.copy(seasons = response.Items) }
                loadSeasonEpisodeIds(response.Items)
            }
            .onFailure {
                val seriesName = _state.value.item?.name ?: return@launch
                loadOfflineSeasons(seriesName)
            }
    }

    private fun loadOfflineSeasons(seriesName: String) = viewModelScope.launch {
        val seasons = jellyfinRepo.getCachedSeasonsBySeriesName(seriesName)
        _state.update { it.copy(seasons = seasons) }
        val idsBySeason = seasons.associate { season ->
            val num = season.indexNumber
            val episodes = if (num != null) jellyfinRepo.getCachedEpisodesBySeriesAndSeason(seriesName, num) else emptyList()
            season.id to episodes.map { it.id }
        }
        _state.update { it.copy(seasonEpisodeIds = idsBySeason) }
    }

    // Fetches each season's episode IDs so poster cards can show a "downloaded X/Y" rollup.
    // Also warms the offline cache for these episodes as a side effect of getItems().
    private fun loadSeasonEpisodeIds(seasons: List<JellyfinItem>) = viewModelScope.launch {
        val idsBySeason = seasons.map { season ->
            async {
                val episodes = jellyfinRepo.getItems(parentId = season.id, types = "Episode").getOrNull()?.Items ?: emptyList()
                season.id to episodes.map { it.id }
            }
        }.awaitAll().toMap()
        _state.update { it.copy(seasonEpisodeIds = idsBySeason) }
    }

    fun selectSeason(index: Int) = viewModelScope.launch {
        _state.update { it.copy(selectedSeasonIndex = index, isLoadingEpisodes = true) }
        val season = _state.value.seasons.getOrNull(index) ?: return@launch
        jellyfinRepo.getItems(parentId = season.id, types = "Episode")
            .onSuccess { response ->
                _state.update { it.copy(episodes = response.Items, isLoadingEpisodes = false) }
            }
            .onFailure {
                // Offline: load only downloaded episodes for this series + season
                val seriesName = _state.value.item?.name ?: run {
                    _state.update { it.copy(isLoadingEpisodes = false) }
                    return@launch
                }
                val seasonNumber = season.indexNumber ?: run {
                    _state.update { it.copy(isLoadingEpisodes = false) }
                    return@launch
                }
                val episodes = jellyfinRepo.getCachedEpisodesBySeriesAndSeason(seriesName, seasonNumber)
                _state.update { it.copy(episodes = episodes, isLoadingEpisodes = false) }
            }
    }

    fun queueDownload(preset: String) = viewModelScope.launch {
        val item = _state.value.item ?: return@launch
        val mediaPath = item.mediaSources?.firstOrNull()?.path ?: return@launch
        downloadRepo.queueTranscode(item, preset, mediaPath).onFailure {
            _state.update { s -> s.copy(downloadError = "Couldn't start download: ${it.message ?: "unknown error"}") }
        }
    }

    fun deleteDownload(itemId: String) = viewModelScope.launch {
        downloadRepo.deleteDownload(itemId)
    }

    fun retryDownload(itemId: String) = viewModelScope.launch {
        val entity = downloadRepo.findById(itemId) ?: return@launch
        downloadRepo.retryTranscode(entity).onFailure {
            _state.update { s -> s.copy(downloadError = "Couldn't retry download: ${it.message ?: "unknown error"}") }
        }
    }

    fun clearDownloadError() = _state.update { it.copy(downloadError = null) }

    fun toggleFavorite() = viewModelScope.launch {
        val item = _state.value.item ?: return@launch
        favoriteRepo.toggle(item)
    }

    fun togglePlayed() = viewModelScope.launch {
        val item = _state.value.item ?: return@launch
        val nowPlayed = !_state.value.isPlayed
        if (nowPlayed) jellyfinRepo.markPlayed(item.id)
        else jellyfinRepo.markUnplayed(item.id)
        _state.update { it.copy(isPlayed = nowPlayed) }
    }

    fun toggleEpisodeView() = viewModelScope.launch {
        val newValue = !_state.value.episodeViewGrid
        _state.update { it.copy(episodeViewGrid = newValue) }
        settings.saveEpisodeViewGrid(newValue)
    }

    fun backdropUrl(itemId: String) =
        JellyfinImageHelper.backdropImageUrl(_state.value.jellyfinUrl, itemId)

    fun logoUrl(itemId: String) =
        JellyfinImageHelper.logoImageUrl(_state.value.jellyfinUrl, itemId)

    fun streamUrl(itemId: String) =
        JellyfinImageHelper.streamUrl(_state.value.jellyfinUrl, itemId, _state.value.jellyfinToken)

    fun posterUrl(itemId: String) =
        JellyfinImageHelper.primaryImageUrl(_state.value.jellyfinUrl, itemId)

    fun thumbnailUrl(itemId: String) =
        JellyfinImageHelper.primaryImageUrl(_state.value.jellyfinUrl, itemId, maxWidth = 500)
}

data class JobSummary(
    val title: String,
    val status: String,
    val preset: String,
    val progress: Float,
)

data class AdminState(
    val jellyfinUrl: String = "",
    val username: String = "",
    val password: String = "",
    val shimUrl: String = "",
    val downloadPath: String = "",
    val isPinEnabled: Boolean = false,
    val settingsLoaded: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isAuthenticating: Boolean = false,
    val authError: String? = null,
    val isTestingShim: Boolean = false,
    val shimOk: Boolean? = null,
    val storageInfo: String? = null,
    val activeJobs: List<JobSummary> = emptyList(),
    val wifiOnly: Boolean = false,
    val showContinueWatching: Boolean = true,
    val showRecentlyAdded: Boolean = true,
    val showMyList: Boolean = true,
    val autoPlayNextEpisode: Boolean = true,
    val maxConcurrentDownloads: Int = 1,
)

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val jellyfinRepo: JellyfinRepository,
    private val downloadRepo: DownloadRepository,
    private val shimApi: com.fuzzymistborn.jellyjar.api.ShimApiService,
    private val okHttpClient: okhttp3.OkHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.update {
                    it.copy(
                        jellyfinUrl = s.jellyfinUrl,
                        shimUrl = s.shimUrl,
                        downloadPath = s.downloadPath,
                        isPinEnabled = s.isPinEnabled,
                        isAuthenticated = s.jellyfinToken.isNotBlank(),
                        settingsLoaded = true,
                        wifiOnly = s.wifiOnly,
                        showContinueWatching = s.showContinueWatching,
                        showRecentlyAdded = s.showRecentlyAdded,
                        showMyList = s.showMyList,
                        autoPlayNextEpisode = s.autoPlayNextEpisode,
                        maxConcurrentDownloads = s.maxConcurrentDownloads,
                    )
                }
                refreshStorageInfo()
            }
        }
        viewModelScope.launch {
            downloadRepo.downloads.collect { downloads ->
                _state.update {
                    it.copy(activeJobs = downloads
                        .filter { d -> d.status !in listOf("COMPLETE", "FAILED") }
                        .map { d -> JobSummary(d.title, d.status, d.preset, d.progress) }
                    )
                }
                refreshStorageInfo()
            }
        }
    }

    fun setJellyfinUrl(v: String) = _state.update { it.copy(jellyfinUrl = v) }
    fun setUsername(v: String) = _state.update { it.copy(username = v) }
    fun setPassword(v: String) = _state.update { it.copy(password = v) }
    fun setShimUrl(v: String) = _state.update { it.copy(shimUrl = v) }
    fun setDownloadPath(v: String) = _state.update { it.copy(downloadPath = v) }

    fun authenticate() = viewModelScope.launch {
        _state.update { it.copy(isAuthenticating = true, authError = null) }
        jellyfinRepo.authenticate(
            url = ensureScheme(_state.value.jellyfinUrl),
            username = _state.value.username,
            password = _state.value.password,
        ).onSuccess { result ->
            settings.saveJellyfinAuth(ensureScheme(_state.value.jellyfinUrl), result.userId, result.token)
            _state.update { it.copy(isAuthenticating = false, isAuthenticated = true) }
        }.onFailure { e ->
            _state.update { it.copy(isAuthenticating = false, authError = e.message) }
        }
    }

    fun testShim() = viewModelScope.launch {
        _state.update { it.copy(isTestingShim = true, shimOk = null) }
        val shimUrl = ensureScheme(_state.value.shimUrl)
        if (shimUrl.isBlank()) {
            _state.update { it.copy(isTestingShim = false, shimOk = false) }
            return@launch
        }
        runCatching {
            val url = shimUrl.trimEnd('/') + "/"
            android.util.Log.d("JellyJar", "Testing Press at: $url")
            val retrofit = retrofit2.Retrofit.Builder()
                .baseUrl(url)
                .client(okHttpClient)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build()
            retrofit.create(com.fuzzymistborn.jellyjar.api.ShimApiService::class.java).health()
        }
            .onSuccess { _state.update { it.copy(isTestingShim = false, shimOk = true) } }
            .onFailure { e ->
                android.util.Log.e("JellyJar", "Press test failed: ${e.message}")
                _state.update { it.copy(isTestingShim = false, shimOk = false) }
            }
    }

    private fun ensureScheme(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        // IP addresses default to http (LAN), domain names default to https
        val looksLikeIp = trimmed.split(":")[0].matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        return if (looksLikeIp) "http://$trimmed" else "https://$trimmed"
    }

    fun savePin(pin: String) = viewModelScope.launch {
        if (pin.isBlank()) settings.clearPin()
        else settings.setPin(pin)
    }

    fun clearPin() = viewModelScope.launch { settings.clearPin() }

    fun verifyPin(input: String): Boolean = runBlocking {
        val s = settings.settings.first()
        if (!s.isPinEnabled) return@runBlocking true
        settings.verifyPin(input, s.parentalPinHash)
    }

    fun setWifiOnly(v: Boolean) = _state.update { it.copy(wifiOnly = v) }

    fun setShowContinueWatching(v: Boolean) {
        _state.update { it.copy(showContinueWatching = v) }
        viewModelScope.launch { settings.saveShowContinueWatching(v) }
    }

    fun setShowRecentlyAdded(v: Boolean) {
        _state.update { it.copy(showRecentlyAdded = v) }
        viewModelScope.launch { settings.saveShowRecentlyAdded(v) }
    }

    fun setShowMyList(v: Boolean) {
        _state.update { it.copy(showMyList = v) }
        viewModelScope.launch { settings.saveShowMyList(v) }
    }

    fun setAutoPlayNextEpisode(v: Boolean) {
        _state.update { it.copy(autoPlayNextEpisode = v) }
        viewModelScope.launch { settings.saveAutoPlayNextEpisode(v) }
    }

    fun setMaxConcurrentDownloads(v: Int) {
        _state.update { it.copy(maxConcurrentDownloads = v) }
        viewModelScope.launch { settings.saveMaxConcurrentDownloads(v) }
    }

    fun saveAll() = viewModelScope.launch {
        settings.saveShimUrl(ensureScheme(_state.value.shimUrl))
        settings.saveDownloadPath(_state.value.downloadPath)
        settings.saveWifiOnly(_state.value.wifiOnly)
        refreshStorageInfo()
    }

    private suspend fun refreshStorageInfo() {
        val info = downloadRepo.getDeviceStorageInfo()
        _state.update { it.copy(storageInfo = formatStorageInfo(info)) }
    }

    private fun formatStorageInfo(info: com.fuzzymistborn.jellyjar.data.repository.DeviceStorageInfo): String {
        fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
        return "${gb(info.usedByJellyJarBytes)} used by JellyJar · ${gb(info.freeBytes)} free of ${gb(info.totalBytes)}"
    }
}

private fun <T> runBlocking(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }

// ─── Downloads Queue ViewModel ────────────────────────────────────────────────

data class StorageStats(
    val totalMb: Long,
    val watchedMb: Long,
    val watchedCount: Int,
    val watchedItems: List<com.fuzzymistborn.jellyjar.data.local.DownloadEntity>,
)

data class DownloadsState(
    val active: List<com.fuzzymistborn.jellyjar.data.local.DownloadEntity> = emptyList(),
    val queued: List<com.fuzzymistborn.jellyjar.data.local.DownloadEntity> = emptyList(),
    val completed: List<com.fuzzymistborn.jellyjar.data.local.DownloadEntity> = emptyList(),
    val failed: List<com.fuzzymistborn.jellyjar.data.local.DownloadEntity> = emptyList(),
    val queuePaused: Boolean = false,
    val jellyfinUrl: String = "",
    val storageStats: StorageStats? = null,
    val etaByJellyfinId: Map<String, Int?> = emptyMap(),
    val downloadError: String? = null,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepo: DownloadRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = _state.asStateFlow()

    private val progressSamples = mutableMapOf<String, Pair<Float, Long>>()

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.update { it.copy(jellyfinUrl = s.jellyfinUrl, queuePaused = s.downloadQueuePaused) }
            }
        }
        viewModelScope.launch {
            downloadRepo.downloads.collect { all ->
                val now = System.currentTimeMillis()
                val activeStatusNames = listOf(
                    com.fuzzymistborn.jellyjar.model.DownloadStatus.TRANSCODING.name,
                    com.fuzzymistborn.jellyjar.model.DownloadStatus.DOWNLOADING.name,
                )
                val newEtas = mutableMapOf<String, Int?>()
                all.filter { d -> d.status in activeStatusNames }.forEach { d ->
                    val prev = progressSamples[d.jellyfinId]
                    val eta: Int? = if (prev != null && d.progress > prev.first && d.progress < 100f) {
                        val elapsedMs = now - prev.second
                        if (elapsedMs > 0) {
                            val rate = (d.progress - prev.first) / elapsedMs.toFloat()
                            ((100f - d.progress) / rate / 60000f).toInt().coerceAtLeast(1)
                        } else null
                    } else null
                    newEtas[d.jellyfinId] = eta
                    progressSamples[d.jellyfinId] = d.progress to now
                }

                val completed = all.filter { d -> d.status == com.fuzzymistborn.jellyjar.model.DownloadStatus.COMPLETE.name }
                val totalMb = completed.sumOf { it.sizeBytes } / (1024 * 1024)
                val watched = completed.filter { d ->
                    val runtimeMs = (d.runtimeMinutes ?: 0).toLong() * 60_000L
                    runtimeMs > 0 && d.playbackPositionMs >= runtimeMs * 0.8
                }
                val watchedMb = watched.sumOf { it.sizeBytes } / (1024 * 1024)
                val stats = if (watched.isNotEmpty()) StorageStats(totalMb, watchedMb, watched.size, watched) else null

                _state.update {
                    it.copy(
                        active = all.filter { d -> d.status in activeStatusNames },
                        queued = all
                            .filter { d -> d.status == com.fuzzymistborn.jellyjar.model.DownloadStatus.QUEUED.name }
                            .sortedWith(compareBy({ d -> d.queuePosition }, { d -> d.addedAt })),
                        completed = completed,
                        failed = all.filter { d -> d.status == com.fuzzymistborn.jellyjar.model.DownloadStatus.FAILED.name },
                        etaByJellyfinId = newEtas,
                        storageStats = stats,
                    )
                }
            }
        }
    }

    fun cancelDownload(jellyfinId: String) = viewModelScope.launch {
        downloadRepo.deleteDownload(jellyfinId)
    }

    fun removeDownload(jellyfinId: String) = viewModelScope.launch {
        downloadRepo.deleteDownload(jellyfinId)
    }

    fun retryDownload(jellyfinId: String) = viewModelScope.launch {
        val entity = downloadRepo.findById(jellyfinId) ?: return@launch
        downloadRepo.retryTranscode(entity).onFailure {
            _state.update { s -> s.copy(downloadError = "Couldn't retry download: ${it.message ?: "unknown error"}") }
        }
    }

    fun clearDownloadError() = _state.update { it.copy(downloadError = null) }

    fun pauseQueue() = viewModelScope.launch { settings.saveDownloadQueuePaused(true) }

    fun resumeQueue() = viewModelScope.launch { settings.saveDownloadQueuePaused(false) }

    fun moveUp(jellyfinId: String) = viewModelScope.launch { downloadRepo.moveInQueue(jellyfinId, -1) }

    fun moveDown(jellyfinId: String) = viewModelScope.launch { downloadRepo.moveInQueue(jellyfinId, 1) }

    fun prioritize(jellyfinId: String) = viewModelScope.launch { downloadRepo.prioritize(jellyfinId) }

    fun cancelAllActive() = viewModelScope.launch {
        (_state.value.active + _state.value.queued).forEach { downloadRepo.deleteDownload(it.jellyfinId) }
    }

    fun deleteAllCompleted() = viewModelScope.launch {
        _state.value.completed.forEach { downloadRepo.deleteDownload(it.jellyfinId) }
    }

    fun retryAllFailed() = viewModelScope.launch {
        var failCount = 0
        _state.value.failed.forEach { downloadRepo.retryTranscode(it).onFailure { failCount++ } }
        if (failCount > 0) _state.update { it.copy(downloadError = "$failCount download(s) failed to retry") }
    }

    fun clearAllFailed() = viewModelScope.launch {
        _state.value.failed.forEach { downloadRepo.deleteDownload(it.jellyfinId) }
    }

    fun deleteWatched() = viewModelScope.launch {
        _state.value.storageStats?.watchedItems?.forEach { downloadRepo.deleteDownload(it.jellyfinId) }
    }

    fun thumbnailUrl(jellyfinId: String): String =
        com.fuzzymistborn.jellyjar.api.JellyfinImageHelper.primaryImageUrl(
            _state.value.jellyfinUrl, jellyfinId, maxWidth = 200
        )
}

// ─── Storage ViewModel ────────────────────────────────────────────────────────

enum class StorageSort { SIZE, NEWEST, OLDEST, NAME }

data class StorageState(
    val items: List<DownloadEntity> = emptyList(),
    val sort: StorageSort = StorageSort.SIZE,
    val movieCount: Int = 0,
    val movieBytes: Long = 0L,
    val episodeCount: Int = 0,
    val episodeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val deviceTotalBytes: Long = 0L,
    val watchedItems: List<DownloadEntity> = emptyList(),
    val watchedBytes: Long = 0L,
) {
    val sortedItems: List<DownloadEntity> get() = when (sort) {
        StorageSort.SIZE -> items.sortedByDescending { it.sizeBytes }
        StorageSort.NEWEST -> items.sortedByDescending { it.addedAt }
        StorageSort.OLDEST -> items.sortedBy { it.addedAt }
        StorageSort.NAME -> items.sortedBy { it.title.lowercase() }
    }
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val downloadRepo: DownloadRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageState())
    val state: StateFlow<StorageState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            downloadRepo.completedDownloads.collect { completed ->
                val movies = completed.filter { it.type == "Movie" }
                val episodes = completed.filter { it.type == "Episode" }
                val watched = completed.filter { d ->
                    val runtimeMs = (d.runtimeMinutes ?: 0).toLong() * 60_000L
                    runtimeMs > 0 && d.playbackPositionMs >= runtimeMs * 0.8
                }
                val info = downloadRepo.getDeviceStorageInfo()
                _state.update {
                    it.copy(
                        items = completed,
                        movieCount = movies.size,
                        movieBytes = movies.sumOf { m -> m.sizeBytes },
                        episodeCount = episodes.size,
                        episodeBytes = episodes.sumOf { e -> e.sizeBytes },
                        totalBytes = completed.sumOf { c -> c.sizeBytes },
                        freeBytes = info.freeBytes,
                        deviceTotalBytes = info.totalBytes,
                        watchedItems = watched,
                        watchedBytes = watched.sumOf { w -> w.sizeBytes },
                    )
                }
            }
        }
    }

    fun setSort(sort: StorageSort) = _state.update { it.copy(sort = sort) }

    fun delete(jellyfinId: String) = viewModelScope.launch {
        downloadRepo.deleteDownload(jellyfinId)
    }

    fun deleteWatched() = viewModelScope.launch {
        _state.value.watchedItems.forEach { downloadRepo.deleteDownload(it.jellyfinId) }
    }

    fun deleteOldest(count: Int) = viewModelScope.launch {
        _state.value.items.sortedBy { it.addedAt }.take(count)
            .forEach { downloadRepo.deleteDownload(it.jellyfinId) }
    }

    fun deleteEverything() = viewModelScope.launch {
        _state.value.items.forEach { downloadRepo.deleteDownload(it.jellyfinId) }
    }
}

// ─── Seasons ViewModel ────────────────────────────────────────────────────────

// ─── Season ViewModel ─────────────────────────────────────────────────────────

data class SeasonState(
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonName: String? = null,
    val episodes: List<JellyfinItem> = emptyList(),
    val episodeDownloads: Map<String, DownloadEntity> = emptyMap(),
    val isLoading: Boolean = false,
    val isOnline: Boolean = true,
    val episodeViewGrid: Boolean = true,
    val error: String? = null,
    val downloadError: String? = null,
    val jellyfinUrl: String = "",
    val jellyfinToken: String = "",
)

@HiltViewModel
class SeasonViewModel @Inject constructor(
    private val jellyfinRepo: JellyfinRepository,
    private val downloadRepo: DownloadRepository,
    private val settings: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(SeasonState())
    val state: StateFlow<SeasonState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                _state.update { it.copy(isOnline = online) }
            }
        }
        viewModelScope.launch {
            downloadRepo.downloads.collect { all ->
                _state.update { it.copy(episodeDownloads = all.associateBy { d -> d.jellyfinId }) }
            }
        }
    }

    fun load(seasonId: String, seriesId: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, seriesId = seriesId) }
        val s = settings.settings.first()
        _state.update { it.copy(jellyfinUrl = s.jellyfinUrl, jellyfinToken = s.jellyfinToken, episodeViewGrid = s.episodeViewGrid) }

        // Load season info — cache result for offline episode fallback
        val cachedSeason = jellyfinRepo.getCachedItem(seasonId)
        jellyfinRepo.getItem(seasonId)
            .onSuccess { season ->
                _state.update { it.copy(seasonName = season.name, seriesName = season.seriesName) }
            }
            .onFailure {
                if (cachedSeason != null) {
                    _state.update { it.copy(seasonName = cachedSeason.name, seriesName = cachedSeason.seriesName) }
                }
            }

        // Load episodes
        jellyfinRepo.getItems(parentId = seasonId, types = "Episode")
            .onSuccess { response ->
                _state.update { it.copy(episodes = response.Items, isLoading = false) }
            }
            .onFailure {
                val seriesName = cachedSeason?.seriesName
                val seasonNumber = cachedSeason?.indexNumber
                val episodes = if (seriesName != null && seasonNumber != null)
                    jellyfinRepo.getCachedEpisodesBySeriesAndSeason(seriesName, seasonNumber)
                else {
                    _state.update { it.copy(error = "Unavailable offline") }
                    emptyList()
                }
                _state.update { it.copy(episodes = episodes, isLoading = false) }
            }

    }

    fun queueEpisodeDownload(episode: JellyfinItem, preset: String) = viewModelScope.launch {
        val path = episode.mediaSources?.firstOrNull()?.path ?: return@launch
        downloadRepo.queueTranscode(episode, preset, path).onFailure {
            _state.update { s -> s.copy(downloadError = "Couldn't start download: ${it.message ?: "unknown error"}") }
        }
    }

    fun deleteDownload(itemId: String) = viewModelScope.launch {
        downloadRepo.deleteDownload(itemId)
    }

    fun retryEpisodeDownload(itemId: String) = viewModelScope.launch {
        val entity = downloadRepo.findById(itemId) ?: return@launch
        downloadRepo.retryTranscode(entity).onFailure {
            _state.update { s -> s.copy(downloadError = "Couldn't retry download: ${it.message ?: "unknown error"}") }
        }
    }

    fun clearDownloadError() = _state.update { it.copy(downloadError = null) }

    fun downloadRemaining(preset: String) = viewModelScope.launch {
        val downloads = _state.value.episodeDownloads
        var failCount = 0
        _state.value.episodes
            .filter { ep ->
                val dl = downloads[ep.id]
                dl == null || dl.status == DownloadStatus.FAILED.name
            }
            .forEach { ep ->
                ep.mediaSources?.firstOrNull()?.path?.let { path ->
                    downloadRepo.queueTranscode(ep, preset, path).onFailure { failCount++ }
                }
            }
        if (failCount > 0) _state.update { it.copy(downloadError = "$failCount episode(s) failed to queue") }
    }

    fun downloadNextN(n: Int, preset: String) = viewModelScope.launch {
        val downloads = _state.value.episodeDownloads
        var failCount = 0
        _state.value.episodes
            .filter { ep ->
                val dl = downloads[ep.id]
                dl == null || dl.status == DownloadStatus.FAILED.name
            }
            .take(n)
            .forEach { ep ->
                ep.mediaSources?.firstOrNull()?.path?.let { path ->
                    downloadRepo.queueTranscode(ep, preset, path).onFailure { failCount++ }
                }
            }
        if (failCount > 0) _state.update { it.copy(downloadError = "$failCount episode(s) failed to queue") }
    }

    fun toggleEpisodeView() = viewModelScope.launch {
        val newValue = !_state.value.episodeViewGrid
        _state.update { it.copy(episodeViewGrid = newValue) }
        settings.saveEpisodeViewGrid(newValue)
    }

    fun backdropUrl() =
        _state.value.seriesId?.let {
            JellyfinImageHelper.backdropImageUrl(_state.value.jellyfinUrl, it)
        } ?: ""

    fun thumbnailUrl(itemId: String) =
        JellyfinImageHelper.primaryImageUrl(_state.value.jellyfinUrl, itemId, maxWidth = 500)

    fun streamUrl(itemId: String) =
        JellyfinImageHelper.streamUrl(_state.value.jellyfinUrl, itemId, _state.value.jellyfinToken)
}

// ─── Player ViewModel ─────────────────────────────────────────────────────────

sealed class NextEpisodeTarget {
    data class Local(val localPath: String, val jellyfinId: String) : NextEpisodeTarget()
    data class Stream(val streamUrl: String, val jellyfinId: String) : NextEpisodeTarget()
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val downloadRepo: DownloadRepository,
    private val jellyfinRepo: JellyfinRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    fun loadStartPosition(jellyfinId: String): Long =
        kotlinx.coroutines.runBlocking { downloadRepo.getPlaybackPosition(jellyfinId) }

    // Resolves the episode that should play next when `currentJellyfinId` finishes, honoring the
    // Auto-play Next Episode setting. Tries the live Jellyfin server first, then falls back to the
    // offline cache (only episodes with a completed local download can be targeted while offline).
    suspend fun resolveNextEpisode(currentJellyfinId: String): NextEpisodeTarget? {
        if (!settings.currentSnapshot().autoPlayNextEpisode) return null

        // If the server is reachable, trust its answer outright — including "no next episode"
        // (series finale) — rather than falling through to a possibly-stale offline cache.
        val onlineCurrent = jellyfinRepo.getItem(currentJellyfinId).getOrNull()
        if (onlineCurrent != null) {
            val next = jellyfinRepo.findNextEpisodeOnline(onlineCurrent) ?: return null
            val dl = downloadRepo.findById(next.id)
            return if (dl?.status == com.fuzzymistborn.jellyjar.model.DownloadStatus.COMPLETE.name) {
                NextEpisodeTarget.Local(dl.localPath, next.id)
            } else {
                val s = settings.currentSnapshot()
                NextEpisodeTarget.Stream(JellyfinImageHelper.streamUrl(s.jellyfinUrl, next.id, s.jellyfinToken), next.id)
            }
        }

        val cached = jellyfinRepo.getCachedItem(currentJellyfinId) ?: return null
        val seriesName = cached.seriesName ?: return null
        val seasonNumber = cached.parentIndexNumber ?: return null
        if (cached.type != "Episode") return null

        val inSeason = jellyfinRepo.getCachedEpisodesBySeriesAndSeason(seriesName, seasonNumber)
            .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
        val next = inSeason.firstOrNull { (it.indexNumber ?: -1) > (cached.indexNumber ?: -1) }
            ?: jellyfinRepo.getCachedSeasonsBySeriesName(seriesName)
                .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
                .firstOrNull { (it.indexNumber ?: -1) > seasonNumber }
                ?.indexNumber
                ?.let { nextSeasonNum ->
                    jellyfinRepo.getCachedEpisodesBySeriesAndSeason(seriesName, nextSeasonNum)
                        .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
                        .firstOrNull()
                }
            ?: return null

        val dl = downloadRepo.findById(next.id)
        return if (dl?.status == com.fuzzymistborn.jellyjar.model.DownloadStatus.COMPLETE.name) {
            NextEpisodeTarget.Local(dl.localPath, next.id)
        } else null
    }

    fun savePosition(jellyfinId: String, positionMs: Long) = viewModelScope.launch {
        downloadRepo.updatePlaybackPosition(jellyfinId, positionMs)
    }

    fun reportStart(jellyfinId: String, positionMs: Long, mediaSourceId: String? = null) = viewModelScope.launch {
        jellyfinRepo.reportPlaybackStart(jellyfinId, positionMs, mediaSourceId)
    }

    fun reportProgress(jellyfinId: String, positionMs: Long, isPaused: Boolean, mediaSourceId: String? = null) = viewModelScope.launch {
        jellyfinRepo.reportPlaybackProgress(jellyfinId, positionMs, isPaused, mediaSourceId)
    }

    fun reportStopped(jellyfinId: String, positionMs: Long, mediaSourceId: String? = null) {
        // Use a detached scope so this survives viewModelScope cancellation on ViewModel clear
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            jellyfinRepo.reportPlaybackStopped(jellyfinId, positionMs, mediaSourceId)
        }
    }
}

// ─── Seasons (legacy series browser) ViewModel ────────────────────────────────

data class SeasonsState(
    val seriesName: String? = null,
    val seasons: List<JellyfinItem> = emptyList(),
    val episodes: List<JellyfinItem> = emptyList(),
    val selectedSeasonIndex: Int? = null,
    val isLoading: Boolean = false,
    val jellyfinUrl: String = "",
    val jellyfinToken: String = "",
)

@HiltViewModel
class SeasonsViewModel @Inject constructor(
    private val jellyfinRepo: JellyfinRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SeasonsState())
    val state: StateFlow<SeasonsState> = _state.asStateFlow()

    fun load(seriesId: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }
        val s = settings.settings.first()
        _state.update { it.copy(jellyfinUrl = s.jellyfinUrl, jellyfinToken = s.jellyfinToken) }

        // Load series info for the name
        jellyfinRepo.getItem(seriesId).onSuccess { series ->
            _state.update { it.copy(seriesName = series.name) }
        }

        // Load seasons — show poster grid first, no auto-selection
        jellyfinRepo.getItems(
            parentId = seriesId,
            types = "Season",
        ).onSuccess { response ->
            _state.update { it.copy(seasons = response.Items, isLoading = false) }
        }.onFailure {
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun selectSeason(index: Int) = viewModelScope.launch {
        _state.update { it.copy(selectedSeasonIndex = index, isLoading = true) }
        val seasonId = _state.value.seasons.getOrNull(index)?.id ?: return@launch
        jellyfinRepo.getItems(
            parentId = seasonId,
            types = "Episode",
        ).onSuccess { response ->
            _state.update { it.copy(episodes = response.Items, isLoading = false) }
        }.onFailure {
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun clearSeasonSelection() {
        _state.update { it.copy(selectedSeasonIndex = null, episodes = emptyList()) }
    }

    fun posterUrl(itemId: String) =
        JellyfinImageHelper.primaryImageUrl(_state.value.jellyfinUrl, itemId)

    fun thumbnailUrl(itemId: String) =
        JellyfinImageHelper.primaryImageUrl(_state.value.jellyfinUrl, itemId, maxWidth = 500)

    fun streamUrl(itemId: String) =
        JellyfinImageHelper.streamUrl(_state.value.jellyfinUrl, itemId, _state.value.jellyfinToken)
}
