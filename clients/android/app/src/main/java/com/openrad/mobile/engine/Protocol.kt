package com.openrad.mobile.engine

import java.net.InetSocketAddress

/**
 * Control-plane messages. Mirrors `protocol.rs` / `network.rs` of the Rust client
 * (and `radproto.py` of the Python reference). Pure functions, no I/O.
 */
object Protocol {
    const val CLIENT_OP = 0x0100031fL
    const val SERVER_OP = 0x01000320L
    const val DEFAULT_HOST = "148.113.190.78"
    const val DEFAULT_PORT = 17301
    const val CLIENT_VERSION = "2.1.4951.1"

    val SERVER_MODULUS: ByteArray = hexToBytes(
        "00e5a22d757626a24793eccf9574b33e3c8a77c2fd468a44ab335095be04336584d9f47a9728f07478b161d3a004fca4" +
            "fd36da5f28d436c51bcd0baa515909fc2a3697941554747892e205cc165e53d7758175738a4350f22583b2f415aabe38" +
            "10b5fc60d3958c257523355152c0fca6b6167864514af08b0a0017c76d69d54dff12b63cd7b3d83da7d13315dad870e6" +
            "f4813a0c58a4d0a7e4d783a4dc2d3b3fa71528549a71c415e02428cf75626d1ef8a7bc654d44efbc249bd5e3ac1dbbba0d",
    )

    fun operation(data: ByteArray): Long = Tlv.unique(Tlv.parse(data), SERVER_OP).u32()

    fun validateNodeName(name: String) {
        ensure(name.isNotBlank()) { "Device name cannot be empty" }
        ensure(name.none { it.isISOControl() }) { "Device name contains a control character" }
        Tlv.text(0x03000304L, name)
    }

    fun login(name: String, latency: Long, purpose: Int, peer: Long? = null): ByteArray {
        ensure(name.isNotEmpty() && purpose in 3..5) { "invalid login profile" }
        val parts = arrayListOf(
            Tlv.u32(CLIENT_OP, 20),
            Tlv.u32(0x010003e0L, 11),
            Tlv.tlv(0x122fL, concat(Tlv.u32(0x0100023bL, 31), Tlv.u32(0x0100025aL, 0), Tlv.text(0x03000304L, name))),
            Tlv.tlv(0x1286L, concat(Tlv.u32(0x010001ecL, 8), Tlv.text(0x030001edL, CLIENT_VERSION))),
        )
        if (purpose == 4) {
            val profile = listOf(
                0x01000337L to 10L, 0x01000338L to 0L, 0x01000339L to 19045L, 0x0100033aL to 0L,
                0x0b00033bL to 0L, 0x0b00039fL to 1L, 0x010003c8L to 0L, 0x010003c9L to 34404L,
                0x0b00033cL to 1L, 0x010003dcL to 1L,
            ).map { (t, v) -> Tlv.u32(t, v) }
            parts.add(Tlv.tlv(0x1331L, concat(*profile.toTypedArray())))
        }
        if (purpose != 3 && latency > 0) parts.add(Tlv.u32(0x01000343L, latency))
        if (purpose == 5) {
            parts.add(Tlv.u64(0x0200030bL, peer ?: fail("peer RID required")))
            parts.add(Tlv.u32(0x01000377L, 0))
        }
        return concat(*parts.toTypedArray())
    }

    fun publicList(query: String, id: Long, cursor: Long): ByteArray = concat(
        Tlv.u32(CLIENT_OP, 43),
        Tlv.tlv(0x1332L, concat(Tlv.u64(0x02000340L, id), Tlv.u64(0x0200030fL, cursor), Tlv.text(0x03000306L, query))),
    )

    fun join(name: String, id: Long, seq: Long): ByteArray {
        ensure(name.isNotEmpty() && seq > 0) { "invalid join request" }
        return concat(
            Tlv.u32(CLIENT_OP, 39),
            Tlv.tlv(
                0x131cL,
                concat(Tlv.text(0x03000306L, name), Tlv.u32(0x0b00033fL, 1), Tlv.u64(0x02000340L, id), Tlv.u32(0x010003beL, seq)),
            ),
        )
    }

    fun heartbeat(): ByteArray = Tlv.u32(CLIENT_OP, 4)

    fun requestRelay(cid: Long): ByteArray = concat(Tlv.u32(CLIENT_OP, 22), Tlv.u64(0x020001c1L, cid))

    fun requestTcpCandidates(cid: Long): ByteArray = concat(Tlv.u32(CLIENT_OP, 2), Tlv.u64(0x020001c1L, cid))

