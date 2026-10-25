package io.github.gringoestrangeiro.openradplugin.security;

import io.github.gringoestrangeiro.openradplugin.link.ArpPacket;
import io.github.gringoestrangeiro.openradplugin.link.EthernetFrame;
import io.github.gringoestrangeiro.openradplugin.link.Ipv4Packet;
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
            EthernetFrame frame = EthernetFrame.parse(raw);
            if (!Arrays.equals(frame.sourceMac(), grant.sourceMac()))
                return new Rejected("source MAC mismatch");
            if (frame.etherType() == EthernetFrame.ARP) {
                if (!isBroadcast(frame.destinationMac())
                        && !Arrays.equals(frame.destinationMac(), localMac)) {
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
                                ArpPacket.REPLY,
                                localMac,
                                localIp,
                                grant.sourceMac(),
                                grant.sourceIp());
                return new ArpResponse(
                        new EthernetFrame(
                                        grant.sourceMac(),
                                        localMac,
                                        EthernetFrame.ARP,
                                        reply.encode())
                                .encode());
            }
            if (!Arrays.equals(frame.destinationMac(), localMac))
                return new Rejected("unicast MAC mismatch");
            Ipv4Packet ip = Ipv4Packet.parse(frame.payload());
            if (ip.sourceIp() != grant.sourceIp() || ip.destinationIp() != localIp) {
                return new Rejected("IPv4 address mismatch");
            }
            TcpSegment tcp = TcpSegment.parse(ip.tcpBytes(), ip.sourceIp(), ip.destinationIp());
            if (tcp.destinationPort() != localPort) return new Rejected("TCP port not exposed");
            return new AdmittedTcp(grant.rid(), tcp);
        } catch (IllegalArgumentException ex) {
            return new Rejected("malformed or unsupported packet");
        }
    }

    private static boolean isBroadcast(byte[] mac) {
        for (byte octet : mac) if ((octet & 0xff) != 0xff) return false;
        return true;
    }
}
