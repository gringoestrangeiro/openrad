package io.github.gringoestrangeiro.openradplugin.peer;

import static io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.*;
import static io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec.concat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

class IncomingHubTest {
    static byte[] offer(long cid, long rid) {
        return concat(
                u32(SERVER_OP, 11),
                u64(0x020001c1, cid),
                encode(
                        0x1235,
                        concat(
                                u64(0x020001e1, rid),
                                encode(0x0a0001cd, "secret-password".getBytes()))));
    }

    static byte[] followup(long cid) {
        return concat(u32(SERVER_OP, 29), u64(0x020001c1, cid), encode(0x127c, new byte[0]));
    }

    @Test
    void duplicateCidNeverReplacesSecretAndFollowupsStayCorrelated() {
        try (IncomingHub hub = new IncomingHub()) {
            hub.ingest(offer(7, 11));
            hub.ingest(concat(u32(SERVER_OP, 11), u64(0x020001c1, 7)));
            hub.ingest(offer(8, 11));
            hub.ingest(followup(8));
            hub.ingest(followup(7));
            var setup = hub.take(11).orElseThrow();
            assertEquals(7, setup.cid());
            assertArrayEquals(followup(7), setup.records().poll());
            assertNull(setup.records().poll());
            hub.ingest(followup(7));
            assertArrayEquals(followup(7), setup.records().poll());
            hub.ingest(offer(9, 11));
            assertTrue(hub.pendingRids().isEmpty());
            hub.finish(11);
            hub.ingest(offer(9, 11));
            assertEquals(1, hub.pendingRids().size());
            setup.close();
        }
    }

    @Test
    void limitsActiveRoutesAndReleasesCapacityWhenOneFinishes() {
        try (IncomingHub hub = new IncomingHub()) {
            for (int i = 1; i <= 33; i++) hub.ingest(offer(i, i));
            for (int i = 1; i <= 32; i++) {
                var setup = hub.take(i).orElseThrow();
                assertEquals(i, setup.peerRid());
            }
            assertTrue(hub.take(33).isEmpty(),
                    "the 33rd active route must be postponed instead of allocating memory");
            hub.finish(1);
            assertTrue(hub.take(33).isPresent());
        }
    }

    @Test
    void boundedMailboxesExpireAndReleaseBothIndexes() {
        AtomicLong now = new AtomicLong();
        try (IncomingHub hub = new IncomingHub(now::get)) {
            for (int i = 1; i <= 300; i++) hub.ingest(offer(i, i));
            assertEquals(64, hub.pendingRids().size());
            for (int i = 0; i < 100; i++) hub.ingest(followup(1));
            var setup = hub.take(1).orElseThrow();
            assertEquals(4, setup.records().size());
            for (int i = 0; i < 100; i++) hub.ingest(followup(1));
            assertEquals(16, setup.records().size());
            now.set(46_000_000_000L);
            hub.expire();
            assertTrue(hub.pendingRids().isEmpty());
            hub.ingest(offer(1, 1));
            assertTrue(hub.take(1).isPresent());
            setup.close();
        }
    }
}
