package com.fuzzymistborn.jellyjar.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fuzzymistborn.jellyjar.data.repository.DownloadRepository
import com.fuzzymistborn.jellyjar.data.repository.JellyfinRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// Periodically re-fetches watched state and artwork for downloaded items, so offline metadata
// doesn't go stale when playback happens on another Jellyfin client (web/TV/etc.). Only touches
// items already downloaded — there's no server-side "favorite" to sync against (JellyJar's My
// List is a local-only concept), and full library metadata isn't cached offline yet.
@HiltWorker
class MetadataRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val jellyfinRepo: JellyfinRepository,
    private val downloadRepo: DownloadRepository,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "metadata_refresh"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<MetadataRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }

    override suspend fun doWork(): Result {
        for (jellyfinId in downloadRepo.getCompletedJellyfinIds()) {
            if (isStopped) break
            jellyfinRepo.getItem(jellyfinId)
            downloadRepo.refreshThumbnail(jellyfinId)
        }
        // Best-effort background sync — a partial failure (item deleted server-side, transient
        // network blip) shouldn't fail the whole periodic run or trigger WorkManager retries.
        return Result.success()
    }
}
