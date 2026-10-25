package io.github.gringoestrangeiro.openradplugin.runtime;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** Attachment-scoped fair discovery: fresh peers precede retries; incoming offers have priority. */
public final class PeerReachability {
    static final int MAX_TRACKED = 65536;

    public enum Failure {
        TIMEOUT,
        REMOTE_CLOSED,
        CONNECTION_REFUSED,
        PROTOCOL_REJECTED,
        IO_FAILURE,
        OTHER
    }

    public record Snapshot(
            int eligible,
            int attempted,
            int authenticated,
            int backingOff,
            long failures,
            Map<Failure, Long> reasons) {
        public Snapshot {
            reasons = Map.copyOf(reasons);
        }
    }

    private static final class Entry {
        long order, retryAt;
        int failures;
        boolean authenticated;
    }

    private final Map<Long, Entry> entries = new HashMap<>();
    private final EnumMap<Failure, Long> reasons = new EnumMap<>(Failure.class);
    private final LongSupplier clock;
    private long sequence, failures;

    public PeerReachability() {
        this(System::nanoTime);
    }

    PeerReachability(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void retain(Set<Long> eligible) {
        entries.keySet().retainAll(eligible);
        // Bounded bookkeeping: members beyond the bound wait until others leave the roster.
        for (long rid : eligible) {
            if (entries.size() >= MAX_TRACKED && !entries.containsKey(rid)) break;
            entries.computeIfAbsent(rid, k -> new Entry());
        }
    }

    public synchronized List<Long> candidates(Set<Long> occupied, Set<Long> incoming, int limit) {
        if (limit < 0 || limit > 4096) throw new IllegalArgumentException("candidate limit");
        long now = clock.getAsLong();
        return entries.keySet().stream()
                .filter(rid -> !occupied.contains(rid))
                .filter(
                        rid ->
                                incoming.contains(rid)
                                        || entries.get(rid).retryAt == 0
                                        || now - entries.get(rid).retryAt >= 0)
                .sorted(
                        Comparator.<Long>comparingInt(rid -> incoming.contains(rid) ? 0 : 1)
                                .thenComparingLong(rid -> entries.get(rid).order)
                                .thenComparing(Long::compareUnsigned))
                .limit(limit)
                .toList();
    }

    public synchronized void started(long rid) {
        Entry entry = entries.get(rid);
        if (entry != null) entry.order = ++sequence;
    }

    public synchronized void authenticated(long rid) {
        Entry entry = entries.get(rid);
        if (entry != null) {
            entry.authenticated = true;
            entry.failures = 0;
            entry.retryAt = 0;
        }
    }

    public synchronized void failed(long rid, Failure reason) {
        Entry entry = entries.get(rid);
        if (entry == null) return; // Late worker from a revoked member cannot reinsert it.
        entry.failures = Math.min(8, entry.failures + 1);
        long seconds = Math.min(600, 5L << (entry.failures - 1));
        entry.retryAt = clock.getAsLong() + Duration.ofSeconds(seconds).toNanos();
        failures++;
        reasons.merge(reason, 1L, Long::sum);
    }

    public synchronized Snapshot snapshot() {
        int attempted = 0, authenticated = 0, backoff = 0;
        long now = clock.getAsLong();
        for (Entry entry : entries.values()) {
            if (entry.order != 0) attempted++;
            if (entry.authenticated) authenticated++;
            if (entry.retryAt != 0 && now - entry.retryAt < 0) backoff++;
        }
        return new Snapshot(entries.size(), attempted, authenticated, backoff, failures, reasons);
    }

    public static Failure classify(Exception exception) {
        Throwable cause = exception;
        for (int i = 0; i < 8; i++) {
            if (cause instanceof SocketTimeoutException) return Failure.TIMEOUT;
            if (cause instanceof EOFException) return Failure.REMOTE_CLOSED;
            if (cause instanceof ConnectException) return Failure.CONNECTION_REFUSED;
            if (cause instanceof IllegalArgumentException) return Failure.PROTOCOL_REJECTED;
            if (cause.getCause() == null || cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return exception instanceof IOException ? Failure.IO_FAILURE : Failure.OTHER;
    }
}
