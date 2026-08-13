package com.fuzzymistborn.jellyjar.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.fuzzymistborn.jellyjar.api.AuthRequest
import com.fuzzymistborn.jellyjar.api.CodecProfile
import com.fuzzymistborn.jellyjar.api.DeviceProfile
import com.fuzzymistborn.jellyjar.api.ItemsResponse
import com.fuzzymistborn.jellyjar.api.JellyfinApiService
import com.fuzzymistborn.jellyjar.api.JellyfinImageHelper
import com.fuzzymistborn.jellyjar.api.PlaybackInfoRequest
import com.fuzzymistborn.jellyjar.api.ProfileCondition
import com.fuzzymistborn.jellyjar.api.PlaybackProgressRequest
import com.fuzzymistborn.jellyjar.api.PlaybackStartRequest
import com.fuzzymistborn.jellyjar.api.PlaybackStopRequest
import com.fuzzymistborn.jellyjar.api.ShimApiService
import com.fuzzymistborn.jellyjar.data.local.CachedItemDao
import com.fuzzymistborn.jellyjar.data.local.CachedItemEntity
import com.fuzzymistborn.jellyjar.data.local.DownloadDao
import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import com.fuzzymistborn.jellyjar.data.local.FavoriteDao
import com.fuzzymistborn.jellyjar.data.local.FavoriteEntity
import com.fuzzymistborn.jellyjar.data.local.PlaybackPositionDao
import com.fuzzymistborn.jellyjar.data.local.PlaybackPositionEntity
import com.fuzzymistborn.jellyjar.model.*
import com.fuzzymistborn.jellyjar.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// ─── Jellyfin Repository ──────────────────────────────────────────────────────

enum class PlaybackMethod { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }

data class PlaybackDiagnostics(val method: PlaybackMethod, val reasons: List<String>, val container: String? = null)

// A selectable audio or subtitle track as the *server* sees it, identified by its MediaStream
// index (what AudioStreamIndex/SubtitleStreamIndex expect).
data class ServerTrack(
    val index: Int,
    val label: String,
    val isDefault: Boolean,
)

// An external subtitle the player can side-load, so text subtitles survive a transcode.
data class ExternalSubtitle(
    val index: Int,
    val label: String,
    val url: String,
    val mimeType: String,
    val language: String?,
)

// Everything one PlaybackInfo negotiation produced. `audioTracks` is the full server-side list,
// which is a superset of what ExoPlayer sees whenever the stream is transcoded.
data class StreamResolution(
    val url: String,
    val diagnostics: PlaybackDiagnostics,
    val audioTracks: List<ServerTrack> = emptyList(),
    val subtitleTracks: List<ServerTrack> = emptyList(),
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    val selectedAudioIndex: Int? = null,
    val selectedSubtitleIndex: Int? = null,
)

