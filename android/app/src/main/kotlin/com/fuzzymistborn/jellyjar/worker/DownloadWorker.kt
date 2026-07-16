package com.fuzzymistborn.jellyjar.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.fuzzymistborn.jellyjar.MainActivity
import com.fuzzymistborn.jellyjar.data.repository.DownloadRepository
import java.util.concurrent.TimeUnit
import com.fuzzymistborn.jellyjar.data.repository.SettingsRepository
import com.fuzzymistborn.jellyjar.data.repository.currentSnapshot
import com.fuzzymistborn.jellyjar.model.TranscodeJob
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadRepo: DownloadRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_JOB_ID = "shim_job_id"
        const val KEY_FILENAME = "filename"
        const val CHANNEL_ID = "jellyjar_downloads"

        const val MAX_RETRY_ATTEMPTS = 5

        fun buildRequest(shimJobId: String, filename: String, wifiOnly: Boolean = false): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_JOB_ID to shimJobId,
                        KEY_FILENAME to filename,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: return Result.failure()
        val downloadPath = settings.currentSnapshot().downloadPath

        createNotificationChannel()
        setForeground(createForegroundInfo("Waiting for transcode…", 0))

        // Prefer a live SSE stream for smooth, low-latency progress; only falls back to polling
        // if Press doesn't support /stream (older deployment) or the connection drops.
        var job: TranscodeJob? = runCatching {
            var terminal: TranscodeJob? = null
            downloadRepo.streamJobStatus(jobId).collect { j ->
                when (j.status) {
                    "complete", "failed" -> terminal = j
                    else -> {
                        val progress = (j.progress ?: 0f).toInt()
                        setForeground(createForegroundInfo("Transcoding $progress%", progress))
                    }
                }
            }
            terminal
        }.getOrNull()

        if (job == null) {
            // Poll until transcode is complete
            var attempts = 0
            var polling = true
            while (polling) {
                val result = downloadRepo.pollJobStatus(jobId)
                if (result.isFailure) {
                    if (attempts++ > 3) {
                        return if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                            Result.retry()
                        } else {
                            downloadRepo.markFailed(jobId)
                            postResultNotification(jobId, success = false)
                            Result.failure()
                        }
                    }
                    delay(5_000)
                    continue
                }
                job = result.getOrNull()!!
                when (job!!.status) {
                    "complete" -> polling = false
                    "failed" -> {
                        postResultNotification(jobId, success = false)
                        return Result.failure(workDataOf("error" to (job!!.error ?: "Transcode failed")))
                    }
                    else -> {
                        val progress = (job!!.progress ?: 0f).toInt()
                        setForeground(createForegroundInfo("Transcoding $progress%", progress))
                        delay(5_000)
                    }
                }
            }
        }
        if (job == null) return Result.failure()
        if (job!!.status == "failed") {
            postResultNotification(jobId, success = false)
            return Result.failure(workDataOf("error" to (job!!.error ?: "Transcode failed")))
        }

        setForeground(createForegroundInfo("Downloading…", 0))
        downloadRepo.downloadFile(jobId, downloadPath, filename).onFailure {
            android.util.Log.e("DownloadWorker", "Download failed: ${it.message}", it)
            return if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                downloadRepo.markFailed(jobId)
                postResultNotification(jobId, success = false)
                Result.failure(workDataOf("error" to it.message))
            }
        }

        postResultNotification(jobId, success = true)
        return Result.success()
    }

    // Foreground progress notifications disappear once doWork() returns, so post a separate,
    // tappable one for the terminal state — tapping it opens Downloads (complete or failed).
    private suspend fun postResultNotification(jobId: String, success: Boolean) {
        val title = downloadRepo.findByShimJobId(jobId)?.title ?: "Download"

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, jobId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(if (success) "Download complete" else "Download failed")
            .setContentText(title)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(jobId.hashCode(), notification)
    }

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("JellyJar")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "JellyJar download progress" }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}

// Stub foreground service declaration (WorkManager manages lifecycle)
class DownloadService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?) = null
}
