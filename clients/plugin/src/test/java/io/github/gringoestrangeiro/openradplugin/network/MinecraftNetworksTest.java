package io.github.gringoestrangeiro.openradplugin.network;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.config.MinecraftNetworkSettings;

import org.junit.jupiter.api.Test;

import java.util.*;

class MinecraftNetworksTest {
    @Test
    void generatedYamlListsEveryGroupAndIndividualSelector() {
        var catalog =
                MinecraftNetworks.catalog(
                        List.of(
                                network("Português 1"),
                                network("Português 2"),
                                network("English 1")));
        String yaml = MinecraftNetworks.optionsYaml(catalog);
        for (String selector : MinecraftNetworks.selectors(catalog))
            assertTrue(yaml.contains("  - \"" + selector + "\"\n"));
        assertTrue(yaml.contains("all-ptbr"));
        assertTrue(yaml.contains("all-english"));
    }

    private static PublicNetworks.Network network(String label) {
        return new PublicNetworks.Network("Minecraft [" + label + "]", 37);
    }

    @Test
    void resolvesEveryNumberAndDeduplicatesOverlappingGroups() {
        var catalog =
                MinecraftNetworks.catalog(
                        List.of(
                                network("Português 10"),
                                network("Português 3"),
                                network("Português 1"),
                                network("Português 2"),
                                network("English 1"),
                                network("English 2")));
        assertEquals(
                List.of("ptbr-1", "ptbr-2", "ptbr-3", "ptbr-10"),
                MinecraftNetworks.select(catalog, List.of("all-ptbr"), 10).networks().stream()
                        .map(MinecraftNetworks.Entry::selector)
                        .toList());
        var numbers =
                MinecraftNetworks.select(catalog, List.of("ptbr-2", "ptbr-3", "english-2"), 10);
        assertEquals(
                List.of(
                        "Minecraft [Português 2]",
                        "Minecraft [Português 3]",
                        "Minecraft [English 2]"),
                numbers.networks().stream().map(MinecraftNetworks.Entry::name).toList());
        assertEquals(
                6,
                MinecraftNetworks.select(catalog, List.of("all", "ptbr-2", "all-english"), 10)
                        .networks()
                        .size());
        assertEquals(
                List.of("ptbr-99"),
                MinecraftNetworks.select(catalog, List.of("ptbr-99"), 10).unmatched());
        assertThrows(
                IllegalArgumentException.class,
                () -> MinecraftNetworks.select(catalog, List.of("all"), 5));
    }

    @Test
    void preservesRealUnicodeNamesWhileOfferingReadableAliasesForAllLanguages() {
        var labels = new LinkedHashMap<String, String>();
        labels.put("Tiếng Việt 12", "vietnamese-12");
        labels.put("Русский 30", "russian-30");
        labels.put("中文 14", "chinese-14");
        labels.put("日本語", "japanese-1");
        labels.put("한국어", "korean-1");
        labels.put("Українська 2", "ukrainian-2");
        labels.put("Español 2", "spanish-2");
        labels.put("Français", "french-1");
        labels.put("Ελληνικα", "greek-1");
        labels.put("العربية", "arabic-1");
        labels.put("Română", "romanian-1");
        labels.put("Češky", "czech-1");
        var catalog =
                MinecraftNetworks.catalog(
                        labels.keySet().stream().map(MinecraftNetworksTest::network).toList());
        for (var label : labels.entrySet()) {
            var selected = MinecraftNetworks.select(catalog, List.of(label.getValue()), 20);
            assertTrue(selected.unmatched().isEmpty(), label.toString());
            assertEquals(
                    "Minecraft [" + label.getKey() + "]", selected.networks().getFirst().name());
        }
        assertEquals(
                labels.size(),
                MinecraftNetworks.select(catalog, List.of("all"), 20).networks().size());
        assertTrue(MinecraftNetworks.selectors(catalog).contains("all-chinese"));
        // Future languages remain selectable without a code update.
        var future = MinecraftNetworks.catalog(List.of(network("Future Language 7")));
        assertEquals("future-language-7", future.getFirst().selector());
        assertEquals(
                1,
                MinecraftNetworks.select(future, List.of("all-future-language"), 20)
                        .networks()
                        .size());
    }

    @Test
    void excludesOtherGamesAndControlInjectionAndValidatesConfigLimits() {
        var catalog =
                MinecraftNetworks.catalog(
                        List.of(
                                new PublicNetworks.Network("Other game", 2),
                                new PublicNetworks.Network("Minecraft\nforged status", 1),
                                network("English 1"),
                                network("English 1"),
                                new PublicNetworks.Network("Minecraft Dungeons", 3)));
        assertEquals(2, catalog.size());
        assertEquals(
                1,
                MinecraftNetworks.select(catalog, List.of("minecraft-dungeons"), 10)
                        .networks()
                        .size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MinecraftNetworkSettings(false, List.of("all"), 256));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MinecraftNetworkSettings(true, List.of("all"), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MinecraftNetworkSettings(true, List.of("../all"), 256));
        assertEquals(
                List.of("all-english"),
                new MinecraftNetworkSettings(true, List.of(" ALL-ENGLISH ", "all-english"), 256)
                        .select());
    }
}
