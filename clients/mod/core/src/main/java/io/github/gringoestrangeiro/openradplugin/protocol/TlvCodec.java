package io.github.gringoestrangeiro.openradplugin.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class TlvCodec {
    public static final int SERVER_OP = 0x01000320;
    public static final int CLIENT_OP = 0x0100031f;
    public static final int MAX_PAYLOAD = 4 * 1024 * 1024;
    public static final int MAX_RECORDS = 16384;

    private TlvCodec() {}

    public record Record(int tag, byte[] value) {
        public Record {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    public static List<Record> parse(byte[] data) {
        if (data.length > MAX_PAYLOAD)
            throw new IllegalArgumentException("TLV payload exceeds limit");
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        List<Record> records = new ArrayList<>();
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 8 || records.size() >= MAX_RECORDS) {
                throw new IllegalArgumentException("invalid TLV header/count");
            }
            long size = Integer.toUnsignedLong(buffer.getInt());
            int tag = buffer.getInt();
            if (size > buffer.remaining()) throw new IllegalArgumentException("truncated TLV body");
            byte[] value = new byte[(int) size];
            buffer.get(value);
            records.add(new Record(tag, value));
        }
        return List.copyOf(records);
    }

    public static Optional<byte[]> optional(List<Record> records, int tag) {
        byte[] found = null;
        for (Record record : records) {
            if (record.tag() == tag) {
                if (found != null)
                    throw new IllegalArgumentException(
                            "duplicate singleton " + Integer.toHexString(tag));
                found = record.value();
            }
        }
        return Optional.ofNullable(found);
    }

    public static byte[] field(List<Record> records, int tag) {
        return optional(records, tag)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "missing field " + Integer.toHexString(tag)));
    }

    public static long int32(byte[] bytes) {
        if (bytes.length != 4) throw new IllegalArgumentException("invalid u32 width");
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt());
    }

    public static long int64(byte[] bytes) {
        if (bytes.length != 8) throw new IllegalArgumentException("invalid u64 width");
        return ByteBuffer.wrap(bytes).getLong();
    }

    public static byte[] encode(int tag, byte[] value) {
        if (value.length > MAX_PAYLOAD - 8)
            throw new IllegalArgumentException("TLV body exceeds limit");
        ByteBuffer out = ByteBuffer.allocate(8 + value.length).order(ByteOrder.BIG_ENDIAN);
        out.putInt(value.length).putInt(tag).put(value);
        return out.array();
    }

    public static byte[] u32(int tag, long value) {
        if (value < 0 || value > 0xffff_ffffL)
            throw new IllegalArgumentException("u32 outside range");
        return encode(tag, ByteBuffer.allocate(4).putInt((int) value).array());
    }

    public static byte[] u64(int tag, long value) {
        return encode(tag, ByteBuffer.allocate(8).putLong(value).array());
    }

    public static byte[] textValue(int tag, String value) {
        rejectControls(value);
        byte[] encoded;
        try {
            ByteBuffer data =
                    StandardCharsets.UTF_16BE
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(value));
            encoded = new byte[data.remaining()];
            data.get(encoded);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("invalid UTF16 input", e);
        }
        if (encoded.length > 512) throw new IllegalArgumentException("text too long");
        byte[] terminated = Arrays.copyOf(encoded, encoded.length + 2);
        terminated[terminated.length - 1] = 1;
        return encode(tag, terminated);
    }

    public static String text(byte[] bytes) {
        if (bytes.length < 2
                || bytes.length > 8192
                || (bytes.length & 1) != 0
                || bytes[bytes.length - 2] != 0
                || bytes[bytes.length - 1] != 1) {
            throw new IllegalArgumentException("invalid UTF16 text");
        }
        try {
            String decoded =
                    StandardCharsets.UTF_16BE
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, 0, bytes.length - 2))
                            .toString();
            rejectControls(decoded);
            return decoded;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("invalid UTF16 encoding", e);
        }
    }

    public static int operation(byte[] payload) {
        return Math.toIntExact(int32(field(parse(payload), SERVER_OP)));
    }

    private static void rejectControls(String value) {
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("control character in text");
        }
    }
}
