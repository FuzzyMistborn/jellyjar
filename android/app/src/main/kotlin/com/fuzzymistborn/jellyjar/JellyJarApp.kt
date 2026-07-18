package com.fuzzymistborn.jellyjar

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.fuzzymistborn.jellyjar.data.repository.DownloadQueueManager
import com.fuzzymistborn.jellyjar.worker.MetadataRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class JellyJarApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var downloadQueueManager: DownloadQueueManager

    override fun onCreate() {
        super.onCreate()
        downloadQueueManager.start()
        MetadataRefreshWorker.schedule(WorkManager.getInstance(this))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
