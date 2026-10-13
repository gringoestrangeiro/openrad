package com.openrad.mobile.data

import com.openrad.mobile.engine.Identity
import com.openrad.mobile.engine.Ip
import com.openrad.mobile.engine.hexToBytes
import com.openrad.mobile.engine.toHex
import com.openrad.mobile.model.AppSettings
import com.openrad.mobile.model.JoinConfiguration
import com.openrad.mobile.model.JoinNetwork
import com.openrad.mobile.model.PeerSort
import com.openrad.mobile.model.StartPage
import com.openrad.mobile.util.validateConfigName
import com.openrad.mobile.util.validateNetworkName
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON forms of persisted data. Favorites and join configurations are stored
 * separately from credentials; passwords are never part of any codec.
 */
object Codecs {
    fun encodeIdentity(id: Identity): String = JSONObject()
        .put("format", "openrad-identity-v1")
        .put("rid", java.lang.Long.toUnsignedString(id.rid))
        .put("vip", Ip.format(id.vip))
        .put("node_name", id.nodeName)
        .put("address", id.address.toHex())
        .put("credential", id.credential.toHex())
        .put("server_address", id.serverAddress)
        .toString()

    fun decodeIdentity(text: String): Identity {
        val o = JSONObject(text)
        require(o.optString("format", "openrad-identity-v1") == "openrad-identity-v1") { "unsupported identity format" }
        return Identity(
            java.lang.Long.parseUnsignedLong(o.get("rid").toString()),
            Ip.parse(o.getString("vip")),
            o.getString("node_name"),
            hexToBytes(o.getString("address")),
            hexToBytes(o.getString("credential")),
            o.getString("server_address"),
        )
    }

    fun encodeSettings(s: AppSettings): String = JSONObject()
        .put("language", s.language)
        .put("auto_connect", s.autoConnect)
        .put("auto_reconnect", s.autoReconnect)
        .put("reconnect_attempts", s.reconnectAttempts)
        .put("reconnect_base_delay_seconds", s.reconnectBaseDelaySeconds)
        .put("node_name", s.nodeName)
        .put("start_page", s.startPage.name)
        .put("show_traffic", s.showTraffic)
        .put("show_traffic_graphs", s.showTrafficGraphs)
        .put("decimal_units", s.decimalUnits)
        .put("show_offline_peers", s.showOfflinePeers)
        .put("peer_sort", s.peerSort.name)
        .put("show_recent_activity", s.showRecentActivity)
        .put("recent_activity_count", s.recentActivityCount)
        .put("show_peer_details", s.showPeerDetails)
        .put("show_peer_ip", s.showPeerIp)
        .put("show_internal_ids", s.showInternalIds)
        .put("show_diagnostics", s.showDiagnostics)
        .put("force_relay", s.forceRelay)
        .put("outgoing_broadcasts", s.outgoingBroadcasts)
        .put("scale", s.scale.toDouble())
        .toString()

    fun decodeSettings(text: String): AppSettings {
        val o = JSONObject(text)
        val d = AppSettings()
        return AppSettings(
            language = o.optString("language", d.language),
            autoConnect = o.optBoolean("auto_connect", d.autoConnect),
            autoReconnect = o.optBoolean("auto_reconnect", d.autoReconnect),
            reconnectAttempts = o.optInt("reconnect_attempts", d.reconnectAttempts),
            reconnectBaseDelaySeconds = o.optInt("reconnect_base_delay_seconds", d.reconnectBaseDelaySeconds),
            nodeName = o.optString("node_name", d.nodeName),
            startPage = enumOr(o.optString("start_page"), d.startPage),
            showTraffic = o.optBoolean("show_traffic", d.showTraffic),
            showTrafficGraphs = o.optBoolean("show_traffic_graphs", d.showTrafficGraphs),
            decimalUnits = o.optBoolean("decimal_units", d.decimalUnits),
            showOfflinePeers = o.optBoolean("show_offline_peers", d.showOfflinePeers),
            peerSort = enumOr(o.optString("peer_sort"), d.peerSort),
            showRecentActivity = o.optBoolean("show_recent_activity", d.showRecentActivity),
            recentActivityCount = o.optInt("recent_activity_count", d.recentActivityCount),
            showPeerDetails = o.optBoolean("show_peer_details", d.showPeerDetails),
            showPeerIp = o.optBoolean("show_peer_ip", d.showPeerIp),
            showInternalIds = o.optBoolean("show_internal_ids", d.showInternalIds),
            showDiagnostics = o.optBoolean("show_diagnostics", d.showDiagnostics),
            forceRelay = o.optBoolean("force_relay", d.forceRelay),
            outgoingBroadcasts = o.optBoolean("outgoing_broadcasts", d.outgoingBroadcasts),
            scale = o.optDouble("scale", d.scale.toDouble()).toFloat(),
        ).normalized()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    fun encodeFavorites(names: Set<String>): String = JSONArray(names.sorted()).toString()

    fun decodeFavorites(text: String): Set<String> {
        val a = JSONArray(text)
        return (0 until a.length()).map { a.getString(it) }.filter(::validateNetworkName).toSet()
    }

    fun encodeConfigurations(configs: List<JoinConfiguration>): String = JSONArray(
        configs.map { c ->
            JSONObject().put("name", c.name).put(
                "networks",
                JSONArray(c.networks.map { JSONObject().put("name", it.name).put("private", it.private) }),
            )
        },
    ).toString()

    /** Invalid entries are rejected as a whole so the caller can keep the original for repair. */
    fun decodeConfigurations(text: String): List<JoinConfiguration> {
        val a = JSONArray(text)
        require(a.length() <= JoinConfiguration.MAX_CONFIGURATIONS) { "too many join configurations" }
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val name = o.getString("name")
            require(validateConfigName(name)) { "invalid configuration name" }
            val nets = o.getJSONArray("networks")
            require(nets.length() <= JoinConfiguration.MAX_NETWORKS) { "too many networks in configuration" }
            JoinConfiguration(
                name,
                (0 until nets.length()).map { j ->
                    val n = nets.getJSONObject(j)
                    val network = n.getString("name")
                    require(validateNetworkName(network)) { "invalid network name" }
                    JoinNetwork(network, n.optBoolean("private", false))
                },
            )
        }
    }
}
