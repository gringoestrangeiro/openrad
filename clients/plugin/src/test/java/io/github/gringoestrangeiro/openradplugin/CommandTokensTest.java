package io.github.gringoestrangeiro.openradplugin;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
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

    @Test
    void completionEscapesQuotedAndBackslashedNetworkNames() {
        for (String name : List.of("Simple", "Shared \"Network\"", "Our \\ Network", "File\\Alias")) {
            assertEquals(List.of("leave", name),
                    OpenRadPlugin.tokens(new String[] {"leave", OpenRadPlugin.quoteCommandToken(name)}));
        }
    }

    @Test
    void onlyConsoleSendersCanSupplyAPassword() {
        assertTrue(OpenRadPlugin.acceptsNetworkPassword(sender(ConsoleCommandSender.class)));
        assertTrue(OpenRadPlugin.acceptsNetworkPassword(sender(RemoteConsoleCommandSender.class)));
        assertFalse(OpenRadPlugin.acceptsNetworkPassword(sender(BlockCommandSender.class)));
        assertFalse(OpenRadPlugin.acceptsNetworkPassword(sender(ProxiedCommandSender.class)));
    }

    private static CommandSender sender(Class<? extends CommandSender> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> { throw new AssertionError(method); }));
    }

    @Test
    void backslashInsideQuotesEscapesOnlyQuoteAndBackslash() {
        assertEquals(List.of("join", "My Net", "pa ss\\word"),
                OpenRadPlugin.tokens(new String[] {"join", "\"My", "Net\"", "\"pa", "ss\\word\""}));
        assertEquals(List.of("a b\\c"), OpenRadPlugin.tokens(new String[] {"\"a b\\c\""}));
        assertEquals(List.of("say \"hi\""), OpenRadPlugin.tokens(new String[] {"\"say \\\"hi\\\"\""}));
        assertEquals(List.of("x\\y"), OpenRadPlugin.tokens(new String[] {"\"x\\\\y\""}));
        assertEquals(List.of("trail\\"), OpenRadPlugin.tokens(new String[] {"\"trail\\\\\""}));
        assertThrows(IllegalArgumentException.class,
                () -> OpenRadPlugin.tokens(new String[] {"\"dangling\\"}));
        assertThrows(IllegalArgumentException.class,
                () -> OpenRadPlugin.tokens(new String[] {"\"open\\\""}));
    }

    @Test
    void backslashOutsideQuotesIsLiteral() {
        assertEquals(List.of("join", "C:\\dir\\x", "a\\b"),
                OpenRadPlugin.tokens(new String[] {"join", "C:\\dir\\x", "a\\b"}));
    }

    @Test
    void switchValuesAcceptOnOffAndTheCliTrueFalse() {
        for (String value : List.of("on", "ON", "true", "True"))
            assertEquals(Boolean.TRUE, OpenRadPlugin.switchValue(value));
        for (String value : List.of("off", "Off", "false", "FALSE"))
            assertEquals(Boolean.FALSE, OpenRadPlugin.switchValue(value));
        for (String value : List.of("", "yes", "1", "ontem"))
            assertNull(OpenRadPlugin.switchValue(value));
    }
}
