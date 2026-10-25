package io.github.gringoestrangeiro.openradplugin.crypto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Bounded identity-only JSON reader, avoiding a runtime dependency on Bukkit's JSON implementation.
 */
final class IdentityJson {
    private final String text;
    private int offset;

    private IdentityJson(String text) {
        this.text = text;
    }

    static Map<String, Object> parse(String text) {
        IdentityJson parser = new IdentityJson(text);
        Map<String, Object> result = parser.object(0);
        parser.space();
        Crypto.require(parser.offset == text.length(), "invalid identity JSON");
        return result;
    }

    private Map<String, Object> object(int depth) {
        checkDepth(depth);
        expect('{');
        Map<String, Object> values = new HashMap<>();
        if (take('}')) return values;
        do {
            space();
            String key = string();
            expect(':');
            Object value = value(depth + 1);
            Crypto.require(!values.containsKey(key), "duplicate identity JSON field");
            values.put(key, value);
        } while (take(','));
        expect('}');
        return values;
    }

    private Object value(int depth) {
        checkDepth(depth);
        space();
        Crypto.require(offset < text.length(), "truncated identity JSON");
        char c = text.charAt(offset);
        if (c == '"') return string();
        if (c == '{') return object(depth + 1);
        if (c == '[') {
            offset++;
            if (!take(']')) {
                do {
                    value(depth + 1);
                } while (take(','));
                expect(']');
            }
            return Boolean.FALSE;
        }
        for (String literal : new String[] {"true", "false", "null"}) {
            if (text.startsWith(literal, offset)) {
                offset += literal.length();
                return Boolean.FALSE;
            }
        }
        int start = offset;
        if (c == '-') offset++;
        Crypto.require(offset < text.length(), "invalid identity JSON number");
        if (text.charAt(offset) == '0') offset++;
        else {
            Crypto.require(
                    text.charAt(offset) >= '1' && text.charAt(offset) <= '9',
                    "invalid identity JSON number");
            while (offset < text.length() && asciiDigit(text.charAt(offset))) offset++;
        }
        boolean decimal = false;
        if (offset < text.length() && text.charAt(offset) == '.') {
            decimal = true;
            offset++;
            digits();
        }
        if (offset < text.length() && (text.charAt(offset) == 'e' || text.charAt(offset) == 'E')) {
            decimal = true;
            offset++;
            if (offset < text.length()
                    && (text.charAt(offset) == '+' || text.charAt(offset) == '-')) offset++;
            digits();
        }
        String number = text.substring(start, offset);
        return decimal ? new BigDecimal(number) : new BigInteger(number);
    }

    private void digits() {
        int start = offset;
        while (offset < text.length() && asciiDigit(text.charAt(offset))) offset++;
        Crypto.require(offset > start, "invalid identity JSON number");
    }

    private static boolean asciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (offset < text.length()) {
            char c = text.charAt(offset++);
            if (c == '"') {
                String result = out.toString();
                for (int i = 0; i < result.length(); i++) {
                    if (Character.isHighSurrogate(result.charAt(i))) {
                        Crypto.require(
                                i + 1 < result.length()
                                        && Character.isLowSurrogate(result.charAt(++i)),
                                "invalid identity JSON Unicode");
                    } else
                        Crypto.require(
                                !Character.isLowSurrogate(result.charAt(i)),
                                "invalid identity JSON Unicode");
                }
                return result;
            }
            Crypto.require(c >= 0x20, "invalid identity JSON string");
            if (c != '\\') {
                out.append(c);
                continue;
            }
            Crypto.require(offset < text.length(), "truncated identity JSON escape");
            char escape = text.charAt(offset++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    Crypto.require(offset + 4 <= text.length(), "truncated identity JSON escape");
                    String hex = text.substring(offset, offset + 4);
                    Crypto.require(hex.matches("[0-9a-fA-F]{4}"), "invalid identity JSON escape");
                    out.append((char) Integer.parseInt(hex, 16));
                    offset += 4;
                }
                default -> throw new IllegalArgumentException("invalid identity JSON escape");
            }
        }
        throw new IllegalArgumentException("unterminated identity JSON string");
    }

    private void space() {
        while (offset < text.length() && " \t\r\n".indexOf(text.charAt(offset)) >= 0) offset++;
    }

    private boolean take(char c) {
        space();
        if (offset < text.length() && text.charAt(offset) == c) {
            offset++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        Crypto.require(take(c), "invalid identity JSON syntax");
    }

    private static void checkDepth(int depth) {
        Crypto.require(depth <= 32, "identity JSON nesting too deep");
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
