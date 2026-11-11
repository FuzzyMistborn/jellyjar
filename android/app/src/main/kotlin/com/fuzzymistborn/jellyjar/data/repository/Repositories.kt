package com.fuzzymistborn.jellyjar.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
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
import com.fuzzymistborn.jellyjar.api.UpdateUserItemDataRequest
import com.fuzzymistborn.jellyjar.data.local.CachedItemDao
import com.fuzzymistborn.jellyjar.data.local.CachedItemEntity
import com.fuzzymistborn.jellyjar.data.local.DownloadDao
import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import com.fuzzymistborn.jellyjar.data.local.FavoriteDao
import com.fuzzymistborn.jellyjar.data.local.FavoriteEntity
import com.fuzzymistborn.jellyjar.data.local.PlaybackPositionDao
import com.fuzzymistborn.jellyjar.data.local.PlaybackPositionEntity
import com.fuzzymistborn.jellyjar.data.local.PendingPlaybackSync
import com.fuzzymistborn.jellyjar.data.local.PendingPlaybackSyncDao
import com.fuzzymistborn.jellyjar.model.*
import com.fuzzymistborn.jellyjar.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
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
    suspend fun authenticate(url: String, username: String, password: String, deviceId: String): Result<AuthResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val retrofit = buildJellyfinRetrofit(url)
                val service = retrofit.create(JellyfinApiService::class.java)
                val response = service.authenticate(
                    authHeader = JellyfinImageHelper.unauthHeader(deviceId),
                    body = AuthRequest(Username = username, Pw = password),
                )
                AuthResult(
                    userId = response.User.Id,
                    token = response.AccessToken,
                    username = response.User.Name,
                    deviceId = deviceId,
                )
            }
        }

    // QuickConnect: initiate() gets a Secret (kept client-side only, never shown) + a short
    // human-readable Code (shown to the user to enter at jellyfin.org/quickconnect or the
    // server's own UI). poll() is called on a timer against the same Secret until Authenticated
    // flips true, then authenticateWithQuickConnect() exchanges the Secret for a real token —
    // same AuthResult shape as password auth, so callers can reuse saveJellyfinAuth(). All three
    // calls must send the same deviceId: the token is issued to the device that initiated.
    suspend fun initiateQuickConnect(url: String, deviceId: String): Result<com.fuzzymistborn.jellyjar.api.QuickConnectState> =
        withContext(Dispatchers.IO) {
            runCatching {
                val service = buildJellyfinRetrofit(url).create(JellyfinApiService::class.java)
                service.initiateQuickConnect(authHeader = JellyfinImageHelper.unauthHeader(deviceId))
            }
        }

    suspend fun pollQuickConnect(url: String, secret: String, deviceId: String): Result<com.fuzzymistborn.jellyjar.api.QuickConnectState> =
        withContext(Dispatchers.IO) {
            runCatching {
                val service = buildJellyfinRetrofit(url).create(JellyfinApiService::class.java)
                service.getQuickConnectState(authHeader = JellyfinImageHelper.unauthHeader(deviceId), secret = secret)
            }
        }

    suspend fun authenticateWithQuickConnect(url: String, secret: String, deviceId: String): Result<AuthResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val retrofit = buildJellyfinRetrofit(url)
                val service = retrofit.create(JellyfinApiService::class.java)
                val response = service.authenticateWithQuickConnect(
                    authHeader = JellyfinImageHelper.unauthHeader(deviceId),
                    body = com.fuzzymistborn.jellyjar.api.QuickConnectAuthRequest(Secret = secret),
                )
                AuthResult(
                    userId = response.User.Id,
                    token = response.AccessToken,
                    username = response.User.Name,
                    deviceId = deviceId,
                )
            }
        }

    suspend fun getCurrentUser(): Result<com.fuzzymistborn.jellyjar.api.JellyfinUser> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getCurrentUser(authHeader = JellyfinImageHelper.authHeader(s))
        }
    }

    suspend fun getLibraries(): Result<List<JellyfinLibrary>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getLibraries(
                userId = s.jellyfinUserId,
                authHeader = JellyfinImageHelper.authHeader(s),
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
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
    ): Result<ItemsResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            val response = service.getItems(
                userId = s.jellyfinUserId,
                authHeader = JellyfinImageHelper.authHeader(s),
                parentId = parentId,
                types = types,
                startIndex = startIndex,
                limit = limit,
                searchTerm = searchTerm?.takeIf { it.isNotBlank() },
                filters = filters,
                genres = genres?.takeIf { it.isNotBlank() },
                sortBy = sortBy,
                sortOrder = sortOrder,
            )
            if (searchTerm.isNullOrBlank()) {
                cachedItemDao.upsertAll(response.Items.map { it.toEntity() })
            }
            response
        }
    }

    // The given items as this user can see them: the server drops ids the account has no access
    // to, so this doubles as a permission filter.
    suspend fun getItemsByIds(ids: List<String>): Result<List<JellyfinItem>> = withContext(Dispatchers.IO) {
        runCatching {
            if (ids.isEmpty()) return@runCatching emptyList()
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getItems(
                userId = s.jellyfinUserId,
                authHeader = JellyfinImageHelper.authHeader(s),
                ids = ids.joinToString(","),
                limit = ids.size,
            ).Items
        }
    }

    suspend fun getRecentlyAdded(limit: Int = 15): Result<List<JellyfinItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getItems(
                userId = s.jellyfinUserId,
                authHeader = JellyfinImageHelper.authHeader(s),
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
            service.getResumeItems(
                userId = s.jellyfinUserId,
                authHeader = JellyfinImageHelper.authHeader(s),
                types = "Movie,Episode",
                limit = limit,
            ).Items
        }
    }

    suspend fun getNextUp(seriesId: String): Result<JellyfinItem?> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getNextUp(
                authHeader = JellyfinImageHelper.authHeader(s),
                userId = s.jellyfinUserId,
                seriesId = seriesId,
            ).Items.firstOrNull()
        }
    }

    suspend fun getGenres(parentId: String? = null): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.getGenres(
                authHeader = JellyfinImageHelper.authHeader(s),
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
        val auth = JellyfinImageHelper.authHeader(s)

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
                authHeader = JellyfinImageHelper.authHeader(s),
            )
            cachedItemDao.upsertAll(listOf(item.toEntity()))
            item
        }
    }

    suspend fun markPlayed(itemId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.markPlayed(s.jellyfinUserId, itemId, JellyfinImageHelper.authHeader(s))
        }
    }

    suspend fun markUnplayed(itemId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.currentSnapshot()
            val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
            service.markUnplayed(s.jellyfinUserId, itemId, JellyfinImageHelper.authHeader(s))
        }
    }

    suspend fun reportPlaybackStart(itemId: String, positionMs: Long, mediaSourceId: String? = null) =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.currentSnapshot()
                val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
                service.reportPlaybackStart(
                    authHeader = JellyfinImageHelper.authHeader(s),
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
                    authHeader = JellyfinImageHelper.authHeader(s),
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
                    authHeader = JellyfinImageHelper.authHeader(s),
                    body = PlaybackStopRequest(ItemId = itemId, PositionTicks = positionMs * 10_000L, MediaSourceId = mediaSourceId),
                )
            }
        }

    // Writes position/played state directly, stamped with when the viewing actually happened —
    // how PlaybackSyncRepository replays offline viewing. Tries the 10.9+ route first and falls
    // back to the older per-user one on 404, the same newest-first approach as getSkipSegments().
    suspend fun updateUserData(itemId: String, positionMs: Long, played: Boolean, playedAtMs: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.currentSnapshot()
                val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
                val auth = JellyfinImageHelper.authHeader(s)
                val body = UpdateUserItemDataRequest(
                    PlaybackPositionTicks = positionMs * 10_000L,
                    Played = played,
                    LastPlayedDate = java.time.Instant.ofEpochMilli(playedAtMs).toString(),
                )
                try {
                    service.updateUserData(itemId, s.jellyfinUserId, auth, body)
                } catch (e: retrofit2.HttpException) {
                    if (e.code() != 404) throw e
                    service.updateUserDataLegacy(s.jellyfinUserId, itemId, auth, body)
                }
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
                    authHeader = JellyfinImageHelper.authHeader(s),
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

data class AuthResult(val userId: String, val token: String, val username: String, val deviceId: String)

private fun documentUriToFilePath(uri: Uri): String? = runCatching {
    val docId = DocumentsContract.getDocumentId(uri)
    val parts = docId.split(":", limit = 2)
    if (parts.size == 2 && parts[0] == "primary") {
        "${Environment.getExternalStorageDirectory()}/${parts[1]}"
    } else null
}.getOrNull()

data class DeviceStorageInfo(val usedByJellyJarBytes: Long, val freeBytes: Long, val totalBytes: Long)

data class StorageSnapshot(val device: DeviceStorageInfo, val folders: List<FolderStorage>)

data class FolderStorage(val info: FolderInfo, val usedByJellyJarBytes: Long, val downloadCount: Int)

// ─── Download Repository ──────────────────────────────────────────────────────

@Singleton
class DownloadRepository @Inject constructor(
    private val shimApi: ShimApiService,
    private val okHttpClient: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val playbackPositionDao: PlaybackPositionDao,
    private val settings: SettingsRepository,
    private val jellyfinRepo: JellyfinRepository,
    private val storage: DownloadStorage,
    @param:ApplicationContext private val context: Context,
) {
    val downloads: Flow<List<DownloadEntity>> = downloadDao.observeAll().distinctUntilChanged()
    val completedDownloads: Flow<List<DownloadEntity>> = downloadDao.observeCompleted().distinctUntilChanged()

    companion object {
        // Absolute floor used when we can't estimate the output size (e.g. Press unreachable or
        // the item has no known runtime) — still guards against starting a download onto a full disk.
        private const val MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024

        private const val COPY_BUFFER_BYTES = 256 * 1024

        // Headroom beyond the file itself when choosing a folder at copy time.
        private const val COPY_MARGIN_BYTES = 64L * 1024 * 1024

        // Space required in a folder when Press doesn't report the file's size.
        private const val UNKNOWN_SIZE_RESERVE_BYTES = 4L * 1024 * 1024 * 1024

        // Encodes are limited by the queue manager, but copies to the device are not, and they
        // overlap the next encode. Downloads and moves share one permit: two simultaneous copies
        // would just split the bandwidth (or the card's write speed).
        val copyPermit = kotlinx.coroutines.sync.Semaphore(1)
    }

    // Moves outlive the screen that started them: a copy cancelled with a ViewModel would leave a
    // half-written file behind.
    private val moveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    // In-flight moves by download id: a second tap is ignored, and deleting the download cancels
    // its move (and waits for the partial copy to be cleaned up) before removing anything.
    private val moveJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    // Folders a download ran out of space in, by download id — see pickDestination().
    private val outOfSpace = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val _movingIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val movingIds: kotlinx.coroutines.flow.StateFlow<Set<String>> = _movingIds
    private val _moveErrors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val moveErrors: kotlinx.coroutines.flow.SharedFlow<String> = _moveErrors

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
        // "Save to…": a configured download folder to use instead of the automatic choice.
        targetFolder: String? = null,
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
            checkFreeSpace(preset, item.runtimeMinutes, targetFolder)
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
                storageRoot = targetFolder,
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
    // Returns whether the item is now being encoded (and so holds a concurrency slot): false when
    // Press already had a finished file for it and handed that back, which goes straight to the
    // device copy.
    suspend fun startQueuedItem(entity: DownloadEntity): Result<Boolean> = withContext(Dispatchers.IO) {
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
                    item_id = entity.jellyfinId,
                )
            )
            // The row can be deleted while startTranscode() was in flight — an upsert here would
            // resurrect it with a Press job the user never sees or can cancel. Kill that job instead
            // — unless it's a finished file Press reused, which other users' downloads may share.
            if (downloadDao.findById(entity.jellyfinId) == null) {
                if (job.status != "complete") runCatching { shimService().deleteJob(job.job_id) }
                return@runCatching false
            }
            val encoding = job.status != "complete"
            downloadDao.upsert(
                entity.copy(
                    shimJobId = job.job_id,
                    status = (if (encoding) DownloadStatus.TRANSCODING else DownloadStatus.DOWNLOADING).name,
                    progress = 0f,
                )
            )
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_${entity.jellyfinId}",
                ExistingWorkPolicy.REPLACE,
                DownloadWorker.buildRequest(job.job_id, relativePath, settings.currentSnapshot().wifiOnly),
            )
            encoding
        }.onFailure {
            downloadDao.updateStatus(entity.jellyfinId, DownloadStatus.FAILED.name)
        }
    }

    // Files Press has finished and would hand out again without encoding (GET /library).
    suspend fun readyLibrary(): Result<List<ReadyEntry>> = withContext(Dispatchers.IO) {
        runCatching { shimService().library() }
    }

    // Of these queued items, the ones Press already has a finished file for. Best-effort: an
    // unreachable or older Press just means "none", and the items wait their turn as usual.
    suspend fun readyOnPress(items: List<DownloadEntity>): List<DownloadEntity> = withContext(Dispatchers.IO) {
        val candidates = items.filter { it.mediaSourcePath != null }
        if (candidates.isEmpty()) return@withContext emptyList()
        runCatching {
            val request = ReadyCheckRequest(candidates.map {
                TranscodeRequest(source_path = it.mediaSourcePath!!, preset = it.preset, output_filename = null)
            })
            shimService().checkReady(request).mapNotNull { candidates.getOrNull(it) }
        }.getOrDefault(emptyList())
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

    // Opens the output stream for relativePath (subfolders allowed) under a download folder.
    // Returns the stream, the path to record, and the document URI (null for a plain path).
    // allowExisting = false refuses a same-named file instead of truncating it.
    private fun openDestination(
        destinationDir: String,
        relativePath: String,
        allowExisting: Boolean = true,
    ): Triple<java.io.OutputStream, String, String?> {
        val pathSegments = relativePath.split("/").filter { it.isNotBlank() }
        val filename = pathSegments.lastOrNull() ?: relativePath
        val subFolders = pathSegments.dropLast(1)

        return if (destinationDir.startsWith("content://")) {
            val treeUri = Uri.parse(destinationDir)
            var dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Cannot open tree URI: $destinationDir")
            for (folder in subFolders) {
                dir = dir.findFile(folder)?.takeIf { it.isDirectory }
                    ?: dir.createDirectory(folder)
                    ?: error("Cannot create folder '$folder' in: $destinationDir")
            }
            val existing = dir.findFile(filename)
            check(existing == null || allowExisting) { "A file named $filename already exists in that folder" }
            val doc = existing
                ?: dir.createFile("video/mp4", filename)
                ?: error("Cannot create file in: $destinationDir")
            // "wt" truncates an existing file — findFile() can return one left by an
            // interrupted attempt; plain "w" would leave stale trailing bytes if the new
            // write is shorter, corrupting the MP4.
            val stream = try {
                context.contentResolver.openOutputStream(doc.uri, "wt")
                    ?: error("Cannot open output stream for: ${doc.uri}")
            } catch (e: Throwable) {
                // Don't leave the empty document we just created behind.
                if (existing == null) doc.delete()
                throw e
            }
            // Convert the document URI to a real file path so ExoPlayer/File() can read it
            // directly. Only works for the "primary" (internal shared) storage volume —
            // SD cards and other document providers have no stable filesystem path.
            // ExoPlayer can still play the content:// URI directly (ContentDataSource), so
            // fall back to that string instead of failing the whole download.
            val filePath = documentUriToFilePath(doc.uri) ?: doc.uri.toString()
            Triple(stream, filePath, doc.uri.toString())
        } else {
            val destFile = File(destinationDir, relativePath)
            destFile.parentFile?.mkdirs()
            Triple(destFile.outputStream() as java.io.OutputStream, destFile.absolutePath, null)
        }
    }

    // Picks the download folder for a file of sizeBytes (<= 0 = unknown). See chooseFolder().
    private suspend fun pickDestination(entity: DownloadEntity, sizeBytes: Long): String {
        val folders = settings.currentSnapshot().downloadFolders
        check(folders.isNotEmpty()) { "No download folder set — pick one in Settings → Downloads" }
        val unknownSize = sizeBytes <= 0
        // An unknown size is assumed to be a feature-length file, not the bare minimum, so it
        // isn't sent to a folder that only just clears the floor.
        val required = if (unknownSize) UNKNOWN_SIZE_RESERVE_BYTES else sizeBytes + COPY_MARGIN_BYTES
        val preferred = entity.seriesName?.takeIf { entity.type == "Episode" }
            ?.let { downloadDao.latestRootForSeries(it) }
        val requested = entity.storageRoot?.takeIf { it in folders }
        // A folder this download already ran out of space in is skipped on the next attempt, so
        // a retry spills over to another folder instead of failing the same way.
        val full = outOfSpace[entity.jellyfinId].orEmpty()
        val candidates = storage.candidates(folders).map { if (it.uri in full) it.copy(available = false) else it }
        val choice = chooseFolder(candidates, required, preferred, requested)
            ?: if (unknownSize) {
                // Nothing clears the reserve: take whichever has the most room, above the floor.
                chooseFolder(candidates.sortedByDescending { it.freeBytes }, MIN_FREE_SPACE_BYTES, null, requested)
            } else null
        return choice ?: error(
            when {
                requested != null -> "The folder chosen for this download is unavailable or full"
                candidates.none { it.available } -> "No download folder is available — insert the SD card or check Settings → Downloads"
                else -> "Not enough storage in any download folder"
            }
        )
    }

    suspend fun downloadFile(
        jobId: String,
        // A path relative to the chosen download folder — may include subfolders (e.g. the Jellyfin-style
        // "TV Shows/Series/Season 01/file.mp4" produced by downloadRelativePath()), or just a
        // bare filename for callers that don't need nesting.
        relativePath: String,
        expectedSha256: String? = null,
        tracksJson: String? = null,
        // The finished file's size as Press reports it; used to pick a folder when the response
        // has no Content-Length. -1 = unknown.
        sizeHint: Long = -1L,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entity = downloadDao.findByShimJobId(jobId)
                    ?: error("No download record for job $jobId")

                downloadDao.updateProgress(entity.jellyfinId, 0f, DownloadStatus.DOWNLOADING.name)

                val responseBody = shimService().downloadFile(jobId)
                val totalBytes = responseBody.contentLength()
                // Chosen here rather than at queue time: the file's real size is known now, and
                // another copy may have used up the space (or the card been ejected) since.
                val destinationDir = try {
                    pickDestination(entity, if (totalBytes > 0) totalBytes else sizeHint)
                } catch (e: Throwable) {
                    responseBody.close()
                    throw e
                }
                var downloadedBytes = 0L
                var lastReportedProgressInt = -1

                val (outputStream, savedPath, savedUri) = openDestination(destinationDir, relativePath)

                // Hashing as the bytes arrive replaces a second full read of the saved file, which
                // on a content:// destination is slow enough to double the transfer time.
                val digest = expectedSha256?.let { java.security.MessageDigest.getInstance("SHA-256") }
                // 8 KB writes into a scoped-storage (FUSE) file are syscall-bound and cap well
                // below what Wi-Fi delivers; large writes through a buffer don't.
                try {
                    java.io.BufferedOutputStream(outputStream, COPY_BUFFER_BYTES).use { output ->
                        responseBody.byteStream().use { input ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                digest?.update(buffer, 0, bytesRead)
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
                } catch (e: Throwable) {
                    // Don't leave a truncated MP4 behind (it counts against free space and no row
                    // points at it), and if the folder filled up, avoid it on the next attempt.
                    deleteSavedOutput(savedPath, savedUri)
                    if (isOutOfSpace(e)) {
                        outOfSpace.getOrPut(entity.jellyfinId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                            .add(destinationDir)
                    }
                    throw e
                }

                if (totalBytes > 0 && downloadedBytes != totalBytes) {
                    deleteSavedOutput(savedPath, savedUri)
                    error("Download incomplete: got $downloadedBytes of $totalBytes bytes")
                }

                if (digest != null) {
                    val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
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

                outOfSpace.remove(entity.jellyfinId)
                downloadDao.upsert(
                    entity.copy(
                        localPath = savedPath,
                        localUri = savedUri,
                        storageRoot = destinationDir,
                        status = DownloadStatus.COMPLETE.name,
                        progress = 100f,
                        sizeBytes = downloadedBytes,
                        thumbnailPath = thumbnailPath,
                        tracksJson = tracksJson,
                    )
                )
                savedPath
            }
        }

    // A file written through SAF belongs to the storage provider, not this app, so File.delete()
    // on its real path (which documentUriToFilePath() resolves on the primary volume) silently
    // fails. Whenever there is a document URI, delete through that.
    private fun deleteSavedOutput(savedPath: String, savedUri: String?) {
        if (savedUri != null) {
            DocumentFile.fromSingleUri(context, Uri.parse(savedUri))?.delete()
        } else {
            File(savedPath).delete()
        }
    }

    private fun isOutOfSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any {
            (it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC) ||
                it.message?.contains("ENOSPC") == true ||
                it.message?.contains("No space left", ignoreCase = true) == true
        }

    // Starts moving a finished download to another configured folder. Returns immediately;
    // progress is movingIds, failures and warnings arrive on moveErrors. A second request for a
    // download that is already moving is ignored.
    fun startMove(jellyfinId: String, targetFolder: String) {
        val job = moveScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                moveDownload(jellyfinId, targetFolder)
                    .onSuccess { warning -> warning?.let { _moveErrors.tryEmit(it) } }
                    .onFailure {
                        // A cancelled move is not a failed one; let the scope see the cancellation.
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        android.util.Log.w("DownloadRepository", "Move failed for $jellyfinId: ${it.message}", it)
                        _moveErrors.tryEmit(it.message ?: "Couldn't move the download")
                    }
            } finally {
                moveJobs.remove(jellyfinId)
                _movingIds.update { it - jellyfinId }
            }
        }
        if (moveJobs.putIfAbsent(jellyfinId, job) != null) {
            job.cancel()
            return
        }
        _movingIds.update { it + jellyfinId }
        job.start()
    }

    // Copies the file into targetFolder, repoints the row, then deletes the original. A failure
    // (or cancellation) at any point deletes the partial copy and leaves the original untouched.
    // The result is a warning when the move worked but the original couldn't be removed.
    private suspend fun moveDownload(jellyfinId: String, targetFolder: String): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entity = downloadDao.findById(jellyfinId) ?: error("Download no longer exists")
                check(entity.status == DownloadStatus.COMPLETE.name) { "Only finished downloads can be moved" }
                val sourceUri = entity.localUri ?: error("This download can't be moved")
                val folders = settings.currentSnapshot().downloadFolders
                check(targetFolder in folders) { "That folder is no longer a download folder" }
                // Already in (or inside) the target tree. Copying would resolve to the same
                // document, and opening it for writing would truncate the original.
                if (storage.rootOf(entity, folders) == targetFolder || storage.treeContains(targetFolder, sourceUri)) {
                    return@runCatching null
                }

                val sourceDoc = DocumentFile.fromSingleUri(context, Uri.parse(sourceUri))
                    ?.takeIf { it.exists() } ?: error("The original file isn't available — is the SD card inserted?")
                val size = sourceDoc.length()
                val target = storage.info(targetFolder)
                check(target.available) { "${target.label} isn't available" }
                check(target.freeBytes > size + COPY_MARGIN_BYTES) { "Not enough free space on ${target.label}" }

                val sourceLabel = storage.rootOf(entity, folders)?.let { storage.info(it).label } ?: "the original folder"
                val relativePath = downloadRelativePath(entity, sourceDoc.name ?: File(entity.localPath).name)
                copyPermit.acquire()
                var saved: Triple<java.io.OutputStream, String, String?>? = null
                var finished = false
                try {
                    // Open the source first so a failure here creates nothing in the target.
                    val sourceStream = context.contentResolver.openInputStream(Uri.parse(sourceUri))
                        ?: error("Cannot open the original file")
                    val (output, savedPath, savedUri) = try {
                        openDestination(targetFolder, relativePath, allowExisting = false)
                    } catch (e: Throwable) {
                        sourceStream.close()
                        throw e
                    }.also { saved = it }
                    sourceStream.use { input ->
                        java.io.BufferedOutputStream(output, COPY_BUFFER_BYTES).use { out ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    val written = savedUri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it))?.length() }
                        ?: File(savedPath).length()
                    check(written == size) { "Copy incomplete: wrote $written of $size bytes" }
                    // Zero rows changed = the download was deleted while we copied; the copy is
                    // then an orphan and the finally block removes it.
                    check(downloadDao.updateLocation(jellyfinId, savedPath, savedUri, targetFolder) > 0) {
                        "Download was deleted during the move"
                    }
                    finished = true
                } finally {
                    if (!finished) saved?.let { deleteSavedOutput(it.second, it.third) }
                    copyPermit.release()
                }
                if (sourceDoc.delete()) null
                else {
                    android.util.Log.w("DownloadRepository", "Moved $jellyfinId but could not delete the original at $sourceUri")
                    "Moved, but the original couldn't be deleted from $sourceLabel — remove it there to free the space"
                }
            }
        }

    // Why a finished download can't be read right now (its SD card is out, or the folder grant is
    // gone), or null if its storage is fine — so the player can say that instead of a bare
    // "couldn't play this file".
    suspend fun storageUnavailableReason(jellyfinId: String): String? = withContext(Dispatchers.IO) {
        val entity = downloadDao.findById(jellyfinId) ?: return@withContext null
        val root = storage.rootOf(entity, settings.currentSnapshot().downloadFolders) ?: return@withContext null
        val info = storage.info(root)
        if (info.available) null
        else "This download is stored on ${info.label}, which isn't available. Insert the card and try again."
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

    // Free/total across every reachable folder (a volume counted once even if two folders live on
    // it) plus per-folder figures in priority order, from one read of the folders and downloads so
    // the two can't disagree. Zeros when no folder is configured or none is reachable.
    suspend fun getStorageSnapshot(): StorageSnapshot = withContext(Dispatchers.IO) {
        val folders = settings.currentSnapshot().downloadFolders
        val infos = folders.map { storage.info(it) }
        val completed = downloadDao.observeCompleted().first()
        val reachable = infos.filter { it.available }.distinctBy { it.volumeKey }
        // Only files that are actually on a reachable volume count against its space.
        val gone = infos.filterNot { it.available }.map { it.uri }.toSet()
        val device = DeviceStorageInfo(
            usedByJellyJarBytes = completed.filterNot { storage.rootOf(it, folders) in gone }.sumOf { it.sizeBytes },
            freeBytes = reachable.sumOf { it.freeBytes },
            totalBytes = reachable.sumOf { it.totalBytes },
        )
        val perFolder = infos.map { info ->
            val mine = completed.filter { storage.rootOf(it, folders) == info.uri }
            FolderStorage(info, mine.sumOf { it.sizeBytes }, mine.size)
        }
        StorageSnapshot(device, perFolder)
    }

    // How many downloads depend on a folder, for deciding whether it can be removed: finished
    // files in it; downloads still queued, encoding or copying that asked for it or that will be
    // routed automatically (they might pick it, and removal would release the grant under them);
    // and — for the first folder, which is where the single pre-multi-folder folder was migrated
    // to — finished rows whose location was never recorded. Failed downloads hold no file and
    // don't count.
    suspend fun downloadsUsingFolder(folder: String): Int = withContext(Dispatchers.IO) {
        val folders = settings.currentSnapshot().downloadFolders
        val completed = downloadDao.observeCompleted().first()
        val inFolder = completed.count { storage.rootOf(it, folders) == folder }
        val unplaced = if (folders.firstOrNull() == folder) {
            completed.count { storage.rootOf(it, folders) == null }
        } else 0
        val active = downloadDao.activeRows().count { it.storageRoot == null || it.storageRoot == folder }
        inFolder + unplaced + active
    }

    // Stamps pre-multi-folder rows with the folder they live in, so per-series folder affinity
    // and per-folder usage don't depend on URI matching forever.
    suspend fun backfillStorageRoots() = withContext(Dispatchers.IO) {
        val folders = settings.currentSnapshot().downloadFolders
        if (folders.isEmpty()) return@withContext
        downloadDao.completedWithUri().filter { it.storageRoot == null }.forEach { row ->
            storage.rootOf(row, folders)?.let { downloadDao.updateStorageRoot(row.jellyfinId, it) }
        }
    }

    // getDeviceStorageInfo()/downloadFile() both silently fall back to internal or external
    // storage when no folder has been picked, so a download with no configured destination used
    // to run all the way through Press and then land somewhere the user never chose. Fail at
    // queue time instead, where the screen can surface it.
    private suspend fun requireDownloadFolder() {
        check(settings.currentSnapshot().downloadFolders.isNotEmpty()) {
            "No download folder set — pick one in Settings → Downloads"
        }
    }

    // Estimates transcode output size from the preset's configured bitrates and the source
    // runtime, then requires a safety margin of free space beyond that before allowing the
    // download to start. A file goes to one folder, so what matters is the roomiest reachable
    // one (or the requested one). Falls back to a flat minimum when the estimate can't be computed.
    private suspend fun checkFreeSpace(preset: String, runtimeMinutes: Int?, targetFolder: String? = null) {
        val folders = settings.currentSnapshot().downloadFolders
        val candidates = storage.candidates(folders)
            .let { all -> if (targetFolder != null && targetFolder in folders) all.filter { it.uri == targetFolder } else all }
            .filter { it.available }
        check(candidates.isNotEmpty()) {
            "No download folder is available — insert the SD card or check Settings → Downloads"
        }
        val freeBytes = candidates.maxOf { it.freeBytes }
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
            // The user may have freed space since the folder filled up.
            outOfSpace.remove(entity.jellyfinId)
            val effectiveRoot = entity.storageRoot?.takeIf { storage.info(it).available }
            checkFreeSpace(entity.preset, entity.runtimeMinutes, effectiveRoot)
            if (entity.mediaSourcePath == null) error("No source path saved for retry")
            downloadDao.upsert(
                entity.copy(
                    // A "Save to" request survives a retry only while that folder is still usable;
                    // otherwise every retry would fail against it with no way to change it.
                    storageRoot = effectiveRoot,
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
        // A move in progress would finish into a row that is about to disappear. Stop it and wait
        // for its partial copy to be removed before touching anything.
        moveJobs[jellyfinId]?.cancelAndJoin()
        outOfSpace.remove(jellyfinId)
        val entity = downloadDao.findById(jellyfinId) ?: return@withContext
        WorkManager.getInstance(context).cancelUniqueWork("download_$jellyfinId")
        if (entity.status != DownloadStatus.COMPLETE.name && entity.shimJobId != null) {
            // Only cancel a job that's still encoding. A finished one may be shared with another
            // user's download (Press reuses finished files), and is left to CLEANUP_AFTER_DAYS.
            runCatching {
                if (shimService().getJob(entity.shimJobId).status != "complete") {
                    shimService().deleteJob(entity.shimJobId)
                }
            }
        }
        if (entity.localPath.isNotBlank()) {
            val folders = settings.currentSnapshot().downloadFolders
            val deleted = when {
                // The document URI recorded at download time: unambiguous, and unaffected by the
                // file being renamed or the user re-picking a different download folder.
                entity.localUri != null ->
                    DocumentFile.fromSingleUri(context, Uri.parse(entity.localUri))?.delete() == true

                // Rows written before localUri existed: fall back to re-finding by name under the
                // currently-configured trees. File.delete() has no permission on a SAF file.
                folders.any { it.startsWith("content://") } -> {
                    val filename = File(entity.localPath).name
                    folders.filter { it.startsWith("content://") }.any { tree ->
                        DocumentFile.fromTreeUri(context, Uri.parse(tree))
                            ?.findFile(filename)
                            ?.delete() == true
                    }
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
        // The local resume point belonged to the offline copy; left behind it would make a later
        // stream or re-download "Resume" from a spot the user may have long since finished. Only
        // for a finished download — a failed/queued row never had offline playback of its own.
        if (entity.status == DownloadStatus.COMPLETE.name) playbackPositionDao.delete(jellyfinId)
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

// ─── Playback Sync Repository ─────────────────────────────────────────────────

// Makes sure viewing that happens while Jellyfin is unreachable (offline downloads, LAN server
// out of range) still reaches the server eventually. Every playback report used to be fire-and-
// forget, so offline progress was simply lost — and MetadataRefreshWorker would later pull the
// server's stale state and overwrite the correct local "played" flag with it.
@Singleton
class PlaybackSyncRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pendingDao: PendingPlaybackSyncDao,
    private val jellyfinRepo: JellyfinRepository,
    private val settings: SettingsRepository,
) {
    private val flushMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun recordStopped(itemId: String, positionMs: Long, mediaSourceId: String? = null) {
        val at = System.currentTimeMillis()
        val ok = jellyfinRepo.reportPlaybackStopped(itemId, positionMs, mediaSourceId).isSuccess
        settle(itemId, ok, positionMs = positionMs, played = false, at = at)
    }

    suspend fun recordFinished(itemId: String, mediaSourceId: String? = null) {
        val at = System.currentTimeMillis()
        jellyfinRepo.reportPlaybackStopped(itemId, 0L, mediaSourceId)
        val ok = jellyfinRepo.markPlayed(itemId).isSuccess
        settle(itemId, ok, positionMs = 0L, played = true, at = at)
    }

    suspend fun pendingIds(): Set<String> = withContext(Dispatchers.IO) { pendingDao.pendingIds().toSet() }

    private suspend fun settle(itemId: String, ok: Boolean, positionMs: Long, played: Boolean, at: Long) =
        withContext(Dispatchers.IO) {
            if (ok) {
                // The server now holds state newer than anything still queued for this item.
                pendingDao.delete(itemId)
                return@withContext
            }
            // Not signed in: there's no server this could ever sync to.
            if (settings.currentSnapshot().jellyfinUrl.isBlank()) return@withContext
            pendingDao.upsert(PendingPlaybackSync(itemId, positionMs, played, at))
            com.fuzzymistborn.jellyjar.worker.PlaybackSyncWorker.enqueue(WorkManager.getInstance(context))
        }

    /**
     * Pushes every queued row to Jellyfin. Returns true once the table is empty, false if the
     * server couldn't be reached (the caller should retry later). Loops until empty so rows
     * written while a flush is already running aren't left behind.
     */
    suspend fun flush(): Boolean = flushMutex.withLock {
        withContext(Dispatchers.IO) {
            var rows = pendingDao.getAll()
            while (rows.isNotEmpty()) {
                for (row in rows) {
                    if (!flushRow(row)) return@withContext false
                }
                rows = pendingDao.getAll()
            }
            true
        }
    }

    // True if the row is settled (synced or deliberately dropped); false if the server is
    // unreachable and the whole flush should stop and retry later.
    private suspend fun flushRow(row: PendingPlaybackSync): Boolean {
        val item = jellyfinRepo.getItem(row.jellyfinId).getOrElse { e ->
            if (e.isPermanentHttpFailure()) {
                // Item deleted server-side (or otherwise unsyncable) — nothing to sync to.
                pendingDao.deleteIfUnchanged(row.jellyfinId, row.updatedAt)
                return true
            }
            return false
        }
        val serverLastPlayedMs = item.userData?.lastPlayedDate?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        }
        if (serverLastPlayedMs != null && serverLastPlayedMs > row.updatedAt) {
            // Played on another client after this offline viewing — the server's state wins.
            pendingDao.deleteIfUnchanged(row.jellyfinId, row.updatedAt)
            return true
        }
        // A partial offline rewatch of something already watched shouldn't un-mark it.
        val played = row.played || item.userData?.played == true
        val result = jellyfinRepo.updateUserData(row.jellyfinId, row.positionMs, played, row.updatedAt)
        result.exceptionOrNull()?.let { e ->
            if (!e.isPermanentHttpFailure()) return false
        }
        pendingDao.deleteIfUnchanged(row.jellyfinId, row.updatedAt)
        return true
    }

    // 400/404 won't get better with retries; 401/403 might (re-login), and anything that isn't an
    // HTTP response at all is a connectivity problem.
    private fun Throwable.isPermanentHttpFailure(): Boolean =
        this is retrofit2.HttpException && (code() == 400 || code() == 404)
}
