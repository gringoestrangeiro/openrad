package com.openrad.mobile.model

import java.net.Inet4Address

/** Mirrors `runtime.rs::PeerState`. Labels come from strings.xml in the UI. */
enum class PeerState {
    OFFLINE,
    ONLINE_QUEUED,
    CONNECTING,
    CONNECTED,
    REFUSED,
    FAILED,
    UNAVAILABLE,
}

/** Authenticated peer transport. Mirrors the Direct TCP / Direct UDP / Relay label. */
enum class Transport {
    DIRECT_TCP,
    DIRECT_UDP,
    RELAY,
}

/** Mirrors `protocol.rs::Network`. networkId is a 16-byte hex string. */
data class NetworkInfo(
    val name: String,
    val networkId: String,
    val isFavorite: Boolean = false,
    val memberCount: Int = 0,
    val ownRole: MemberRole = MemberRole.MEMBER,
    val pendingApproval: Boolean = false,
)

enum class MemberRole {
    MEMBER,
    ADMIN,
    PENDING,
    UNKNOWN,
}

/** Mirrors `protocol.rs::Peer` + `runtime.rs::PeerView`. */
data class Peer(
    val rid: Long,
    val name: String,
    val vip: String,
    val networkIds: Set<String> = emptySet(),
    val state: PeerState = PeerState.OFFLINE,
    val detail: String = "",
    val transport: Transport? = null,
    val endpoint: String? = null,
    val rttMs: Double? = null,
    val rttFailed: Boolean = false,
    val rttRunning: Boolean = false,
)

/** Mirrors `protocol.rs::PublicNetwork`. */
data class PublicNetwork(
    val name: String,
    val reportedCount: Int,
    val joined: Boolean = false,
    val isFavorite: Boolean = false,
)

/** Mirrors `runtime.rs::Traffic`. */
data class Traffic(
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
    val sentFrames: Long = 0,
    val receivedFrames: Long = 0,
    val dropped: Long = 0,
)

/** Immutable engine snapshot. Mirrors `runtime.rs::Snapshot`. */
data class Snapshot(
    val vip: String? = null,
    val networks: List<NetworkInfo> = emptyList(),
    val peers: List<Peer> = emptyList(),
    val interfaceReady: Boolean = false,
    val interfaceError: String? = null,
    val traffic: Traffic = Traffic(),
    val latencyMs: Int = 0,
    val restrictedTraffic: Boolean = false,
    val retryQueued: Int = 0,
    val retryActive: Int = 0,
    val forceRelay: Boolean = false,
    val rid: Long? = null,
    val nodeName: String? = null,
    val pendingIncoming: Int = 0,
)

/** Connection phase. Mirrors `backend.rs::Phase`. */
enum class ConnectionPhase {
    IDLE,
    PROVISIONING,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
    RESETTING,
}

data class ConnectionStatus(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val message: String = "",
    val connected: Boolean = false,
)

data class ActivityEvent(
    val id: Long,
    val text: String,
    val isError: Boolean = false,
)

/** Mirrors `storage.rs::PeerSort`. */
enum class PeerSort {
    NAME,
    STATUS,
    ADDRESS,
}

/** Mirrors `storage.rs::StartPage`. */
enum class StartPage {
    NETWORKS,
    DISCOVER,
}

/** Subset of `storage.rs::Settings` relevant to phase 1. */
data class AppSettings(
    val language: String = "system",
    val autoConnect: Boolean = true,
    val autoReconnect: Boolean = true,
    val reconnectAttempts: Int = 3,
    val reconnectBaseDelaySeconds: Int = 2,
    val nodeName: String = "openrad-android",
    val startPage: StartPage = StartPage.NETWORKS,
    val showTraffic: Boolean = true,
    val showTrafficGraphs: Boolean = true,
    val decimalUnits: Boolean = false,
    val showOfflinePeers: Boolean = true,
    val peerSort: PeerSort = PeerSort.NAME,
    val showRecentActivity: Boolean = true,
    val recentActivityCount: Int = 12,
    val showPeerDetails: Boolean = false,
    val showPeerIp: Boolean = false,
    val showInternalIds: Boolean = false,
    val showDiagnostics: Boolean = false,
    val forceRelay: Boolean = false,
    val outgoingBroadcasts: Boolean = true,
    val scale: Float = 1.0f,
) {
    /** Ranges preserved from `storage.rs::Settings`. */
    fun normalized(): AppSettings = copy(
        reconnectAttempts = reconnectAttempts.coerceIn(1, 10),
        reconnectBaseDelaySeconds = reconnectBaseDelaySeconds.coerceIn(1, 30),
        recentActivityCount = recentActivityCount.coerceIn(5, 40),
        scale = if (scale.isNaN()) 1.0f else scale.coerceIn(0.8f, 1.5f),
        language = if (language in LANGUAGES) language else "system",
        nodeName = nodeName.trim().ifEmpty { "openrad-android" },
    )

    companion object {
        val LANGUAGES = listOf("system", "en", "pt", "ru", "vi")
    }
}

/** Result counters of a batch join, kept visible after it finishes. */
data class BatchProgress(
    val total: Int,
    val joined: Int = 0,
    val skipped: Int = 0,
    val pendingApproval: Int = 0,
    val failed: Int = 0,
    val running: Boolean = true,
) {
    val done: Int get() = joined + skipped + pendingApproval + failed
}

/** Saved network for auto-join. Mirrors `storage.rs::JoinNetwork` (no password!). */
data class JoinNetwork(
    val name: String,
    val private: Boolean,
)

data class JoinConfiguration(
    val name: String,
    val networks: List<JoinNetwork>,
) {
    companion object {
        const val MAX_CONFIGURATIONS = 32
        const val MAX_NETWORKS = 128
    }
}
