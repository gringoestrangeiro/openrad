package io.github.gringoestrangeiro.openradplugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.config.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.*;

class PeerReachabilityTest {
    @Test
    void discoveryContinuesBeyond64AndVisitsEveryEligibleMember() {
        var scheduler = new PeerReachability(() -> 0L);
        Set<Long> eligible = LongStream.rangeClosed(1, 512).boxed().collect(Collectors.toSet());
        scheduler.retain(eligible);
        Set<Long> connected = new HashSet<>();
        for (int round = 0; round < 32; round++) {
            var peers = scheduler.candidates(connected, Set.of(), 16);
            assertEquals(16, peers.size());
            for (long rid : peers) {
                scheduler.started(rid);
                scheduler.authenticated(rid);
                assertTrue(connected.add(rid));
            }
        }
        assertEquals(512, scheduler.snapshot().attempted());
        assertEquals(512, scheduler.snapshot().authenticated());
        assertTrue(scheduler.candidates(connected, Set.of(), 16).isEmpty());
    }

    @Test
    void freshMembersPrecedeFailedRetriesAndIncomingOffersHavePriority() {
        AtomicLong now = new AtomicLong();
        var scheduler = new PeerReachability(now::get);
        scheduler.retain(Set.of(1L, 2L, 3L, 4L));
        assertEquals(List.of(1L, 2L), scheduler.candidates(Set.of(), Set.of(), 2));
        scheduler.started(1);
        scheduler.failed(1, PeerReachability.Failure.TIMEOUT);
        scheduler.started(2);
        scheduler.failed(2, PeerReachability.Failure.REMOTE_CLOSED);
        assertEquals(List.of(3L, 4L), scheduler.candidates(Set.of(), Set.of(), 4));
        assertEquals(1L, scheduler.candidates(Set.of(), Set.of(1L), 1).getFirst());
        now.set(Duration.ofSeconds(6).toNanos());
        assertEquals(List.of(3L, 4L, 1L, 2L), scheduler.candidates(Set.of(), Set.of(), 4));
        assertEquals(2, scheduler.snapshot().failures());
    }

    @Test
    void failuresUseBoundedExponentialBackoffAndSuccessfulAuthResetsIt() {
        AtomicLong now = new AtomicLong();
        var scheduler = new PeerReachability(now::get);
        scheduler.retain(Set.of(8L));
        for (int i = 0; i < 12; i++) scheduler.failed(8, PeerReachability.Failure.TIMEOUT);
        now.set(Duration.ofSeconds(599).toNanos());
        assertTrue(scheduler.candidates(Set.of(), Set.of(), 1).isEmpty());
        assertEquals(1, scheduler.snapshot().backingOff());
        now.set(Duration.ofSeconds(600).toNanos());
        assertEquals(List.of(8L), scheduler.candidates(Set.of(), Set.of(), 1));
        scheduler.authenticated(8);
        scheduler.failed(8, PeerReachability.Failure.REMOTE_CLOSED);
        now.addAndGet(Duration.ofSeconds(5).toNanos());
        assertEquals(List.of(8L), scheduler.candidates(Set.of(), Set.of(), 1));
    }

    @Test
    void revokedMembersAndLateWorkersCannotReenterDiscovery() {
        var scheduler = new PeerReachability(() -> 0L);
        scheduler.retain(Set.of(1L, 2L));
        scheduler.started(1);
        scheduler.retain(Set.of(2L));
        scheduler.failed(1, PeerReachability.Failure.TIMEOUT);
        scheduler.authenticated(1);
        assertEquals(List.of(2L), scheduler.candidates(Set.of(), Set.of(1L), 16));
        assertEquals(0, scheduler.snapshot().failures());
        // Large rosters are bounded without failing the session.
        scheduler.retain(
                LongStream.range(1, PeerReachability.MAX_TRACKED + 100)
                        .boxed()
                        .collect(Collectors.toSet()));
        assertEquals(PeerReachability.MAX_TRACKED, scheduler.snapshot().eligible());
    }

    @Test
    void failureCategoriesDoNotNeedToExposeExceptionMessages() {
        assertEquals(
                PeerReachability.Failure.REMOTE_CLOSED,
                PeerReachability.classify(new EOFException("private payload")));
        assertEquals(
                PeerReachability.Failure.TIMEOUT,
                PeerReachability.classify(new IOException(new SocketTimeoutException())));
        assertEquals(
                PeerReachability.Failure.PROTOCOL_REJECTED,
                PeerReachability.classify(new IllegalArgumentException("private credential")));
        assertEquals(
                PeerReachability.Failure.CONNECTION_REFUSED,
                PeerReachability.classify(new ConnectException()));
        assertEquals(
                PeerReachability.Failure.IO_FAILURE, PeerReachability.classify(new IOException()));
    }

    @Test
    void zeroMeansAllWithinTheRosterInsteadOfAnExecutorWithZeroCapacity() {
        var bridge =
                new BridgeSettings(true, "127.0.0.1", 25575, 25565, 4, 8, 15, "", 0, "", 0, 1000);
        var settings =
                new VpnSettings(
                        bridge,
                        "synthetic",
                        "127.0.0.1",
                        17301,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        true,
                        true,
                        true,
                        0,
                        3,
                        60);
        assertEquals(4096, settings.peerLimit());
        assertEquals(16, settings.handshakeLimit());
    }
}
