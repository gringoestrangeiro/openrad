package io.github.gringoestrangeiro.openradplugin.protocol;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/** Protocol constructors translated from OpenRad 2.0.2 protocol.rs. */
public final class Wire {
    private Wire() {}

    public static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) out.writeBytes(a);
        return out.toByteArray();
    }

    public static List<TlvCodec.Record> fields(byte[] b) {
        return TlvCodec.parse(b);
    }

    public static byte[] field(List<TlvCodec.Record> r, int tag) {
        return TlvCodec.field(r, tag);
    }

    public static long number(List<TlvCodec.Record> r, int tag) {
        return TlvCodec.int32(field(r, tag));
    }

    public static long wide(List<TlvCodec.Record> r, int tag) {
        return TlvCodec.int64(field(r, tag));
    }

    public static byte[] op(int op) {
        return TlvCodec.u32(TlvCodec.CLIENT_OP, op);
    }

    public static byte[] u32(int tag, long n) {
        return TlvCodec.u32(tag, n);
    }

    public static byte[] u64(int tag, long n) {
        return TlvCodec.u64(tag, n);
    }

    public static byte[] tlv(int tag, byte[] b) {
        return TlvCodec.encode(tag, b);
    }

    public static byte[] text(int tag, String s) {
        return TlvCodec.textValue(tag, s);
    }

    public static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }

    public static String guid(byte[] b) {
        require(b.length == 16, "network ID width");
        return HexFormat.of().formatHex(b);
    }

    public static Inet4Address ipv4(long ip) {
        try {
            return (Inet4Address)
                    InetAddress.getByAddress(ByteBuffer.allocate(4).putInt((int) ip).array());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static int ipInt(Inet4Address ip) {
        return ByteBuffer.wrap(ip.getAddress()).getInt();
    }

    /** Strict dotted-quad IPv4 literal: no hostname, IPv6, mapped IPv6, or leading zeros. */
    public static Inet4Address ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        require(parts.length == 4, "invalid IPv4 address");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            require(parts[i].matches("0|[1-9][0-9]{0,2}"), "invalid IPv4 address");
            int n = Integer.parseInt(parts[i]);
            require(n <= 255, "invalid IPv4 address");
            bytes[i] = (byte) n;
        }
        return ipv4(ByteBuffer.wrap(bytes).getInt());
    }

    public static InetAddress literal(String text) {
        require(
                text.length() <= 45
                        && (text.contains(":")
                                ? text.matches("[0-9a-fA-F:.]+")
                                : text.matches("[0-9.]+")),
                "numeric IP required");
        if (!text.contains(":")) {
            String[] parts = text.split("\\.", -1);
            require(parts.length == 4, "invalid IPv4 address");
            for (String part : parts) {
                require(part.matches("0|[1-9][0-9]{0,2}"), "invalid IPv4 address");
                require(Integer.parseInt(part) <= 255, "invalid IPv4 address");
            }
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid IP");
        }
    }

    public static String display(byte[] b) {
        require(
                b.length >= 2
                        && b.length <= 8192
                        && b.length % 2 == 0
                        && b[b.length - 2] == 0
                        && b[b.length - 1] == 1,
                "invalid UTF16 display");
        String raw = new String(b, 0, b.length - 2, StandardCharsets.UTF_16BE);
        StringBuilder s = new StringBuilder();
        raw.codePoints().forEach(c -> s.appendCodePoint(Character.isISOControl(c) ? 0xfffd : c));
        return s.toString();
    }

    public static byte[] login(String name, long latency, int purpose, long peer) {
        require(!name.isBlank() && purpose >= 3 && purpose <= 5, "invalid login");
        byte[] body =
                concat(
                        op(20),
                        u32(0x010003e0, 11),
                        tlv(
                                0x122f,
                                concat(
                                        u32(0x0100023b, 31),
                                        u32(0x0100025a, 0),
                                        text(0x03000304, name))),
                        tlv(0x1286, concat(u32(0x010001ec, 8), text(0x030001ed, "2.1.4951.1"))));
        if (purpose == 4) {
            long[][] values = {
                {0x01000337, 10},
                {0x01000338, 0},
                {0x01000339, 19045},
                {0x0100033a, 0},
                {0x0b00033b, 0},
                {0x0b00039f, 1},
                {0x010003c8, 0},
                {0x010003c9, 34404},
                {0x0b00033c, 1},
                {0x010003dc, 1}
            };
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            for (long[] v : values) os.writeBytes(u32((int) v[0], v[1]));
            body = concat(body, tlv(0x1331, os.toByteArray()));
        }
        if (purpose != 3 && latency > 0) body = concat(body, u32(0x01000343, latency));
        if (purpose == 5) {
            require(peer != 0, "peer RID required");
            body = concat(body, u64(0x0200030b, peer), u32(0x01000377, 0));
        }
        return body;
    }
}
