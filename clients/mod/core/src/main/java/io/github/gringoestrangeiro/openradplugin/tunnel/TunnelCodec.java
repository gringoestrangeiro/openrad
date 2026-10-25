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
        byte[] header = frameHeader(frame.length);
        byte[] out = Arrays.copyOf(header, header.length + frame.length);
        System.arraycopy(frame, 0, out, header.length, frame.length);
        return out;
    }

    /** The ten envelope bytes that {@link #encodeFrame} places before a frame of this length. */
    public static byte[] frameHeader(int frameLength) {
        if (frameLength < 14 || frameLength > 65535)
            throw new IllegalArgumentException("invalid Ethernet length");
        // kind u16 0, flags u32 0, then the little-endian u32 length.
        byte[] header = new byte[10];
        header[6] = (byte) frameLength;
        header[7] = (byte) (frameLength >>> 8);
        return header;
    }

    public sealed interface Packet permits Frames, Keepalive, Other {}

    /**
     * Decoded Ethernet frames. Public construction and {@link #frames()} copy defensively; the
     * decoder hands over freshly parsed arrays without the extra copies.
     */
    public static final class Frames implements Packet {
        private final List<byte[]> frames;

        public Frames(List<byte[]> frames) {
            this.frames = copy(frames);
        }

        private Frames(List<byte[]> owned, boolean adopt) {
            frames = Collections.unmodifiableList(owned);
        }

        private static List<byte[]> copy(List<byte[]> frames) {
            List<byte[]> copy = new ArrayList<>(frames.size());
            for (byte[] frame : frames) copy.add(frame.clone());
            return Collections.unmodifiableList(copy);
        }

        public List<byte[]> frames() {
            return copy(frames);
        }

        /**
         * Read-only view of the decoded frames without copying. The caller takes ownership of
         * the arrays (no other reference exists) and must not expose them to other consumers.
         */
        public List<byte[]> ownedFrames() {
            return frames;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Frames f && frames.equals(f.frames);
        }

        @Override
        public int hashCode() {
            return frames.hashCode();
        }

        @Override
        public String toString() {
            return "Frames[frames=" + frames + "]";
        }
    }

    public record Keepalive(int sequence, boolean reply) implements Packet {}

    public record Other(int kind) implements Packet {}

    /** Validates every envelope before exposing the first frame. */
    public static Packet decode(byte[] data) {
        return decode(data, data.length);
    }

    /** {@link #decode(byte[])} of data[0, length); frames are copied out of {@code data}. */
    public static Packet decode(byte[] data, int length) {
        if (length < 2) throw new IllegalArgumentException("truncated tunnel packet");
        ByteBuffer b = ByteBuffer.wrap(data, 0, length);
        int kind = Short.toUnsignedInt(b.getShort());
        if (kind == 3 || kind == 7) {
            if (length != 6) throw new IllegalArgumentException("invalid keepalive length");
            return new Keepalive(b.getInt(), kind == 7);
        }
        if (kind != 0) return new Other(kind);
        b.order(ByteOrder.LITTLE_ENDIAN);
        List<byte[]> frames = new ArrayList<>(2);
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
        return new Frames(frames, true);
    }

    public static byte[] keepalive(int sequence, boolean reply) {
        return ByteBuffer.allocate(6).putShort((short) (reply ? 7 : 3)).putInt(sequence).array();
    }
}
