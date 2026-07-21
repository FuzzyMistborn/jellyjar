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
    val downloadProgress: Map<String, Float> = emptyMap(),
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.DEFAULT,
    val genres: List<String> = emptyList(),
    val selectedGenre: String? = null,
    val showContinueWatching: Boolean = true,
    val showRecentlyAdded: Boolean = true,
    val showMyList: Boolean = true,
    val genreFilterEnabled: Boolean = true,
    val globalSearchActive: Boolean = false,
    val globalSearchQuery: String = "",
    val globalSearchResults: List<JellyfinItem> = emptyList(),
    val isGlobalSearching: Boolean = false,
    val resumeItems: List<JellyfinItem> = emptyList(),
    val recentlyAdded: List<JellyfinItem> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val favoriteItems: List<JellyfinItem> = emptyList(),
    // Up to 4 thumbnails from completed downloads, shown as a mosaic on the home grid's
    // Downloads tile so it reads as artwork-first like every other tile, not a flat icon card.
    val downloadThumbnails: List<String> = emptyList(),
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
        // Handles the very first time we observe `online == true` (app start); every later
        // false->true transition is handled once by `reconnected` below — collecting both here and
        // there would fire loadLibrary() twice for the same reconnect. The flag is only set once we
        // actually act on an online reading, NOT on the first emission: isOnline is a StateFlow
        // seeded false (see NetworkMonitor), so the first emission is always that false seed. Keying
        // off "first emission" would burn the flag on that seed and leave the real online cold-start
        // load to depend entirely on the `reconnected` edge — a replay=0 race that can drop the load.
        var initialLoadDone = false
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                _state.update { it.copy(isOnline = online) }
                if (online && !initialLoadDone && !_state.value.showingDownloads) {
                    initialLoadDone = true
                    loadLibrary()
                }
            }
        }
        // Keeps the offline synthetic library tiles (Movies/TV Shows, filtered to what's actually
        // downloaded) continuously in sync with connectivity and the download set, rather than
        // computing them once at the instant connectivity is lost. A one-shot computation at that
        // single edge can miss the case where the app is already offline at cold start and this
        // collector's first reading races the "online" one above, or where downloads change while
        // already offline — recomputing on every relevant change avoids that class of bug entirely.
        // Mirrors the shared cross-screen signal into local state so a live-call failure
        // discovered on another screen (Detail, Player) flips this screen's offline view too,
        // without waiting for this ViewModel's own next Jellyfin call to fail.
        viewModelScope.launch {
            networkMonitor.serverReachable.collect { reachable ->
                _state.update { it.copy(jellyfinAvailable = reachable) }
            }
        }
        viewModelScope.launch {
            combine(
                networkMonitor.isOnline,
                _state.map { it.jellyfinAvailable }.distinctUntilChanged(),
                downloadRepo.completedDownloads,
            ) { online, jellyfinAvailable, downloads -> Triple(online, jellyfinAvailable, downloads) }
                .collect { (online, jellyfinAvailable, downloads) ->
                    if (!online || !jellyfinAvailable) {
                        _state.update { s ->
                            s.copy(
                                libraries = syntheticLibrariesFor(downloads),
                                resumeItems = emptyList(),
                                recentlyAdded = emptyList(),
                            )
                        }
                    }
                }
        }
        // Retry Jellyfin with backoff when online but server is unreachable; stops once available
        viewModelScope.launch {
            _state.map { it.jellyfinAvailable }.distinctUntilChanged().collectLatest { available ->
                if (available) return@collectLatest
                var delayMs = 15_000L
                while (true) {
                    kotlinx.coroutines.delay(delayMs)
                    if (_state.value.isOnline && !_state.value.showingDownloads) {
                        loadLibrary()
                        delayMs = (delayMs * 2).coerceAtMost(60_000L)
                    }
                }
            }
        }
        // Device connectivity dropped and came back (Wi-Fi toggle, network switch, etc.) —
        // re-check Jellyfin immediately instead of waiting on the backoff loop above, mirroring
        // DetailViewModel's use of the same signal. The first `reconnected` emission is always the
        // cold-start seed(false)->true resolution (NetworkMonitor seeds isOnline false), which the
        // isOnline collector above already handles as the initial load — skip it here so the two
        // don't both fire loadLibrary() for that one edge.
        var firstReconnect = true
        viewModelScope.launch {
            networkMonitor.reconnected.collect {
                if (firstReconnect) {
                    firstReconnect = false
                    return@collect
                }
                if (!_state.value.showingDownloads) loadLibrary()
            }
        }
        viewModelScope.launch {
            settings.settings.collect { s ->
                val prevToken = _state.value.jellyfinToken
                val prevUrl = _state.value.jellyfinUrl
                _state.update { it.copy(jellyfinUrl = s.jellyfinUrl, jellyfinToken = s.jellyfinToken, showContinueWatching = s.showContinueWatching, showRecentlyAdded = s.showRecentlyAdded, showMyList = s.showMyList, genreFilterEnabled = s.genreFilterEnabled) }
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
                    it.copy(
                        downloadStatuses = downloads.associateBy({ it.jellyfinId }, { it.status }),
                        downloadProgress = downloads.associateBy({ it.jellyfinId }, { it.progress }),
                        downloadThumbnails = downloads
                            .filter { d -> d.status == DownloadStatus.COMPLETE.name }
                            .sortedByDescending { d -> d.addedAt }
                            .mapNotNull { d -> d.thumbnailUri }
                            .take(4),
                    )
                }
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
                networkMonitor.reportServerReachable(true)
                _state.update { s -> s.copy(libraries = libs) }
                loadHomeRows()
            }
            .onFailure {
                // Reported through NetworkMonitor rather than set on local state directly: the
                // serverReachable mirror collector in init{} updates jellyfinAvailable, which the
                // reactive offline-libraries collector then picks up to rebuild `libraries` from
                // what's actually downloaded.
                networkMonitor.reportServerReachable(false)
                _state.update { s -> s.copy(isLoading = false, isRefreshing = false, resumeItems = emptyList(), recentlyAdded = emptyList()) }
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
        // Items and genre chips are independent of each other — fetch them concurrently instead
        // of paying for two sequential round trips.
        val itemsDeferred = async {
            jellyfinRepo.getItems(parentId = parentId, types = types, startIndex = 0, genres = _state.value.selectedGenre)
        }
        val fetchGenres = selectedLib != null && _state.value.genres.isEmpty()
        val genresDeferred = if (fetchGenres) async { jellyfinRepo.getGenres(parentId) } else null

        itemsDeferred.await()
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
                networkMonitor.reportServerReachable(false)
                _state.update { s -> s.copy(isLoading = false, isRefreshing = false) }
            }
        genresDeferred?.await()?.onSuccess { genres ->
            _state.update { s -> s.copy(genres = genres) }
        }
    }

    private fun loadHomeRows() = viewModelScope.launch {
        jellyfinRepo.getResumeItems().onSuccess { items ->
            _state.update { it.copy(resumeItems = items) }
        }
        jellyfinRepo.getRecentlyAdded().onSuccess { items ->
            _state.update { it.copy(recentlyAdded = items) }
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
                genres = _state.value.selectedGenre,
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

    private fun syntheticLibrariesFor(downloads: List<DownloadEntity>): List<JellyfinLibrary> = buildList {
        if (downloads.any { it.type == "Movie" })
            add(JellyfinLibrary(id = "offline_movies", name = "Movies", collectionType = "movies"))
        if (downloads.any { it.type == "Episode" })
            add(JellyfinLibrary(id = "offline_tv", name = "TV Shows", collectionType = "tvshows"))
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
                    .map { seriesName ->
                        // Series cache row can be missing (e.g. it predates cacheParentSeriesIfMissing,
                        // or the cache was cleared) even though its episodes are downloaded. Falling
                        // back to a stub keeps the tile visible instead of silently dropping it — the
                        // offline season/episode lookups below key off `name`, not `id`, so a
                        // synthetic id is fine for navigation.
                        jellyfinRepo.getCachedSeriesByName(seriesName)
                            ?: JellyfinItem(
                                id = "offline-series-$seriesName",
                                name = seriesName,
                                type = "Series",
                                overview = null, year = null, communityRating = null, runTimeTicks = null,
                                seriesName = null, seasonName = null, indexNumber = null, parentIndexNumber = null,
                                mediaSources = null, imageTags = null, backdropImageTags = null, userData = null,
                            )
                    }
            }
            else -> downloads.map { it.toJellyfinItem() }
        }
        _state.update { s -> s.copy(items = items, totalCount = items.size, isLoading = false) }
    }

    fun goHome() {
        _state.update { it.copy(selectedLibrary = null, showingDownloads = false, items = emptyList(), totalCount = 0, genres = emptyList(), selectedGenre = null) }
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
        _state.update { it.copy(selectedLibrary = name, items = emptyList(), totalCount = 0, genres = emptyList(), selectedGenre = null) }
        if (name != null) {
            // Read networkMonitor.isOnline.value directly, not _state.value.isOnline: the state
            // field defaults to true and is only synced by an async collector launched in init,
            // so a cold-start tap racing that collector could take the live path while genuinely
            // offline (same class of bug as DetailViewModel's loadItem/selectSeason).
            if (networkMonitor.isOnline.value && _state.value.jellyfinAvailable) loadLibrary()
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

    // Genre filtering is server-side (results are paginated), so changing it reloads the library
    fun setGenre(genre: String?) {
        if (_state.value.selectedGenre == genre) return
        _state.update { it.copy(selectedGenre = genre, items = emptyList(), totalCount = 0) }
        if (networkMonitor.isOnline.value && _state.value.jellyfinAvailable) loadLibrary()
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
                        container = "mp4", mediaStreams = null)
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
    val isWifi: Boolean = true,
    val streamOverCellular: Boolean = false,
    val error: String? = null,
    val downloadError: String? = null,
    val jellyfinUrl: String = "",
    val jellyfinToken: String = "",
    val episodeViewGrid: Boolean = false,
    val streamPositionMs: Long = 0L,
    val isFavorite: Boolean = false,
    val isPlayed: Boolean = false,
) {
    // Streaming/direct-play requires Wi-Fi unless the user opted into cellular streaming;
    // library browsing and download-queueing stay gated on plain isOnline.
    val canStream: Boolean get() = isOnline && (isWifi || streamOverCellular)
}

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
    private var loadItemJob: Job? = null
    private var currentItemId: String? = null

    init {
        viewModelScope.launch {
            combine(
                networkMonitor.isOnline,
                networkMonitor.isWifi,
                settings.settings.map { it.streamOverCellular },
            ) { online, wifi, overCellular -> Triple(online, wifi, overCellular) }
                .collect { (online, wifi, overCellular) ->
                    _state.update { it.copy(isOnline = online, isWifi = wifi, streamOverCellular = overCellular) }
                }
        }
        viewModelScope.launch {
            networkMonitor.reconnected.collect {
                currentItemId?.let { loadItem(it) }
            }
        }
        viewModelScope.launch {
            favoriteRepo.favoriteIds.collect { ids ->
                _state.update { it.copy(isFavorite = it.item?.id in ids) }
            }
        }
    }

    fun loadItem(itemId: String) {
        currentItemId = itemId
        loadItemJob?.cancel()
        loadItemJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            settings.settings.first().also { s ->
                _state.update { it.copy(jellyfinUrl = s.jellyfinUrl, jellyfinToken = s.jellyfinToken, episodeViewGrid = s.episodeViewGrid) }
            }
            val savedPositionMs = downloadRepo.getPlaybackPosition(itemId)
            _state.update { it.copy(streamPositionMs = savedPositionMs) }
            // Offline (or force-offline) must skip the live call entirely rather than only
            // falling back on failure — a reachable Jellyfin server would otherwise return every
            // season unfiltered, bypassing loadOfflineSeasons' downloads-only filtering. Read
            // networkMonitor.isOnline.value directly rather than _state.value.isOnline: the state
            // field is only synced by an async collector launched in init and can still hold its
            // default `true` in a cold-start race, whereas NetworkMonitor's StateFlow is always
            // current the moment it's constructed.
            if (!networkMonitor.isOnline.value) {
                loadOfflineItem(itemId)
            } else {
                jellyfinRepo.getItem(itemId)
                    .onSuccess { item ->
                        networkMonitor.reportServerReachable(true)
                        val isFav = favoriteRepo.isFavorite(item.id)
                        _state.update { it.copy(item = item, isLoading = false, isFavorite = isFav, isPlayed = item.userData?.played == true) }
                        if (item.type == "Series") loadSeasons(itemId)
                    }
                    .onFailure {
                        // Reports through NetworkMonitor so the Library screen (and any other
                        // screen) reflects "server unreachable" immediately, instead of only this
                        // screen falling back to its offline cache.
                        networkMonitor.reportServerReachable(false)
                        loadOfflineItem(itemId)
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
    }

    // loadOfflineLibrary's TV Shows tile falls back to a synthetic "offline-series-$seriesName"
    // id when no cached_items "Series" row exists for a series whose episodes are still
    // downloaded (e.g. the cache row was pruned independently of the downloads table). That id
    // was never persisted, so getCachedItem's by-id lookup always misses it — handle it directly
    // by name instead of erroring out with "Unavailable offline".
    private suspend fun loadOfflineItem(itemId: String) {
        val syntheticSeriesName = itemId.removePrefix("offline-series-").takeIf { itemId.startsWith("offline-series-") }
        if (syntheticSeriesName != null) {
            val stub = JellyfinItem(
                id = itemId, name = syntheticSeriesName, type = "Series",
                overview = null, year = null, communityRating = null, runTimeTicks = null,
                seriesName = null, seasonName = null, indexNumber = null, parentIndexNumber = null,
                mediaSources = null, imageTags = null, backdropImageTags = null, userData = null,
            )
            _state.update { it.copy(item = stub, isLoading = false, error = null) }
            loadOfflineSeasons(syntheticSeriesName)
            return
        }
        val cached = jellyfinRepo.getCachedItem(itemId)
        if (cached != null) {
            _state.update { it.copy(item = cached, isLoading = false, error = null) }
            if (cached.type == "Series") loadOfflineSeasons(cached.name)
        } else {
            _state.update { it.copy(isLoading = false, error = "Unavailable offline") }
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

    // Offline, "cached" (ever viewed while online) and "downloaded" (actually playable offline)
    // are different sets — getCachedSeasonsBySeriesName/getCachedEpisodesBySeriesAndSeason return
    // every season/episode this device has ever seen metadata for, not just what's downloaded.
    // Restricting to downloadRepo's completed set is what makes "only season 1 downloaded" show
    // only season 1 while offline, instead of every season the server ever listed.
    private suspend fun downloadedEpisodeIdsFor(seriesName: String): Set<String> =
        downloadRepo.completedDownloads.first()
            .filter { it.type == "Episode" && it.seriesName == seriesName }
            .map { it.jellyfinId }
            .toSet()

    // Builds the season list from downloaded Episode rows (getDownloadedSeasonNumbers), not from
    // cached Season-type rows (getCachedSeasonsBySeriesName) — the latter depends on a Season
    // row's own seriesName column, which is only populated if the season list was fetched online
    // and is unverified to be reliably set by Jellyfin on Season API objects. Episode.seriesName is
    // proven reliable elsewhere in this app, so deriving season identity from episodes instead
    // guarantees a season with a real completed download always shows up. Any matching cached
    // Season row is used only for cosmetic enrichment (poster art, overview) when it happens to
    // exist; a synthetic stub covers the case where it doesn't.
    private fun loadOfflineSeasons(seriesName: String) = viewModelScope.launch {
        val seasonNumbers = jellyfinRepo.getDownloadedSeasonNumbers(seriesName)
        val cachedSeasonsByNumber = jellyfinRepo.getCachedSeasonsBySeriesName(seriesName).associateBy { it.indexNumber }
        val seasons = seasonNumbers.map { num ->
            cachedSeasonsByNumber[num] ?: JellyfinItem(
                id = "offline-season-$seriesName-$num", name = "Season $num", type = "Season",
                overview = null, year = null, communityRating = null, runTimeTicks = null,
                seriesName = seriesName, seasonName = null, indexNumber = num, parentIndexNumber = null,
                mediaSources = null, imageTags = null, backdropImageTags = null, userData = null,
            )
        }
        val idsBySeason = seasons.associate { season ->
            val num = season.indexNumber
            val episodes = if (num != null) jellyfinRepo.getCachedEpisodesBySeriesAndSeason(seriesName, num) else emptyList()
            season.id to episodes.map { it.id }
        }
        _state.update { it.copy(seasons = seasons, seasonEpisodeIds = idsBySeason) }
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

        suspend fun loadOfflineEpisodes() {
            val seriesName = _state.value.item?.name ?: run {
                _state.update { it.copy(isLoadingEpisodes = false) }
                return
            }
            val seasonNumber = season.indexNumber ?: run {
                _state.update { it.copy(isLoadingEpisodes = false) }
                return
            }
            val downloadedIds = downloadedEpisodeIdsFor(seriesName)
            val episodes = jellyfinRepo.getCachedEpisodesBySeriesAndSeason(seriesName, seasonNumber)
                .filter { it.id in downloadedIds }
            _state.update { it.copy(episodes = episodes, isLoadingEpisodes = false) }
        }

        // Same as loadItem: offline must skip the live call entirely, not just fall back on
        // failure, or a reachable server returns every episode unfiltered. Read
        // networkMonitor.isOnline.value directly for the same cold-start-race reason as loadItem.
        if (!networkMonitor.isOnline.value) {
            loadOfflineEpisodes()
            return@launch
        }
        jellyfinRepo.getItems(parentId = season.id, types = "Episode")
            .onSuccess { response ->
                _state.update { it.copy(episodes = response.Items, isLoadingEpisodes = false) }
            }
            .onFailure { loadOfflineEpisodes() }
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

    suspend fun streamUrl(itemId: String) = jellyfinRepo.getStreamUrl(itemId)

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
    val hasCredentials: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isAuthenticating: Boolean = false,
    val isCheckingConnection: Boolean = false,
    val authError: String? = null,
    val isTestingShim: Boolean = false,
    val shimOk: Boolean? = null,
    val shimVersion: String? = null,
    val storageInfo: String? = null,
    val activeJobs: List<JobSummary> = emptyList(),
    val wifiOnly: Boolean = false,
    val streamOverCellular: Boolean = false,
    val showContinueWatching: Boolean = true,
    val showRecentlyAdded: Boolean = true,
    val showMyList: Boolean = true,
    val autoPlayNextEpisode: Boolean = true,
    val introSkipEnabled: Boolean = true,
    val trickplayEnabled: Boolean = true,
    val playbackStatsEnabled: Boolean = true,
    val genreFilterEnabled: Boolean = true,
    val maxConcurrentDownloads: Int = 1,
    val playbackQuality: com.fuzzymistborn.jellyjar.model.PlaybackQuality = com.fuzzymistborn.jellyjar.model.PlaybackQuality.AUTO,
    val forceOfflineMode: Boolean = false,
    val isDiscovering: Boolean = false,
    val discoveredServers: List<com.fuzzymistborn.jellyjar.util.DiscoveredJellyfinServer> = emptyList(),
    val discoverError: String? = null,
    val isQuickConnecting: Boolean = false,
    val quickConnectCode: String? = null,
    val quickConnectError: String? = null,
)

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val jellyfinRepo: JellyfinRepository,
    private val downloadRepo: DownloadRepository,
    private val shimApi: com.fuzzymistborn.jellyjar.api.ShimApiService,
    private val okHttpClient: okhttp3.OkHttpClient,
    private val discoveryService: com.fuzzymistborn.jellyjar.util.JellyfinDiscoveryService,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state.asStateFlow()

    // While the user is editing a URL field, the settings Flow must not clobber the typed text
    // with the stored value (any other setting being saved re-emits the whole preferences map).
    private var jellyfinUrlDirty = false
    private var shimUrlDirty = false

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                val hasCredentials = s.jellyfinToken.isNotBlank()
                val urlChanged = _state.value.jellyfinUrl != s.jellyfinUrl
                _state.update {
                    it.copy(
                        jellyfinUrl = if (jellyfinUrlDirty) it.jellyfinUrl else s.jellyfinUrl,
                        shimUrl = if (shimUrlDirty) it.shimUrl else s.shimUrl,
                        downloadPath = s.downloadPath,
                        isPinEnabled = s.isPinEnabled,
                        hasCredentials = hasCredentials,
                        settingsLoaded = true,
                        wifiOnly = s.wifiOnly,
                        streamOverCellular = s.streamOverCellular,
                        showContinueWatching = s.showContinueWatching,
                        showRecentlyAdded = s.showRecentlyAdded,
                        showMyList = s.showMyList,
                        autoPlayNextEpisode = s.autoPlayNextEpisode,
                        introSkipEnabled = s.introSkipEnabled,
                        trickplayEnabled = s.trickplayEnabled,
                        playbackStatsEnabled = s.playbackStatsEnabled,
                        genreFilterEnabled = s.genreFilterEnabled,
                        maxConcurrentDownloads = s.maxConcurrentDownloads,
                        playbackQuality = s.playbackQuality,
                        forceOfflineMode = s.forceOfflineMode,
                    )
                }
                refreshStorageInfo()
                if (hasCredentials && urlChanged) checkJellyfinConnection()
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

    fun setJellyfinUrl(v: String) {
        jellyfinUrlDirty = true
        _state.update { it.copy(jellyfinUrl = v) }
    }

    // Clears the dirty flag so the settings Flow can resync the field once the user stops
    // editing it, even if they never tap Authenticate (mirrors commitShimUrl's role for the
    // Press URL). Does not persist anything — the Jellyfin URL is only saved together with
    // credentials via authenticate().
    fun commitJellyfinUrl() {
        jellyfinUrlDirty = false
    }
    fun setUsername(v: String) = _state.update { it.copy(username = v) }
    fun setPassword(v: String) = _state.update { it.copy(password = v) }
    fun setShimUrl(v: String) {
        shimUrlDirty = true
        _state.update { it.copy(shimUrl = v) }
    }

    // Persists the Press URL (with scheme applied). Called on field focus loss, before a
    // connection test, and on leaving the screen — there is no explicit Save button.
    fun commitShimUrl() {
        if (!shimUrlDirty) return
        shimUrlDirty = false
        val url = ensureScheme(_state.value.shimUrl)
        _state.update { it.copy(shimUrl = url) }
        viewModelScope.launch { settings.saveShimUrl(url) }
    }
    fun setDownloadPath(v: String) {
        _state.update { it.copy(downloadPath = v) }
        viewModelScope.launch { settings.saveDownloadPath(v) }
    }

    fun authenticate() = viewModelScope.launch {
        _state.update { it.copy(isAuthenticating = true, authError = null) }
        jellyfinRepo.authenticate(
            url = ensureScheme(_state.value.jellyfinUrl),
            username = _state.value.username,
            password = _state.value.password,
        ).onSuccess { result ->
            settings.saveJellyfinAuth(ensureScheme(_state.value.jellyfinUrl), result.userId, result.token)
            jellyfinUrlDirty = false
            _state.update { it.copy(isAuthenticating = false, hasCredentials = true, isAuthenticated = true) }
        }.onFailure { e ->
            _state.update { it.copy(isAuthenticating = false, isAuthenticated = false, authError = e.message) }
        }
    }

    private var quickConnectJob: Job? = null

    // Starts a QuickConnect session and polls the server every 2s (Jellyfin's own web client
    // uses the same interval) until the user approves the code in another session, or ~5 minutes
    // pass and the secret expires server-side.
    fun startQuickConnect() {
        quickConnectJob?.cancel()
        val url = ensureScheme(_state.value.jellyfinUrl)
        if (url.isBlank()) {
            _state.update { it.copy(quickConnectError = "Enter a server URL first") }
            return
        }
        _state.update { it.copy(isQuickConnecting = true, quickConnectCode = null, quickConnectError = null) }
        quickConnectJob = viewModelScope.launch {
            val initResult = jellyfinRepo.initiateQuickConnect(url)
            val session = initResult.getOrElse { e ->
                _state.update { it.copy(isQuickConnecting = false, quickConnectError = e.message ?: "Quick Connect is not available on this server") }
                return@launch
            }
            _state.update { it.copy(quickConnectCode = session.Code) }
            val deadline = System.currentTimeMillis() + 5 * 60 * 1000
            while (System.currentTimeMillis() < deadline) {
                delay(2000)
                val poll = jellyfinRepo.pollQuickConnect(url, session.Secret).getOrElse { e ->
                    _state.update { it.copy(isQuickConnecting = false, quickConnectCode = null, quickConnectError = e.message) }
                    return@launch
                }
                if (poll.Authenticated) {
                    jellyfinRepo.authenticateWithQuickConnect(url, session.Secret)
                        .onSuccess { result ->
                            settings.saveJellyfinAuth(url, result.userId, result.token)
                            jellyfinUrlDirty = false
                            _state.update {
                                it.copy(
                                    isQuickConnecting = false,
                                    quickConnectCode = null,
                                    hasCredentials = true,
                                    isAuthenticated = true,
                                )
                            }
                        }
                        .onFailure { e ->
                            _state.update { it.copy(isQuickConnecting = false, quickConnectCode = null, quickConnectError = e.message) }
                        }
                    return@launch
                }
            }
            _state.update { it.copy(isQuickConnecting = false, quickConnectCode = null, quickConnectError = "Quick Connect code expired") }
        }
    }

    fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        quickConnectJob = null
        _state.update { it.copy(isQuickConnecting = false, quickConnectCode = null, quickConnectError = null) }
    }

    // Performs a real call to Jellyfin (not just "do we have a saved token") so the Admin
    // "Connected" indicator matches what the Home screen actually experiences.
    fun checkJellyfinConnection() = viewModelScope.launch {
        if (!_state.value.hasCredentials) {
            _state.update { it.copy(isAuthenticated = false) }
            return@launch
        }
        _state.update { it.copy(isCheckingConnection = true) }
        jellyfinRepo.getLibraries()
            .onSuccess { _state.update { it.copy(isCheckingConnection = false, isAuthenticated = true) } }
            .onFailure { e -> _state.update { it.copy(isCheckingConnection = false, isAuthenticated = false, authError = e.message) } }
    }

    fun testShim() = viewModelScope.launch {
        commitShimUrl()
        _state.update { it.copy(isTestingShim = true, shimOk = null, shimVersion = null) }
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
            .onSuccess { health ->
                _state.update { it.copy(isTestingShim = false, shimOk = true, shimVersion = health["version"]) }
            }
            .onFailure { e ->
                android.util.Log.e("JellyJar", "Press test failed: ${e.message}")
                _state.update { it.copy(isTestingShim = false, shimOk = false, shimVersion = null) }
            }
    }

    fun discoverJellyfinServers() = viewModelScope.launch {
        _state.update { it.copy(isDiscovering = true, discoveredServers = emptyList(), discoverError = null) }
        runCatching { discoveryService.discover() }
            .onSuccess { servers ->
                _state.update {
                    it.copy(
                        isDiscovering = false,
                        discoveredServers = servers,
                        discoverError = if (servers.isEmpty()) "No servers found on the local network" else null,
                    )
                }
                if (servers.size == 1) selectDiscoveredServer(servers.first())
            }
            .onFailure { e ->
                _state.update { it.copy(isDiscovering = false, discoverError = e.message ?: "Discovery failed") }
            }
    }

    fun selectDiscoveredServer(server: com.fuzzymistborn.jellyjar.util.DiscoveredJellyfinServer) {
        jellyfinUrlDirty = true
        _state.update { it.copy(jellyfinUrl = server.address, discoveredServers = emptyList()) }
        commitJellyfinUrl()
    }

    fun dismissDiscoveredServers() = _state.update { it.copy(discoveredServers = emptyList(), discoverError = null) }

    private fun ensureScheme(url: String): String {
        val trimmed = url.trim().trimEnd('/')
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

    fun verifyPin(input: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val s = settings.settings.first()
        onResult(if (!s.isPinEnabled) true else settings.verifyPin(input, s.parentalPinHash))
    }

    fun setWifiOnly(v: Boolean) {
        _state.update { it.copy(wifiOnly = v) }
        viewModelScope.launch { settings.saveWifiOnly(v) }
    }

    fun setStreamOverCellular(v: Boolean) {
        _state.update { it.copy(streamOverCellular = v) }
        viewModelScope.launch { settings.saveStreamOverCellular(v) }
    }

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

    fun setIntroSkipEnabled(v: Boolean) {
        _state.update { it.copy(introSkipEnabled = v) }
        viewModelScope.launch { settings.saveIntroSkipEnabled(v) }
    }

    fun setTrickplayEnabled(v: Boolean) {
        _state.update { it.copy(trickplayEnabled = v) }
        viewModelScope.launch { settings.saveTrickplayEnabled(v) }
    }

    fun setPlaybackStatsEnabled(v: Boolean) {
        _state.update { it.copy(playbackStatsEnabled = v) }
        viewModelScope.launch { settings.savePlaybackStatsEnabled(v) }
    }

    fun setGenreFilterEnabled(v: Boolean) {
        _state.update { it.copy(genreFilterEnabled = v) }
        viewModelScope.launch { settings.saveGenreFilterEnabled(v) }
    }

    fun setForceOfflineMode(v: Boolean) {
        _state.update { it.copy(forceOfflineMode = v) }
        viewModelScope.launch { settings.saveForceOfflineMode(v) }
    }

    fun setMaxConcurrentDownloads(v: Int) {
        _state.update { it.copy(maxConcurrentDownloads = v) }
        viewModelScope.launch { settings.saveMaxConcurrentDownloads(v) }
    }

    fun setPlaybackQuality(v: com.fuzzymistborn.jellyjar.model.PlaybackQuality) {
        _state.update { it.copy(playbackQuality = v) }
        viewModelScope.launch { settings.savePlaybackQuality(v) }
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
                    if (prev != null && d.progress > prev.first && d.progress < 100f) {
                        val elapsedMs = now - prev.second
                        val eta = if (elapsedMs > 0) {
                            val rate = (d.progress - prev.first) / elapsedMs.toFloat()
                            ((100f - d.progress) / rate / 60000f).toInt().coerceAtLeast(1)
                        } else {
                            _state.value.etaByJellyfinId[d.jellyfinId]
                        }
                        newEtas[d.jellyfinId] = eta
                        progressSamples[d.jellyfinId] = d.progress to now
                    } else if (prev == null || d.progress < prev.first) {
                        // No sample yet, or progress went backwards (e.g. a retry restarted this
                        // item from 0) — a stale baseline from a previous attempt would otherwise
                        // pin the ETA to null/wrong until progress climbs back past the old value.
                        newEtas[d.jellyfinId] = null
                        progressSamples[d.jellyfinId] = d.progress to now
                    } else {
                        // This item's progress hasn't advanced since the last emission of the shared
                        // downloads Flow (likely triggered by a different concurrent download ticking).
                        // Keep the last known ETA and baseline instead of resetting — otherwise ETA
                        // flickers/disappears whenever 2+ downloads are active at once.
                        newEtas[d.jellyfinId] = _state.value.etaByJellyfinId[d.jellyfinId]
                    }
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
    val seasonId: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonName: String? = null,
    val episodes: List<JellyfinItem> = emptyList(),
    val episodeDownloads: Map<String, DownloadEntity> = emptyMap(),
    val isLoading: Boolean = false,
    val isOnline: Boolean = true,
    val isWifi: Boolean = true,
    val streamOverCellular: Boolean = false,
    val episodeViewGrid: Boolean = false,
    val error: String? = null,
    val downloadError: String? = null,
    val jellyfinUrl: String = "",
    val jellyfinToken: String = "",
) {
    val canStream: Boolean get() = isOnline && (isWifi || streamOverCellular)
}

@HiltViewModel
class SeasonViewModel @Inject constructor(
    private val jellyfinRepo: JellyfinRepository,
    private val downloadRepo: DownloadRepository,
    private val settings: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(SeasonState())
    val state: StateFlow<SeasonState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                networkMonitor.isOnline,
                networkMonitor.isWifi,
                settings.settings.map { it.streamOverCellular },
            ) { online, wifi, overCellular -> Triple(online, wifi, overCellular) }
                .collect { (online, wifi, overCellular) ->
                    _state.update { it.copy(isOnline = online, isWifi = wifi, streamOverCellular = overCellular) }
                }
        }
        viewModelScope.launch {
            networkMonitor.reconnected.collect {
                val seasonId = _state.value.seasonId
                val seriesId = _state.value.seriesId
                if (seasonId != null && seriesId != null) load(seasonId, seriesId)
            }
        }
        viewModelScope.launch {
            downloadRepo.downloads.collect { all ->
                _state.update { it.copy(episodeDownloads = all.associateBy { d -> d.jellyfinId }) }
            }
        }
    }

    fun load(seasonId: String, seriesId: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, seasonId = seasonId, seriesId = seriesId) }
        val s = settings.settings.first()
        _state.update { it.copy(jellyfinUrl = s.jellyfinUrl, jellyfinToken = s.jellyfinToken, episodeViewGrid = s.episodeViewGrid) }

        // DetailViewModel.loadOfflineSeasons can hand navigation a synthetic
        // "offline-season-$seriesName-$num" id (paired with a synthetic "offline-series-$seriesName"
        // seriesId) when there's no cached Season/Series row backing a downloaded episode. Those ids
        // were never persisted to cached_items, so getCachedItem always misses them — derive
        // seriesName/seasonNumber straight from the ids instead of depending on that lookup. The
        // number is always the last '-'-delimited token, so substringAfterLast is unambiguous even
        // though seriesName itself may contain hyphens.
        val syntheticSeriesName = seriesId.removePrefix("offline-series-").takeIf { seriesId.startsWith("offline-series-") }
        val syntheticSeasonNumber = seasonId.takeIf { it.startsWith("offline-season-") }
            ?.substringAfterLast('-')?.toIntOrNull()

        val cachedSeason = jellyfinRepo.getCachedItem(seasonId)
        val cachedSeries = jellyfinRepo.getCachedItem(seriesId)
        // cachedSeason.seriesName depends on a Season row's own seriesName column, which is not
        // reliably populated (see loadOfflineSeasons) — prefer the series' own name/synthetic name.
        val resolvedSeriesName = cachedSeries?.name ?: syntheticSeriesName ?: cachedSeason?.seriesName
        val resolvedSeasonNumber = cachedSeason?.indexNumber ?: syntheticSeasonNumber

        // Shared by the offline-gated path and the live-call-failure fallback below, so the two
        // can't drift out of sync with each other.
        suspend fun loadOfflineEpisodes() {
            val episodes = if (resolvedSeriesName != null && resolvedSeasonNumber != null) {
                jellyfinRepo.getCachedEpisodesBySeriesAndSeason(resolvedSeriesName, resolvedSeasonNumber)
            } else {
                _state.update { it.copy(error = "Unavailable offline") }
                emptyList()
            }
            _state.update { it.copy(episodes = episodes, isLoading = false) }
        }

        // Offline (or force-offline) must skip the live calls entirely rather than only falling
        // back on failure — a reachable Jellyfin server would otherwise be attempted first even
        // when genuinely offline, and a synthetic id would 404 anyway.
        if (!networkMonitor.isOnline.value) {
            val offlineSeasonName = cachedSeason?.name ?: resolvedSeasonNumber?.let { num -> "Season $num" }
            _state.update { it.copy(seasonName = offlineSeasonName, seriesName = resolvedSeriesName) }
            loadOfflineEpisodes()
            return@launch
        }

        jellyfinRepo.getItem(seasonId)
            .onSuccess { season ->
                _state.update { it.copy(seasonName = season.name, seriesName = season.seriesName) }
            }
            .onFailure {
                if (cachedSeason != null || resolvedSeriesName != null) {
                    _state.update { it.copy(seasonName = cachedSeason?.name ?: it.seasonName, seriesName = resolvedSeriesName ?: it.seriesName) }
                }
            }

        // Load episodes
        jellyfinRepo.getItems(parentId = seasonId, types = "Episode")
            .onSuccess { response ->
                _state.update { it.copy(episodes = response.Items, isLoading = false) }
            }
            .onFailure { loadOfflineEpisodes() }
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

    suspend fun streamUrl(itemId: String) = jellyfinRepo.getStreamUrl(itemId)
}

// ─── Player ViewModel ─────────────────────────────────────────────────────────

sealed class NextEpisodeTarget {
    data class Local(val localPath: String, val jellyfinId: String) : NextEpisodeTarget()
    data class Stream(val streamUrl: String, val jellyfinId: String) : NextEpisodeTarget()
}

// Everything the player needs to render trickplay scrub previews for one item
data class TrickplaySpec(
    val itemId: String,
    val widthKey: Int,           // resolution key used in the tile URL
    val info: TrickplayInfo,
    val baseUrl: String,
    val token: String,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val downloadRepo: DownloadRepository,
    private val jellyfinRepo: JellyfinRepository,
    private val settings: SettingsRepository,
    private val okHttpClient: okhttp3.OkHttpClient,
) : ViewModel() {

    // Detached scope for reportStopped(): deliberately NOT tied to viewModelScope/onCleared —
    // it must outlive ViewModel clearing so the final "stopped" event still reaches Jellyfin
    // when the report is triggered by the ViewModel being torn down (e.g. navigating away).
    // Held as a single instance (not recreated per call) so calls share one SupervisorJob
    // instead of each leaking its own untracked scope, and given a handler so a failed report
    // can't crash the process.
    private val detachedScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                android.util.Log.e("PlayerViewModel", "detached report failed", e)
            }
    )

    suspend fun loadStartPosition(jellyfinId: String): Long =
        downloadRepo.getPlaybackPosition(jellyfinId)

    // Only meaningful for streamed playback (downloaded files always play back directly from
    // disk, no negotiation involved) — callers should skip this for local paths. Prefers the
    // diagnostics captured when the stream URL was resolved for this same playback (before
    // navigating here) — PlaybackInfo negotiation is stateful server-side, so a second call can
    // legitimately return a different method than the one actually playing.
    suspend fun loadPlaybackDiagnostics(jellyfinId: String): PlaybackDiagnostics? {
        if (!settings.currentSnapshot().playbackStatsEnabled) return null
        return jellyfinRepo.takeCachedDiagnostics(jellyfinId)
            ?: jellyfinRepo.getStreamUrlWithDiagnostics(jellyfinId).second
    }

    // Re-negotiates the stream at a different quality mid-playback (in-player quality switcher).
    // Session-only — deliberately doesn't touch the persisted Admin default, so switching for one
    // title doesn't silently change what every other title streams at.
    suspend fun changeStreamQuality(
        jellyfinId: String,
        quality: com.fuzzymistborn.jellyjar.model.PlaybackQuality,
    ): Pair<String, PlaybackDiagnostics> =
        jellyfinRepo.getStreamUrlWithDiagnostics(jellyfinId, quality)

    suspend fun currentPlaybackQuality(): com.fuzzymistborn.jellyjar.model.PlaybackQuality =
        settings.currentSnapshot().playbackQuality

    // Intro/credits markers for the skip button. Prefers segments captured on the download
    // (works offline), falls back to asking the server. Empty when the setting is off.
    suspend fun loadSkipSegments(jellyfinId: String): List<SkipSegment> {
        if (!settings.currentSnapshot().introSkipEnabled) return emptyList()
        downloadRepo.findById(jellyfinId)?.segmentsJson?.let { json ->
            runCatching {
                com.google.gson.Gson().fromJson(json, Array<SkipSegment>::class.java).toList()
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return runCatching { jellyfinRepo.getSkipSegments(jellyfinId) }.getOrDefault(emptyList())
    }

    // Trickplay tile metadata for scrub previews. Server-generated tiles only, so this is
    // stream-playback + online only; returns null when disabled or unavailable.
    suspend fun loadTrickplay(jellyfinId: String): TrickplaySpec? {
        val s = settings.currentSnapshot()
        if (!s.trickplayEnabled || s.jellyfinUrl.isBlank()) return null
        val item = jellyfinRepo.getItem(jellyfinId).getOrNull() ?: return null
        val byWidth = item.trickplay?.values?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        // Pick the resolution closest to 320px — big enough to read, small to fetch
        val entry = byWidth.entries.minByOrNull {
            kotlin.math.abs((it.key.toIntOrNull() ?: it.value.width) - 320)
        } ?: return null
        return TrickplaySpec(
            itemId = item.id,
            widthKey = entry.key.toIntOrNull() ?: entry.value.width,
            info = entry.value,
            baseUrl = s.jellyfinUrl,
            token = s.jellyfinToken,
        )
    }

    private val tileCache = android.util.LruCache<String, android.graphics.Bitmap>(6)

    // Fetches and decodes one trickplay tile (a grid of thumbnails); cached per URL.
    suspend fun loadTrickplayTile(spec: TrickplaySpec, tileIndex: Int): android.graphics.Bitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = JellyfinImageHelper.trickplayTileUrl(spec.baseUrl, spec.itemId, spec.widthKey, tileIndex, spec.token)
            tileCache.get(url)?.let { return@withContext it }
            runCatching {
                val response = okHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute()
                response.use {
                    if (!it.isSuccessful) return@runCatching null
                    android.graphics.BitmapFactory.decodeStream(it.body.byteStream())
                }
            }.getOrNull()?.also { tileCache.put(url, it) }
        }

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
                NextEpisodeTarget.Stream(jellyfinRepo.getStreamUrl(next.id), next.id)
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
        detachedScope.launch {
            jellyfinRepo.reportPlaybackStopped(jellyfinId, positionMs, mediaSourceId)
        }
    }
}