    fun advertiseTcp(cid: Long, host: String, port: Int): ByteArray = concat(
        Tlv.u32(CLIENT_OP, 2),
        Tlv.u64(0x020001c1L, cid),
        Tlv.tlv(0x1230L, Tlv.tlv(0x127dL, concat(Tlv.text(0x030001c2L, host), Tlv.u32(0x010001c3L, port.toLong())))),
    )

    class PublicListing(val networks: List<Pair<String, Long>>, val cursor: Long)

    fun listingId(data: ByteArray): Long =
        Tlv.unique(Tlv.parse(Tlv.unique(Tlv.parse(data), 0x1334L).value), 0x02000340L).u64()

    fun listing(data: ByteArray, id: Long): PublicListing {
        val f = Tlv.parse(Tlv.unique(Tlv.parse(data), 0x1334L).value)
        ensure(Tlv.unique(f, 0x02000340L).u64() == id) { "listing correlation mismatch" }
        val out = f.filter { it.tag == 0x1333L }.map {
            val n = Tlv.parse(it.value)
            Tlv.displayText(Tlv.unique(n, 0x03000306L)) to Tlv.unique(n, 0x01000341L).u32()
        }
        return PublicListing(out, Tlv.unique(f, 0x0200030fL).u64())
    }

    /** An authenticated server asked the same identity to use another server. */
    class ServerRedirect(val host: String) : Exception("authenticated server redirect")

    fun checkRedirect(records: List<Record>) {
        if (Tlv.unique(records, SERVER_OP).u32() == 12L) {
            val fields = Tlv.parse(Tlv.unique(records, 0x1238L).value)
            val address = Tlv.parse(Tlv.unique(fields, 0x1234L).value)
            val host = Tlv.decodeText(Tlv.unique(address, 0x030001c9L))
            Ip.parse(host)
            throw ServerRedirect(host)
        }
    }

    class Assignment(val vip: Int, val address: ByteArray)

    fun ownAssignment(records: List<Record>): Assignment? {
        if (Tlv.unique(records, SERVER_OP).u32() != 38L) return null
        val children = Tlv.parse(Tlv.unique(records, 0x00001316L).value)
        val ipv4 = Tlv.optional(children, 0x01000305L)
        val address = Tlv.optional(children, 0x09000305L)
        if (ipv4 == null && address == null) return null
        ensure(ipv4 != null && address != null && address.value.size == 16) { "incomplete or malformed own-address pair" }
        val value = ipv4!!.u32()
        ensure(value != 0L && value != 0xffffffffL) { "server returned an unspecified/broadcast VIP" }
        return Assignment(value.toInt(), address!!.value)
    }

    /** Purpose-4 LoginComplete → optional Message → Networks with the VIP. */
    class Registration {
        private var loginComplete = false
        var assignment: Assignment? = null
            private set

        fun receive(plain: ByteArray): Assignment? {
            ensure(assignment == null) { "assignment already received" }
            val records = Tlv.parse(plain)
            checkRedirect(records)
            val op = Tlv.unique(records, SERVER_OP).u32()
            if (!loginComplete) {
                ensure(op == 21L) { "expected LoginComplete, received operation $op" }
                loginComplete = true
            } else if (op == 38L) {
                assignment = ownAssignment(records)
            } else {
                ensure(op == 9L) { "unexpected registration operation $op" }
            }
            return assignment
        }
    }

    /** Purpose-3 LoginComplete → Registered, exactly once. */
    class Provisioning {
        private var loginComplete = false

        fun receive(plain: ByteArray): Identity? {
            val records = Tlv.parse(plain)
            checkRedirect(records)
            val op = Tlv.unique(records, SERVER_OP).u32()
            if (!loginComplete) {
                ensure(op == 21L) { "expected LoginComplete, received operation $op" }
                loginComplete = true
                return null
            }
            ensure(op == 8L) { "unexpected provisioning operation $op" }
            return Identity.registered(records)
        }
    }

    // ---- peer connection offers ----

    /**
     * Peer-connection credentials. The password bytes are owned by the holder:
     * wipe them with [wipe] once the peer channel has consumed them or the
     * offer is discarded.
     */
    class ConnectionOffer(val cid: Long, val rid: Long, val password: ByteArray) {
        fun wipe() = password.fill(0)
    }

    fun connectionDetails(data: ByteArray): ConnectionOffer {
        val outer = Tlv.parse(data)
        val cid = Tlv.unique(outer, 0x020001c1L).u64()
        val fields = Tlv.parse(Tlv.unique(outer, 0x1235L).value)
        val rid = Tlv.unique(fields, 0x020001e1L).u64()
        val password = Tlv.unique(fields, 0x0a0001cdL).value
        ensure(cid != 0L && rid != 0L && password.size in 6..1024) { "invalid connection credentials" }
        return ConnectionOffer(cid, rid, password)
    }

