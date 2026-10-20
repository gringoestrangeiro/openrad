package com.openrad.mobile.engine

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Peer setup limits and retry pacing, independent of the wire protocol.
 * Mirrors `scheduling.rs`: most setup time is spent waiting on network I/O, so
 * many attempts stay in flight at once. Established channels do not consume
 * setup slots, and incoming offers keep room even while outgoing attempts stall.
 */
object PeerScheduling {
    const val MAX_HANDSHAKES = 128
    const val MAX_OUTGOING_HANDSHAKES = 96
    const val DIRECT_TCP_LANES = 4
    const val MAX_PENDING_OFFERS = 256
    /** Every incoming setup may queue an advertisement for the control session. */
    const val ADVERTISEMENT_QUEUE = MAX_HANDSHAKES * 4

    /** Direct paths get this head start over relay authentication (`peer.rs`). */
    const val DIRECT_HEAD_START_MS = 4000L
    const val MAX_RELAY_WAIT_MS = 8000L
    /**
     * Relay authentication waits this long after its ticket while a direct
     * attempt that already heard from the peer is still running (`peer.rs`).
     */
    const val MAX_PROGRESS_HOLD_MS = 16_000L
    /** Punching window of one UDP rendezvous, both roles; covers the progress hold. */
    const val UDP_ATTEMPT_MS = 14_000L
    /** The service sends the acceptor no relay ticket unless asked (`incoming.rs`). */
    const val ACCEPTOR_RELAY_DELAY_MS = 2_000L
    /** Relay-only acceptors ask almost at once, after reading buffered records. */
    const val RELAY_ONLY_REQUEST_DELAY_MS = 300L
    /** An offer whose initiator sends no candidate or ticket at all is abandoned. */
    const val ABANDONED_OFFER_MS = 15_000L

    /** A channel that lasted this long resets its retry backoff. */
    const val STABLE_CONNECTION_MS = 60_000L

    /** Retry promptly, with rid-derived jitter so peers do not reconnect in lockstep. */
    fun retryDelayMs(rid: Long, failures: Int): Long {
        val baseMs = 1500L shl (maxOf(failures, 1) - 1).coerceAtMost(3)
        val mixed = (rid * GOLDEN).rotateLeft(Math.floorMod(failures, 64))
        return baseMs + java.lang.Long.remainderUnsigned(mixed, baseMs * 2000 + 1) / 1000
    }

    /** Incoming offers first, then channels that worked before, other retries, new peers. */
    fun priorityLane(incoming: Boolean, retry: Boolean, previouslyConnected: Boolean): Int = when {
        incoming -> 0
        previouslyConnected -> 1
        retry -> 2
        else -> 3
    }

    /**
     * Relay authentication waits for the direct head start, measured from the
     * later of the relay offer and the last direct start, but never longer than
     * [MAX_RELAY_WAIT_MS] after the offer. While a running direct attempt has
     * heard from the peer ([directProgress]) it waits up to [MAX_PROGRESS_HOLD_MS]
     * instead, so a working path is not cut off by a relay that merely started
     * first. An exhausted direct path skips the wait.
     */
    fun relayReady(now: Long, receivedAt: Long, lastDirectStart: Long?, directExhausted: Boolean, directProgress: Boolean = false): Boolean {
        if (directExhausted) return true
        if (directProgress) return now >= receivedAt + MAX_PROGRESS_HOLD_MS
        val startAt = minOf(maxOf(lastDirectStart ?: receivedAt, receivedAt) + DIRECT_HEAD_START_MS, receivedAt + MAX_RELAY_WAIT_MS)
        return now >= startAt
    }

    private const val GOLDEN = -0x61c8864680b583ebL // 0x9e3779b97f4a7c15
}

/**
 * Uses more setup capacity when many peers need recovery and reduces pressure
 * when recent attempts mostly fail, without stopping retries altogether.
 */
class RetryPacer(now: Long) {
    data class Policy(val activeLimit: Int, val startIntervalMs: Long)

    private var nextStart = now
    private val outcomes = ArrayDeque<Pair<Long, Boolean>>()

    fun recordOutcome(now: Long, success: Boolean) {
        outcomes.addLast(now to success)
        while (outcomes.size > MAX_OUTCOMES) outcomes.removeFirst()
        prune(now)
    }

    private fun prune(now: Long) {
        while (outcomes.isNotEmpty() && now - outcomes.first().first > OUTCOME_WINDOW_MS) outcomes.removeFirst()
    }

    fun policy(now: Long, waiting: Int): Policy {
        prune(now)
        var policy = when {
            waiting >= 32 -> Policy(48, 50)
            waiting >= 8 -> Policy(32, 100)
            else -> Policy(16, 200)
        }
        val failed = outcomes.count { !it.second }
        if (outcomes.size >= 8 && failed * 4 >= outcomes.size * 3) {
            policy = Policy(minOf(policy.activeLimit, 32), maxOf(policy.startIntervalMs, 150))
        }
        return policy
    }

