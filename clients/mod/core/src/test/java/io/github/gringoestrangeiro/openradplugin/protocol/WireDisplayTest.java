package io.github.gringoestrangeiro.openradplugin.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WireDisplayTest {
    private static byte[] wire(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_16BE);
        byte[] out = new byte[body.length + 2];
        System.arraycopy(body, 0, out, 0, body.length);
        out[out.length - 1] = 1;
        return out;
    }

    @Test
    void peerNamesNeutraliseLegacyColourAndFormattingCodes() {
        assertEquals("Admin�c�lOP", Wire.displayName(wire("Admin§c§lOP")));
        assertEquals("�k�r", Wire.displayName(wire("§k§r")));
    }

    @Test
    void peerNamesNeutraliseBidiOverridesIsolatesAndFormatCharacters() {
        for (int c : new int[] {0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067,
                0x2068, 0x2069, 0x200b, 0x200d, 0x200e, 0x200f, 0x061c, 0xfeff, 0xad}) {
            assertEquals(
                    "a�b",
                    Wire.displayName(wire("a" + new String(Character.toChars(c)) + "b")),
                    Integer.toHexString(c));
        }
    }

    @Test
    void peerNamesNeutraliseControlsAndLineSeparators() {
        assertEquals("a�b�c�d�e",
                Wire.displayName(wire("a\nb c d\u0085e")));
    }

    @Test
    void peerNamesKeepOrdinaryTextAndAstralCharacters() {
        String name = "José 山田 😀 אב";
        assertEquals(name, Wire.displayName(wire(name)));
    }

    @Test
    void peerNamesAreCappedWithoutSplittingSurrogatePairs() {
        String long64 = "x".repeat(63) + "😀";
        assertEquals(long64, Wire.displayName(wire(long64 + "extra")));
        assertEquals(64, Wire.displayName(wire("y".repeat(500))).length());
    }

    @Test
    void networkNamesUsedAsKeysKeepEverythingButIsoControls() {
        assertEquals("Net§a‍‮", Wire.display(wire("Net§a‍‮")));
        assertEquals("a�b", Wire.display(wire("a\nb")));
    }

    @Test
    void presentationNeutralisationAppliesToAnyText() {
        assertEquals("�a�b", Wire.neutralised("§a‮b"));
        assertEquals("plain", Wire.neutralised("plain"));
    }
}
