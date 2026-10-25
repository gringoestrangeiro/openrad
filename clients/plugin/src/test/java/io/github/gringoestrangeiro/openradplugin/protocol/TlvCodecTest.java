package io.github.gringoestrangeiro.openradplugin.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class TlvCodecTest {
    @Test
    void knownRustCompatibleGoldenVector() {
        byte[] expected = {0, 0, 0, 4, 1, 0, 3, 31, 0, 0, 0, 2};
        assertArrayEquals(expected, TlvCodec.u32(TlvCodec.CLIENT_OP, 2));
        assertEquals(
                2, TlvCodec.int32(TlvCodec.field(TlvCodec.parse(expected), TlvCodec.CLIENT_OP)));
    }

    @Test
    void recognizesUtf16TerminatorAndSupplementaryCharacters() {
        String value = "Servidor 🎮";
        byte[] encoded = TlvCodec.textValue(7, value);
        assertEquals(value, TlvCodec.text(TlvCodec.field(TlvCodec.parse(encoded), 7)));
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.text(new byte[] {0, 65, 0, 0}));
        assertThrows(
                IllegalArgumentException.class,
                () -> TlvCodec.text(new byte[] {(byte) 0xd8, 0, 0, 1}));
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.textValue(7, "ola\n"));
    }

    @Test
    void refusesTruncationUnsignedLengthAndTooManyRecords() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TlvCodec.parse(
                                new byte[] {
                                    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0, 0, 0, 1
                                }));
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.parse(new byte[] {0, 0, 0, 1}));
        byte[] bomb = new byte[TlvCodec.MAX_RECORDS * 8 + 8];
        assertThrows(IllegalArgumentException.class, () -> TlvCodec.parse(bomb));
        assertThrows(
                IllegalArgumentException.class,
                () -> TlvCodec.parse(new byte[TlvCodec.MAX_PAYLOAD + 1]));
    }

    @Test
    void fieldIsSingletonAndCopiesCannotMutateParsedRecords() {
        byte[] source = TlvCodec.encode(9, new byte[] {10, 20});
        List<TlvCodec.Record> parsed = TlvCodec.parse(source);
        source[source.length - 1] = 0;
        byte[] copy = parsed.getFirst().value();
        copy[0] = 0;
        assertArrayEquals(new byte[] {10, 20}, TlvCodec.field(parsed, 9));
        byte[] doubled = new byte[20];
        System.arraycopy(TlvCodec.encode(9, new byte[] {1, 2}), 0, doubled, 0, 10);
        System.arraycopy(TlvCodec.encode(9, new byte[] {3, 4}), 0, doubled, 10, 10);
        assertThrows(
                IllegalArgumentException.class, () -> TlvCodec.field(TlvCodec.parse(doubled), 9));
    }
}
