package com.openrad.mobile.engine

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-Kotlin OpenRad engine: authenticated control session, peer channels
 * (direct TCP, direct UDP or relay, both directions), network operations and the
 * L3 bridge. Direct paths are used only while [NetworkPolicy] reports Wi-Fi.
 *
 * Mirrors the shared engine of `daemon.rs` + `runtime.rs` (and the Python
 * reference `radvpn.py::Engine`). Blocking socket I/O runs on dedicated threads;
 * every queue, wait and retry is bounded.
 */
class Engine(
    config: EngineConfig,
    private val store: IdentityStore,
    private val listener: EngineListener,
    private val protector: SocketProtector? = null,
    private val networkPolicy: NetworkPolicy = NetworkPolicy.UNRESTRICTED,
) {
    @Volatile
    var config: EngineConfig = config
        private set

    private val lock = Any()
    private val running = AtomicBoolean(false)
    /** Cancellation flag handed to sockets: true once the engine must stop. */
    private val stopping = AtomicBoolean(false)
    private var controlThread: Thread? = null

    @Volatile private var phase = EngineState.Phase.STOPPED
    @Volatile private var error: String? = null
    @Volatile private var identity: Identity? = null
    @Volatile private var latencyMs = 0L
    @Volatile private var routeIp: String? = null
    /** Authenticated UES hosts of the main session, for incoming mapped UDP. */
    @Volatile private var uesHosts: List<Int> = emptyList()
    /** Relay-only state last applied to workers: force-relay or a non-Wi-Fi network. */
    private var relayOnlyApplied = config.forceRelay || !networkPolicy.isWifi()
    private var wifiApplied = networkPolicy.isWifi()

    private var membership = Membership(0)
    private val workers = HashMap<Long, PeerWorker>()
    /** Bumped on every [workers] change; with [Membership.version] it keys the permitted-peer caches. */
    private var workersVersion = 0L
    private var permittedFor: Membership? = null
    private var permittedVersion = -1L
    private var permittedWorkersVersion = -1L
    private var permittedRids: Set<Long> = emptySet()
    private var permittedWorkers: List<PeerWorker> = emptyList()
    private val retries = HashMap<Long, PeerRetry>()
    private var retryPacer = RetryPacer(now())
    /** True while the retry pacer holds back due work; the control loop then polls sooner. */
    private var pacedBacklog = false
    private val pending = LinkedHashMap<Long, PendingOffer>()
    private val wire = ArrayBlockingQueue<ByteArray>(PeerScheduling.ADVERTISEMENT_QUEUE)
    private val commands = ArrayBlockingQueue<Command>(32)
    private val dirty = AtomicBoolean(true)
    private var lastPublish = 0L
    private var lastJoinStart = 0L
    private var nextRequest = 1L

    private val sentBytes = AtomicLong()
    private val receivedBytes = AtomicLong()
    private val sentFrames = AtomicLong()
    private val receivedFrames = AtomicLong()
    private val dropped = AtomicLong()

    /** Set by the VPN service when a TUN is attached; otherwise echo replies are synthesized. */
    @Volatile var tunAttached = false

    val bridge = L3Bridge(
        ownVip = { identity?.vip ?: 0 },
        peers = { connectedPeers() },
        outgoingBroadcasts = { this.config.outgoingBroadcasts },
    )

    private class PendingOffer(val offer: Protocol.ConnectionOffer, val created: Long, val records: MutableList<ByteArray>) {
        fun wipe() = offer.wipe()
    }

    private class PeerRetry(var failures: Int, var at: Long, var previouslyConnected: Boolean)

    private sealed class Command(val done: (CommandResult) -> Unit) {
        class Search(val query: String, val cursor: Long, done: (CommandResult) -> Unit) : Command(done)
        class Join(val name: String, val password: CharArray?, done: (CommandResult) -> Unit) : Command(done)
        class Create(val name: String, val password: CharArray, done: (CommandResult) -> Unit) : Command(done)
        class Remove(val networkId: String, val delete: Boolean, done: (CommandResult) -> Unit) : Command(done)

        fun fail(message: String) = done(CommandResult.Failed(message))

        fun wipe() {
            if (this is Join) password?.fill('\u0000')
            if (this is Create) password.fill('\u0000')
        }
    }

    private class Active(
        val operation: NetworkOperation?,
        val search: Command.Search?,
        val requestId: Long,
        val deadline: Long,
        val done: (CommandResult) -> Unit,
    )

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (!running.compareAndSet(false, true)) return
        stopping.set(false)
        controlThread = Thread({ runControl() }, "openrad-control").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopping.set(true)
        if (!running.getAndSet(false)) return
        synchronized(lock) { workers.values.forEach { it.cancel() } }
        controlThread?.interrupt()
        controlThread?.join(8000)
        val all = synchronized(lock) { workers.values.toList() }
        val deadline = now() + 7000
        for (w in all) w.thread.join((deadline - now()).coerceAtLeast(1))
        synchronized(lock) {
            workers.clear()
            workersVersion++
            pending.values.forEach { it.wipe() }
            pending.clear()
        }
        drainCommands("engine stopped")
        phase = EngineState.Phase.STOPPED
        publish(force = true)
    }

    val isRunning: Boolean get() = running.get()

    fun updateConfig(next: EngineConfig) {
        config = next
        applyTransportPolicy()
        dirty.set(true)
    }

    /**
     * Re-reads [NetworkPolicy] after a network change. Leaving Wi-Fi retires
     * direct channels and setups so peers reconnect relay-only; returning to
     * Wi-Fi restarts pending setups so they may use direct paths again.
     */
    fun networkChanged() {
        val wifi = networkPolicy.isWifi()
        val changed = synchronized(lock) { (wifi != wifiApplied).also { wifiApplied = wifi } }
        if (changed && !config.forceRelay) {
            event(if (wifi) "Wi-Fi network: direct paths enabled." else "Not on Wi-Fi: direct paths disabled, relay only.", false)
        }
        applyTransportPolicy()
        dirty.set(true)
    }

    /** Direct TCP/UDP are allowed only on Wi-Fi and without force-relay. */
    private fun relayOnly(): Boolean = config.forceRelay || !networkPolicy.isWifi()

    private fun applyTransportPolicy() {
        val (changed, enabled) = synchronized(lock) {
            val enabled = relayOnly()
            (enabled != relayOnlyApplied).also { relayOnlyApplied = enabled } to enabled
        }
        if (changed) applyForceRelay(enabled)
    }

    // ------------------------------------------------------------------ public commands

    fun search(query: String, cursor: Long, done: (CommandResult) -> Unit) = submit(Command.Search(query, cursor, done))

    fun join(name: String, password: CharArray?, done: (CommandResult) -> Unit) = submit(Command.Join(name, password, done))

    fun create(name: String, password: CharArray, done: (CommandResult) -> Unit) = submit(Command.Create(name, password, done))

    fun leave(networkId: String, done: (CommandResult) -> Unit) = submit(Command.Remove(networkId, false, done))

    fun delete(networkId: String, done: (CommandResult) -> Unit) = submit(Command.Remove(networkId, true, done))

    private fun submit(command: Command) {
        if (phase != EngineState.Phase.ONLINE) {
            command.wipe()
            command.fail("control session is not online")
            return
        }
        if (!commands.offer(command)) {
            command.wipe()
            command.fail("control command queue is full")
        }
    }

    /** Correlated authenticated keepalive; times out after exactly 3000 ms. */
    fun ping(rid: Long, done: (CommandResult) -> Unit) {
        val worker = synchronized(lock) { workers[rid] }
        if (worker == null || worker.link != LinkState.CONNECTED) {
            done(CommandResult.Failed("Connect to this peer before testing RTT."))
            return
        }
        if (!worker.probe.compareAndSet(null, Probe(rid, now(), done))) {
            done(CommandResult.Failed("A ping is already running for this peer."))
        }
    }

    /**
     * Moves queued peer retries forward to now. Repeated requests neither
     * postpone queued work nor erase backoff history; the retry pacer still applies.
     */
    fun retryPeers(): Int = synchronized(lock) {
        val t = now()
        val eligible = membership.eligible()
        var count = 0
        for ((rid, retry) in retries) {
            if (rid !in eligible || workers[rid]?.thread?.isAlive == true) continue
            retry.at = minOf(retry.at, t)
            count++
        }
        dirty.set(true)
        count
    }

    /** IPv4 packet read from the TUN. */
    fun sendIp(packet: ByteArray, length: Int): L3Bridge.Result {
        val result = bridge.outbound(packet, length)
        if (result != L3Bridge.Result.SENT) dropped.incrementAndGet()
        return result
    }

    fun snapshot(): EngineState = synchronized(lock) { buildState() }

    // ------------------------------------------------------------------ control loop

    private fun runControl() {
        var attempt = 0
        val redirected = HashSet<String>()
        var host: String? = null
        while (running.get()) {
            var control: Control? = null
            val active = ArrayList<Active>()
            try {
                var id = store.load()
                if (id == null) {
                    phase = EngineState.Phase.PROVISIONING
                    publish(force = true)
                    event("Registering this device…", false)
                    id = provisionIdentity(config.nodeName, config.host, config.port, protector, stopping, config.modulus)
                    store.save(id)
                    event("Device registered.", false)
                }
                if (id.nodeName != config.nodeName && config.nodeName.isNotBlank()) {
                    Protocol.validateNodeName(config.nodeName)
                    id = id.copy(nodeName = config.nodeName)
                    store.save(id)
                }
                identity = id
                phase = if (attempt == 0) EngineState.Phase.CONNECTING else EngineState.Phase.RECONNECTING
                publish(force = true)
                val target = host ?: id.serverAddress
                val loginName = id.nodeName
                val session = Control.open(target, config.port, id.rid, id.credential, 4, protector, stopping, config.modulus) { latency ->
                    Protocol.login(loginName, latency, 4)
                }
                control = session
                latencyMs = session.latencyMs
                val receiver = Protocol.Registration()
                val members = Membership(id.rid)
                val deadline = now() + 20_000
                var assignment: Protocol.Assignment? = null
                for (i in 0 until 64) {
                    val data = session.receive(deadline)
                    assignment = receiver.receive(data)
                    if (Protocol.operation(data) == 21L) uesHosts = Protocol.uesHosts(data)
                    members.snapshot(data)
                    if (assignment != null) break
                }
                val assigned = assignment ?: fail("login did not return a VIP")
                if (assigned.vip != id.vip || !assigned.address.contentEquals(id.address) || target != id.serverAddress) {
                    id = id.copy(vip = assigned.vip, address = assigned.address, serverAddress = target)
                    store.save(id)
                }
                identity = id
                synchronized(lock) {
                    membership = members
                    retries.clear()
                    retryPacer = RetryPacer(now())
                }
                routeIp = session.stream.localAddress
                phase = EngineState.Phase.ONLINE
                error = null
                attempt = 0
                redirected.clear()
                event("Online as ${Ip.format(id.vip)}.", false)
                publish(force = true)
                onlineLoop(session, active)
            } catch (redirect: Protocol.ServerRedirect) {
                val current = host ?: identity?.serverAddress ?: config.host
                if (redirect.host in redirected || redirected.size >= 4) {
                    error = "server redirect loop"
                    phase = EngineState.Phase.ERROR
                    halt()
                } else {
                    redirected.add(current)
                    host = redirect.host
                    event("Server redirected the session to ${redirect.host}.", false)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running.get()) break
                val message = describe(e)
                error = message
                attempt++
                val limit = if (config.autoReconnect) config.reconnectAttempts.coerceIn(1, 10) else 1
                if (attempt >= limit) {
                    phase = EngineState.Phase.ERROR
                    event("Connection failed: $message", true)
                    publish(force = true)
                    halt()
                } else {
                    phase = EngineState.Phase.RECONNECTING
                    event("Connection lost: $message. Retrying…", true)
                    publish(force = true)
                    val delay = (config.reconnectBaseDelaySeconds.coerceIn(1, 30) * 1000L shl (attempt - 1).coerceAtMost(5))
                        .coerceAtMost(60_000)
                    sleepWhileRunning(delay)
                }
            } finally {
                for (a in active) {
                    a.operation?.wipe()
                    a.done(CommandResult.Failed("control connection closed"))
                }
                active.clear()
                control?.close()
                synchronized(lock) {
                    workers.values.forEach { it.cancel() }
                    pending.values.forEach { it.wipe() }
                    pending.clear()
                }
                wire.clear()
                dirty.set(true)
            }
        }
        drainCommands("engine stopped")
        if (phase != EngineState.Phase.ERROR) phase = EngineState.Phase.STOPPED
        publish(force = true)
    }

    private fun onlineLoop(control: Control, active: MutableList<Active>) {
        var heartbeat = now()
        while (running.get()) {
            val t = now()
            if (t >= heartbeat) {
                control.send(Protocol.heartbeat())
                heartbeat = t + 10_000
            }
            for (i in 0 until 32) control.send(wire.poll() ?: break)
            startCommands(control, active)
            val expired = active.filter { t >= it.deadline }
            for (a in expired) {
                active.remove(a)
                a.operation?.wipe()
                a.done(CommandResult.Failed(if (a.search != null) "search timed out" else "network operation timed out"))
            }
            schedule()
            publish()
            // Poll briefly while work is pending; otherwise sleep longer to spare the battery.
            val wait = when {
                commands.isNotEmpty() || active.isNotEmpty() || wire.isNotEmpty() -> 20L
                synchronized(lock) { pacedBacklog } -> 50L
                else -> 250L
            }
            val data = control.poll(wait) ?: continue
            controlData(data)
            dispatch(control, data, active)
        }
    }

    private fun startCommands(control: Control, active: MutableList<Active>) {
        while (active.size < 8) {
            val next = commands.peek() ?: return
            val t = now()
            if ((next is Command.Join || next is Command.Create) && t - lastJoinStart < 50) return
            if (next is Command.Join && next.password != null && active.any { it.operation?.isPrivateJoin == true }) return
            commands.poll()
            val request = nextRequest
            nextRequest = nextRequest % 0xffffffffL + 1
            try {
                when (next) {
                    is Command.Search -> {
                        control.send(Protocol.publicList(next.query, request, next.cursor))
                        active.add(Active(null, next, request, t + 20_000, next.done))
                    }
                    is Command.Join -> {
                        val already = synchronized(lock) { membership.networks.values.firstOrNull { it.name == next.name } }
                        if (already != null) {
                            val pendingApproval = synchronized(lock) { membership.role(already.networkId, membership.ownRid) == 0L }
                            next.done(CommandResult.Network(NetworkOperation.Outcome.Joined(already, pendingApproval)))
                        } else {
                            val op = NetworkOperation.join(next.name, next.password, request, request)
                            control.send(op.packet)
                            lastJoinStart = t
                            active.add(Active(op, null, request, t + 35_000, next.done))
                        }
                    }
                    is Command.Create -> {
                        val op = NetworkOperation.create(next.name, next.password, request, request)
                        control.send(op.packet)
                        lastJoinStart = t
                        active.add(Active(op, null, request, t + 35_000, next.done))
                    }
                    is Command.Remove -> {
                        val network = synchronized(lock) { membership.networks[next.networkId] }
                            ?: fail("network is not an authenticated membership")
                        val op = NetworkOperation.remove(next.networkId, network.name, next.delete, request, request)
                        control.send(op.packet)
                        active.add(Active(op, null, request, t + 35_000, next.done))
                    }
                }
            } catch (e: ProtocolException) {
                next.fail(e.message ?: "invalid request")
            } finally {
                next.wipe()
            }
        }
    }

    private fun dispatch(control: Control, data: ByteArray, active: MutableList<Active>) {
        if (active.isEmpty()) return
        val op = Protocol.operation(data)
        if (op == 45L) {
            val id = runCatching { Protocol.listingId(data) }.getOrNull() ?: return
            val a = active.firstOrNull { it.search != null && it.requestId == id } ?: return
            active.remove(a)
            val result = try {
                val listing = Protocol.listing(data, id)
                CommandResult.Listing(a.search!!.query, listing.networks, listing.cursor)
            } catch (e: ProtocolException) {
                CommandResult.Failed(e.message ?: "invalid listing")
            }
            a.done(result)
            return
        }
        val candidates = active.filter { it.operation != null }
        val targets = if (op == 40L) {
            // Ambiguous password replies must never advance multiple operations.
            val accepting = candidates.filter { runCatching { it.operation!!.acceptsAuthReply(data) }.getOrDefault(false) }
            if (accepting.size == 1) accepting else emptyList()
        } else {
            candidates
        }
        for (a in targets) {
            try {
                val step = synchronized(lock) { a.operation!!.handle(data, membership) }
                step.send?.let { control.send(it) }
                step.outcome?.let {
                    active.remove(a)
                    a.operation?.wipe()
                    dirty.set(true)
                    a.done(CommandResult.Network(it))
                }
            } catch (e: ProtocolException) {
                active.remove(a)
                a.operation?.wipe()
                a.done(CommandResult.Failed(e.message ?: "network operation failed"))
            }
        }
    }

    private fun controlData(data: ByteArray) {
        val records = Tlv.parse(data)
        val op = Tlv.unique(records, Protocol.SERVER_OP).u32()
        Protocol.checkRedirect(records)
        if (op == 16L) throw IOException("server disconnected the attachment session")
        synchronized(lock) {
            when (op) {
                38L -> {
                    membership.snapshot(data)
                    dirty.set(true)
                }
                41L, 42L -> {
                    membership.changes(data)
                    dirty.set(true)
                }
                11L -> {
                    val offer = Protocol.connectionDetails(data)
                    val duplicate = pending.containsKey(offer.cid) || pending.values.any { it.offer.rid == offer.rid }
                    var stored = false
                    if (!duplicate && pending.size < PeerScheduling.MAX_PENDING_OFFERS) {
                        val worker = workers[offer.rid]
                        // A stopping worker (retired direct channel, peer failure) frees the
                        // peer shortly; keep the offer so schedule() starts it once it exits.
                        if (worker == null || !worker.thread.isAlive || worker.stop.get()) {
                            pending[offer.cid] = PendingOffer(offer, now(), ArrayList())
                            stored = true
                        } else if (worker.link == LinkState.CONNECTING && worker.incoming == null && (identity?.rid ?: 0) > offer.rid) {
                            // Both nodes may dial together; converge on the lower initiator.
                            worker.cancel()
                            pending[offer.cid] = PendingOffer(offer, now(), ArrayList())
                            stored = true
                        }
                    }
                    // A dropped offer is never used: wipe its password at once.
                    if (!stored) offer.wipe()
                }
                6L, 7L, 23L, 29L -> {
                    val cid = Protocol.connectionId(data)
                    val p = pending[cid]
                    if (p != null) {
                        if (p.records.size < 8) p.records.add(data)
                    } else {
                        workers.values.firstOrNull { it.incoming?.cid == cid && it.link == LinkState.CONNECTING }?.offers?.offer(data)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ peers

    /**
     * Starts peer setups in parallel, mirroring the `runtime.rs` scheduler: first
     * attempts start at once within the handshake budget, incoming offers go first
     * and never use outgoing slots, and due retries are paced by [RetryPacer]. Like
     * the desktop there is no cap on connected peers: every eligible member is dialed.
     */
    private fun schedule() = synchronized(lock) {
        val t = now()
        val eligible = membership.eligible()
        for ((rid, w) in workers.entries.toList()) {
            if (rid !in eligible) {
                w.cancel()
                if (!w.thread.isAlive) {
                    workers.remove(rid)
                    workersVersion++
                }
            }
        }
        retries.keys.retainAll(membership.peers.keys)
        // Established channels do not occupy handshake slots; cancelled setups do until they exit.
        val setups = workers.values.filter { it.thread.isAlive && it.link == LinkState.CONNECTING }
        val budget = HandshakeBudget(setups.map { it.incoming != null })
        for ((cid, p) in pending.entries.toList()) {
            if (t - p.created > 40_000) {
                pending.remove(cid)
                p.wipe()
                continue
            }
            if (relayOnly() && p.records.none { runCatching { Protocol.operation(it) }.getOrNull() == 23L }) continue
            val peer = eligible[p.offer.rid] ?: continue
            if (workers[peer.rid]?.thread?.isAlive == true) continue
            if (!budget.tryStart(incoming = true)) break
            pending.remove(cid)
            spawn(peer, p.offer, p.records)
        }
        val offered = pending.values.mapTo(HashSet()) { it.offer.rid }
        val queued = eligible.keys.filter { rid ->
            rid !in offered && workers[rid]?.thread?.isAlive != true && (retries[rid]?.let { t >= it.at } ?: true)
        }.sortedWith(
            compareBy<Long>(
                { rid -> retries[rid].let { PeerScheduling.priorityLane(false, it != null, it?.previouslyConnected == true) } },
                { rid -> retries[rid]?.failures ?: 0 },
                { rid -> rid },
            ),
        )
        var retrying = setups.count { it.incoming == null && it.rid in retries }
        val dueRetries = queued.count { it in retries }
        val policy = retryPacer.policy(t, dueRetries + retrying)
        var remainingRetries = dueRetries
        var paced = false
        for (rid in queued) {
            val isRetry = rid in retries
            if (isRetry && !retryPacer.ready(t, retrying, policy)) {
                paced = true
                continue
            }
            val reserve = if (isRetry) 0 else minOf(remainingRetries, (policy.activeLimit - retrying).coerceAtLeast(0))
            if (!budget.tryStart(incoming = false, reservedOutgoing = reserve)) continue
            if (isRetry) {
                retryPacer.started(t, policy)
                retrying++
                remainingRetries--
            }
            spawn(eligible.getValue(rid), null, emptyList())
        }
        pacedBacklog = paced
    }

    private fun spawn(peer: PeerEntry, incoming: Protocol.ConnectionOffer?, records: List<ByteArray>) {
        val worker = PeerWorker(peer, incoming)
        records.forEach { worker.offers.offer(it) }
        workers[peer.rid] = worker
        workersVersion++
        dirty.set(true)
        try {
            worker.thread.start()
        } catch (e: Throwable) {
            // More parallelism must not turn a temporary OS thread limit into a crash.
            worker.detail = "could not start peer worker"
            worker.link = LinkState.FAILED
            recordFailure(peer.rid, connectedAt = 0L, now())
        }
    }

    /** Queues the next attempt with bounded, jittered backoff. Called with [lock] held. */
    private fun recordFailure(rid: Long, connectedAt: Long, t: Long) {
        val wasRetry = rid in retries
        val retry = retries.getOrPut(rid) { PeerRetry(0, t, false) }
        if (connectedAt != 0L) {
            retry.previouslyConnected = true
            if (t - connectedAt >= PeerScheduling.STABLE_CONNECTION_MS) retry.failures = 0
        } else if (wasRetry) {
            retryPacer.recordOutcome(t, success = false)
        }
        retry.failures = (retry.failures + 1).coerceAtMost(1000)
        retry.at = t + PeerScheduling.retryDelayMs(rid, retry.failures)
    }

    /**
     * Membership-permitted workers and RIDs, recomputed only when the membership or
     * the worker set changed instead of running [Membership.permits] per packet.
     * Caller holds [lock].
     */
    private fun refreshPermitted() {
        val m = membership
        if (m !== permittedFor || m.version != permittedVersion) {
            permittedRids = m.peers.keys.filterTo(HashSet()) { m.permits(it) }
            permittedFor = m
            permittedVersion = m.version
            permittedWorkersVersion = -1L
        }
        if (permittedWorkersVersion != workersVersion) {
            permittedWorkers = workers.values.filter { it.rid in permittedRids }
            permittedWorkersVersion = workersVersion
        }
    }

    private fun connectedPeers(): List<BridgePeer> = synchronized(lock) {
        refreshPermitted()
        permittedWorkers.filter { it.link == LinkState.CONNECTED && !it.stop.get() }
    }

    private fun applyForceRelay(enabled: Boolean) = synchronized(lock) {
        // Retire setups and direct channels so the policy applies in both directions.
        // Cancelled workers record no failure, so they are queued again at once.
        for (w in workers.values) {
            if (w.link == LinkState.CONNECTING || enabled && w.transport != TransportPath.RELAY) w.cancel()
        }
        retries.clear()
    }

    private fun incomingFrame(worker: PeerWorker, frame: ByteArray) {
        val id = identity ?: return
        val channel = worker.channel ?: return
        val ownMac = Protocol.macForIp(id.vip)
        val permitted = synchronized(lock) {
            refreshPermitted()
            worker.rid in permittedRids
        }
        if (!permitted || !Tunnel.validFrame(frame, worker.peer.vip, channel.mac, id.vip, ownMac)) {
            dropped.incrementAndGet()
            return
        }
        receivedFrames.incrementAndGet()
        receivedBytes.addAndGet(frame.size.toLong())
        when (val r = bridge.inbound(worker, frame)) {
            is L3Bridge.Inbound.Deliver -> {
                if (tunAttached) listener.onPacket(r.packet) else echoReply(worker, r.packet, id)
            }
            is L3Bridge.Inbound.Reply -> worker.enqueue(r.frame)
            L3Bridge.Inbound.Drop -> Unit
        }
    }

    /** Without a TUN, answer ICMP echo for our VIP so peers can still verify reachability. */
    private fun echoReply(worker: PeerWorker, ip: ByteArray, id: Identity) {
        val h = Tunnel.ipv4(ip) ?: return
        if (h.destination != id.vip || h.protocol != 1 || readBeU16(ip, 6) and 0x3fff != 0) return
        val icmp = ip.copyOfRange(h.headerLength, h.length)
        if (icmp.size < 8 || icmp[0] != 8.toByte() || icmp[1] != 0.toByte() || Tunnel.checksum(icmp) != 0) return
        icmp[0] = 0
        icmp[2] = 0
        icmp[3] = 0
        val c = Tunnel.checksum(icmp)
        icmp[2] = (c ushr 8).toByte()
        icmp[3] = c.toByte()
        val header = byteArrayOf(0x45, 0) + beU32((20 + icmp.size).toLong()).copyOfRange(2, 4) +
            byteArrayOf(0, 0, 0, 0, 64, 1, 0, 0) + Ip.bytes(id.vip) + Ip.bytes(worker.peer.vip)
        val hc = Tunnel.checksum(header)
        header[10] = (hc ushr 8).toByte()
        header[11] = hc.toByte()
        worker.enqueue(L3Bridge.frame(worker.mac, Protocol.macForIp(id.vip), header + icmp))
    }

    private class Probe(val rid: Long, val started: Long, val done: (CommandResult) -> Unit) {
        @Volatile var sequence: Long = -1
        @Volatile var sentAt: Long = 0
        val finished = AtomicBoolean(false)

        fun finish(result: CommandResult) {
            if (finished.compareAndSet(false, true)) done(result)
        }
    }

    private inner class PeerWorker(val peer: PeerEntry, val incoming: Protocol.ConnectionOffer?) : BridgePeer {
        override val rid: Long get() = peer.rid
        override val vip: Int get() = peer.vip
        override val mac: ByteArray get() = channel?.mac ?: ByteArray(6)
        /** Stops this worker; cancelled by the engine, as opposed to a peer failure. */
        val stop = AtomicBoolean(false)
        @Volatile private var cancelled = false
        val tx = ArrayBlockingQueue<OutFrame>(256)
        val offers = ArrayBlockingQueue<ByteArray>(16)
        val probe = AtomicReference<Probe?>(null)
        @Volatile var link = LinkState.CONNECTING
        @Volatile var detail = ""
        @Volatile var transport: TransportPath? = null
        @Volatile var endpoint: String? = null
        @Volatile var channel: PeerChannel? = null
        @Volatile private var connectedAt = 0L
        @Volatile private var lastRx = now()
        private var coordinator: Control? = null
        val thread = Thread({ run() }, "openrad-peer-${peer.rid}").apply { isDaemon = true }

        /** Engine-initiated stop: records no failure, so the peer may be queued again at once. */
        fun cancel() {
            cancelled = true
            stop.set(true)
        }

        override fun enqueue(frame: ByteArray): Boolean = enqueue(OutFrame(frame))

        override fun enqueue(frame: OutFrame): Boolean {
            if (link != LinkState.CONNECTED || stop.get()) return false
            return tx.offer(frame)
        }

        private fun run() {
            var reader: Thread? = null
            try {
                val ch = if (incoming != null) accept(incoming) else outgoing()
                ch.stream.sustain()
                channel = ch
                transport = ch.transport
                endpoint = ch.stream.remote
                connectedAt = now()
                link = LinkState.CONNECTED
                synchronized(lock) { if (rid in retries) retryPacer.recordOutcome(connectedAt, success = true) }
                dirty.set(true)
                event("${peer.name}: connected · ${ch.transport.label}${if (incoming != null) " (incoming)" else ""}", false)
                val own = identity ?: throw IOException("identity unavailable")
                ch.send(Tunnel.encode(Tunnel.gratuitousArp(own.vip)))
                lastRx = now()
                reader = Thread({ readLoop(ch) }, "openrad-peer-rx-${peer.rid}").apply {
                    isDaemon = true
                    start()
                }
                writeLoop(ch)
            } catch (e: Exception) {
                if (!cancelled && running.get()) {
                    val message = describe(e)
                    detail = message
                    link = if (e is RefusedException) LinkState.REFUSED else LinkState.FAILED
                    event("${peer.name}: $message", true)
                }
            } finally {
                stop.set(true)
                probe.getAndSet(null)?.finish(CommandResult.Failed("peer channel closed"))
                channel?.close()
                coordinator?.close()
                // The channel consumed the offer password during setup; drop it here
                // on every path. PeerChannel lanes copy it synchronously at
                // construction, so wiping never affects the winner.
                incoming?.wipe()
                reader?.join(1500)
                val failed = !cancelled && running.get()
                if (failed && detail.isEmpty()) detail = "peer channel closed"
                if (link == LinkState.CONNECTED || link == LinkState.CONNECTING) {
                    link = if (detail.isEmpty()) LinkState.IDLE else LinkState.FAILED
                }
                // A refusal during the initial rush is not necessarily permanent: retry it
                // through the same paced queue, as `runtime.rs` does, instead of giving up.
                if (failed) synchronized(lock) { recordFailure(rid, connectedAt, now()) }
                tx.clear()
                dirty.set(true)
            }
        }

        private fun writeLoop(ch: PeerChannel) {
            var heartbeat = now() + 15_000
            var sequence = 0L
            while (!stop.get() && running.get()) {
                val t = now()
                val p = probe.get()
                if (p != null) {
                    if (t - p.started >= PING_TIMEOUT_MS) {
                        probe.compareAndSet(p, null)
                        p.finish(CommandResult.Failed("no response — strict firewall"))
                    } else if (p.sequence < 0) {
                        sequence = sequence % 0xffffffffL + 1
                        p.sentAt = System.nanoTime()
                        p.sequence = sequence
                        ch.send(Tunnel.keepalive(sequence, false))
                    }
                }
                if (t >= heartbeat) {
                    sequence = sequence % 0xffffffffL + 1
                    ch.send(Tunnel.keepalive(sequence, false))
                    heartbeat = t + 15_000
                }
                if (t - lastRx > 65_000) throw IOException("peer stopped answering keepalives")
                val wait = minOf(heartbeat - t, if (probe.get() != null) 20L else 200L).coerceAtLeast(1)
                var frame = tx.poll(wait, TimeUnit.MILLISECONDS) ?: continue
                if (ch.batches) {
                    // TCP never drops: the frames already queued (up to 32) leave in one write.
                    val frames = ArrayList<OutFrame>(minOf(32, 1 + tx.size))
                    var bytes = 0L
                    while (true) {
                        frames.add(frame)
                        bytes += frame.frame.size
                        if (frames.size >= 32) break
                        frame = tx.poll() ?: break
                    }
                    ch.sendFrames(frames)
                    sentFrames.addAndGet(frames.size.toLong())
                    sentBytes.addAndGet(bytes)
                    continue
                }
                var batch = 0
                while (true) {
                    // A full UDP send window drops the frame like congestion loss; tunnelled TCP recovers.
                    if (ch.sendFrame(frame)) {
                        sentFrames.incrementAndGet()
                        sentBytes.addAndGet(frame.frame.size.toLong())
                    } else {
                        dropped.incrementAndGet()
                    }
                    if (++batch >= 32) break
                    frame = tx.poll() ?: break
                }
            }
        }

        private fun readLoop(ch: PeerChannel) {
            try {
                while (!stop.get() && running.get()) {
                    val data = ch.poll(500) ?: continue
                    lastRx = now()
                    when (val packet = Tunnel.decode(data)) {
                        is Tunnel.Packet.Keepalive -> if (!packet.reply) {
                            ch.send(Tunnel.keepalive(packet.sequence, true))
                        } else {
                            val p = probe.get()
                            if (p != null && p.sequence == packet.sequence && now() - p.started < PING_TIMEOUT_MS) {
                                probe.compareAndSet(p, null)
                                p.finish(CommandResult.Rtt(rid, (System.nanoTime() - p.sentAt) / 1_000_000.0))
                            }
                        }
                        is Tunnel.Packet.Frames -> packet.frames.forEach { incomingFrame(this, it) }
                        is Tunnel.Packet.Other -> Unit
                    }
                }
            } catch (e: Exception) {
                if (!stop.get() && running.get()) {
                    detail = describe(e)
                    event("${peer.name}: ${detail}", true)
                }
                stop.set(true)
            }
        }

        /**
         * Races every transport like `peer.rs::negotiate`: direct TCP candidates in
         * parallel lanes, direct and UES-mapped UDP rendezvous, and a relay connected
         * and paired as soon as its offer arrives, then authenticated after the
         * direct head start (extended by late direct starts, capped). First wins.
         */
        private fun outgoing(): PeerChannel {
            val own = identity ?: throw IOException("identity unavailable")
            val server = peer.server ?: throw IOException("peer has no coordinator")
            val coord = Control.open(server, config.port, own.rid, own.credential, 5, protector, stop, config.modulus) { latency ->
                Protocol.login(own.nodeName, latency, 5, peer.rid)
            }
            coordinator = coord
            val relayOnly = relayOnly()
            val route = coord.stream.localAddress?.let { runCatching { Ip.parse(it) }.getOrNull() }
            val until = now() + 30_000
            var discoveryUntil = until
            val race = SetupRace<PeerChannel>("out-${peer.rid}") { it.close() }
            val relayPreparation = SetupRace<Framed>("relay-${peer.rid}") { it.close() }
            var mapping: SetupRace<MappedSocket>? = null
            var offer: Protocol.ConnectionOffer? = null
            var serverId = 0L
            var ues: List<Int> = emptyList()
            var candidates: List<Pair<String, Int>>? = null
            var tcpReceived = relayOnly
            var udpReceived = relayOnly
            var mappedReceived = true
            var udpSocket: UdpSocket? = null
            var mappedSocket: UdpSocket? = null
            var udpCandidates: List<InetSocketAddress> = emptyList()
            var mappedCandidates: List<InetSocketAddress> = emptyList()
            var udpNonce: Int? = null
            var mappedNonce: Int? = null
            var relayAt = 0L
            var relayOffered = false
            var relayStream: Framed? = null
            var relayStarted = false
            var directStarted = false
            var lastDirectStart: Long? = null
            var records = 0
            var lastError: String? = null
            var winner: PeerChannel? = null
            try {
                while (true) {
                    if (stop.get()) throw CancelledException()
                    if (now() >= until) throw IOException(failure("peer setup timed out", lastError))
                    while (true) {
                        val (name, result) = race.poll() ?: break
                        val ch = result.getOrNull()
                        if (ch != null) {
                            ch.stream.stop = stop
                            winner = ch
                            return ch
                        }
                        lastError = result.exceptionOrNull()?.let { "$name: ${describe(it)}" }
                    }
                    mapping?.poll()?.let { (_, result) ->
                        mapping?.close()
                        mapping = null
                        result.onSuccess { (socket, endpoint) ->
                            mappedSocket = socket
                            mappedReceived = mappedNonce != null
                            offer?.let { coord.send(Protocol.advertiseMappedUdp(it.cid, endpoint, 0)) }
                        }.onFailure { lastError = "mapped-udp: ${describe(it)}" }
                    }
                    relayPreparation.poll()?.let { (_, result) ->
                        result.onSuccess { relayStream = it }.onFailure {
                            relayStarted = true
                            lastError = "relay: ${describe(it)}"
                        }
                    }
                    val o = offer
                    if (o != null) {
                        val c = candidates
                        if (!relayOnly && !directStarted && !c.isNullOrEmpty() && serverId != 0L) {
                            directStarted = true
                            lastDirectStart = now()
                            startDirectLanes(race, own, o, c, serverId, until)
                        }
                        val direct = udpSocket
                        val nonce = udpNonce
                        if (direct != null && nonce != null && udpCandidates.isNotEmpty()) {
                            udpSocket = null
                            lastDirectStart = now()
                            startUdpLane(race, "direct-udp", direct, udpCandidates, nonce, own, o, until)
                        }
                        val mappedLocal = mappedSocket
                        val mappedPeerNonce = mappedNonce
                        if (mappedLocal != null && mappedPeerNonce != null && mappedCandidates.isNotEmpty()) {
                            mappedSocket = null
                            lastDirectStart = now()
                            startUdpLane(race, "mapped-udp", mappedLocal, mappedCandidates, mappedPeerNonce, own, o, until)
                        }
                        val prepared = relayStream
                        val directExhausted = race.idle && mapping == null && tcpReceived && udpReceived && mappedReceived
                        if (!relayStarted && prepared != null && PeerScheduling.relayReady(now(), relayAt, lastDirectStart, directExhausted)) {
                            relayStarted = true
                            relayStream = null
                            prepared.stop = race.cancel
                            race.launch("relay", abandon = { prepared.close() }) {
                                PeerChannel(prepared, own.rid, own.vip, peer.rid, o.password, false, null, TransportPath.RELAY, (until - now()).coerceAtLeast(100))
                            }
                        }
                    }
                    val discoveryComplete = tcpReceived && udpReceived && mappedReceived && relayStarted
                    if (discoveryComplete || records >= 32 || now() >= discoveryUntil) {
                        if (race.idle && mapping == null && relayPreparation.idle && relayStream == null && (relayStarted || !relayOffered)) {
                            throw IOException(failure("all peer transports failed or no authenticated candidates arrived", lastError))
                        }
                        Thread.sleep(10)
                        continue
                    }
                    val data = coord.poll(20) ?: continue
                    records++
                    val fields = Tlv.parse(data)
                    when (val op = Tlv.unique(fields, Protocol.SERVER_OP).u32()) {
                        21L -> {
                            serverId = Tlv.optional(fields, 0x0100025bL)?.u32() ?: 0L
                            ues = Protocol.uesHosts(data)
                        }
                        11L -> {
                            ensure(offer == null) { "duplicate connection offer" }
                            val next = Protocol.connectionDetails(data)
                            try {
                                ensure(next.rid == peer.rid) { "connection offer target mismatch" }
                            } catch (e: Exception) {
                                next.wipe()
                                throw e
                            }
                            offer = next
                            if (!relayOnly) {
                                coord.send(Protocol.requestTcpCandidates(next.cid))
                                bindUdp(route)?.let { (socket, endpoints) ->
                                    udpSocket = socket
                                    coord.send(Protocol.advertiseUdp(next.cid, endpoints, 0))
                                } ?: run { lastError = "direct-udp: no local UDP candidates on the coordinator route" }
                                mapping = startMapping(ues)
                            }
                            coord.send(Protocol.requestRelay(next.cid))
                        }
                        6L -> {
                            if (!relayOnly && o != null && candidates == null) {
                                candidates = try {
                                    Protocol.tcpCandidates(data, o.cid).also { tcpReceived = true }
                                } catch (e: ProtocolException) {
                                    lastError = "direct-tcp: ${describe(e)}"
                                    null
                                }
                            }
                        }
                        29L -> {
                            if (!relayOnly && o != null) {
                                try {
                                    udpCandidates = Protocol.directCandidates(data, o.cid, 29, 0x127cL, route)
                                    udpNonce = Protocol.udpNonce(data)
                                    udpReceived = true
                                } catch (e: ProtocolException) {
                                    lastError = "direct-udp: ${describe(e)}"
                                }
                            }
                        }
                        7L -> {
                            if (!relayOnly && o != null) {
                                try {
                                    val (endpoint, nonce) = Protocol.mappedUdpCandidate(data, o.cid, route)
                                    mappedCandidates = listOf(endpoint)
                                    mappedNonce = nonce
                                    mappedReceived = true
                                } catch (e: ProtocolException) {
                                    lastError = "mapped-udp: ${describe(e)}"
                                }
                            }
                        }
                        23L -> {
                            if (o != null && !relayOffered) {
                                val relay = Protocol.relayDetails(data, o.cid)
                                relayOffered = true
                                relayAt = now()
                                // Keep accepting late direct candidates while the relay pairs.
                                discoveryUntil = minOf(discoveryUntil, relayAt + 12_000)
                                relayPreparation.launch("prepare") { prepareRelay(relay, until, protector, relayPreparation.cancel) }
                            }
                        }
                        16L, 44L -> throw RefusedException("peer refused the connection (operation $op)")
                    }
                }
            } finally {
                race.close(winner)
                relayPreparation.close()
                mapping?.close()
                relayStream?.close()
                udpSocket?.close()
                mappedSocket?.close()
                // The winner copied the password at construction; loser lanes are
                // cancelled above and fail closed if they read it after the wipe.
                offer?.wipe()
            }
        }

        /** Local UDP socket and its candidates on the coordinator route; null when none is usable. */
        private fun bindUdp(route: Int?): Pair<UdpSocket, List<InetSocketAddress>>? = try {
            Udp.bindCandidates(route, protector)
        } catch (e: ProtocolException) {
            null
        } catch (e: IOException) {
            null
        }

        /**
         * Discovers a socket's public mapping through two agreeing UES hosts, off
         * the setup loop. Null without two authenticated hosts, as discovery would fail.
         */
        private fun startMapping(ues: List<Int>): SetupRace<MappedSocket>? {
            if (ues.distinct().size < 2) return null
            val mapping = SetupRace<MappedSocket>("mapping-${peer.rid}") { it.first.close() }
            mapping.launch("ues") {
                val socket = UdpSocket.bind(protector)
                try {
                    socket to Udp.discoverMapping(socket, ues, mapping.cancel)
                } catch (e: Throwable) {
                    socket.close()
                    throw e
                }
            }
            return mapping
        }

        /** Outgoing UDP: punch the authenticated candidates, ENET CONNECT, then peer SH. */
        private fun startUdpLane(
            race: SetupRace<PeerChannel>,
            name: String,
            socket: UdpSocket,
            endpoints: List<InetSocketAddress>,
            nonce: Int,
            own: Identity,
            offer: Protocol.ConnectionOffer,
            until: Long,
        ) {
            race.launch(name, abandon = { socket.close() }) {
                val enet = Enet.connect(socket, endpoints, nonce, minOf(until - now(), PeerScheduling.DIRECT_ATTEMPT_MS), race.cancel)
                PeerChannel(enet, own.rid, own.vip, peer.rid, offer.password, false, null, TransportPath.DIRECT_UDP, (until - now()).coerceAtLeast(100))
            }
        }

        /** Spreads direct candidates over parallel lanes so stalled endpoints cannot block the rest. */
        private fun startDirectLanes(
            race: SetupRace<PeerChannel>,
            own: Identity,
            offer: Protocol.ConnectionOffer,
            candidates: List<Pair<String, Int>>,
            serverId: Long,
            until: Long,
        ) {
            val lanes = minOf(candidates.size, PeerScheduling.DIRECT_TCP_LANES)
            val body = beU64(own.rid) + beU64(offer.cid) + beU32(serverId)
            for (lane in 0 until lanes) {
                val assigned = candidates.filterIndexed { index, _ -> index % lanes == lane }
                race.launch("direct-tcp-$lane") {
                    val deadline = minOf(until, now() + 8000)
                    val errors = ArrayList<String>()
                    for ((host, port) in assigned) {
                        if (now() >= deadline || race.cancel.get()) break
                        var direct: Framed? = null
                        try {
                            direct = Framed.connect(host, port, (deadline - now()).coerceIn(1, 3000).toInt(), protector, race.cancel)
                            direct.write(byteArrayOf(0, 0x14, 0, 1) + body)
                            val check = readBeU32(direct.readExact(4, now() + 2000))
                            ensure(check == Protocol.rendezvousChecksum(body)) { "TCP rendezvous checksum mismatch" }
                            return@launch PeerChannel(
                                direct, own.rid, own.vip, peer.rid, offer.password, false, null, TransportPath.DIRECT_TCP,
                                (until - now()).coerceIn(100, 10_000),
                            )
                        } catch (e: Exception) {
                            direct?.close()
                            if (e is CancelledException) throw e
                            errors.add("$host:$port ${describe(e)}")
                        }
                    }
                    throw IOException("direct TCP candidates failed: ${errors.joinToString("; ")}")
                }
            }
        }

        /**
         * Incoming side of `incoming.rs::accept`: the TCP listener validates
         * rendezvous attempts concurrently, local and UES-mapped UDP sockets wait
         * for the dialer's candidates, and a relay is prepared in parallel; relay
         * authentication starts after the direct head start.
         */
        private fun accept(offer: Protocol.ConnectionOffer): PeerChannel {
            val own = identity ?: throw IOException("identity unavailable")
            val relayOnly = relayOnly()
            val until = now() + 40_000
            val nonce = readBeU16(randomBytes(2), 0).coerceAtLeast(1)
            val race = SetupRace<PeerChannel>("in-${peer.rid}") { it.close() }
            val relayPreparation = SetupRace<Framed>("in-relay-${peer.rid}") { it.close() }
            var mapping: SetupRace<MappedSocket>? = null
            var listener: ServerSocket? = null
            var directSocket: UdpSocket? = null
            var mappedSocket: UdpSocket? = null
            var mappedCandidates: List<InetSocketAddress> = emptyList()
            var lastDirectStart: Long? = null
            var relayAt = 0L
            var relayOffered = false
            var relayStream: Framed? = null
            var relayStarted = false
            var lastError: String? = null
            var winner: PeerChannel? = null
            val route = routeIp?.let { runCatching { Ip.parse(it) }.getOrNull() }
            fun startUdp(name: String, socket: UdpSocket, endpoints: List<InetSocketAddress>) {
                lastDirectStart = now()
                race.launch(name, abandon = { socket.close() }) {
                    val enet = Enet.accept(socket, endpoints, nonce, PeerScheduling.INCOMING_UDP_MS, race.cancel)
                    PeerChannel(enet, own.rid, own.vip, peer.rid, offer.password, true, null, TransportPath.DIRECT_UDP, (until - now()).coerceAtLeast(100))
                }
            }
            try {
                if (!relayOnly && route != null) {
                    val server = ServerSocket()
                    server.bind(InetSocketAddress(0), 8)
                    server.soTimeout = 200
                    listener = server
                    wire.offer(Protocol.advertiseTcp(offer.cid, Ip.format(route), server.localPort))
                    acceptDirect(race, server, own, offer, until)
                    bindUdp(route)?.let { (socket, endpoints) ->
                        directSocket = socket
                        wire.offer(Protocol.advertiseUdp(offer.cid, endpoints, nonce))
                    }
                    mapping = startMapping(uesHosts)
                }
                val directExhausted = listener == null
                while (true) {
                    if (stop.get()) throw CancelledException()
                    if (now() >= until) throw IOException(failure("incoming peer setup timed out", lastError))
                    while (true) {
                        val (name, result) = race.poll() ?: break
                        val ch = result.getOrNull()
                        if (ch != null) {
                            ch.stream.stop = stop
                            winner = ch
                            return ch
                        }
                        lastError = result.exceptionOrNull()?.let { "$name: ${describe(it)}" }
                    }
                    mapping?.poll()?.let { (_, result) ->
                        mapping?.close()
                        mapping = null
                        result.onSuccess { (socket, endpoint) ->
                            mappedSocket = socket
                            wire.offer(Protocol.advertiseMappedUdp(offer.cid, endpoint, nonce))
                        }.onFailure { lastError = "mapped-udp: ${describe(it)}" }
                    }
                    // Candidates can arrive before our mapping result; start once both are known.
                    val mappedLocal = mappedSocket
                    if (mappedLocal != null && mappedCandidates.isNotEmpty()) {
                        mappedSocket = null
                        startUdp("mapped-udp", mappedLocal, mappedCandidates)
                    }
                    relayPreparation.poll()?.let { (_, result) ->
                        result.onSuccess { relayStream = it }.onFailure {
                            relayStarted = true
                            lastError = "relay: ${describe(it)}"
                        }
                    }
                    val prepared = relayStream
                    if (!relayStarted && prepared != null && PeerScheduling.relayReady(now(), relayAt, lastDirectStart, directExhausted)) {
                        relayStarted = true
                        relayStream = null
                        prepared.stop = race.cancel
                        race.launch("relay", abandon = { prepared.close() }) {
                            PeerChannel(prepared, own.rid, own.vip, peer.rid, offer.password, true, null, TransportPath.RELAY, (until - now()).coerceAtLeast(100))
                        }
                    }
                    val nothingLeft = mapping == null && directSocket == null && mappedSocket == null
                    if (race.idle && relayStarted && nothingLeft) throw IOException(failure("all incoming transports failed", lastError))
                    val data = offers.poll(20, TimeUnit.MILLISECONDS) ?: continue
                    when (runCatching { Protocol.operation(data) }.getOrNull()) {
                        29L -> if (!relayOnly) {
                            val c = runCatching { Protocol.directCandidates(data, offer.cid, 29, 0x127cL, route) }.getOrNull().orEmpty()
                            val socket = directSocket
                            if (c.isNotEmpty() && socket != null) {
                                directSocket = null
                                startUdp("udp", socket, c)
                            }
                        }
                        7L -> if (!relayOnly) {
                            runCatching { Protocol.incomingMappedCandidate(data, offer.cid, route) }.getOrNull()?.let {
                                mappedCandidates = listOf(it)
                            }
                        }
                        23L -> if (!relayOffered) {
                            val relay = Protocol.relayDetails(data, offer.cid)
                            relayOffered = true
                            relayAt = now()
                            relayPreparation.launch("prepare") { prepareRelay(relay, until, protector, relayPreparation.cancel) }
                        }
                    }
                }
            } finally {
                race.close(winner)
                relayPreparation.close()
                mapping?.close()
                relayStream?.close()
                directSocket?.close()
                mappedSocket?.close()
                runCatching { listener?.close() }
            }
        }

        /**
         * Accepts up to eight sockets and validates up to [PeerScheduling.DIRECT_TCP_LANES]
         * rendezvous at once, so a partial or unrelated connection cannot hold the
         * listener. The service handshake stays serialized: one TCP winner only.
         */
        private fun acceptDirect(race: SetupRace<PeerChannel>, server: ServerSocket, own: Identity, offer: Protocol.ConnectionOffer, until: Long) {
            val validated = ArrayBlockingQueue<Framed>(8)
            var closed = false
            fun admit(direct: Framed) {
                val kept = synchronized(validated) { !closed && validated.offer(direct) }
                if (!kept) direct.close()
            }
            val validating = Semaphore(PeerScheduling.DIRECT_TCP_LANES)
            fun validate(sock: Socket) {
                val direct = Framed(sock, race.cancel)
                try {
                    val data = direct.readExact(24, minOf(until, now() + 3000))
                    val ok = data.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0x14, 0, 1)) &&
                        readBeU64(data, 4) == peer.rid && readBeU64(data, 12) == offer.cid && readBeU32(data, 20) != 0L
                    ensure(ok) { "incoming TCP rendezvous identity mismatch" }
                    direct.write(beU32(Protocol.rendezvousChecksum(data.copyOfRange(4, 24))))
                    admit(direct)
                } catch (_: Exception) {
                    direct.close()
                }
            }
            val acceptor = Thread({
                var accepted = 0
                while (!race.cancel.get() && now() < until && accepted < 8) {
                    if (!validating.tryAcquire(200, TimeUnit.MILLISECONDS)) continue
                    val sock = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        validating.release()
                        continue
                    } catch (_: IOException) {
                        validating.release()
                        break
                    }
                    accepted++
                    val validator = Thread({
                        try {
                            validate(sock)
                        } finally {
                            validating.release()
                        }
                    }, "openrad-in-rendezvous-${peer.rid}").apply { isDaemon = true }
                    try {
                        validator.start()
                    } catch (_: Throwable) {
                        runCatching { sock.close() }
                        validating.release()
                    }
                }
            }, "openrad-in-accept-${peer.rid}").apply { isDaemon = true }
            // Without an acceptor the relay still races; the TCP task simply finds nothing.
            runCatching { acceptor.start() }
            race.launch("tcp") {
                try {
                    while (!race.cancel.get() && now() < until) {
                        val direct = validated.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        try {
                            return@launch PeerChannel(
                                direct, own.rid, own.vip, peer.rid, offer.password, true, null, TransportPath.DIRECT_TCP,
                                (until - now()).coerceIn(100, 10_000),
                            )
                        } catch (e: Exception) {
                            direct.close()
                            if (e is CancelledException) throw e
                        }
                    }
                    throw IOException("incoming TCP setup timed out")
                } finally {
                    val leftovers = synchronized(validated) {
                        closed = true
                        ArrayList<Framed>().also { validated.drainTo(it) }
                    }
                    leftovers.forEach { it.close() }
                }
            }
        }
    }

    class RefusedException(message: String) : IOException(message)

    // ------------------------------------------------------------------ state

    private fun buildState(): EngineState {
        val id = identity
        val m = membership
        val eligible = m.eligible()
        val t = now()
        val peers = m.peers.values.filter { it.rid != m.ownRid }.map { p ->
            val w = workers[p.rid]
            val alive = w?.thread?.isAlive == true
            val fails = retries[p.rid]?.failures ?: 0
            val link = when {
                w != null && alive && w.link == LinkState.CONNECTED -> LinkState.CONNECTED
                w != null && alive -> LinkState.CONNECTING
                p.rid !in eligible -> if (p.server != null && p.state in Membership.ONLINE_STATES) LinkState.UNAVAILABLE else LinkState.OFFLINE
                w != null && (w.link == LinkState.FAILED || w.link == LinkState.REFUSED) -> w.link
                else -> LinkState.QUEUED
            }
            PeerView(
                rid = p.rid,
                name = p.name,
                vip = p.vip,
                networkIds = p.networkIds,
                link = link,
                detail = if (link == LinkState.FAILED || link == LinkState.REFUSED) w?.detail.orEmpty() else if (fails > 0 && link == LinkState.CONNECTING) "retry $fails" else "",
                transport = if (link == LinkState.CONNECTED) w?.transport else null,
                endpoint = if (link == LinkState.CONNECTED) w?.endpoint else null,
            )
        }.sortedBy { it.rid }
        val networks = m.networks.values.map { n ->
            NetworkView(n.networkId, n.name, m.role(n.networkId, m.ownRid), 1 + m.peers.values.count { n.networkId in it.networkIds && it.rid != m.ownRid })
        }
        val retryQueued = retries.count { (rid, r) -> workers[rid]?.thread?.isAlive != true && t < r.at && rid in eligible }
        val retryActive = workers.values.count { it.thread.isAlive && it.link == LinkState.CONNECTING && it.rid in retries }
        return EngineState(
            phase = phase,
            error = error,
            rid = id?.rid,
            vip = id?.vip,
            nodeName = id?.nodeName,
            networks = networks,
            peers = peers,
            traffic = TrafficView(sentBytes.get(), receivedBytes.get(), sentFrames.get(), receivedFrames.get(), dropped.get()),
            latencyMs = latencyMs,
            forceRelay = config.forceRelay,
            retryQueued = retryQueued,
            retryActive = retryActive,
            pendingIncoming = pending.size,
        )
    }

    private fun publish(force: Boolean = false) {
        val t = now()
        if (!force && (t - lastPublish < 250 || !dirty.get() && t - lastPublish < 1000)) return
        lastPublish = t
        dirty.set(false)
        val state = synchronized(lock) { buildState() }
        runCatching { listener.onState(state) }
    }

    private fun event(message: String, error: Boolean) {
        dirty.set(true)
        runCatching { listener.onEvent(message, error) }
    }

    private fun drainCommands(reason: String) {
        while (true) {
            val c = commands.poll() ?: break
            c.wipe()
            c.fail(reason)
        }
    }

    private fun halt() {
        stopping.set(true)
        running.set(false)
    }

    private fun sleepWhileRunning(ms: Long) {
        val until = now() + ms
        while (running.get() && now() < until) Thread.sleep(minOf(200, until - now()).coerceAtLeast(1))
    }

    companion object {
        const val PING_TIMEOUT_MS = 3000L

        /** Setup failure with the last transport error, kept short for the peer row. */
        private fun failure(message: String, last: String?): String =
            if (last == null) message else "$message (${last.take(160)})"

        fun describe(e: Throwable): String = when (e) {
            is java.net.ConnectException -> "connection refused or unreachable"
            is java.net.UnknownHostException -> "host unavailable"
            is SocketTimeoutException -> "timed out"
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }
}
