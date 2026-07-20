package com.fuzzymistborn.jellyjar.util

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.inject.Inject

data class DiscoveredJellyfinServer(
    @SerializedName("Address") val address: String = "",
    @SerializedName("Id") val id: String = "",
    @SerializedName("Name") val name: String = "",
)

// Jellyfin servers listen for this UDP broadcast on port 7359 and reply with a JSON payload
// describing themselves (Address/Id/Name) — the same mechanism the official apps use for
// "auto-discover server". No mDNS/NSD involved.
class JellyfinDiscoveryService @Inject constructor() {
    private val gson = com.google.gson.Gson()

    suspend fun discover(timeoutMs: Long = 2000): List<DiscoveredJellyfinServer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredJellyfinServer>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250

                val message = "who is JellyfinServer?".toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(message, message.size, broadcastAddress, 7359))

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val json = String(packet.data, 0, packet.length)
                        runCatching { gson.fromJson(json, DiscoveredJellyfinServer::class.java) }
                            .getOrNull()
                            ?.takeIf { it.address.isNotBlank() }
                            ?.let { found[it.id.ifBlank { it.address }] = it }
                    } catch (e: SocketTimeoutException) {
                        // Expected between packets — keep polling until the overall deadline.
                    }
                }
            }
        }
        found.values.toList()
    }
}
