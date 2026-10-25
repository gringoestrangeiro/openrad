package io.github.gringoestrangeiro.openradclient;

import static org.junit.jupiter.api.Assertions.*;
import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;
import io.github.gringoestrangeiro.openradplugin.network.PublicNetworks;
import org.junit.jupiter.api.Test;
import java.util.*;

class ClientNetworkSelectionTest {
    private static List<MinecraftNetworks.Entry> catalog(String... names) {
        return MinecraftNetworks.catalog(Arrays.stream(names).map(name -> new PublicNetworks.Network(name, 1)).toList());
    }
    private static ClientConfig config(List<String> exact, List<String> selectors, int max) {
        return new ClientConfig(false, exact, 256, 16, 20, selectors, max);
    }

    @Test void knownLanguageFiltersCanBeSelectedBeforeConnecting() {
        var groups = MinecraftNetworks.knownLanguageGroups();
        assertTrue(groups.containsAll(List.of("ptbr", "english", "japanese", "spanish")));
        assertEquals(groups.size(), new HashSet<>(groups).size());
        // A selector chooses only actual names returned by the authenticated catalog.
        var offline = ClientVpnRuntime.selectNetworkNames(
                config(List.of(), List.of("all-ptbr"), 256), List.of());
        assertTrue(offline.names().isEmpty());
        assertEquals(1, offline.problems().size());
    }

    @Test void allUsesActualCatalogAcrossLanguagesAndPicksUpNewNumbersWithoutInventedNames() {
        var actual = catalog("Minecraft [Português 1]", "Minecraft [Português 3]", "Minecraft [English 2]",
                "Minecraft [日本語 9]", "Other game");
        var all = ClientVpnRuntime.selectNetworkNames(config(List.of(), List.of("all"), 256), actual);
        assertEquals(4, all.names().size());
        assertTrue(all.names().contains("Minecraft [日本語 9]"));
        assertFalse(all.names().contains("Minecraft [Português 2]"));
        var language = ClientVpnRuntime.selectNetworkNames(config(List.of(), List.of("all-ptbr"), 256), actual);
        assertEquals(Set.of("Minecraft [Português 1]", "Minecraft [Português 3]"), language.names());
        var numbered = ClientVpnRuntime.selectNetworkNames(config(List.of(), List.of("ptbr-3", "english-2"), 256), actual);
        assertEquals(Set.of("Minecraft [Português 3]", "Minecraft [English 2]"), numbered.names());
    }

    @Test void exactNamesUnionDeduplicatesAndUnknownSelectorsWarnWithoutAuthorizingGuessedNames() {
        var actual = catalog("Minecraft [Português 1]", "Minecraft [English 2]");
        var selected = ClientVpnRuntime.selectNetworkNames(config(List.of("Minecraft [Português 1]", "Explicit"),
                List.of("all-ptbr", "ptbr-99"), 3), actual);
        assertEquals(Set.of("Minecraft [Português 1]", "Explicit"), selected.names());
        assertEquals(1, selected.problems().size());
        assertTrue(selected.problems().getFirst().contains("ptbr-99"));
        assertThrows(IllegalArgumentException.class, () -> ClientVpnRuntime.selectNetworkNames(
                config(List.of("Explicit"), List.of("all"), 2), actual));
        assertThrows(IllegalArgumentException.class, () -> ClientVpnRuntime.selectNetworkNames(
                config(List.of(), List.of("all"), 1), actual));
    }
}