    fun connectionId(data: ByteArray): Long = Tlv.unique(Tlv.parse(data), 0x020001c1L).u64()

    class RelayOffer(val host: String, val port: Int, val ticket: ByteArray)

    fun relayDetails(data: ByteArray, cid: Long): RelayOffer {
        val outer = Tlv.parse(data)
        ensure(Tlv.unique(outer, 0x020001c1L).u64() == cid) { "relay connection ID mismatch" }
        val fields = Tlv.parse(Tlv.unique(outer, 0x123fL).value)
        val host = Tlv.decodeText(Tlv.unique(fields, 0x030001cbL))
        Ip.parse(host)
        val port = Tlv.unique(fields, 0x010001ccL).u32()
        val ticket = Tlv.unique(fields, 0x090001caL).value
        ensure(port in 1..65535 && ticket.size == 256) { "invalid relay endpoint or ticket" }
        return RelayOffer(host, port.toInt(), ticket)
    }

    fun tcpCandidates(data: ByteArray, cid: Long): List<Pair<String, Int>> {
        val outer = Tlv.parse(data)
        ensure(Tlv.unique(outer, 0x020001c1L).u64() == cid) { "direct connection ID mismatch" }
        val out = ArrayList<Pair<String, Int>>()
        for (rec in Tlv.parse(Tlv.unique(outer, 0x1236L).value)) {
            if (rec.tag != 0x127dL || out.size >= 4) continue
            val f = Tlv.parse(rec.value)
            val host = Tlv.decodeText(Tlv.unique(f, 0x030001c2L))
            val port = Tlv.unique(f, 0x010001c3L).u32()
            // Only IPv4 literals are dialed; IPv6 candidates are skipped on mobile.
            val ip = runCatching { Ip.parse(host) }.getOrNull() ?: continue
            if (ip == 0 || ip == -1 || Ip.isMulticast(ip) || port !in 1..65535) continue
            out.add(host to port.toInt())
        }
        return out
    }

    // ---- direct UDP (`protocol.rs`) ----

    /** Local UDP endpoints (op 28); the incoming role adds its rendezvous nonce. */
    fun advertiseUdp(cid: Long, endpoints: List<InetSocketAddress>, nonce: Int): ByteArray {
        ensure(endpoints.isNotEmpty() && endpoints.size <= Udp.MAX_CANDIDATES) { "UDP advertisement bounds" }
        val body = endpoints.map { endpointRecord(0x127dL, 0x030001c2L, 0x010001c3L, it) }.toMutableList()
        if (nonce != 0) body.add(Tlv.u32(0x0100020aL, nonce.toLong()))
        return concat(Tlv.u32(CLIENT_OP, 28), Tlv.u64(0x020001c1L, cid), Tlv.tlv(0x127bL, concat(*body.toTypedArray())))
    }

    /** Advertises a mapping (op 3) only after two authenticated UES hosts agree. */
    fun advertiseMappedUdp(cid: Long, endpoint: InetSocketAddress, nonce: Int): ByteArray {
        val ip = Udp.ipOf(endpoint) ?: fail("mapped UDP endpoint must be IPv4")
        return concat(
            Tlv.u32(CLIENT_OP, 3),
            Tlv.u64(0x020001c1L, cid),
            Tlv.tlv(
                0x1231L,
                concat(
                    Tlv.text(0x030001c4L, Ip.format(ip)), Tlv.u32(0x010001c5L, endpoint.port.toLong()),
                    Tlv.u32(0x0b0001dcL, 0), Tlv.u32(0x0b0001dbL, 0),
                    if (nonce == 0) ByteArray(0) else Tlv.u32(0x0100020aL, nonce.toLong()),
                ),
            ),
        )
    }

    private fun endpointRecord(tag: Long, hostTag: Long, portTag: Long, endpoint: InetSocketAddress): ByteArray {
        val ip = Udp.ipOf(endpoint) ?: fail("advertised UDP endpoint must be IPv4")
        return Tlv.tlv(tag, concat(Tlv.text(hostTag, Ip.format(ip)), Tlv.u32(portTag, endpoint.port.toLong())))
    }

    /** Authenticated UES hosts from LoginComplete (op 21), separate from connection servers. */
    fun uesHosts(data: ByteArray): List<Int> {
        val records = Tlv.parse(data)
        ensure(Tlv.unique(records, SERVER_OP).u32() == 21L) { "expected LoginComplete" }
        val body = Tlv.optional(records, 0x1340L) ?: return emptyList()
        val hosts = ArrayList<Int>()
        for ((index, r) in Tlv.parse(body.value).filter { it.tag == 0x0e00036cL }.withIndex()) {
            ensure(index < 32) { "UES host count limit" }
            val v = r.value
            ensure(v.size in 2..16 && v.last() == 0.toByte()) { "invalid UES host text" }
            val ip = Ip.parse(String(v, 0, v.size - 1, Charsets.US_ASCII))
            ensure(ip != 0 && !Ip.isMulticast(ip) && ip != -1 && !Udp.isLoopback(ip)) { "invalid UES host" }
            if (ip !in hosts) hosts.add(ip)
        }
        return hosts
    }

