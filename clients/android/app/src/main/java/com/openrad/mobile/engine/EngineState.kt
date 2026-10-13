package com.openrad.mobile.engine

/** Immutable engine snapshot published to the UI. Mirrors `runtime.rs::Snapshot`. */
data class EngineState(
    val phase: Phase = Phase.STOPPED,
    val error: String? = null,
    val rid: Long? = null,
    val vip: Int? = null,
    val nodeName: String? = null,
    val networks: List<NetworkView> = emptyList(),
    val peers: List<PeerView> = emptyList(),
    val traffic: TrafficView = TrafficView(),
    val latencyMs: Long = 0,
    val forceRelay: Boolean = false,
    val retryQueued: Int = 0,
    val retryActive: Int = 0,
    val pendingIncoming: Int = 0,
) {
    enum class Phase { STOPPED, PROVISIONING, CONNECTING, ONLINE, RECONNECTING, ERROR }
}

data class NetworkView(
    val networkId: String,
    val name: String,
    /** 0 = awaiting approval, 1 = member, 2 = owner/admin, null = unknown. */
    val ownRole: Long?,
    val memberCount: Int,
)

enum class LinkState { IDLE, QUEUED, CONNECTING, CONNECTED, FAILED, REFUSED, OFFLINE, UNAVAILABLE }

data class PeerView(
    val rid: Long,
    val name: String,
    val vip: Int,
    val networkIds: Set<String>,
    val link: LinkState,
    val detail: String = "",
    val transport: TransportPath? = null,
    val endpoint: String? = null,
)

data class TrafficView(
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
    val sentFrames: Long = 0,
    val receivedFrames: Long = 0,
    val dropped: Long = 0,
)

data class EngineConfig(
    val host: String = Protocol.DEFAULT_HOST,
    val port: Int = Protocol.DEFAULT_PORT,
    val nodeName: String = "openrad-android",
    val forceRelay: Boolean = false,
    val autoReconnect: Boolean = true,
    val reconnectAttempts: Int = 3,
    val reconnectBaseDelaySeconds: Int = 2,
    val outgoingBroadcasts: Boolean = true,
    /** Public RSA modulus of the coordination service; overridden only by tests. */
    val modulus: ByteArray = Protocol.SERVER_MODULUS,
)

/** Durable identity storage. Android uses an encrypted store; tests use memory. */
interface IdentityStore {
    fun load(): Identity?
    fun save(identity: Identity)
    fun clear()
}

class MemoryIdentityStore(private var identity: Identity? = null) : IdentityStore {
    override fun load() = identity
    override fun save(identity: Identity) {
        this.identity = identity
    }
    override fun clear() {
        identity = null
    }
}

interface EngineListener {
    fun onState(state: EngineState) {}
    fun onEvent(message: String, error: Boolean) {}
    /** Validated inbound IPv4 packet for the TUN. */
    fun onPacket(packet: ByteArray) {}
}

/** Outcome of an asynchronous engine command. */
sealed class CommandResult {
    data class Listing(val query: String, val networks: List<Pair<String, Long>>, val cursor: Long) : CommandResult()
    data class Network(val outcome: NetworkOperation.Outcome) : CommandResult()
    data class Rtt(val rid: Long, val millis: Double) : CommandResult()
    data class Failed(val message: String) : CommandResult()
}
