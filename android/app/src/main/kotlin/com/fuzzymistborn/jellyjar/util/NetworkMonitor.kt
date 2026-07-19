package com.fuzzymistborn.jellyjar.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.fuzzymistborn.jellyjar.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

// Registers its NetworkCallbacks exactly once, for the lifetime of the process, instead of via a
// callbackFlow that (re-)registers a fresh callback per collector. Every ViewModel that used to
// collect a callbackFlow-based `isOnline`/`isWifi` independently registered and unregistered its
// own callback as it was created/cleared — with several ViewModels doing this (Library, Detail,
// the offline-libraries collector, `reconnected`), there was a real window where no callback was
// registered and a live disconnect/reconnect could be missed entirely, only picked up on the next
// cold start (when `isCurrentlyConnected()` gets read fresh). A single StateFlow updated by one
// permanent callback has no such gap, and every consumer just reads the current value.
@Singleton
class NetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Same lifetime as the permanent NetworkCallbacks below — this singleton lives for the whole
    // process, so there's no separate scope to tie this to and nothing to cancel it early for.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Real OS-reported connectivity, untouched by the Admin "force offline" override.
    private val _rawOnline = MutableStateFlow(isCurrentlyConnected())

    private val _isOnline = MutableStateFlow(_rawOnline.value)

    // Any usable internet connection (Wi-Fi, cellular, ethernet, ...) — used to gate library
    // browsing, which should work over cellular. Reflects the Admin "force offline" override:
    // false whenever that's enabled, regardless of real connectivity.
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isWifi = MutableStateFlow(isCurrentlyWifi())

    // Specifically Wi-Fi — used to gate streaming/playback, which defaults to Wi-Fi-only unless
    // the user opts into "Stream over Cellular". Not itself gated by "force offline" — `isOnline`
    // being false already fails `canStream` regardless of this value.
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()

    // Buffered by 1 so a reconnect that fires before a consumer starts collecting isn't lost, but
    // emitted explicitly (rather than derived from `_isOnline`, a conflating StateFlow) so a fast
    // false->true->false flip can't be conflated away before anyone observes the edge.
    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnected: Flow<Unit> = _reconnected

    init {
        val internetRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(internetRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _rawOnline.value = true }
            override fun onLost(network: Network) { _rawOnline.value = isCurrentlyConnected() }
            override fun onUnavailable() { _rawOnline.value = false }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _rawOnline.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        })

        val wifiRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(wifiRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _isWifi.value = true }
            override fun onLost(network: Network) { _isWifi.value = isCurrentlyWifi() }
            override fun onUnavailable() { _isWifi.value = false }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _isWifi.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        })

        // Effective online state = real connectivity AND NOT forced offline. Toggling the force
        // override off is treated the same as a real reconnect (fires `reconnected` too) so every
        // existing consumer of that signal picks it back up without special-casing the override.
        combine(
            _rawOnline,
            settingsRepository.settings.map { it.forceOfflineMode }.distinctUntilChanged(),
        ) { raw, forced -> raw && !forced }
            .distinctUntilChanged()
            .onEach { effective ->
                val wasOnline = _isOnline.value
                _isOnline.value = effective
                if (!wasOnline && effective) _reconnected.tryEmit(Unit)
            }
            .launchIn(scope)
    }

    fun isCurrentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isCurrentlyWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