    /**
     * Validates the real IPv4 destination of a candidate. Loopback is accepted
     * only in an explicitly local deployment (`protocol.rs::candidate_address`).
     */
    fun candidateAddress(ip: Int, routeIp: Int?) {
        ensure(
            ip != 0 && !Ip.isMulticast(ip) && ip != -1 && ip ushr 24 != 0 &&
                (!Udp.isLoopback(ip) || (routeIp != null && Udp.isLoopback(routeIp))),
        ) { "invalid candidate address" }
    }

    /**
     * Direct candidates of [operation] under [root] (op 29 / 0x127c for UDP).
     * Malformed entries are excluded individually; IPv6 candidates are skipped on
     * mobile. Mirrors `protocol.rs::direct_candidates_on_route`.
     */
    fun directCandidates(data: ByteArray, cid: Long, operation: Long, root: Long, routeIp: Int?): List<InetSocketAddress> {
        val outer = Tlv.parse(data)
        ensure(Tlv.unique(outer, SERVER_OP).u32() == operation) { "unexpected candidate operation" }
        ensure(Tlv.unique(outer, 0x020001c1L).u64() == cid) { "candidate correlation mismatch" }
        val body = Tlv.optional(outer, root) ?: return emptyList()
        val out = ArrayList<InetSocketAddress>()
        for ((index, r) in Tlv.parse(body.value).filter { it.tag == 0x127dL }.withIndex()) {
            ensure(index < Udp.MAX_CANDIDATES) { "direct candidate limit" }
            val candidate = runCatching {
                val f = Tlv.parse(r.value)
                val ip = Ip.parse(Tlv.decodeText(Tlv.unique(f, 0x030001c2L)))
                candidateAddress(ip, routeIp)
                val port = Tlv.unique(f, 0x010001c3L).u32()
                ensure(port in 1L..65535L) { "invalid candidate port" }
                Udp.endpoint(ip, port.toInt())
            }.getOrNull() ?: continue
            if (candidate !in out) out.add(candidate)
        }
        return out
    }

    /** Rendezvous nonce the incoming peer attached to its UDP candidates (op 29). */
    fun udpNonce(data: ByteArray): Int? {
        val body = Tlv.optional(Tlv.parse(data), 0x127cL) ?: return null
        val nonce = Tlv.optional(Tlv.parse(body.value), 0x0100020aL)?.u32() ?: return null
        return nonce.takeIf { it in 1L..65535L }?.toInt()
    }

    /** Outgoing role: the peer's UES-mapped endpoint and its nonce (op 7). */
    fun mappedUdpCandidate(data: ByteArray, cid: Long, routeIp: Int?): Pair<InetSocketAddress, Int> {
        val (endpoint, nonce) = mapped(data, cid, routeIp)
        ensure(nonce != null && nonce in 1L..65535L) { "invalid mapped UDP candidate" }
        return endpoint to nonce!!.toInt()
    }

    /** Incoming role: the dialer's UES-mapped endpoint (op 7); our own nonce applies. */
    fun incomingMappedCandidate(data: ByteArray, cid: Long, routeIp: Int?): InetSocketAddress = mapped(data, cid, routeIp).first

    private fun mapped(data: ByteArray, cid: Long, routeIp: Int?): Pair<InetSocketAddress, Long?> {
        val outer = Tlv.parse(data)
        ensure(Tlv.unique(outer, SERVER_OP).u32() == 7L && Tlv.unique(outer, 0x020001c1L).u64() == cid) { "mapped candidate correlation" }
        val f = Tlv.parse(Tlv.unique(outer, 0x1237L).value)
        val ip = Ip.parse(Tlv.decodeText(Tlv.unique(f, 0x030001c4L)))
        candidateAddress(ip, routeIp)
        val port = Tlv.unique(f, 0x010001c5L).u32()
        ensure(port in 1L..65535L) { "invalid mapped UDP candidate" }
        return Udp.endpoint(ip, port.toInt()) to Tlv.optional(f, 0x0100020aL)?.u32()
    }

    fun rendezvousChecksum(data: ByteArray): Long {
        var crc = 0xffffffffL
        for (byte in data) {
            var top = crc and 0xff000000L
            repeat(8) {
                top = ((top shl 1) xor (if (top and 0x80000000L != 0L) 0x04c11db7L else 0L)) and 0xffffffffL
            }
            crc = top xor (((crc shl 8) or (byte.toLong() and 0xff)) and 0xffffffffL)
        }
        return crc xor 0xffffffffL
    }

