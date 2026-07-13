package com.fuzzymistborn.jellyjar.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fuzzymistborn.jellyjar.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "jellyjar_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val JELLYFIN_URL = stringPreferencesKey("jellyfin_url")
        val JELLYFIN_USER_ID = stringPreferencesKey("jellyfin_user_id")
        val JELLYFIN_TOKEN = stringPreferencesKey("jellyfin_token")
        val SHIM_URL = stringPreferencesKey("shim_url")
        val DOWNLOAD_PATH = stringPreferencesKey("download_path")
        val PARENTAL_PIN_HASH = stringPreferencesKey("parental_pin_hash")
        val PIN_ENABLED = stringPreferencesKey("pin_enabled")
        val EPISODE_VIEW_GRID = booleanPreferencesKey("episode_view_grid")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val SHOW_CONTINUE_WATCHING = booleanPreferencesKey("show_continue_watching")
        val SHOW_RECENTLY_ADDED = booleanPreferencesKey("show_recently_added")
        val SHOW_MY_LIST = booleanPreferencesKey("show_my_list")
        val AUTO_PLAY_NEXT_EPISODE = booleanPreferencesKey("auto_play_next_episode")
        val DOWNLOAD_QUEUE_PAUSED = booleanPreferencesKey("download_queue_paused")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            jellyfinUrl = prefs[Keys.JELLYFIN_URL] ?: "",
            jellyfinUserId = prefs[Keys.JELLYFIN_USER_ID] ?: "",
            jellyfinToken = prefs[Keys.JELLYFIN_TOKEN] ?: "",
            shimUrl = prefs[Keys.SHIM_URL] ?: "",
            downloadPath = prefs[Keys.DOWNLOAD_PATH] ?: "",
            parentalPinHash = prefs[Keys.PARENTAL_PIN_HASH] ?: "",
            isPinEnabled = prefs[Keys.PIN_ENABLED] == "true",
            episodeViewGrid = prefs[Keys.EPISODE_VIEW_GRID] ?: true,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
            showContinueWatching = prefs[Keys.SHOW_CONTINUE_WATCHING] ?: true,
            showRecentlyAdded = prefs[Keys.SHOW_RECENTLY_ADDED] ?: true,
            showMyList = prefs[Keys.SHOW_MY_LIST] ?: true,
            autoPlayNextEpisode = prefs[Keys.AUTO_PLAY_NEXT_EPISODE] ?: true,
            downloadQueuePaused = prefs[Keys.DOWNLOAD_QUEUE_PAUSED] ?: false,
            maxConcurrentDownloads = (prefs[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 1).coerceIn(1, 2),
        )
    }

    suspend fun saveJellyfinAuth(url: String, userId: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.JELLYFIN_URL] = url
            prefs[Keys.JELLYFIN_USER_ID] = userId
            prefs[Keys.JELLYFIN_TOKEN] = token
        }
    }

    suspend fun saveShimUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHIM_URL] = url
        }
    }

    suspend fun saveDownloadPath(path: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DOWNLOAD_PATH] = path
        }
    }

    suspend fun setPin(pin: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PARENTAL_PIN_HASH] = hashPin(pin)
            prefs[Keys.PIN_ENABLED] = "true"
        }
    }

    suspend fun clearPin() {
        context.dataStore.edit { prefs ->
            prefs[Keys.PARENTAL_PIN_HASH] = ""
            prefs[Keys.PIN_ENABLED] = "false"
        }
    }

    suspend fun saveEpisodeViewGrid(isGrid: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPISODE_VIEW_GRID] = isGrid
        }
    }

    suspend fun saveWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun saveShowContinueWatching(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SHOW_CONTINUE_WATCHING] = show }
    }

    suspend fun saveShowRecentlyAdded(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SHOW_RECENTLY_ADDED] = show }
    }

    suspend fun saveShowMyList(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SHOW_MY_LIST] = show }
    }

    suspend fun saveAutoPlayNextEpisode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_PLAY_NEXT_EPISODE] = enabled }
    }

    suspend fun saveDownloadQueuePaused(paused: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.DOWNLOAD_QUEUE_PAUSED] = paused }
    }

    suspend fun saveMaxConcurrentDownloads(limit: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.MAX_CONCURRENT_DOWNLOADS] = limit.coerceIn(1, 2) }
    }

    fun verifyPin(input: String, storedHash: String): Boolean =
        hashPin(input) == storedHash

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
