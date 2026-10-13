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
    /** Budget of one outgoing direct TCP lane or UDP rendezvous (`peer.rs::negotiate`). */
    const val DIRECT_ATTEMPT_MS = 8000L
    /** Incoming UDP rendezvous budget (`incoming.rs::Setup::accept`). */
    const val INCOMING_UDP_MS = 12_000L

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
     * [MAX_RELAY_WAIT_MS] after the offer. An exhausted direct path skips the wait.
     */
    fun relayReady(now: Long, receivedAt: Long, lastDirectStart: Long?, directExhausted: Boolean): Boolean {
        val startAt = minOf(maxOf(lastDirectStart ?: receivedAt, receivedAt) + DIRECT_HEAD_START_MS, receivedAt + MAX_RELAY_WAIT_MS)
        return directExhausted || now >= startAt
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