    // ---- private network helpers ----

    fun utf16Length(text: String): Int = text.length

    fun validateNetworkName(name: String) {
        ensure(name.isNotBlank() && utf16Length(name) in 1..255) { "network name must contain 1–255 characters" }
        ensure(name.none { it.isISOControl() }) { "network name contains a control character" }
        Tlv.text(0x03000306L, name)
    }

    /** UTF-8 + NUL wire form. The caller zeroes the returned array after use. */
    fun passwordBytes(password: CharArray): ByteArray {
        ensure(password.size in 6..256) { "network password must contain 6–256 characters" }
        ensure(password.none { it.code < 32 || it.code == 127 }) { "network password contains a control character" }
        val buffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
        val out = ByteArray(buffer.remaining() + 1)
        buffer.get(out, 0, out.size - 1)
        if (buffer.hasArray()) buffer.array().fill(0)
        return out
    }

    /** CRC64-ECMA of the exact UTF-16LE name with complemented endpoints. */
    fun networkIdentity(name: String): Long {
        var crc = -1L
        for (byte in name.toByteArray(Charsets.UTF_16LE)) {
            crc = crc xor ((byte.toLong() and 0xff) shl 56)
            repeat(8) { crc = (crc shl 1) xor (if (crc < 0) 0x42f0e1eba9ea3693L else 0L) }
        }
        return crc.inv()
    }

    fun networkVerifier(name: String, password: CharArray, salt: ByteArray): ByteArray {
        ensure(salt.size == 32) { "network salt must be 32 bytes" }
        val identity = Sh.identityBytes(networkIdentity(name))
        val wire = passwordBytes(password)
        try {
            val x = Sh.passwordExponent(salt, identity, wire)
            return leU32(32) + salt + bnBytes(Sh.G.modPow(x, Sh.N))
        } finally {
            wire.fill(0)
        }
    }

    fun networkIdHex(value: ByteArray): String {
        ensure(value.size == 16 && value.any { it != 0.toByte() }) { "invalid network ID" }
        return value.toHex()
    }

    fun macForIp(ip: Int): ByteArray =
        byteArrayOf(2, 0x1a, (ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())
}

/** Reusable registered identity. The credential never leaves the encrypted store. */
data class Identity(
    val rid: Long,
    val vip: Int,
    val nodeName: String,
    val address: ByteArray,
    val credential: ByteArray,
    val serverAddress: String,
) {
    init {
        ensure(rid != 0L) { "invalid assigned RID" }
        ensure(vip != 0 && vip != -1) { "invalid assigned VIP" }
        ensure(address.size == 16 && address.any { it != 0.toByte() }) { "invalid assigned companion address" }
        ensure(credential.size in 6..4096) { "invalid assigned credential length" }
        ensure(nodeName.isNotEmpty() && serverAddress.isNotEmpty()) { "invalid assigned name or server address" }
    }

    override fun toString(): String = "Identity(rid=$rid, vip=${Ip.format(vip)}, nodeName=$nodeName)"

    override fun equals(other: Any?): Boolean = other is Identity && other.rid == rid &&
        other.vip == vip && other.nodeName == nodeName && other.address.contentEquals(address) &&
        other.credential.contentEquals(credential) && other.serverAddress == serverAddress

    override fun hashCode(): Int = rid.hashCode()

    companion object {
        fun registered(records: List<Record>): Identity {
            ensure(Tlv.unique(records, Protocol.SERVER_OP).u32() == 8L) { "expected Registered" }
            val fields = Tlv.parse(Tlv.unique(records, 0x00001233L).value)
            val rid = Tlv.unique(fields, 0x020001e1L)
            ensure(rid.value.size == 8) { "assigned RID requires eight bytes" }
            return Identity(
                rid.u64(),
                Tlv.unique(fields, 0x01000305L).u32().toInt(),
                Tlv.decodeText(Tlv.unique(fields, 0x03000304L)),
                Tlv.unique(fields, 0x09000305L).value,
                Tlv.unique(fields, 0x0a0001c8L).value,
                Tlv.decodeText(Tlv.unique(fields, 0x030001e2L)),
            )
        }
    }
}

data class NetworkEntry(val name: String, val networkId: String)

data class PeerEntry(
    val rid: Long,
    val name: String,
    val vip: Int,
    val server: String?,
    val state: Long,
    val networkIds: Set<String>,
)

/** Authenticated network/peer metadata including incremental removals and roles. */
class Membership(val ownRid: Long) {
    val networks = LinkedHashMap<String, NetworkEntry>()
    val peers = HashMap<Long, PeerEntry>()
    val roles = HashMap<String, HashMap<Long, Long>>()

