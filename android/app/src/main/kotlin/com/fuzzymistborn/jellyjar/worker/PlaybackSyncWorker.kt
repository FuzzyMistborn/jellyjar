package com.fuzzymistborn.jellyjar.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fuzzymistborn.jellyjar.data.repository.PlaybackSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// Flushes playback state recorded while Jellyfin was unreachable (see PlaybackSyncRepository).
// CONNECTED alone doesn't mean the LAN server is reachable — a tablet on mobile data is
// "connected" — so a failed flush returns retry() and lets WorkManager's backoff pace attempts.
@HiltWorker
class PlaybackSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val playbackSync: PlaybackSyncRepository,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "playback_sync"

        // KEEP is safe: flush() re-reads the table until it's empty, so rows written while a
        // flush is already running are picked up by that same run.
        fun enqueue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<PlaybackSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result =
        if (playbackSync.flush()) Result.success() else Result.retry()
}
