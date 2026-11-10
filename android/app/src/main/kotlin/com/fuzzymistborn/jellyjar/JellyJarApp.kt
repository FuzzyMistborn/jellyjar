package com.fuzzymistborn.jellyjar

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.fuzzymistborn.jellyjar.api.ImageRetryInterceptor
import com.fuzzymistborn.jellyjar.data.repository.DownloadQueueManager
import com.fuzzymistborn.jellyjar.worker.MetadataRefreshWorker
import com.fuzzymistborn.jellyjar.worker.PlaybackSyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okhttp3.OkHttpClient

@HiltAndroidApp
class JellyJarApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var downloadQueueManager: DownloadQueueManager

    override fun onCreate() {
        super.onCreate()
        downloadQueueManager.start()
        MetadataRefreshWorker.schedule(WorkManager.getInstance(this))
        // Picks up anything left pending by a previous process (a no-op when nothing is queued).
        PlaybackSyncWorker.enqueue(WorkManager.getInstance(this))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    // Coil's own client (not the Jellyfin/Press one — images need no auth header), with a retry
    // for bodies Jellyfin cuts short. See ImageRetryInterceptor.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { OkHttpClient.Builder().addInterceptor(ImageRetryInterceptor()).build() }
        .build()
}
