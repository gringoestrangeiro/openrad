package io.github.gringoestrangeiro.openradclient;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.gringoestrangeiro.openradplugin.discovery.LanAdvertisement;
import io.github.gringoestrangeiro.openradplugin.link.Checksum;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;

class LanServerRegistryTest {
    @Test void vanillaEphemeralSourcePortIsAcceptedAndClockRollbackDoesNotExtendLifetime() {
        AtomicLong wall = new AtomicLong(100_000), elapsed = new AtomicLong(0);
        LanServerRegistry registry = new LanServerRegistry(wall::get, elapsed::get);
        var vip = ipv4(0x1a000002);
        byte[] frame = new LanAdvertisement("Vanilla LAN", 25565).frame(vip, 25565, 1);
        Checksum.put16(frame, 14 + 20, 49152);
        Checksum.put16(frame, 14 + 26, 0);
        int length = Checksum.u16(frame, 14 + 24);
        byte[] pseudo = new byte[12 + length];
        System.arraycopy(frame, 14 + 12, pseudo, 0, 8);
        pseudo[9] = 17;
        Checksum.put16(pseudo, 10, length);
        System.arraycopy(frame, 14 + 20, pseudo, 12, length);
        int checksum = Checksum.onesComplement(pseudo, 0, pseudo.length);
        Checksum.put16(frame, 14 + 26, checksum == 0 ? 65535 : checksum);
        assertTrue(registry.accept(vip, TunnelCodec.mac(vip), frame));
        wall.set(1);
        elapsed.set(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(LanServerRegistry.TTL_MILLIS));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test void vpnAdvertisementIsDiscoveredUpdatedExpiredAndRevokedWithoutPhysicalSockets() {
        AtomicLong clock = new AtomicLong(1000);
        LanServerRegistry registry = new LanServerRegistry(clock::get);
        var vip = ipv4(0x1a000002);
        assertTrue(registry.accept(vip, TunnelCodec.mac(vip), new LanAdvertisement("Hello", 25565).frame(vip, 25565, 1)));
        assertEquals(new ClientVpnRuntime.LanServer("Hello", "26.0.0.2", 25565, 1000), registry.snapshot().getFirst());
        clock.addAndGet(1000);
        assertTrue(registry.accept(vip, TunnelCodec.mac(vip), new LanAdvertisement("Updated", 25565).frame(vip, 25565, 2)));
        assertEquals(1, registry.snapshot().size());
        assertEquals("Updated", registry.snapshot().getFirst().motd());
        clock.addAndGet(LanServerRegistry.TTL_MILLIS);
        assertTrue(registry.snapshot().isEmpty());
        registry.accept(vip, TunnelCodec.mac(vip), new LanAdvertisement("Again", 25565).frame(vip, 25565, 3));
        registry.removePeer(vip);
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test void rejectsSpoofedVipMacChecksumPortsFragmentsAndPayload() {
        LanServerRegistry registry = new LanServerRegistry();
        var vip = ipv4(0x1a000002);
        byte[] valid = new LanAdvertisement("Hello", 25565).frame(vip, 25565, 1);
        assertFalse(registry.accept(ipv4(0x1a000003), TunnelCodec.mac(vip), valid));
        assertFalse(registry.accept(vip, TunnelCodec.mac(ipv4(0x1a000003)), valid));
        for (int offset : new int[] {0, 6, 14 + 10, 14 + 20, 14 + 22, 14 + 26, valid.length - 1}) {
            byte[] malformed = valid.clone();
            malformed[offset] ^= 1;
            assertFalse(registry.accept(vip, TunnelCodec.mac(vip), malformed), "offset " + offset);
        }
        byte[] fragmented = valid.clone();
        Checksum.put16(fragmented, 14 + 6, 0x2000);
        Checksum.put16(fragmented, 14 + 10, 0);
        Checksum.put16(fragmented, 14 + 10, Checksum.onesComplement(fragmented, 14, 20));
        assertFalse(registry.accept(vip, TunnelCodec.mac(vip), fragmented));
        assertFalse(registry.accept(vip, TunnelCodec.mac(vip), new byte[0]));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test void authenticatedPeerCannotAllocateUnboundedLanEntries() {
        LanServerRegistry registry = new LanServerRegistry();
        int perPeer = LanServerRegistry.MAX_SERVERS_PER_PEER;
        for (int index = 0; index < LanServerRegistry.MAX_SERVERS; index++) {
            var vip = ipv4(0x1a000002 + index / perPeer);
            int port = 1 + index % perPeer;
            assertTrue(registry.accept(vip, TunnelCodec.mac(vip), new LanAdvertisement("Server", port).frame(vip, port, port)));
        }
        var late = ipv4(0x1a000002 + LanServerRegistry.MAX_SERVERS / perPeer);
        assertFalse(registry.accept(late, TunnelCodec.mac(late), new LanAdvertisement("Overflow", 999).frame(late, 999, 1)));
        assertEquals(LanServerRegistry.MAX_SERVERS, registry.snapshot().size());
        registry.clear();
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test void onePeerCannotFillTheServerList() {
        AtomicLong clock = new AtomicLong(1000);
        LanServerRegistry registry = new LanServerRegistry(clock::get);
        var flooding = ipv4(0x1a000002);
        for (int port = 1; port <= 300; port++) {
            clock.incrementAndGet();
            assertTrue(registry.accept(flooding, TunnelCodec.mac(flooding),
                    new LanAdvertisement("Spam", 25565).frame(flooding, 20000 + port, port)));
        }
        assertEquals(LanServerRegistry.MAX_SERVERS_PER_PEER, registry.snapshot().size());
        assertEquals(20300, registry.snapshot().getLast().port(), "the newest advertisement is kept");
        var honest = ipv4(0x1a000003);
        assertTrue(registry.accept(honest, TunnelCodec.mac(honest),
                new LanAdvertisement("Real", 25565).frame(honest, 25565, 1)));
    }
}
