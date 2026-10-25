package io.github.gringoestrangeiro.openradplugin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

final class CommandTokensTest {
    @Test
    void quotedNetworkNamesKeepSpaces() {
        assertEquals(
                List.of("join", "Minha Rede BR", "senha 1"),
                OpenRadPlugin.tokens(new String[] {"join", "\"Minha", "Rede", "BR\"", "\"senha", "1\""}));
        assertEquals(List.of("leave", "Simple"), OpenRadPlugin.tokens(new String[] {"leave", "Simple"}));
        assertEquals(List.of("join", ""), OpenRadPlugin.tokens(new String[] {"join", "\"\""}));
        assertThrows(IllegalArgumentException.class, () -> OpenRadPlugin.tokens(new String[] {"join", "\"open"}));
    }
}
