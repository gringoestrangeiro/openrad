package io.github.gringoestrangeiro.openradplugin.peer;

import static io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.LongSupplier;

/** Bounded CID-correlated offers. Feed only records from an authenticated attachment. */
public final class IncomingHub implements AutoCloseable {
    private final Map<Long, Offer> pending = new HashMap<>();
    private final Map<Long, Route> routes = new HashMap<>();
    private final Map<Long, Long> pendingByRid = new HashMap<>(), routesByRid = new HashMap<>();
    private final LongSupplier clock;
    private static final int MAX_PENDING_OFFERS = 64;
    private static final int MAX_PENDING_RECORDS = 4;
    private static final int MAX_ACTIVE_ROUTES = 32;

    private static final class Offer {
        final long rid, created;
        final byte[] password;
        final List<byte[]> records = new ArrayList<>();

        Offer(long rid, byte[] password, long created) {
            this.rid = rid;
            this.password = password;
            this.created = created;
        }
    }

    private record Route(long rid, long until, BlockingQueue<byte[]> records) {}

    public static final class Setup implements AutoCloseable {
        private final long cid, rid;
        private final byte[] password;
        private final BlockingQueue<byte[]> records;

        private Setup(long cid, Offer offer, BlockingQueue<byte[]> records) {
            this.cid = cid;
            rid = offer.rid;
            password = offer.password;
            this.records = records;
        }

        public long cid() {
            return cid;
        }

        public long peerRid() {
            return rid;
        }

        public byte[] password() {
            return password.clone();
        }

        public BlockingQueue<byte[]> records() {
            return records;
        }

        @Override
        public void close() {
            Arrays.fill(password, (byte) 0);
            records.clear();
        }
    }

    public IncomingHub() {
        this(System::nanoTime);
    }

    IncomingHub(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized boolean ingest(byte[] data) {
        List<TlvCodec.Record> fields = parse(data);
        long operation = int32(field(fields, SERVER_OP));
        if (operation != 11
                && operation != 6
                && operation != 7
                && operation != 23
                && operation != 29) return false;
        if (data.length > 65536) throw new IllegalArgumentException("incoming record size limit");
        long cid = int64(field(fields, 0x020001c1));
        if (cid == 0) throw new IllegalArgumentException("zero incoming CID");
        if (operation == 11) {
            // Deduplicate before nested credential parsing: a duplicate can never replace a secret.
            if (pending.containsKey(cid) || routes.containsKey(cid)) return true;
            List<TlvCodec.Record> credentials = parse(field(fields, 0x1235));
            long rid = int64(field(credentials, 0x020001e1));
            byte[] password = field(credentials, 0x0a0001cd);
            if (rid == 0 || password.length < 6 || password.length > 1024)
                throw new IllegalArgumentException("invalid incoming credentials");
            if (pending.size() >= MAX_PENDING_OFFERS
                    || pendingByRid.containsKey(rid)
                    || routesByRid.containsKey(rid)) {
                Arrays.fill(password, (byte) 0);
                return true;
            }
            pending.put(cid, new Offer(rid, password, clock.getAsLong()));
            pendingByRid.put(rid, cid);
        } else if (routes.containsKey(cid)) routes.get(cid).records.offer(data.clone());
        else if (pending.containsKey(cid)) {
            Offer offer = pending.get(cid);
            if (offer.records.size() < MAX_PENDING_RECORDS)
                offer.records.add(data.clone());
        }
        return true;
    }

    public synchronized Set<Long> pendingRids() {
        return Set.copyOf(pendingByRid.keySet());
    }

    /** Caller checks authenticated network membership before taking an offer. */
    public synchronized Optional<Setup> take(long rid) {
        if (routes.size() >= MAX_ACTIVE_ROUTES) return Optional.empty();
        Long cid = pendingByRid.remove(rid);
        if (cid == null) return Optional.empty();
        Offer offer = pending.remove(cid);
        BlockingQueue<byte[]> records = new ArrayBlockingQueue<>(16);
        records.addAll(offer.records);
        routes.put(cid, new Route(rid, clock.getAsLong() + 45_000_000_000L, records));
        routesByRid.put(rid, cid);
        return Optional.of(new Setup(cid, offer, records));
    }

    public synchronized void reject(long rid) {
        Long cid = pendingByRid.remove(rid);
        if (cid != null) {
            Offer offer = pending.remove(cid);
            Arrays.fill(offer.password, (byte) 0);
        }
    }

    public synchronized void finish(long rid) {
        Long cid = routesByRid.remove(rid);
        if (cid != null) routes.remove(cid);
    }

    public synchronized void expire() {
        long now = clock.getAsLong();
        pending.entrySet()
                .removeIf(
                        e -> {
                            Offer p = e.getValue();
                            if (now - p.created < 30_000_000_000L) return false;
                            pendingByRid.remove(p.rid);
                            Arrays.fill(p.password, (byte) 0);
                            return true;
                        });
        routes.entrySet()
                .removeIf(
                        e -> {
                            Route r = e.getValue();
                            if (now < r.until) return false;
                            routesByRid.remove(r.rid);
                            return true;
                        });
    }

    @Override
    public synchronized void close() {
        for (Offer p : pending.values()) Arrays.fill(p.password, (byte) 0);
        pending.clear();
        routes.clear();
        pendingByRid.clear();
        routesByRid.clear();
    }
}