    /** Bumped before every mutation, so callers can cache [permits] results per version. */
    var version = 0L
        private set

    fun copy(): Membership = Membership(ownRid).also { m ->
        m.networks.putAll(networks)
        m.peers.putAll(peers)
        roles.forEach { (k, v) -> m.roles[k] = HashMap(v) }
    }

    fun role(networkId: String, rid: Long): Long? = roles[networkId]?.get(rid)

    private fun role(fields: List<Record>, rid: Long, networkId: String) {
        val role = Tlv.optional(fields, 0x0100030aL) ?: return
        roles.getOrPut(networkId) { HashMap() }[rid] = role.u32()
    }

    private fun peer(fields: List<Record>, networkId: String? = null) {
        val rid = Tlv.unique(fields, 0x020001e1L).u64()
        val old = peers[rid]
        val ids = HashSet(old?.networkIds ?: emptySet())
        if (networkId != null) ids.add(networkId)
        val server = Tlv.optional(fields, 0x030001c9L)?.let { Tlv.decodeText(it) } ?: old?.server
        val state = Tlv.optional(fields, 0x010003a0L)?.u32() ?: old?.state ?: 0L
        peers[rid] = PeerEntry(
            rid,
            Tlv.decodeText(Tlv.unique(fields, 0x03000304L)),
            Tlv.unique(fields, 0x01000305L).u32().toInt(),
            server,
            state,
            ids,
        )
    }

    fun snapshot(data: ByteArray) {
        version++
        val root = Tlv.optional(Tlv.parse(data), 0x1316L) ?: return
        val entries = Tlv.parse(root.value)
        for (rec in entries) {
            if (rec.tag == 0x1315L) {
                val fields = Tlv.parse(rec.value)
                val id = Protocol.networkIdHex(Tlv.unique(fields, 0x0d000309L).value)
                networks[id] = NetworkEntry(Tlv.decodeText(Tlv.unique(fields, 0x03000306L)), id)
                role(fields, ownRid, id)
            } else if (rec.tag == 0x1317L) {
                peer(Tlv.parse(rec.value))
            }
        }
        for (rec in entries) {
            if (rec.tag != 0x1318L) continue
            val fields = Tlv.parse(rec.value)
            val rid = Tlv.unique(fields, 0x020001e1L).u64()
            val id = Protocol.networkIdHex(Tlv.unique(fields, 0x0d000309L).value)
            role(fields, rid, id)
            peers[rid]?.let { peers[rid] = it.copy(networkIds = it.networkIds + id) }
        }
    }

    fun removeNetwork(id: String) {
        version++
        networks.remove(id)
        roles.remove(id)
        for (rid in peers.keys.toList()) {
            val p = peers[rid]!!
            val ids = p.networkIds - id
            if (ids.isEmpty()) peers.remove(rid) else peers[rid] = p.copy(networkIds = ids)
        }
    }

    fun removeMember(id: String, rid: Long) {
        version++
        if (rid == ownRid) {
            removeNetwork(id)
            return
        }
        roles[id]?.remove(rid)
        val p = peers[rid] ?: return
        val ids = p.networkIds - id
        if (ids.isEmpty()) peers.remove(rid) else peers[rid] = p.copy(networkIds = ids)
    }

    fun changes(data: ByteArray) {
        version++
        val root = Tlv.optional(Tlv.parse(data), 0x131fL) ?: return
        for (rec in Tlv.parse(root.value)) {
            if (rec.tag !in CHANGE_TAGS) continue
            val fields = Tlv.parse(rec.value)
            when (rec.tag) {
                0x131eL -> {
                    val rid = Tlv.unique(fields, 0x020001e1L).u64()
                    val id = Protocol.networkIdHex(Tlv.unique(fields, 0x0d000309L).value)
                    role(fields, rid, id)
                    if (Tlv.optional(fields, 0x01000305L) != null) peer(fields, id)
                }
                0x1323L -> snapshot(Tlv.tlv(0x1316L, Tlv.tlv(0x1315L, rec.value)))
                0x1318L, 0x131dL -> {
                    val ids = fields.filter { it.tag == 0x020001e1L }.map { it.u64() }
                    ensure(ids.size in 1..2) { "invalid member event identifiers" }
                    val id = Protocol.networkIdHex(Tlv.unique(fields, 0x0d000309L).value)
                    if (rec.tag == 0x131dL) removeMember(id, ids[0]) else role(fields, ids[0], id)
                }
                0x132eL -> removeNetwork(Protocol.networkIdHex(Tlv.unique(fields, 0x0d000309L).value))
                0x1366L -> {
                    val rid = Tlv.unique(fields, 0x020001e1L).u64()
                    peers[rid]?.let { peers[rid] = it.copy(state = Tlv.unique(fields, 0x010003a0L).u32()) }
                }
            }
        }
    }

