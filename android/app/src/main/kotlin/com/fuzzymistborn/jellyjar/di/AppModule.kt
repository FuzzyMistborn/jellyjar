package com.fuzzymistborn.jellyjar.di

import android.content.Context
import androidx.room.Room
import com.fuzzymistborn.jellyjar.api.JellyfinApiService
import com.fuzzymistborn.jellyjar.api.ShimApiService
import com.fuzzymistborn.jellyjar.data.local.JellyJarDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JellyJarDatabase =
        Room.databaseBuilder(context, JellyJarDatabase::class.java, "jellyjar.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun provideDownloadDao(db: JellyJarDatabase) = db.downloadDao()
    @Provides fun provideCachedItemDao(db: JellyJarDatabase) = db.cachedItemDao()
    @Provides fun providePlaybackPositionDao(db: JellyJarDatabase) = db.playbackPositionDao()
    @Provides fun provideFavoriteDao(db: JellyJarDatabase) = db.favoriteDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()

    // Use a placeholder base URL — actual URLs are set dynamically in repositories
    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("http://placeholder.invalid/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideJellyfinApi(retrofit: Retrofit): JellyfinApiService =
        retrofit.create(JellyfinApiService::class.java)

    @Provides
    @Singleton
    fun provideShimApi(retrofit: Retrofit): ShimApiService =
        retrofit.create(ShimApiService::class.java)
}