@Singleton
class JellyfinRepository @Inject constructor(
    private val api: JellyfinApiService,
    private val okHttpClient: OkHttpClient,
    private val cachedItemDao: CachedItemDao,
    private val settings: SettingsRepository,
) {
    // PlaybackInfo negotiation is stateful on the server (transcode slots, PlaySessionId, ...),
    // so calling it twice for the same playback (once to build the stream URL, once for the
    // player's diagnostics overlay) can legitimately return two different answers — the UI would
    // then show "Transcoding" for a stream that's actually playing Direct Play. Cache the most
    // recent result per item so a diagnostics read right after the URL fetch reuses it instead of
    // re-negotiating.
    // Concurrent, not plain maps: these are written from Dispatchers.IO during stream resolution
    // and drained by takeCached*() on whatever thread the player happens to be on.
    private val lastDiagnostics = java.util.concurrent.ConcurrentHashMap<String, PlaybackDiagnostics>()
    private val lastResolution = java.util.concurrent.ConcurrentHashMap<String, StreamResolution>()
    suspend fun authenticate(url: String, username: String, password: String): Result<AuthResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val retrofit = buildJellyfinRetrofit(url)
                val service = retrofit.create(JellyfinApiService::class.java)
                val response = service.authenticate(
                    authHeader = JellyfinImageHelper.unauthHeader(),
                    body = AuthRequest(Username = username, Pw = password),
                )
                AuthResult(
                    userId = response.User.Id,
                    token = response.AccessToken,
                    username = response.User.Name,
                )
            }
        }

    // QuickConnect: initiate() gets a Secret (kept client-side only, never shown) + a short
    // human-readable Code (shown to the user to enter at jellyfin.org/quickconnect or the
    // server's own UI). poll() is called on a timer against the same Secret until Authenticated
    // flips true, then authenticateWithQuickConnect() exchanges the Secret for a real token —
    // same AuthResult shape as password auth, so callers can reuse saveJellyfinAuth().
    suspend fun initiateQuickConnect(url: String): Result<com.fuzzymistborn.jellyjar.api.QuickConnectState> =
        withContext(Dispatchers.IO) {
            runCatching {
                val service = buildJellyfinRetrofit(url).create(JellyfinApiService::class.java)
                service.initiateQuickConnect(authHeader = JellyfinImageHelper.unauthHeader())
            }
        }

    suspend fun pollQuickConnect(url: String, secret: String): Result<com.fuzzymistborn.jellyjar.api.QuickConnectState> =
        withContext(Dispatchers.IO) {
            runCatching {
                val service = buildJellyfinRetrofit(url).create(JellyfinApiService::class.java)
                service.getQuickConnectState(authHeader = JellyfinImageHelper.unauthHeader(), secret = secret)
            }
        }

    suspend fun authenticateWithQuickConnect(url: String, secret: String): Result<AuthResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val retrofit = buildJellyfinRetrofit(url)
                val service = retrofit.create(JellyfinApiService::class.java)
                val response = service.authenticateWithQuickConnect(
                    authHeader = JellyfinImageHelper.unauthHeader(),
                    body = com.fuzzymistborn.jellyjar.api.QuickConnectAuthRequest(Secret = secret),
                )
                AuthResult(
                    userId = response.User.Id,
                    token = response.AccessToken,
                    username = response.User.Name,
                )
            }
        }

    suspend fun getLibraries(): Result<List<JellyfinLibrary>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getLibraries(
                userId = s.jellyfinUserId,
                authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
            ).Items
        }
    }

    suspend fun getItems(
        parentId: String? = null,
        types: String? = null,
        startIndex: Int = 0,
        limit: Int = 50,
        searchTerm: String? = null,
        filters: String? = null,
        genres: String? = null,
    ): Result<ItemsResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            val response = service.getItems(
                userId = s.jellyfinUserId,
                authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
                parentId = parentId,
                types = types,
                startIndex = startIndex,
                limit = limit,
                searchTerm = searchTerm?.takeIf { it.isNotBlank() },
                filters = filters,
                genres = genres?.takeIf { it.isNotBlank() },
            )
            if (searchTerm.isNullOrBlank()) {
                cachedItemDao.upsertAll(response.Items.map { it.toEntity() })
            }
            response
        }
    }

    suspend fun getRecentlyAdded(limit: Int = 15): Result<List<JellyfinItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getItems(
                userId = s.jellyfinUserId,
                authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
                types = "Movie,Series",
                sortBy = "DateCreated",
                sortOrder = "Descending",
                limit = limit,
            ).Items
        }
    }

    suspend fun getResumeItems(limit: Int = 12): Result<List<JellyfinItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getItems(
                userId = s.jellyfinUserId,
                authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
                types = "Movie,Episode",
                sortBy = "DatePlayed",
                sortOrder = "Descending",
                filters = "IsResumable",
                limit = limit,
            ).Items
        }
    }

    suspend fun getGenres(parentId: String? = null): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getGenres(
                authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
                parentId = parentId,
                userId = s.jellyfinUserId,
            ).Items.map { it.name }
        }
    }

    // Fetches intro/credits markers: native MediaSegments API (Jellyfin 10.9+) first, then the
    // Intro Skipper plugin endpoint. Best-effort — returns empty on any failure.
    suspend fun getSkipSegments(itemId: String): List<SkipSegment> = withContext(Dispatchers.IO) {
        val s = settings.currentSnapshot()
        if (s.jellyfinUrl.isBlank()) return@withContext emptyList()
        val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
        val auth = JellyfinImageHelper.authHeader(s.jellyfinToken)

        val native = runCatching {
            service.getMediaSegments(itemId, auth).items.orEmpty().mapNotNull { seg ->
                val type = seg.type ?: return@mapNotNull null
                val start = seg.startTicks ?: return@mapNotNull null
                val end = seg.endTicks ?: return@mapNotNull null
                if (type != "Intro" && type != "Outro") return@mapNotNull null
                if (end <= start) return@mapNotNull null
                SkipSegment(type = type, startMs = start / 10_000, endMs = end / 10_000)
            }
        }.getOrDefault(emptyList())
        if (native.isNotEmpty()) return@withContext native

        runCatching {
            service.getIntroSkipperSegments(itemId, auth).mapNotNull { (key, seg) ->
                if (seg.valid == false) return@mapNotNull null
                val startSec = seg.introStart ?: seg.start ?: return@mapNotNull null
                val endSec = seg.introEnd ?: seg.end ?: return@mapNotNull null
                if (endSec <= startSec) return@mapNotNull null
                SkipSegment(
                    type = if (key.equals("Credits", ignoreCase = true)) "Outro" else "Intro",
                    startMs = (startSec * 1000).toLong(),
                    endMs = (endSec * 1000).toLong(),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getItem(itemId: String): Result<JellyfinItem> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            val item = service.getItem(
                userId = s.jellyfinUserId,
                itemId = itemId,
                authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
            )
            cachedItemDao.upsertAll(listOf(item.toEntity()))
            item
        }
    }

    suspend fun markPlayed(itemId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.markPlayed(s.jellyfinUserId, itemId, JellyfinImageHelper.authHeader(s.jellyfinToken))
        }
    }

    suspend fun markUnplayed(itemId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.markUnplayed(s.jellyfinUserId, itemId, JellyfinImageHelper.authHeader(s.jellyfinToken))
        }
    }

    suspend fun reportPlaybackStart(itemId: String, positionMs: Long, mediaSourceId: String? = null) =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.currentSnapshot()
                val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
                service.reportPlaybackStart(
                    authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
                    body = PlaybackStartRequest(ItemId = itemId, PositionTicks = positionMs * 10_000L, MediaSourceId = mediaSourceId),
                )
            }
        }

    suspend fun reportPlaybackProgress(itemId: String, positionMs: Long, isPaused: Boolean, mediaSourceId: String? = null) =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.currentSnapshot()
                val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
                service.reportPlaybackProgress(
                    authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
                    body = PlaybackProgressRequest(ItemId = itemId, PositionTicks = positionMs * 10_000L, IsPaused = isPaused, MediaSourceId = mediaSourceId),
                )
            }
        }

    suspend fun reportPlaybackStopped(itemId: String, positionMs: Long, mediaSourceId: String? = null) =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.currentSnapshot()
                val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
                service.reportPlaybackStopped(
                    authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
                    body = PlaybackStopRequest(ItemId = itemId, PositionTicks = positionMs * 10_000L, MediaSourceId = mediaSourceId),
                )
            }
        }

    private fun buildJellyfinRetrofit(url: String): retrofit2.Retrofit {
        val baseUrl = if (url.isBlank()) "https://placeholder.invalid/" else url.trimEnd('/') + "/"
        return retrofit2.Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
    }

    // Looks up the episode that follows `current` (same season first, then the first episode of
    // the next season). Returns null for non-episodes or when `current` is already the finale.
    suspend fun findNextEpisodeOnline(current: JellyfinItem): JellyfinItem? = withContext(Dispatchers.IO) {
        if (current.type != "Episode") return@withContext null
        val seasonId = current.seasonId ?: return@withContext null
        val seriesId = current.seriesId ?: return@withContext null

        val inSeason = getItems(parentId = seasonId, types = "Episode").getOrNull()?.Items
            ?.sortedBy { it.indexNumber ?: Int.MAX_VALUE }
        inSeason?.firstOrNull { (it.indexNumber ?: -1) > (current.indexNumber ?: -1) }?.let { return@withContext it }

        val seasons = getItems(parentId = seriesId, types = "Season").getOrNull()?.Items
            ?.sortedBy { it.indexNumber ?: Int.MAX_VALUE }
        val nextSeason = seasons?.firstOrNull { (it.indexNumber ?: -1) > (current.parentIndexNumber ?: -1) }
            ?: return@withContext null
        getItems(parentId = nextSeason.id, types = "Episode").getOrNull()?.Items
            ?.sortedBy { it.indexNumber ?: Int.MAX_VALUE }
            ?.firstOrNull()
    }

    // Negotiates playback with Jellyfin instead of always assuming direct-play. Stock ExoPlayer
    // has no DTS/DTS-HD MA or TrueHD decoder, so those audio codecs are excluded from the
    // DeviceProfile's DirectPlayProfiles — Jellyfin responds with a TranscodingUrl (audio-only
    // remux) for sources with that audio, and a normal direct-play source otherwise. Falls back
    // to the plain direct-play URL if PlaybackInfo fails for any reason (e.g. older server).
    suspend fun getStreamUrl(itemId: String): String = getStreamUrlWithDiagnostics(itemId).first

    // Returns the full track list captured by the most recent negotiation for this item, if any.
    // Consumed once, like takeCachedDiagnostics.
    fun takeCachedResolution(itemId: String): StreamResolution? = lastResolution.remove(itemId)

    // Returns the diagnostics captured by the most recent getStreamUrlWithDiagnostics() call for
    // this item, if any, without re-negotiating with the server. Consumed once (removed from the
    // cache) so a later, unrelated read of the same item still triggers a fresh negotiation.
    fun takeCachedDiagnostics(itemId: String): PlaybackDiagnostics? = lastDiagnostics.remove(itemId)

    // Same PlaybackInfo negotiation as getStreamUrl, but also reports which playback method
    // Jellyfin picked (and why, when transcoding) so the player UI can show it to the user.
    // `qualityOverride` lets the in-player quality switcher renegotiate for this one playback
    // without touching the persisted Admin default (null = use the persisted setting).
    suspend fun getStreamUrlWithDiagnostics(
        itemId: String,
        qualityOverride: com.fuzzymistborn.jellyjar.model.PlaybackQuality? = null,
    ): Pair<String, PlaybackDiagnostics> =
        resolveStream(itemId, qualityOverride).let { it.url to it.diagnostics }

    // Negotiates playback and reports every track the server can offer. `audioStreamIndex` /
    // `subtitleStreamIndex` re-negotiate for a specific track: they're what makes switching audio
    // work on a transcoded stream, where the delivered HLS only ever carries one audio track.
    suspend fun resolveStream(
        itemId: String,
        qualityOverride: com.fuzzymistborn.jellyjar.model.PlaybackQuality? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
    ): StreamResolution =
        withContext(Dispatchers.IO) {
            val s = settings.currentSnapshot()
            val fallback = JellyfinImageHelper.streamUrl(s.jellyfinUrl, itemId, s.jellyfinToken)
            runCatching {
                val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
                val quality = qualityOverride ?: s.playbackQuality
                val bitrateCap = quality.maxBitrate
                // Width/Height conditions are what actually make Jellyfin downscale the transcode
                // output — a bitrate cap alone re-encodes at the source's original resolution.
                val codecProfiles = if (quality.maxWidth != null && quality.maxHeight != null) {
                    listOf(
                        CodecProfile(
                            Conditions = listOf(
                                ProfileCondition(Property = "Width", Value = quality.maxWidth.toString()),
                                ProfileCondition(Property = "Height", Value = quality.maxHeight.toString()),
                            ),
                        ),
                    )
                } else emptyList()
                val response = service.getPlaybackInfo(
                    itemId = itemId,
                    authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
                    userId = s.jellyfinUserId,
                    audioStreamIndex = audioStreamIndex,
                    subtitleStreamIndex = subtitleStreamIndex,
                    body = PlaybackInfoRequest(
                        DeviceProfile = DeviceProfile(
                            MaxStreamingBitrate = bitrateCap ?: 120_000_000,
                            MaxStaticBitrate = bitrateCap,
                            CodecProfiles = codecProfiles,
                        ),
                        MaxStreamingBitrate = bitrateCap,
                    ),
                )
                val source = response.MediaSources.firstOrNull()
                    ?: return@runCatching StreamResolution(fallback, PlaybackDiagnostics(PlaybackMethod.DIRECT_PLAY, emptyList()))
                val streams = source.MediaStreams.orEmpty()
                val audioTracks = streams.filter { it.Type == "Audio" }
                    .map { ServerTrack(it.Index, trackLabel(it, "Audio"), it.IsDefault) }
                val subtitleTracks = streams.filter { it.Type == "Subtitle" }
                    .map { ServerTrack(it.Index, trackLabel(it, "Subtitle"), it.IsDefault) }
                val externalSubtitles = streams
                    .filter { it.Type == "Subtitle" && it.IsTextSubtitleStream && it.DeliveryUrl != null }
                    .map { stream ->
                        val deliveryUrl = stream.DeliveryUrl!!
                        ExternalSubtitle(
                            index = stream.Index,
                            label = trackLabel(stream, "Subtitle"),
                            // DeliveryUrl is server-relative. ExoPlayer fetches side-loaded
                            // subtitles through a plain data source with no Authorization header,
                            // so the token has to ride along in the query string the same way the
                            // main /Videos/.../stream URL already does.
                            url = withApiKey(
                                if (deliveryUrl.startsWith("http")) deliveryUrl
                                else s.jellyfinUrl.trimEnd('/') + deliveryUrl,
                                s.jellyfinToken,
                            ),
                            mimeType = subtitleMimeType(deliveryUrl),
                            language = stream.Language,
                        )
                    }
                val transcodingUrl = source.TranscodingUrl
                val base = if (!source.SupportsDirectPlay && !source.SupportsDirectStream && transcodingUrl != null) {
                    val url = s.jellyfinUrl.trimEnd('/') + transcodingUrl
                    url to PlaybackDiagnostics(PlaybackMethod.TRANSCODE, source.TranscodeReasons ?: emptyList(), source.Container)
                } else {
                    val url = JellyfinImageHelper.streamUrl(
                        s.jellyfinUrl, itemId, s.jellyfinToken,
                        container = source.Container, mediaSourceId = source.Id,
                    )
                    val method = if (source.SupportsDirectPlay) PlaybackMethod.DIRECT_PLAY else PlaybackMethod.DIRECT_STREAM
                    url to PlaybackDiagnostics(method, emptyList(), source.Container)
                }
                StreamResolution(
                    url = base.first,
                    diagnostics = base.second,
                    audioTracks = audioTracks,
                    subtitleTracks = subtitleTracks,
                    externalSubtitles = externalSubtitles,
                    selectedAudioIndex = audioStreamIndex ?: source.DefaultAudioStreamIndex,
                    selectedSubtitleIndex = subtitleStreamIndex ?: source.DefaultSubtitleStreamIndex,
                )
            }.getOrDefault(StreamResolution(fallback, PlaybackDiagnostics(PlaybackMethod.DIRECT_PLAY, emptyList())))
                .also {
                    lastDiagnostics[itemId] = it.diagnostics
                    lastResolution[itemId] = it
                }
        }

    // Jellyfin's DisplayTitle is usually the nicest ("English - AAC 5.1"); fall back to language
    // and title so a track is never listed as a bare number.
    private fun trackLabel(stream: com.fuzzymistborn.jellyjar.api.PlaybackMediaStream, kind: String): String =
        stream.DisplayTitle?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                stream.Language?.takeIf { it.isNotBlank() }?.uppercase(),
                stream.Title?.takeIf { it.isNotBlank() },
                stream.Codec?.takeIf { it.isNotBlank() }?.uppercase(),
            ).joinToString(" · ").takeIf { it.isNotBlank() }
            ?: "$kind ${stream.Index}"

    private fun withApiKey(url: String, token: String): String = when {
        token.isBlank() || url.contains("api_key=") -> url
        url.contains('?') -> "$url&api_key=$token"
        else -> "$url?api_key=$token"
    }

    private fun subtitleMimeType(deliveryUrl: String): String =
        when (deliveryUrl.substringAfterLast('.').substringBefore('?').lowercase()) {
            "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
            "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
            else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        }

    fun primaryImageUrl(itemId: String, baseUrl: String): String =
        JellyfinImageHelper.primaryImageUrl(baseUrl, itemId)

    fun backdropUrl(itemId: String, baseUrl: String): String =
        JellyfinImageHelper.backdropImageUrl(baseUrl, itemId)

    // ── Offline cache lookups ──────────────────────────────────────────────────

    suspend fun getCachedItem(itemId: String): JellyfinItem? = withContext(Dispatchers.IO) {
        cachedItemDao.findById(itemId)?.toJellyfinItem()
    }

    // Ensures a Series-type cache row exists for `seriesId` (needed so the offline "TV Shows"
    // library tile can resolve a downloaded episode's series by name). Only hits the network when
    // the row is missing, so callers looping over a series' episodes (queueing a season, the
    // metadata refresh worker) don't refetch the same series once per episode.
    suspend fun cacheParentSeriesIfMissing(seriesId: String) = withContext(Dispatchers.IO) {
        if (cachedItemDao.findById(seriesId) == null) {
            getItem(seriesId)
        }
    }

    suspend fun getCachedSeriesByName(name: String): JellyfinItem? = withContext(Dispatchers.IO) {
        cachedItemDao.findByNameAndType(name, "Series")?.toJellyfinItem()
    }

    suspend fun getCachedSeasonsBySeriesName(seriesName: String): List<JellyfinItem> = withContext(Dispatchers.IO) {
        cachedItemDao.findSeasonsBySeriesName(seriesName).map { it.toJellyfinItem() }
    }

    // Reliable alternative to getCachedSeasonsBySeriesName — see findDownloadedSeasonNumbers.
    suspend fun getDownloadedSeasonNumbers(seriesName: String): List<Int> = withContext(Dispatchers.IO) {
        cachedItemDao.findDownloadedSeasonNumbers(seriesName)
    }

    suspend fun getCachedEpisodesBySeriesAndSeason(seriesName: String, seasonNumber: Int): List<JellyfinItem> =
        withContext(Dispatchers.IO) {
            cachedItemDao.findEpisodesBySeriesAndSeason(seriesName, seasonNumber).map { it.toJellyfinItem() }
        }

    private fun CachedItemEntity.toJellyfinItem() = JellyfinItem(
        id = id, name = name, type = type,
        overview = overview, year = year, communityRating = communityRating,
        runTimeTicks = runtimeMinutes?.let { it.toLong() * 600_000_000L },
        seriesName = seriesName, seasonName = seasonName,
        indexNumber = indexNumber, parentIndexNumber = parentIndexNumber,
        mediaSources = mediaSourcePath?.let { path ->
            listOf(MediaSource(id = id, path = path, size = null, container = null, mediaStreams = null))
        },
        imageTags = null, backdropImageTags = null,
        userData = UserData(playbackPositionTicks = null, played = played, playCount = 0),
    )

    private fun JellyfinItem.toEntity() = CachedItemEntity(
        id = id,
        name = name,
        type = type,
        overview = overview,
        year = year,
        communityRating = communityRating,
        runtimeMinutes = runtimeMinutes,
        seriesName = seriesName,
        seasonName = seasonName,
        indexNumber = indexNumber,
        parentIndexNumber = parentIndexNumber,
        mediaSourcePath = mediaSources?.firstOrNull()?.path,
        cachedAt = System.currentTimeMillis(),
        played = userData?.played ?: false,
    )
}