    fun ready(now: Long, active: Int, policy: Policy): Boolean = active < policy.activeLimit && now >= nextStart

    fun started(now: Long, policy: Policy) {
        nextStart = now + policy.startIntervalMs
    }

    private companion object {
        const val OUTCOME_WINDOW_MS = 30_000L
        const val MAX_OUTCOMES = 256
    }
}

/**
 * Counts setups still in progress, including cancelled ones until their thread
 * exits, so replacement attempts cannot exceed the limits.
 */
class HandshakeBudget(incoming: Iterable<Boolean>) {
    private var total = 0
    private var outgoing = 0

    init {
        for (isIncoming in incoming) {
            total++
            if (!isIncoming) outgoing++
        }
    }

    /** Holds outgoing room for retries already due; incoming offers never use outgoing slots. */
    fun tryStart(incoming: Boolean, reservedOutgoing: Int = 0): Boolean {
        if (total >= PeerScheduling.MAX_HANDSHAKES ||
            !incoming && outgoing >= (PeerScheduling.MAX_OUTGOING_HANDSHAKES - reservedOutgoing).coerceAtLeast(0)
        ) {
            return false
        }
        total++
        if (!incoming) outgoing++
        return true
    }
}

/**
 * Transport attempts racing to set up one peer. Mirrors `SetupTasks`: the
 * first success wins without waiting for slow losers. Losers observe [cancel],
 * and anything they produce after [close] is disposed instead of leaking.
 * [launch], [poll] and [close] are called from the owning thread only.
 */
class SetupRace<T : Any>(private val label: String, private val dispose: (T) -> Unit) {
    val cancel = AtomicBoolean(false)
    private val results = LinkedBlockingQueue<Pair<String, Result<T>>>()
    private val guard = Any()
    private var closed = false
    private var active = 0

    /** True when every launched attempt has been collected with [poll]. */
    val idle: Boolean get() = active == 0

    /** [abandon] releases resources handed to [block] when its thread cannot start. */
    fun launch(name: String, abandon: () -> Unit = {}, block: () -> T) {
        active++
        val thread = Thread({ deliver(name, runCatching(block)) }, "openrad-$label-$name").apply { isDaemon = true }
        try {
            thread.start()
        } catch (e: Throwable) {
            // A temporary OS thread limit must surface as a failed attempt, not a stuck setup.
            runCatching(abandon)
            deliver(name, Result.failure(IOException("could not start $name attempt", e)))
        }
    }

    private fun deliver(name: String, result: Result<T>) {
        val kept = synchronized(guard) { !closed && results.add(name to result) }
        if (!kept) result.getOrNull()?.let { runCatching { dispose(it) } }
    }

    /** Next finished attempt, waiting up to [waitMs]; null when none finished. */
    fun poll(waitMs: Long = 0): Pair<String, Result<T>>? {
        val next = if (waitMs > 0) results.poll(waitMs, TimeUnit.MILLISECONDS) else results.poll()
        if (next != null) active--
        return next
    }

    /** Cancels the remaining attempts and disposes every uncollected result except [winner]. */
    fun close(winner: T? = null) {
        cancel.set(true)
        val leftovers = synchronized(guard) {
            closed = true
            ArrayList<Pair<String, Result<T>>>().also { results.drainTo(it) }
        }
        for ((_, result) in leftovers) {
            val value = result.getOrNull()
            if (value != null && value !== winner) runCatching { dispose(value) }
        }
    }
}

/**
 * Counts running direct attempts that proved the path reachable: a valid punch
 * from the peer arrived, or a TCP rendezvous matched (`scheduling.rs::DirectProgress`).
 */
class DirectProgress {
    private val count = java.util.concurrent.atomic.AtomicInteger()

    val active: Boolean get() = count.get() > 0

    /** One tracker per transport worker; [Tracker.close] stops counting it. */
    fun tracker(): Tracker = Tracker()

    inner class Tracker : java.io.Closeable {
        private val marked = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        fun mark() {
            if (!closed.get() && marked.compareAndSet(false, true)) count.incrementAndGet()
        }

        override fun close() {
            if (closed.compareAndSet(false, true) && marked.get()) count.decrementAndGet()
        }
    }
}

/**
 * Process-wide guard for acceptor relay requests on the shared attachment
 * session (`incoming.rs::acceptor_relay`): a refusal or disconnect soon after
 * one disables further requests instead of risking the session.
 */
class AcceptorRelayGuard {
    @Volatile private var disabled = false
    @Volatile private var lastRequest: Long? = null

    val allowed: Boolean get() = !disabled

    fun requested(now: Long) {
        lastRequest = now
    }

    /** True when the objection followed a request closely enough to disable requests. */
    fun objected(now: Long): Boolean {
        val recent = lastRequest?.let { now - it <= OBJECTION_WINDOW_MS } == true
        if (recent) disabled = true
        return recent
    }

    companion object {
        const val OBJECTION_WINDOW_MS = 5_000L
        val shared = AcceptorRelayGuard()
    }
}
