package com.fuzzymistborn.jellyjar.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// Drives the local download queue: whenever downloads or settings change, it promotes QUEUED
// items to Press (via startQueuedItem) until maxConcurrentDownloads items are in flight.
// Pausing the queue only stops new promotions; items already transcoding/downloading finish.
@Singleton
class DownloadQueueManager @Inject constructor(
    private val downloadRepo: DownloadRepository,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var backoffUntilMs = 0L
    private var retryKick: Job? = null

    companion object {
        // Wait this long after a failed promotion (e.g. Press unreachable) before trying the
        // next queued item, so an outage doesn't fail the whole queue in one burst.
        private const val FAILURE_BACKOFF_MS = 30_000L
    }

    fun start() {
        scope.launch {
            merge(
                downloadRepo.downloads.map { },
                settings.settings.map { },
                kick,
            ).collect {
                // Never let a transient failure (DataStore/DB read) kill the queue loop
                runCatching { promote() }
                    .onFailure { android.util.Log.e("DownloadQueueManager", "promote() failed", it) }
            }
        }
    }

    private suspend fun promote() = mutex.withLock {
        val s = settings.currentSnapshot()
        if (s.downloadQueuePaused) return@withLock
        if (System.currentTimeMillis() < backoffUntilMs) {
            scheduleRetryKick()
            return@withLock
        }
        var slots = s.maxConcurrentDownloads - downloadRepo.countInFlight()
        while (slots > 0) {
            val next = downloadRepo.queuedInOrder().firstOrNull() ?: return@withLock
            if (downloadRepo.startQueuedItem(next).isFailure) {
                // startQueuedItem already marked the item FAILED; back off before the next one
                backoffUntilMs = System.currentTimeMillis() + FAILURE_BACKOFF_MS
                scheduleRetryKick()
                return@withLock
            }
            slots--
        }
    }

    private fun scheduleRetryKick() {
        if (retryKick?.isActive == true) return
        retryKick = scope.launch {
            delay((backoffUntilMs - System.currentTimeMillis()).coerceAtLeast(0))
            kick.tryEmit(Unit)
        }
    }
}