data class AuthResult(val userId: String, val token: String, val username: String)

private fun documentUriToFilePath(uri: Uri): String? = runCatching {
    val docId = DocumentsContract.getDocumentId(uri)
    val parts = docId.split(":", limit = 2)
    if (parts.size == 2 && parts[0] == "primary") {
        "${Environment.getExternalStorageDirectory()}/${parts[1]}"
    } else null
}.getOrNull()

data class DeviceStorageInfo(val usedByJellyJarBytes: Long, val freeBytes: Long, val totalBytes: Long)

// ─── Download Repository ──────────────────────────────────────────────────────

@Singleton
class DownloadRepository @Inject constructor(
    private val shimApi: ShimApiService,
    private val okHttpClient: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val playbackPositionDao: PlaybackPositionDao,
    private val settings: SettingsRepository,
    private val jellyfinRepo: JellyfinRepository,
    @param:ApplicationContext private val context: Context,
) {
    val downloads: Flow<List<DownloadEntity>> = downloadDao.observeAll().distinctUntilChanged()
    val completedDownloads: Flow<List<DownloadEntity>> = downloadDao.observeCompleted().distinctUntilChanged()

    companion object {
        // Absolute floor used when we can't estimate the output size (e.g. Press unreachable or
        // the item has no known runtime) — still guards against starting a download onto a full disk.
        private const val MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024
    }

    private suspend fun shimService(): ShimApiService {
        val url = settings.currentSnapshot().shimUrl
        val baseUrl = if (url.isBlank()) "https://placeholder.invalid/" else url.trimEnd('/') + "/"
        return retrofit2.Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(ShimApiService::class.java)
    }

    // Adds the item to the end of the local download queue. The DownloadQueueManager promotes
    // queued items to Press (and starts a DownloadWorker) as concurrency slots free up.
    suspend fun queueTranscode(
        item: JellyfinItem,
        preset: String,
        mediaSourcePath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // A duplicate tap (or re-queue while already in flight) must not clobber a row whose
            // Press job is actively running — that would orphan the running job server-side while
            // a second one starts, and both would race to write the same output file.
            downloadDao.findById(item.id)?.let { existing ->
                if (existing.status in setOf(
                        DownloadStatus.QUEUED.name,
                        DownloadStatus.TRANSCODING.name,
                        DownloadStatus.DOWNLOADING.name,
                    )
                ) {
                    return@runCatching
                }
            }
            requireDownloadFolder()
            checkFreeSpace(preset, item.runtimeMinutes)
            // Capture intro/credits markers now so the skip button works offline later
            val segments = runCatching { jellyfinRepo.getSkipSegments(item.id) }.getOrDefault(emptyList())
            // Cache the parent series now so the offline "TV Shows" library tile can resolve it by
            // name later — that lookup only works if a Series-type cache row exists, and previously
            // this only happened as a side effect of the user having visited the series' own Detail
            // screen (fragile: easy to end up with downloaded episodes but no cached series).
            if (item.type == "Episode" && item.seriesId != null) {
                jellyfinRepo.cacheParentSeriesIfMissing(item.seriesId)
            }
            val entity = DownloadEntity(
                jellyfinId = item.id,
                title = item.displayTitle,
                localPath = "",
                status = DownloadStatus.QUEUED.name,
                progress = 0f,
                sizeBytes = 0L,
                preset = preset,
                addedAt = System.currentTimeMillis(),
                shimJobId = null,
                thumbnailPath = null,
                overview = item.overview,
                year = item.year,
                runtimeMinutes = item.runtimeMinutes,
                type = item.type,
                seriesName = item.seriesName,
                seasonName = item.seasonName,
                indexNumber = item.indexNumber,
                parentIndexNumber = item.parentIndexNumber,
                mediaSourcePath = mediaSourcePath,
                queuePosition = downloadDao.maxQueuePosition() + 1,
                segmentsJson = segments.takeIf { it.isNotEmpty() }?.let { com.google.gson.Gson().toJson(it) },
            )
            downloadDao.upsert(entity)
        }
    }

    // Builds a Jellyfin-style relative path (subfolders + filename) under the download root:
    //   Movies/Title (Year)/<filename>
    //   TV Shows/Series/Season NN/<filename>
    // Falls back to a flat Movies/ or TV Shows/ folder when the season/series metadata needed
    // for a fuller path is missing (e.g. an episode whose series wasn't resolvable).
    private fun downloadRelativePath(entity: DownloadEntity, filename: String): String {
        fun sanitize(name: String) = name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "Unknown" }

        return if (entity.type == "Episode" && !entity.seriesName.isNullOrBlank()) {
            val seasonFolder = entity.parentIndexNumber?.let { "Season %02d".format(it) } ?: "Season 00"
            "TV Shows/${sanitize(entity.seriesName)}/$seasonFolder/$filename"
        } else if (entity.type == "Episode") {
            "TV Shows/$filename"
        } else {
            val yearSuffix = entity.year?.let { " ($it)" }.orEmpty()
            "Movies/${sanitize(entity.title)}$yearSuffix/$filename"
        }
    }

    // Submits a QUEUED item to Press and hands the poll+download off to a DownloadWorker.
    // Called only by DownloadQueueManager, which enforces the concurrency limit and pause flag.
    suspend fun startQueuedItem(entity: DownloadEntity): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mediaPath = entity.mediaSourcePath ?: error("No source path saved")
            // Named after the original source file (sanitized) instead of the opaque Jellyfin
            // item ID, so both the Press output file and the file saved on-device read as the
            // actual title instead of a GUID. The item ID is kept as a short suffix purely to
            // rule out a collision between two source files that happen to share a base name.
            val sourceName = File(mediaPath).nameWithoutExtension
                .replace(Regex("[/\\\\:*?\"<>|]"), "_")
                .ifBlank { entity.jellyfinId }
            val filename = "${sourceName}_${entity.preset}_${entity.jellyfinId.take(8)}.mp4"
            // Relative path under the user's chosen download root, laid out like Jellyfin's own
            // library structure so files are browsable outside the app too, e.g.
            // "TV Shows/Show/Season 01/..." or "Movies/Title (Year)/...".
            val relativePath = downloadRelativePath(entity, filename)
            val job = shimService().startTranscode(
                TranscodeRequest(
                    source_path = mediaPath,
                    preset = entity.preset,
                    output_filename = filename,
                    display_name = entity.title,
                )
            )
            // The row can be deleted while startTranscode() was in flight — an upsert here would
            // resurrect it with a Press job the user never sees or can cancel. Kill that job instead.
            if (downloadDao.findById(entity.jellyfinId) == null) {
                runCatching { shimService().deleteJob(job.job_id) }
                return@runCatching
            }
            downloadDao.upsert(entity.copy(shimJobId = job.job_id, status = DownloadStatus.TRANSCODING.name, progress = 0f))
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_${entity.jellyfinId}",
                ExistingWorkPolicy.REPLACE,
                DownloadWorker.buildRequest(job.job_id, relativePath, settings.currentSnapshot().wifiOnly),
            )
            Unit
        }.onFailure {
            downloadDao.updateStatus(entity.jellyfinId, DownloadStatus.FAILED.name)
        }
    }

    suspend fun queuedInOrder(): List<DownloadEntity> = withContext(Dispatchers.IO) {
        downloadDao.queuedInOrder()
    }

    suspend fun countInFlight(): Int = withContext(Dispatchers.IO) {
        downloadDao.countInFlight()
    }

    suspend fun prioritize(jellyfinId: String) = withContext(Dispatchers.IO) {
        downloadDao.updateQueuePosition(jellyfinId, downloadDao.minQueuePosition() - 1)
    }

    // Swaps the item with its neighbor above (direction = -1) or below (+1) in the queue.
    suspend fun moveInQueue(jellyfinId: String, direction: Int) = withContext(Dispatchers.IO) {
        val queued = downloadDao.queuedInOrder()
        val index = queued.indexOfFirst { it.jellyfinId == jellyfinId }
        if (index == -1) return@withContext
        val neighbor = queued.getOrNull(index + direction) ?: return@withContext
        val current = queued[index]
        // Positions can collide (e.g. both 0 from older rows); re-derive distinct values on swap.
        val (a, b) = if (current.queuePosition != neighbor.queuePosition) {
            current.queuePosition to neighbor.queuePosition
        } else {
            val base = current.queuePosition
            if (direction > 0) base to base + 1 else base + 1 to base
        }
        downloadDao.updateQueuePosition(current.jellyfinId, b)
        downloadDao.updateQueuePosition(neighbor.jellyfinId, a)
    }

    private suspend fun applyJobUpdate(job: TranscodeJob) {
        val entity = downloadDao.findByShimJobId(job.job_id) ?: return
        val status = when (job.status) {
            "queued", "running" -> DownloadStatus.TRANSCODING.name
            "complete" -> DownloadStatus.DOWNLOADING.name
            "failed" -> DownloadStatus.FAILED.name
            else -> entity.status
        }
        val newProgress = job.progress ?: 0f
        if (entity.status != status || entity.progress != newProgress) {
            downloadDao.updateProgress(entity.jellyfinId, newProgress, status)
        }
    }

    // Opens a Server-Sent Events connection to Press for real-time job updates instead of
    // polling — used by DownloadWorker as the primary path, falling back to pollJobStatus()
    // if Press doesn't support /stream or the connection drops.
    fun streamJobStatus(jobId: String): Flow<TranscodeJob> = callbackFlow {
        val url = settings.currentSnapshot().shimUrl
        val baseUrl = if (url.isBlank()) "https://placeholder.invalid" else url.trimEnd('/')
        val gson = com.google.gson.Gson()
        val request = Request.Builder().url("$baseUrl/jobs/$jobId/stream").build()
        val listener = object : okhttp3.sse.EventSourceListener() {
            override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                runCatching { gson.fromJson(data, TranscodeJob::class.java) }
                    .onSuccess { job ->
                        applyJobUpdateBlocking(job)
                        trySend(job)
                        if (job.status == "complete" || job.status == "failed") close()
                    }
            }

            override fun onClosed(eventSource: okhttp3.sse.EventSource) {
                close()
            }

            override fun onFailure(eventSource: okhttp3.sse.EventSource, t: Throwable?, response: okhttp3.Response?) {
                close(t ?: java.io.IOException("SSE connection failed"))
            }
        }
        val eventSource = okhttp3.sse.EventSources.createFactory(okHttpClient).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    // callbackFlow's collector runs on the caller's dispatcher; applyJobUpdate touches Room,
    // so hop to IO for the DB write without blocking the SSE listener thread.
    private fun applyJobUpdateBlocking(job: TranscodeJob) {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { applyJobUpdate(job) }
    }

    suspend fun pollJobStatus(jobId: String): Result<TranscodeJob> = withContext(Dispatchers.IO) {
        runCatching {
            val job = shimService().getJob(jobId)
            applyJobUpdate(job)
            job
        }
    }

    suspend fun downloadFile(
        jobId: String,
        destinationDir: String,
        // A path relative to destinationDir — may include subfolders (e.g. the Jellyfin-style
        // "TV Shows/Series/Season 01/file.mp4" produced by downloadRelativePath()), or just a
        // bare filename for callers that don't need nesting.
        relativePath: String,
        expectedSha256: String? = null,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entity = downloadDao.findByShimJobId(jobId)
                    ?: error("No download record for job $jobId")

                downloadDao.updateProgress(entity.jellyfinId, 0f, DownloadStatus.DOWNLOADING.name)

                val responseBody = shimService().downloadFile(jobId)
                val totalBytes = responseBody.contentLength()
                var downloadedBytes = 0L
                var lastReportedProgressInt = -1

                val pathSegments = relativePath.split("/").filter { it.isNotBlank() }
                val filename = pathSegments.lastOrNull() ?: relativePath
                val subFolders = pathSegments.dropLast(1)

                val (outputStream, savedPath, savedUri) = if (destinationDir.startsWith("content://")) {
                    val treeUri = Uri.parse(destinationDir)
                    var dir = DocumentFile.fromTreeUri(context, treeUri)
                        ?: error("Cannot open tree URI: $destinationDir")
                    for (folder in subFolders) {
                        dir = dir.findFile(folder)?.takeIf { it.isDirectory }
                            ?: dir.createDirectory(folder)
                            ?: error("Cannot create folder '$folder' in: $destinationDir")
                    }
                    val doc = dir.findFile(filename)
                        ?: dir.createFile("video/mp4", filename)
                        ?: error("Cannot create file in: $destinationDir")
                    // "wt" truncates an existing file — findFile() can return one left by an
                    // interrupted attempt; plain "w" would leave stale trailing bytes if the new
                    // write is shorter, corrupting the MP4.
                    val stream = context.contentResolver.openOutputStream(doc.uri, "wt")
                        ?: error("Cannot open output stream for: ${doc.uri}")
                    // Convert the document URI to a real file path so ExoPlayer/File() can read it
                    // directly. Only works for the "primary" (internal shared) storage volume —
                    // SD cards and other document providers have no stable filesystem path.
                    // ExoPlayer can still play the content:// URI directly (ContentDataSource), so
                    // fall back to that string instead of failing the whole download.
                    val filePath = documentUriToFilePath(doc.uri) ?: doc.uri.toString()
                    Triple(stream, filePath, doc.uri.toString())
                } else {
                    val dir = destinationDir.ifBlank { context.filesDir.absolutePath }
                    val destFile = File(dir, relativePath)
                    destFile.parentFile?.mkdirs()
                    Triple(destFile.outputStream() as java.io.OutputStream, destFile.absolutePath, null)
                }

                outputStream.use { output ->
                    responseBody.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = (downloadedBytes.toFloat() / totalBytes) * 100f
                                val progressInt = progress.toInt()
                                if (progressInt != lastReportedProgressInt) {
                                    lastReportedProgressInt = progressInt
                                    downloadDao.updateProgress(
                                        entity.jellyfinId, progress, DownloadStatus.DOWNLOADING.name
                                    )
                                }
                            }
                        }
                    }
                }

                if (totalBytes > 0 && downloadedBytes != totalBytes) {
                    deleteSavedOutput(savedPath, savedUri)
                    error("Download incomplete: got $downloadedBytes of $totalBytes bytes")
                }

                if (expectedSha256 != null) {
                    val actualSha256 = openSavedOutput(savedPath, savedUri).use { stream ->
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                        deleteSavedOutput(savedPath, savedUri)
                        error("Downloaded file hash mismatch (integrity check failed)")
                    }
                }

                // The row can be deleted while the transfer was in flight — an upsert here would
                // resurrect a "completed" download the user already removed, with the file it
                // deleted still on disk under its old name.
                if (downloadDao.findById(entity.jellyfinId) == null) {
                    deleteSavedOutput(savedPath, savedUri)
                    error("Download record for $jobId was deleted during transfer")
                }

                val thumbnailPath = runCatching {
                    saveThumbnailLocally(entity.jellyfinId)
                }.getOrNull()

                downloadDao.upsert(
                    entity.copy(
                        localPath = savedPath,
                        localUri = savedUri,
                        status = DownloadStatus.COMPLETE.name,
                        progress = 100f,
                        sizeBytes = downloadedBytes,
                        thumbnailPath = thumbnailPath,
                    )
                )
                savedPath
            }
        }

    // savedPath is a real filesystem path except on non-"primary" storage (SD cards, other
    // document providers), where documentUriToFilePath() can't resolve one and savedPath is the
    // content:// URI itself. These read/delete through whichever one applies.
    private fun openSavedOutput(savedPath: String, savedUri: String?): java.io.InputStream =
        if (savedUri != null && savedPath == savedUri) {
            context.contentResolver.openInputStream(Uri.parse(savedUri))
                ?: error("Cannot open input stream for: $savedUri")
        } else {
            File(savedPath).inputStream()
        }

    private fun deleteSavedOutput(savedPath: String, savedUri: String?) {
        if (savedUri != null && savedPath == savedUri) {
            DocumentFile.fromSingleUri(context, Uri.parse(savedUri))?.delete()
        } else {
            File(savedPath).delete()
        }
    }

    suspend fun markFailed(shimJobId: String) = withContext(Dispatchers.IO) {
        val entity = downloadDao.findByShimJobId(shimJobId) ?: return@withContext
        downloadDao.updateStatus(entity.jellyfinId, DownloadStatus.FAILED.name)
    }

    suspend fun findById(jellyfinId: String): DownloadEntity? = withContext(Dispatchers.IO) {
        downloadDao.findById(jellyfinId)
    }

    suspend fun findByShimJobId(shimJobId: String): DownloadEntity? = withContext(Dispatchers.IO) {
        downloadDao.findByShimJobId(shimJobId)
    }

    suspend fun getDeviceStorageInfo(): DeviceStorageInfo = withContext(Dispatchers.IO) {
        val downloadPath = settings.currentSnapshot().downloadPath
        val resolvedPath = when {
            downloadPath.startsWith("content://") ->
                documentUriToFilePath(Uri.parse(downloadPath)) ?: Environment.getExternalStorageDirectory().absolutePath
            downloadPath.isNotBlank() -> downloadPath
            else -> context.filesDir.absolutePath
        }
        val statFs = runCatching { StatFs(resolvedPath) }
            .getOrElse { StatFs(Environment.getExternalStorageDirectory().absolutePath) }
        DeviceStorageInfo(
            usedByJellyJarBytes = downloadDao.totalCompletedBytes(),
            freeBytes = statFs.availableBytes,
            totalBytes = statFs.totalBytes,
        )
    }

    // getDeviceStorageInfo()/downloadFile() both silently fall back to internal or external
    // storage when no folder has been picked, so a download with no configured destination used
    // to run all the way through Press and then land somewhere the user never chose. Fail at
    // queue time instead, where the screen can surface it.
    private suspend fun requireDownloadFolder() {
        check(settings.currentSnapshot().downloadPath.isNotBlank()) {
            "No download folder set — pick one in Settings → Downloads"
        }
    }

    // Estimates transcode output size from the preset's configured bitrates and the source
    // runtime, then requires a safety margin of free space beyond that before allowing the
    // download to start. Falls back to a flat minimum when the estimate can't be computed.
    private suspend fun checkFreeSpace(preset: String, runtimeMinutes: Int?) {
        val freeBytes = getDeviceStorageInfo().freeBytes
        val estimate = estimateOutputBytes(preset, runtimeMinutes)
        val required = estimate?.let { (it * 1.15).toLong() } ?: MIN_FREE_SPACE_BYTES
        check(freeBytes > required) {
            fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
            "Not enough storage: need ~${gb(required)}, only ${gb(freeBytes)} free"
        }
    }

    private suspend fun estimateOutputBytes(preset: String, runtimeMinutes: Int?): Long? {
        if (runtimeMinutes == null || runtimeMinutes <= 0) return null
        val config = runCatching { shimService().getPresetDetails()[preset] }.getOrNull() ?: return null
        val videoBps = parseBitrateBps(config.video_bitrate) ?: return null
        val audioBps = parseBitrateBps(config.audio_bitrate) ?: return null
        val seconds = runtimeMinutes.toLong() * 60
        return ((videoBps + audioBps) / 8) * seconds
    }

    private fun parseBitrateBps(value: String): Long? {
        val trimmed = value.trim().lowercase()
        return when {
            trimmed.endsWith("k") -> trimmed.dropLast(1).toLongOrNull()?.times(1_000)
            trimmed.endsWith("m") -> trimmed.dropLast(1).toLongOrNull()?.times(1_000_000)
            else -> trimmed.toLongOrNull()
        }
    }

    // Puts a failed item back at the end of the local queue; the queue manager restarts it.
    suspend fun retryTranscode(entity: DownloadEntity): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireDownloadFolder()
            checkFreeSpace(entity.preset, entity.runtimeMinutes)
            if (entity.mediaSourcePath == null) error("No source path saved for retry")
            downloadDao.upsert(
                entity.copy(
                    status = DownloadStatus.QUEUED.name,
                    progress = 0f,
                    shimJobId = null,
                    queuePosition = downloadDao.maxQueuePosition() + 1,
                )
            )
        }
    }

    suspend fun updatePlaybackPosition(jellyfinId: String, positionMs: Long) = withContext(Dispatchers.IO) {
        playbackPositionDao.upsert(
            PlaybackPositionEntity(jellyfinId, positionMs, System.currentTimeMillis())
        )
        // Also mirror into DownloadEntity if it exists, for easy access from the downloads list
        downloadDao.updatePlaybackPosition(jellyfinId, positionMs)
    }

    suspend fun getPlaybackPosition(jellyfinId: String): Long = withContext(Dispatchers.IO) {
        playbackPositionDao.getPosition(jellyfinId) ?: 0L
    }

    suspend fun getCompletedJellyfinIds(): List<String> = withContext(Dispatchers.IO) {
        downloadDao.observeCompleted().first().map { it.jellyfinId }
    }

    // Re-fetches a downloaded item's poster so offline artwork picks up server-side changes
    // (new artwork, replaced poster, etc.) instead of staying frozen at download time.
    suspend fun refreshThumbnail(jellyfinId: String) = withContext(Dispatchers.IO) {
        val entity = downloadDao.findById(jellyfinId) ?: return@withContext
        saveThumbnailLocally(jellyfinId)?.let { path ->
            downloadDao.upsert(entity.copy(thumbnailPath = path))
        }
    }

    suspend fun updatePlayed(jellyfinId: String, played: Boolean) = withContext(Dispatchers.IO) {
        downloadDao.updatePlayed(jellyfinId, played)
    }

    private suspend fun saveThumbnailLocally(jellyfinId: String): String? {
        val s = settings.currentSnapshot()
        if (s.jellyfinUrl.isBlank()) return null
        val baseUrl = JellyfinImageHelper.primaryImageUrl(s.jellyfinUrl, jellyfinId, maxWidth = 400)
        val url = if (s.jellyfinToken.isNotBlank()) "$baseUrl&api_key=${s.jellyfinToken}" else baseUrl
        val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) return null
        val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
        val file = File(dir, "$jellyfinId.jpg")
        response.body.byteStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.absolutePath
    }

    suspend fun deleteDownload(jellyfinId: String) = withContext(Dispatchers.IO) {
        val entity = downloadDao.findById(jellyfinId) ?: return@withContext
        WorkManager.getInstance(context).cancelUniqueWork("download_$jellyfinId")
        if (entity.status != DownloadStatus.COMPLETE.name && entity.shimJobId != null) {
            runCatching { shimService().deleteJob(entity.shimJobId) }
        }
        if (entity.localPath.isNotBlank()) {
            val downloadPath = settings.currentSnapshot().downloadPath
            val deleted = when {
                // The document URI recorded at download time: unambiguous, and unaffected by the
                // file being renamed or the user re-picking a different download folder.
                entity.localUri != null ->
                    DocumentFile.fromSingleUri(context, Uri.parse(entity.localUri))?.delete() == true

                // Rows written before localUri existed: fall back to re-finding by name under the
                // currently-configured tree. File.delete() has no permission on a SAF file.
                downloadPath.startsWith("content://") -> {
                    val filename = File(entity.localPath).name
                    DocumentFile.fromTreeUri(context, Uri.parse(downloadPath))
                        ?.findFile(filename)
                        ?.delete() == true
                }

                else -> File(entity.localPath).delete()
            }
            if (!deleted) {
                // The DB row goes away regardless, so without this the file becomes an orphan
                // that no screen in the app can see or account for.
                android.util.Log.w(
                    "DownloadRepository",
                    "Could not delete media for $jellyfinId (uri=${entity.localUri}, path=${entity.localPath})"
                )
            }
        }
        entity.thumbnailPath?.let { File(it).delete() }
        downloadDao.delete(entity)
    }
}

// ─── Favorite Repository ──────────────────────────────────────────────────────

@Singleton
class FavoriteRepository @Inject constructor(
    private val favoriteDao: FavoriteDao,
) {
    val favoriteIds: kotlinx.coroutines.flow.Flow<Set<String>> =
        favoriteDao.observeIds().map { it.toSet() }

    suspend fun toggle(item: com.fuzzymistborn.jellyjar.model.JellyfinItem) = withContext(Dispatchers.IO) {
        if (favoriteDao.isFavorite(item.id)) {
            favoriteDao.delete(item.id)
        } else {
            favoriteDao.upsert(FavoriteEntity(
                jellyfinId = item.id,
                title = item.displayTitle,
                type = item.type,
            ))
        }
    }

    suspend fun isFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        favoriteDao.isFavorite(id)
    }

    val favorites: kotlinx.coroutines.flow.Flow<List<FavoriteEntity>> = favoriteDao.observeAll()
}
