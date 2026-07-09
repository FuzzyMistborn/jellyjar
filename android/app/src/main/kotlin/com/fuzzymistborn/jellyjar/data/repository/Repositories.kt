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
import com.fuzzymistborn.jellyjar.api.ItemsResponse
import com.fuzzymistborn.jellyjar.api.JellyfinApiService
import com.fuzzymistborn.jellyjar.api.JellyfinImageHelper
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// ─── Jellyfin Repository ──────────────────────────────────────────────────────

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

    fun primaryImageUrl(itemId: String, baseUrl: String): String =
        JellyfinImageHelper.primaryImageUrl(baseUrl, itemId)

    fun backdropUrl(itemId: String, baseUrl: String): String =
        JellyfinImageHelper.backdropImageUrl(baseUrl, itemId)

    fun logoUrl(itemId: String, baseUrl: String): String =
        JellyfinImageHelper.logoImageUrl(baseUrl, itemId)

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
            listOf(MediaSource(id = id, path = path, size = null, container = null, videoStreams = null, audioStreams = null))
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

// ─── Download Repository ──────────────────────────────────────────────────────

@Singleton
class DownloadRepository @Inject constructor(
    private val shimApi: ShimApiService,
    private val okHttpClient: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val playbackPositionDao: PlaybackPositionDao,
    private val settings: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) {
    val downloads: Flow<List<DownloadEntity>> = downloadDao.observeAll().distinctUntilChanged()
    val completedDownloads: Flow<List<DownloadEntity>> = downloadDao.observeCompleted().distinctUntilChanged()

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

    suspend fun queueTranscode(
        item: JellyfinItem,
        preset: String,
        mediaSourcePath: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val job = shimService().startTranscode(
                TranscodeRequest(
                    source_path = mediaSourcePath,
                    preset = preset,
                    output_filename = "${item.id}_${preset}.mp4",
                )
            )

            val entity = DownloadEntity(
                jellyfinId = item.id,
                title = item.displayTitle,
                localPath = "",
                status = DownloadStatus.TRANSCODING.name,
                progress = 0f,
                sizeBytes = 0L,
                preset = preset,
                addedAt = System.currentTimeMillis(),
                shimJobId = job.job_id,
                thumbnailPath = null,
                overview = item.overview,
                year = item.year,
                runtimeMinutes = item.runtimeMinutes,
                type = item.type,
                seriesName = item.seriesName,
                mediaSourcePath = mediaSourcePath,
            )
            val wifiOnly = settings.currentSnapshot().wifiOnly
            downloadDao.upsert(entity)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_${item.id}",
                ExistingWorkPolicy.REPLACE,
                DownloadWorker.buildRequest(job.job_id, "${item.id}_${preset}.mp4", wifiOnly),
            )
            job.job_id
        }
    }

    suspend fun pollJobStatus(jobId: String): Result<TranscodeJob> = withContext(Dispatchers.IO) {
        runCatching {
            val job = shimService().getJob(jobId)
            val entity = downloadDao.findByShimJobId(jobId)
            if (entity != null) {
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
            job
        }
    }

    suspend fun downloadFile(jobId: String, destinationDir: String, filename: String): Result<String> =
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
                    val stream = context.contentResolver.openOutputStream(doc.uri)
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

    suspend fun retryTranscode(entity: DownloadEntity): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val mediaPath = entity.mediaSourcePath ?: error("No source path saved for retry")
            val job = shimService().startTranscode(
                TranscodeRequest(
                    source_path = mediaPath,
                    preset = entity.preset,
                    output_filename = "${entity.jellyfinId}_${entity.preset}.mp4",
                )
            )
            val wifiOnly = settings.currentSnapshot().wifiOnly
            downloadDao.upsert(entity.copy(shimJobId = job.job_id, status = DownloadStatus.TRANSCODING.name, progress = 0f))
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_${entity.jellyfinId}",
                ExistingWorkPolicy.REPLACE,
                DownloadWorker.buildRequest(job.job_id, "${entity.jellyfinId}_${entity.preset}.mp4", wifiOnly),
            )
            job.job_id
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