    /** One peer is reachable when online, has a server, and both roles in a shared network are approved. */
    fun permits(rid: Long): Boolean {
        val p = peers[rid] ?: return false
        if (rid == ownRid || p.server == null || p.state !in ONLINE_STATES) return false
        return p.networkIds.any { id ->
            networks.containsKey(id) && role(id, ownRid) != 0L && role(id, rid) != 0L
        }
    }

    fun eligible(): Map<Long, PeerEntry> = peers.filterKeys { permits(it) }

    companion object {
        private val CHANGE_TAGS = setOf(0x131eL, 0x1323L, 0x1318L, 0x131dL, 0x132eL, 0x1366L)
        val ONLINE_STATES = setOf(1L, 5L)
    }
}

/** One correlated join/create/leave/delete transaction. Passwords are zeroed after use. */
class NetworkOperation private constructor(
    val action: Action,
    val requestId: Long,
    val sequence: Long,
    val name: String?,
    val networkId: String?,
    private val sh: ShClient?,
    val packet: ByteArray,
) {
    enum class Action(val code: Long) { CREATE(1), JOIN(2), LEAVE(3), DELETE(7) }

    sealed class Outcome {
        data class Joined(val network: NetworkEntry, val pendingApproval: Boolean) : Outcome()
        data class Created(val network: NetworkEntry) : Outcome()
        data class Removed(val networkId: String, val deleted: Boolean) : Outcome()
    }

    class Step(val send: ByteArray? = null, val outcome: Outcome? = null)

    private var joined: NetworkEntry? = null
    private val approved = HashSet<String>()

    val isPrivateJoin: Boolean get() = sh != null

    /** Drops the private-join password copy, if any; safe to call more than once. */
    fun wipe() {
        sh?.wipe()
    }

    /**
     * Older servers can omit correlation on password replies. Route such a reply
     * only when exactly one operation accepts it (checked by the caller).
     */
    fun acceptsAuthReply(data: ByteArray): Boolean {
        if (action != Action.JOIN) return false
        val f = Tlv.parse(Tlv.optional(Tlv.parse(data), 0x131cL)?.value ?: return false)
        val id = Tlv.optional(f, 0x02000340L)?.u64()
        val seq = Tlv.optional(f, 0x010003beL)?.u32()
        return (id == null || id == requestId) && (seq == null || seq == sequence) &&
            (id != null || seq != null || sh != null)
    }

    fun handle(data: ByteArray, membership: Membership): Step {
        val outer = Tlv.parse(data)
        val op = Tlv.unique(outer, Protocol.SERVER_OP).u32()
        if (op == 40L && action == Action.JOIN) {
            val f = Tlv.parse(Tlv.unique(outer, 0x131cL).value)
            Tlv.optional(f, 0x02000340L)?.let { if (it.u64() != requestId) return Step() }
            Tlv.optional(f, 0x010003beL)?.let { if (it.u32() != sequence) return Step() }
            Tlv.optional(f, 0x0100034aL)?.let { ensure(it.value.contentEquals(ByteArray(4))) { "unexpected network join context" } }
            ensure(Tlv.unique(f, 0x02000303L).u64() == 0L) { "network join refused; check name and password" }
            val auth = sh ?: fail("this network requires a password")
            val blob = Tlv.unique(f, 0x0a00030eL).value
            ensure(!blob.contentEquals(Sh.record(0x10000000L, ByteArray(4)))) { "network password authentication failed" }
            val reply = when (auth.state) {
                2 -> auth.receiveF10(blob)
                4 -> auth.receiveF12(blob)
                6 -> {
                    auth.receiveF14(blob)
                    null
                }
                else -> fail("unexpected network authentication state")
            }
            return Step(
                reply?.let {
                    concat(Tlv.u32(Protocol.CLIENT_OP, 39), Tlv.tlv(0x131cL, concat(Tlv.tlv(0x0a00030eL, it), Tlv.u32(0x010003beL, sequence))))
                },
            )
        }
        if (op == 42L && action == Action.JOIN) approved.addAll(approvedNetworkIds(outer))
        if (op == 37L) {
            val f = Tlv.parse(Tlv.unique(outer, 0x131aL).value)
            if (Tlv.unique(f, 0x02000340L).u64() != requestId) return Step()
            ensure(Tlv.unique(f, 0x0100030cL).u32() == action.code) { "network response action mismatch" }
            Tlv.optional(f, 0x010001d2L)?.let { fail("network operation refused (server error ${it.u32()})") }
            when (action) {
                Action.JOIN -> {
                    ensure(sh == null || sh.state == 7) { "private join completed without server password proof" }
                    val root = Tlv.tlv(0x1316L, Tlv.unique(f, 0x1316L).value)
                    val match = matchNetwork(root, membership.ownRid)
                    membership.snapshot(root)
                    joined = match
                    when (membership.role(match.networkId, membership.ownRid)) {
                        0L -> return Step(outcome = Outcome.Joined(match, pendingApproval = true))
                        1L, 2L -> return Step(outcome = Outcome.Joined(match, pendingApproval = false))
                    }
                }
                Action.CREATE -> {
                    ensure(Tlv.unique(f, 0x0100034aL).u32() == sequence) { "network response context mismatch" }
                    val root = Tlv.tlv(0x1316L, Tlv.tlv(0x1315L, Tlv.unique(f, 0x1315L).value))
                    val match = matchNetwork(root, membership.ownRid)
                    membership.snapshot(root)
                    return Step(outcome = Outcome.Created(match))
                }
                Action.LEAVE, Action.DELETE -> {
                    ensure(Tlv.unique(f, 0x0100034aL).u32() == sequence) { "network response context mismatch" }
                    ensure(Protocol.networkIdHex(Tlv.unique(f, 0x0d000309L).value) == networkId) { "network response ID mismatch" }
                    membership.removeNetwork(networkId!!)
                    return Step(outcome = Outcome.Removed(networkId, action == Action.DELETE))
                }
            }
        }
        val j = joined
        if (j != null && j.networkId in approved) return Step(outcome = Outcome.Joined(j, pendingApproval = false))
        return Step()
    }

    private fun matchNetwork(root: ByteArray, ownRid: Long): NetworkEntry {
        val returned = Membership(ownRid)
        returned.snapshot(root)
        val matches = returned.networks.values.filter { it.name == name }
        ensure(matches.size == 1) { "network response name mismatch" }
        return matches[0]
    }

    companion object {
        fun approvedNetworkIds(records: List<Record>): Set<String> {
            if (Tlv.unique(records, Protocol.SERVER_OP).u32() != 42L) return emptySet()
            val root = Tlv.optional(records, 0x131fL) ?: return emptySet()
            return Tlv.parse(root.value).filter { it.tag == 0x131eL }.map {
                Protocol.networkIdHex(Tlv.unique(Tlv.parse(it.value), 0x0d000309L).value)
            }.toSet()
        }

        private fun management(code: Long, id: Long, seq: Long, fields: ByteArray) = concat(
            Tlv.u32(Protocol.CLIENT_OP, 52),
            Tlv.tlv(0x1319L, concat(Tlv.u32(0x0100030cL, code), Tlv.u64(0x02000340L, id), Tlv.u32(0x0100034aL, seq), fields)),
        )

        fun join(name: String, password: CharArray?, id: Long, seq: Long): NetworkOperation {
            ensure(id != 0L && seq != 0L) { "invalid network request correlation" }
            Protocol.validateNetworkName(name)
            if (password == null) return NetworkOperation(Action.JOIN, id, seq, name, null, null, Protocol.join(name, id, seq))
            val wire = Protocol.passwordBytes(password)
            val sh = try {
                ShClient(Protocol.networkIdentity(name), wire)
            } finally {
                wire.fill(0)
            }
            val packet = concat(
                Tlv.u32(Protocol.CLIENT_OP, 39),
                Tlv.tlv(
                    0x131cL,
                    concat(
                        Tlv.text(0x03000306L, name), Tlv.u32(0x0b00033fL, 0), Tlv.tlv(0x0a00030eL, sh.start()),
                        Tlv.u64(0x02000340L, id), Tlv.u32(0x010003beL, seq),
                    ),
                ),
            )
            return NetworkOperation(Action.JOIN, id, seq, name, null, sh, packet)
        }

        fun create(name: String, password: CharArray, id: Long, seq: Long, salt: ByteArray = randomBytes(32)): NetworkOperation {
            Protocol.validateNetworkName(name)
            val verifier = Protocol.networkVerifier(name, password, salt)
            val packet = management(
                1, id, seq,
                Tlv.tlv(0x1314L, concat(Tlv.u32(0x0b000308L, 0), Tlv.text(0x03000306L, name), Tlv.tlv(0x0a000307L, verifier))),
            )
            verifier.fill(0)
            return NetworkOperation(Action.CREATE, id, seq, name, null, null, packet)
        }

        fun remove(networkId: String, name: String, delete: Boolean, id: Long, seq: Long): NetworkOperation {
            val guid = hexToBytes(networkId)
            Protocol.networkIdHex(guid)
            val action = if (delete) Action.DELETE else Action.LEAVE
            return NetworkOperation(action, id, seq, name, networkId, null, management(action.code, id, seq, Tlv.tlv(0x0d000309L, guid)))
        }
    }
}
