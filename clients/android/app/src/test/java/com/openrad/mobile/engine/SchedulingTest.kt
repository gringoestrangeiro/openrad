package com.openrad.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Mirrors the `scheduling.rs` tests plus the relay head start of `peer.rs`. */
class SchedulingTest {
    @Test
    fun retryBackoffIsBoundedAndSpreadsLargeRosters() {
        val delays = (1L..150L).map { PeerScheduling.retryDelayMs(it, 1) }.toSortedSet()
        assertTrue(delays.size > 100)
        assertTrue(delays.last() - delays.first() > 2500)
        for (rid in 1L..150L) {
            for ((failures, min, max) in listOf(
                Triple(1, 1500L, 4500L),
                Triple(2, 3000L, 9000L),
                Triple(3, 6000L, 18_000L),
                Triple(4, 12_000L, 36_000L),
                Triple(Int.MAX_VALUE, 12_000L, 36_000L),
            )) {
                val delay = PeerScheduling.retryDelayMs(rid, failures)
                assertTrue("rid $rid failures $failures: $delay", delay in min..max)
            }
        }
    }

    @Test
    fun recoveryScalesWithBacklogAndNeverExceedsOutgoingCapacity() {
        val pacer = RetryPacer(0)
        assertEquals(16, pacer.policy(0, 1).activeLimit)
        assertEquals(32, pacer.policy(0, 8).activeLimit)
        val burst = pacer.policy(0, 300)
        assertEquals(48, burst.activeLimit)
        assertTrue(pacer.ready(0, 47, burst))
        assertFalse(pacer.ready(0, 48, burst))
        assertTrue(burst.activeLimit < PeerScheduling.MAX_OUTGOING_HANDSHAKES)
    }

    @Test
    fun failuresReducePressureAndExpireAfterTheWindow() {
        val pacer = RetryPacer(0)
        repeat(8) { pacer.recordOutcome(0, success = false) }
        assertEquals(RetryPacer.Policy(32, 150), pacer.policy(0, 100))
        assertEquals(48, pacer.policy(31_000, 100).activeLimit)
    }

    @Test
    fun overdueRetriesStartQuicklyWithoutAnUnboundedBurst() {
        val pacer = RetryPacer(0)
        val policy = pacer.policy(0, 100)
        assertTrue(pacer.ready(0, 0, policy))
        pacer.started(0, policy)
        assertFalse(pacer.ready(49, 0, policy))
        assertTrue(pacer.ready(50, 0, policy))
        pacer.started(300_000, policy)
        assertFalse(pacer.ready(300_000, 0, policy))
    }

    @Test
    fun recoveryPrioritizesIncomingOffersThenPriorChannels() {
        val lanes = listOf(
            PeerScheduling.priorityLane(incoming = false, retry = false, previouslyConnected = false) to 1,
            PeerScheduling.priorityLane(incoming = false, retry = true, previouslyConnected = false) to 2,
            PeerScheduling.priorityLane(incoming = false, retry = true, previouslyConnected = true) to 3,
            PeerScheduling.priorityLane(incoming = true, retry = true, previouslyConnected = false) to 4,
        )
        assertEquals(listOf(4, 3, 2, 1), lanes.sortedBy { it.first }.map { it.second })
    }

    @Test
    fun aHundredOutgoingPeersAreScheduledInTwoBatches() {
        var queued = 100
        val batches = ArrayList<Int>()
        while (queued > 0) {
            val budget = HandshakeBudget(emptyList())
            var started = 0
            while (queued > 0 && budget.tryStart(incoming = false)) {
                queued--
                started++
            }
            batches.add(started)
        }
        assertEquals(listOf(96, 4), batches)
    }

    @Test
    fun stalledOutgoingSetupsLeaveRoomForIncomingOffers() {
        val budget = HandshakeBudget(List(PeerScheduling.MAX_OUTGOING_HANDSHAKES) { false })
        assertFalse(budget.tryStart(incoming = false))
        repeat(PeerScheduling.MAX_HANDSHAKES - PeerScheduling.MAX_OUTGOING_HANDSHAKES) { assertTrue(budget.tryStart(incoming = true)) }
        assertFalse(budget.tryStart(incoming = true))
    }

    @Test
    fun dueRetriesKeepOutgoingSlotsWhileFirstAttemptsContinue() {
        val budget = HandshakeBudget(List(90) { false })
        assertTrue(budget.tryStart(incoming = false, reservedOutgoing = 5))
        assertFalse(budget.tryStart(incoming = false, reservedOutgoing = 5))
        assertTrue(budget.tryStart(incoming = false))
    }

    @Test
    fun relayWaitsForTheDirectHeadStartWithAFiniteDeadline() {
        assertFalse(PeerScheduling.relayReady(3999, 0, null, directExhausted = false))
        assertTrue(PeerScheduling.relayReady(4000, 0, null, directExhausted = false))
        // A late direct start earns its own head start, capped at eight seconds after the offer.
        assertFalse(PeerScheduling.relayReady(5000, 0, 2000, directExhausted = false))
        assertTrue(PeerScheduling.relayReady(6000, 0, 2000, directExhausted = false))
        assertTrue(PeerScheduling.relayReady(8000, 0, 7000, directExhausted = false))
        assertTrue(PeerScheduling.relayReady(0, 0, null, directExhausted = true))
    }

    private class Resource(val name: String) {
        val closed = AtomicInteger()
    }

    @Test
    fun firstSuccessWinsWithoutWaitingForSlowLosersWhichAreDisposedLater() {
        val race = SetupRace<Resource>("test") { it.closed.incrementAndGet() }
        val release = CountDownLatch(1)
        val slow = Resource("slow")
        race.launch("slow") {
            release.await(5, TimeUnit.SECONDS)
            slow
        }
        race.launch("failing") { throw IllegalStateException("no route") }
        val fast = Resource("fast")
        race.launch("fast") { fast }
        var winner: Resource? = null
        val started = now()
        while (winner == null && now() - started < 5000) {
            val (_, result) = race.poll(50) ?: continue
            winner = result.getOrNull()
        }
        assertEquals("fast", winner?.name)
        assertTrue("winner waited for a slow loser", now() - started < 2000)
        assertFalse(race.idle)
        race.close(winner)
        assertTrue(race.cancel.get())
        release.countDown()
        val until = now() + 5000
        while (slow.closed.get() == 0 && now() < until) Thread.sleep(10)
        assertEquals(1, slow.closed.get())
        assertEquals(0, fast.closed.get())
        assertNull(race.poll())
    }
}
