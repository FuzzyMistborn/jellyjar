package com.fuzzymistborn.jellyjar

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.fuzzymistborn.jellyjar.data.repository.DownloadQueueManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class JellyJarApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var downloadQueueManager: DownloadQueueManager

    override fun onCreate() {
        super.onCreate()
        downloadQueueManager.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
