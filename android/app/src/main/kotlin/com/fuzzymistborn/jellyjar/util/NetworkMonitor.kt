package com.fuzzymistborn.jellyjar.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(isCurrentlyConnected())

    // Any usable internet connection (Wi-Fi, cellular, ethernet, ...) — used to gate library
    // browsing, which should work over cellular.
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isWifi = MutableStateFlow(isCurrentlyWifi())

    // Specifically Wi-Fi — used to gate streaming/playback, which defaults to Wi-Fi-only unless
    // the user opts into "Stream over Cellular".
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()

    // Buffered by 1 so a reconnect that fires before a consumer starts collecting isn't lost, but
    // emitted explicitly (rather than derived from `_isOnline`, a conflating StateFlow) so a fast
    // false->true->false flip can't be conflated away before anyone observes the edge.
    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnected: Flow<Unit> = _reconnected

    private fun setOnline(value: Boolean) {
        val wasOnline = _isOnline.value
        _isOnline.value = value
        if (!wasOnline && value) _reconnected.tryEmit(Unit)
    }

    init {
        val internetRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(internetRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { setOnline(true) }
            override fun onLost(network: Network) { setOnline(isCurrentlyConnected()) }
            override fun onUnavailable() { setOnline(false) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                setOnline(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
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
