package io.github.gringoestrangeiro.openradclient;

/** Matches Cyrillic writing in received Minecraft MOTDs, not a server's country. */
public final class ServerMotdFilter {
    private ServerMotdFilter() {}

    public static boolean hasCyrillic(String motd) {
        return motd != null && motd.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.CYRILLIC);
    }
}
