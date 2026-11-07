package io.github.gringoestrangeiro.openradplugin.protocol;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** Protocol constructors translated from OpenRad 2.0.2 protocol.rs. */
public final class Wire {
    private static final Pattern OCTET = Pattern.compile("0|[1-9][0-9]{0,2}");
    private static final Pattern IPV6_TEXT = Pattern.compile("[0-9a-fA-F:.]+");
    private static final Pattern IPV4_TEXT = Pattern.compile("[0-9.]+");

    private Wire() {}

    public static byte[] concat(byte[]... arrays) {
        int size = 0;
        for (byte[] a : arrays) size = Math.addExact(size, a.length);
        byte[] out = new byte[size];
        int at = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, at, a.length);
            at += a.length;
        }
        return out;
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
            require(OCTET.matcher(parts[i]).matches(), "invalid IPv4 address");
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
                                ? IPV6_TEXT.matcher(text).matches()
                                : IPV4_TEXT.matcher(text).matches()),
                "numeric IP required");
        if (!text.contains(":")) {
            String[] parts = text.split("\\.", -1);
            require(parts.length == 4, "invalid IPv4 address");
            for (String part : parts) {
                require(OCTET.matcher(part).matches(), "invalid IPv4 address");
                require(Integer.parseInt(part) <= 255, "invalid IPv4 address");
            }
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid IP");
        }
    }

    /**
     * Wire text of an address, exactly like Rust's {@code IpAddr} Display: dotted IPv4, and IPv6
     * compressed per RFC 5952 without a zone. {@link InetAddress#getHostAddress()} instead writes
     * the uncompressed form and, for interface addresses, a {@code %zone} suffix (for example
     * {@code 2804:...%wlan0}) that the service does not accept as a candidate address.
     */
    public static String addressText(InetAddress ip) {
        byte[] b = ip.getAddress();
        if (b.length == 4)
            return (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + "." + (b[3] & 255);
        require(b.length == 16, "invalid IP address");
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) groups[i] = ((b[2 * i] & 255) << 8) | (b[2 * i + 1] & 255);
        boolean mapped = true;
        for (int i = 0; i < 5; i++) mapped &= groups[i] == 0;
        if (mapped && groups[5] == 0xffff)
            return "::ffff:" + (b[12] & 255) + "." + (b[13] & 255) + "." + (b[14] & 255) + "."
                    + (b[15] & 255);
        // The first longest run of at least two zero groups becomes "::".
        int bestStart = -1, bestLength = 1;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < 8 && groups[i] == 0) i++;
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        StringBuilder text = new StringBuilder(39);
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                text.append("::");
                i += bestLength - 1;
                continue;
            }
            if (text.length() > 0 && text.charAt(text.length() - 1) != ':') text.append(':');
            text.append(Integer.toHexString(groups[i]));
        }
        return text.toString();
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
