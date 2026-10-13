package com.openrad.mobile.util

import com.openrad.mobile.model.Peer
import com.openrad.mobile.model.PeerSort
import com.openrad.mobile.model.PeerState

import java.util.Locale

private val STATE_RANK = mapOf(
    PeerState.CONNECTED to 0,
    PeerState.CONNECTING to 1,
    PeerState.ONLINE_QUEUED to 2,
    PeerState.FAILED to 3,
    PeerState.REFUSED to 4,
    PeerState.UNAVAILABLE to 5,
    PeerState.OFFLINE to 6,
)

/** Validation mirrored from `network::validate_name`: 1-255 UTF-16 units, non-empty, no control chars. */
fun validateNetworkName(name: String): Boolean {
    if (name.length !in 1..255 || name.isBlank()) return false
    if (name.any { it.isISOControl() }) return false
    return true
}

/** Network password: 6-256 chars, no control chars. Never persist. */
fun validateNetworkPassword(password: String): Boolean {
    if (password.length !in 6..256) return false
    if (password.any { it.isISOControl() }) return false
    return true
}

/** Configuration name: 1-80 chars, no control chars, not blank. */
fun validateConfigName(name: String): Boolean {
    val count = name.codePointCount(0, name.length)
    if (count !in 1..80) return false
    if (name.isBlank()) return false
    if (name.any { it.isISOControl() }) return false
    return true
}

fun formatBytes(bytes: Long, decimalUnits: Boolean): String {
    val base = if (decimalUnits) 1000.0 else 1024.0
    val units = if (decimalUnits) {
        arrayOf("B", "kB", "MB", "GB", "TB")
    } else {
        arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    }
    if (bytes < base) return "$bytes ${units[0]}"
    var value = bytes.toDouble()
    var idx = 0
    while (value >= base && idx < units.lastIndex) {
        value /= base
        idx++
    }
    return if (value >= 100) "%.0f %s".format(Locale.US, value, units[idx])
    else "%.1f %s".format(Locale.US, value, units[idx])
}

/** Peer filtering + sorting. Mirrors `PeerListCache` from `app.rs`. */
fun filterAndSortPeers(
    peers: List<Peer>,
    query: String,
    networkId: String?,
    showOffline: Boolean,
    sort: PeerSort,
): List<Peer> {
    val q = query.trim().lowercase()
    return peers
        .asSequence()
        .filter { peer ->
            if (!showOffline && peer.state == PeerState.OFFLINE) return@filter false
            if (networkId != null && networkId !in peer.networkIds) return@filter false
            if (q.isEmpty()) return@filter true
            peer.name.lowercase().contains(q) || peer.vip.lowercase().contains(q)
        }
        .sortedWith(
            when (sort) {
                PeerSort.NAME -> compareBy { it.name.lowercase() }
                PeerSort.ADDRESS -> compareBy({ ipToLong(it.vip) }, { it.name.lowercase() })
                PeerSort.STATUS -> compareBy(
                    { STATE_RANK[it.state] ?: 99 },
                    { it.name.lowercase() },
                )
            },
        )
        .toList()
}

private fun ipToLong(ip: String): Long {
    val parts = ip.split(".")
    if (parts.size != 4) return Long.MAX_VALUE
    var acc = 0L
    for (p in parts) {
        val n = p.toLongOrNull() ?: return Long.MAX_VALUE
        if (n !in 0..255) return Long.MAX_VALUE
        acc = (acc shl 8) or n
    }
    return acc
}
