package io.github.gringoestrangeiro.openradplugin.tunnel;

import static io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.Record;

import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Service negotiation and validated uncompressed Ethernet envelopes. */
public final class TunnelCodec {
    private TunnelCodec() {}

    public static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) length = Math.addExact(length, value.length);
        ByteBuffer out = ByteBuffer.allocate(length);
        for (byte[] value : values) out.put(value);
        return out.array();
    }

    public static byte[] mac(Inet4Address ip) {
        byte[] a = ip.getAddress();
        return new byte[] {2, 0x1a, a[0], a[1], a[2], a[3]};
    }

    public static byte[] syn(long rid, byte[] mac) {
        checkMac(mac);
        return concat(u32(0x01000302, 8), encode(0x09000304, mac), u64(0x020001e1, rid));
    }

    public record Negotiated(byte[] response, byte[] mac, int version) {
        public Negotiated {
            response = response.clone();
            mac = mac.clone();
        }

        @Override
        public byte[] response() {
            return response.clone();
        }

        @Override
        public byte[] mac() {
            return mac.clone();
        }
    }

    private static void checkMac(byte[] mac) {
        if (mac.length != 6 || (mac[0] & 1) != 0)
            throw new IllegalArgumentException("invalid service MAC");
    }

    private static int version(List<Record> f) {
        int version = (int) Math.min(8, int32(field(f, 0x01000302)));
        if (version < 5) throw new IllegalArgumentException("unsupported service version");
        return version;
    }

    public static Negotiated synack(byte[] data) {
        List<Record> f = parse(data);
        if (!Arrays.equals(field(f, 0x02000303), new byte[8]))
            throw new IllegalArgumentException("peer service refused");
        int v = version(f);
        byte[] m = field(f, 0x09000304);
        checkMac(m);
        return new Negotiated(concat(u64(0x02000303, 0), u32(0x01000302, v)), m, v);
    }

    public static Negotiated acceptSyn(byte[] data, long peerRid, byte[] ownMac) {
        List<Record> f = parse(data);
        if (int64(field(f, 0x020001e1)) != peerRid)
            throw new IllegalArgumentException("service peer identity mismatch");
        int v = version(f);
        byte[] m = field(f, 0x09000304);
        checkMac(m);
        checkMac(ownMac);
        return new Negotiated(
                concat(u64(0x02000303, 0), u32(0x01000302, v), encode(0x09000304, ownMac)), m, v);
    }

    public static void acceptAck(byte[] data, int version) {
        List<Record> f = parse(data);
        if (!Arrays.equals(field(f, 0x02000303), new byte[8])
                || int32(field(f, 0x01000302)) != version)
            throw new IllegalArgumentException("service acknowledgement mismatch");
    }

    public static byte[] encodeFrame(byte[] frame) {
        if (frame.length < 14 || frame.length > 65535)
            throw new IllegalArgumentException("invalid Ethernet length");
        return ByteBuffer.allocate(frame.length + 10)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 0)
                .putInt(0)
                .putInt(frame.length)
                .put(frame)
                .array();
    }

    public sealed interface Packet permits Frames, Keepalive, Other {}

    public record Frames(List<byte[]> frames) implements Packet {
        public Frames {
            frames = frames.stream().map(byte[]::clone).toList();
        }

        @Override
        public List<byte[]> frames() {
            return frames.stream().map(byte[]::clone).toList();
        }
    }

    public record Keepalive(int sequence, boolean reply) implements Packet {}

    public record Other(int kind) implements Packet {}

    /** Validates every envelope before exposing the first frame. */
    public static Packet decode(byte[] data) {
        if (data.length < 2) throw new IllegalArgumentException("truncated tunnel packet");
        ByteBuffer b = ByteBuffer.wrap(data);
        int kind = Short.toUnsignedInt(b.getShort());
        if (kind == 3 || kind == 7) {
            if (data.length != 6) throw new IllegalArgumentException("invalid keepalive length");
            return new Keepalive(b.getInt(), kind == 7);
        }
        if (kind != 0) return new Other(kind);
        b.order(ByteOrder.LITTLE_ENDIAN);
        List<byte[]> frames = new ArrayList<>();
        while (b.hasRemaining()) {
            if (b.remaining() < 22)
                throw new IllegalArgumentException("truncated Ethernet envelope");
            int flags = b.getInt();
            long len = Integer.toUnsignedLong(b.getInt());
            if (flags != 0 || len < 14 || len > 65535 || len > b.remaining())
                throw new IllegalArgumentException("invalid Ethernet flags/length");
            byte[] frame = new byte[(int) len];
            b.get(frame);
            frames.add(frame);
        }
        if (frames.isEmpty()) throw new IllegalArgumentException("empty Ethernet packet");
        return new Frames(frames);
    }

    public static byte[] keepalive(int sequence, boolean reply) {
        return ByteBuffer.allocate(6).putShort((short) (reply ? 7 : 3)).putInt(sequence).array();
    }
}
