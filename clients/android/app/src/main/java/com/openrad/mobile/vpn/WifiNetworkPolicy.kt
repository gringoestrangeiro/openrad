package com.openrad.mobile.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.openrad.mobile.engine.NetworkPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct peer paths (TCP and UDP) only on Wi-Fi; cellular and every other
 * network are relay-only. Tracks the non-VPN networks: while OpenRad's own VPN
 * is up, the app's default network is the VPN itself, but engine sockets are
 * protected and use the underlying network. Android prefers a validated Wi-Fi
 * network as that default, so its presence is the signal; until the first
 * classification arrives the policy fails safe to relay-only.
 */
class WifiNetworkPolicy(context: Context) : NetworkPolicy {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var wifi = false

    override fun isWifi(): Boolean = wifi

    /** Starts tracking; [onChange] runs on a connectivity thread whenever the classification flips. */
    fun start(onChange: () -> Unit) = synchronized(lock) {
        if (callback != null) return@synchronized
        val cm = connectivity ?: return@synchronized
        runCatching {
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) cm.getNetworkCapabilities(network)?.let { if (tracked(it)) networks[network] = it }
        }
        wifi = classify()
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (tracked(capabilities)) networks[network] = capabilities else networks.remove(network)
                update(onChange)
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                update(onChange)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        if (runCatching { cm.registerNetworkCallback(request, next) }.isSuccess) callback = next
    }

    fun stop() = synchronized(lock) {
        callback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        callback = null
        networks.clear()
        wifi = false
    }

    private fun update(onChange: () -> Unit) {
        val next = classify()
        if (next != wifi) {
            wifi = next
            onChange()
        }
    }

    private fun tracked(c: NetworkCapabilities) =
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun classify(): Boolean = networks.values.any {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
