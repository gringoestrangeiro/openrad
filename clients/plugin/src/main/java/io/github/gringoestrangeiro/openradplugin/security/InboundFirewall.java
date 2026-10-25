package io.github.gringoestrangeiro.openradplugin.security;

import io.github.gringoestrangeiro.openradplugin.link.ArpPacket;
import io.github.gringoestrangeiro.openradplugin.link.Checksum;
import io.github.gringoestrangeiro.openradplugin.link.EthernetFrame;
import io.github.gringoestrangeiro.openradplugin.link.TcpSegment;

import java.util.Arrays;
import java.util.Set;

public final class InboundFirewall {
    private InboundFirewall() {}

    public record PeerGrant(
            long rid,
            boolean authenticated,
            Set<String> joinedAllowedNetworks,
            int sourceIp,
            byte[] sourceMac) {
        public PeerGrant {
            joinedAllowedNetworks = Set.copyOf(joinedAllowedNetworks);
            if (sourceMac.length != 6) throw new IllegalArgumentException("invalid peer MAC");
            sourceMac = sourceMac.clone();
        }

        @Override
        public byte[] sourceMac() {
            return sourceMac.clone();
        }

        public boolean eligible() {
            return authenticated && !joinedAllowedNetworks.isEmpty();
        }

        /** Compares with the granted MAC without the defensive copy of {@link #sourceMac()}. */
        public boolean sourceMacEquals(byte[] mac) {
            return Arrays.equals(sourceMac, mac);
        }
    }

    public sealed interface Result permits Rejected, ArpResponse, AdmittedTcp {}

    public record Rejected(String reason) implements Result {}

    public record ArpResponse(byte[] frame) implements Result {
        public ArpResponse {
            frame = frame.clone();
        }

        @Override
        public byte[] frame() {
            return frame.clone();
        }
    }

    public record AdmittedTcp(long rid, TcpSegment segment) implements Result {}

    public static Result inspect(
            byte[] raw, PeerGrant grant, int localIp, byte[] localMac, int localPort) {
        if (localMac.length != 6 || localPort < 1 || localPort > 65535) {
            throw new IllegalArgumentException("invalid local service endpoint");
        }
        if (!grant.eligible()) return new Rejected("peer not authenticated in an allowed network");
        try {
            // Validates exactly as EthernetFrame/Ipv4Packet/TcpSegment.parse would, in place.
            if (raw.length < 14 || raw.length > EthernetFrame.MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("invalid Ethernet length");
            }
            int etherType = Checksum.u16(raw, 12);
            if (etherType != EthernetFrame.IPV4 && etherType != EthernetFrame.ARP)
                throw new IllegalArgumentException("unsupported EtherType");
            if (!Arrays.equals(raw, 6, 12, grant.sourceMac, 0, 6))
                return new Rejected("source MAC mismatch");
            if (etherType == EthernetFrame.ARP)
                return arp(EthernetFrame.parse(raw), grant, localIp, localMac);
            if (!Arrays.equals(raw, 0, 6, localMac, 0, 6))
                return new Rejected("unicast MAC mismatch");
            int ip = 14, available = raw.length - ip;
            if (available < 20 || (raw[ip] & 0xff) != 0x45) {
                throw new IllegalArgumentException("unsupported IPv4 version/IHL/options");
            }
            int total = Checksum.u16(raw, ip + 2);
            if (total > available || total < 40) {
                throw new IllegalArgumentException("IPv4 total length mismatch");
            }
            if ((Checksum.u16(raw, ip + 6) & 0xbfff) != 0)
                throw new IllegalArgumentException("IPv4 fragments not supported");
            if ((raw[ip + 8] & 0xff) == 0 || (raw[ip + 9] & 0xff) != 6) {
                throw new IllegalArgumentException("unsupported IPv4 TTL/protocol");
            }
            if (Checksum.onesComplement(raw, ip, 20) != 0)
                throw new IllegalArgumentException("IPv4 checksum invalid");
            int sourceIp = Checksum.u32(raw, ip + 12), destinationIp = Checksum.u32(raw, ip + 16);
            if (sourceIp != grant.sourceIp() || destinationIp != localIp) {
                return new Rejected("IPv4 address mismatch");
            }
            TcpSegment tcp = TcpSegment.parse(raw, ip + 20, total - 20, sourceIp, destinationIp);
            if (tcp.destinationPort() != localPort) return new Rejected("TCP port not exposed");
            return new AdmittedTcp(grant.rid(), tcp);
        } catch (IllegalArgumentException ex) {
            return new Rejected("malformed or unsupported packet");
        }
    }

    private static Result arp(EthernetFrame frame, PeerGrant grant, int localIp, byte[] localMac) {
        if (!isBroadcast(frame.destinationMac()) && !Arrays.equals(frame.destinationMac(), localMac)) {
            return new Rejected("ARP destination MAC invalid");
        }
        ArpPacket arp = ArpPacket.parse(frame.payload());
        if (arp.opcode() != ArpPacket.REQUEST
                || arp.targetIp() != localIp
                || arp.senderIp() != grant.sourceIp()
                || !Arrays.equals(arp.senderMac(), grant.sourceMac())) {
            return new Rejected("ARP origin/target invalid");
        }
        ArpPacket reply =
                new ArpPacket(
                        ArpPacket.REPLY, localMac, localIp, grant.sourceMac(), grant.sourceIp());
        return new ArpResponse(
                new EthernetFrame(grant.sourceMac(), localMac, EthernetFrame.ARP, reply.encode())
                        .encode());
    }

    private static boolean isBroadcast(byte[] mac) {
        for (byte octet : mac) if ((octet & 0xff) != 0xff) return false;
        return true;
    }
}
