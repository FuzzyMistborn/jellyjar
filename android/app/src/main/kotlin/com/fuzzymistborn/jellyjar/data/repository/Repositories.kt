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
import com.fuzzymistborn.jellyjar.api.DeviceProfile
import com.fuzzymistborn.jellyjar.api.ItemsResponse
import com.fuzzymistborn.jellyjar.api.JellyfinApiService
import com.fuzzymistborn.jellyjar.api.JellyfinImageHelper
import com.fuzzymistborn.jellyjar.api.PlaybackInfoRequest
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

data class PlaybackDiagnostics(val method: PlaybackMethod, val reasons: List<String>)

@Singleton
class JellyfinRepository @Inject constructor(
    private val api: JellyfinApiService,
    private val okHttpClient: OkHttpClient,
    private val cachedItemDao: CachedItemDao,
    private val settings: SettingsRepository,
) {
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

    // Same PlaybackInfo negotiation as getStreamUrl, but also reports which playback method
    // Jellyfin picked (and why, when transcoding) so the player UI can show it to the user.
    suspend fun getStreamUrlWithDiagnostics(itemId: String): Pair<String, PlaybackDiagnostics> =
        withContext(Dispatchers.IO) {
            val s = settings.currentSnapshot()
            val fallback = JellyfinImageHelper.streamUrl(s.jellyfinUrl, itemId, s.jellyfinToken)
            runCatching {
                val service = buildJellyfinRetrofit(s.jellyfinUrl).create(JellyfinApiService::class.java)
                val bitrateCap = s.playbackQuality.maxBitrate
                val response = service.getPlaybackInfo(
                    itemId = itemId,
                    authHeader = JellyfinImageHelper.authHeader(s.jellyfinToken),
                    userId = s.jellyfinUserId,
                    body = PlaybackInfoRequest(
                        DeviceProfile = DeviceProfile(MaxStreamingBitrate = bitrateCap ?: 120_000_000),
                        MaxStreamingBitrate = bitrateCap,
                    ),
                )
                val source = response.MediaSources.firstOrNull()
                    ?: return@runCatching Pair(fallback, PlaybackDiagnostics(PlaybackMethod.DIRECT_PLAY, emptyList()))
                val transcodingUrl = source.TranscodingUrl
                if (!source.SupportsDirectPlay && !source.SupportsDirectStream && transcodingUrl != null) {
                    val url = s.jellyfinUrl.trimEnd('/') + transcodingUrl
                    Pair(url, PlaybackDiagnostics(PlaybackMethod.TRANSCODE, source.TranscodeReasons ?: emptyList()))
                } else {
                    val url = JellyfinImageHelper.streamUrl(
                        s.jellyfinUrl, itemId, s.jellyfinToken,
                        container = source.Container, mediaSourceId = source.Id,
                    )
                    val method = if (source.SupportsDirectPlay) PlaybackMethod.DIRECT_PLAY else PlaybackMethod.DIRECT_STREAM
                    Pair(url, PlaybackDiagnostics(method, emptyList()))
                }
            }.getOrDefault(Pair(fallback, PlaybackDiagnostics(PlaybackMethod.DIRECT_PLAY, emptyList())))
        }

    fun primaryImageUrl(itemId: String, baseUrl: String): String =
        JellyfinImageHelper.primaryImageUrl(baseUrl, itemId)

    fun backdropUrl(itemId: String, baseUrl: String): String =
        JellyfinImageHelper.backdropImageUrl(baseUrl, itemId)

    // ── Offline cache lookups ──────────────────────────────────────────────────

    suspend fun getCachedItem(itemId: String): JellyfinItem? = withContext(Dispatchers.IO) {
        cachedItemDao.findById(itemId)?.toJellyfinItem()
    }

    suspend fun getCachedSeriesByName(name: String): JellyfinItem? = withContext(Dispatchers.IO) {
        cachedItemDao.findByNameAndType(name, "Series")?.toJellyfinItem()
    }

    suspend fun getCachedSeasonsBySeriesName(seriesName: String): List<JellyfinItem> = withContext(Dispatchers.IO) {
        cachedItemDao.findSeasonsBySeriesName(seriesName).map { it.toJellyfinItem() }
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
            checkFreeSpace(preset, item.runtimeMinutes)
            // Capture intro/credits markers now so the skip button works offline later
            val segments = runCatching { jellyfinRepo.getSkipSegments(item.id) }.getOrDefault(emptyList())
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
                mediaSourcePath = mediaSourcePath,
                queuePosition = downloadDao.maxQueuePosition() + 1,
                segmentsJson = segments.takeIf { it.isNotEmpty() }?.let { com.google.gson.Gson().toJson(it) },
            )
            downloadDao.upsert(entity)
        }
    }

    // Submits a QUEUED item to Press and hands the poll+download off to a DownloadWorker.
    // Called only by DownloadQueueManager, which enforces the concurrency limit and pause flag.
    suspend fun startQueuedItem(entity: DownloadEntity): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mediaPath = entity.mediaSourcePath ?: error("No source path saved")
            val filename = "${entity.jellyfinId}_${entity.preset}.mp4"
            val job = shimService().startTranscode(
                TranscodeRequest(
                    source_path = mediaPath,
                    preset = entity.preset,
                    output_filename = filename,
                    display_name = entity.title,
                )
            )
            downloadDao.upsert(entity.copy(shimJobId = job.job_id, status = DownloadStatus.TRANSCODING.name, progress = 0f))
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_${entity.jellyfinId}",
                ExistingWorkPolicy.REPLACE,
                DownloadWorker.buildRequest(job.job_id, filename, settings.currentSnapshot().wifiOnly),
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
        filename: String,
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

                val (outputStream, savedPath) = if (destinationDir.startsWith("content://")) {
                    val treeUri = Uri.parse(destinationDir)
                    val tree = DocumentFile.fromTreeUri(context, treeUri)
                        ?: error("Cannot open tree URI: $destinationDir")
                    val doc = tree.findFile(filename)
                        ?: tree.createFile("video/mp4", filename)
                        ?: error("Cannot create file in: $destinationDir")
                    // "wt" truncates an existing file — findFile() can return one left by an
                    // interrupted attempt; plain "w" would leave stale trailing bytes if the new
                    // write is shorter, corrupting the MP4.
                    val stream = context.contentResolver.openOutputStream(doc.uri, "wt")
                        ?: error("Cannot open output stream for: ${doc.uri}")
                    // Convert the document URI to a real file path so ExoPlayer can read it.
                    // ContentResolver.open* on document URIs from tree access is blocked by
                    // ExternalStorageProvider unless the URI came from ACTION_OPEN_DOCUMENT.
                    val filePath = documentUriToFilePath(doc.uri)
                        ?: error("Cannot resolve file path for: ${doc.uri}")
                    Pair(stream, filePath)
                } else {
                    val dir = destinationDir.ifBlank { context.filesDir.absolutePath }
                    val destFile = File(dir, filename)
                    destFile.parentFile?.mkdirs()
                    Pair(destFile.outputStream() as java.io.OutputStream, destFile.absolutePath)
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
                    File(savedPath).delete()
                    error("Download incomplete: got $downloadedBytes of $totalBytes bytes")
                }

                if (expectedSha256 != null) {
                    val actualSha256 = File(savedPath).inputStream().use { stream ->
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                        File(savedPath).delete()
                        error("Downloaded file hash mismatch (integrity check failed)")
                    }
                }

                val thumbnailPath = runCatching {
                    saveThumbnailLocally(entity.jellyfinId)
                }.getOrNull()

                downloadDao.upsert(
                    entity.copy(
                        localPath = savedPath,
                        status = DownloadStatus.COMPLETE.name,
                        progress = 100f,
                        sizeBytes = downloadedBytes,
                        thumbnailPath = thumbnailPath,
                    )
                )
                savedPath
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
            if (downloadPath.startsWith("content://")) {
                // File was created via SAF; File.delete() has no permission — use DocumentFile instead
                val filename = File(entity.localPath).name
                DocumentFile.fromTreeUri(context, Uri.parse(downloadPath))
                    ?.findFile(filename)
                    ?.delete()
            } else {
                File(entity.localPath).delete()
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
