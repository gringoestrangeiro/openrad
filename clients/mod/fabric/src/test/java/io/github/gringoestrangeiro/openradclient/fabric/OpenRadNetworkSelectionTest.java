package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradclient.ClientConfig;
import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;
import io.github.gringoestrangeiro.openradplugin.network.PublicNetworks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenRadNetworkSelectionTest {
    private static ClientConfig profile(boolean auto, List<String> names, List<String> filters) {
        return new ClientConfig(auto, names, 256, 16, 20, filters, 256);
    }

    @Test void noneClearsHiddenLegacyExactNameAndDisablesAutoConnect() {
        var draft = new OpenRadMenuDraft(profile(true,
                List.of("Minecraft [Português 1]"), List.of()));
        var selection = new OpenRadNetworkSelection(draft);
        assertEquals(OpenRadNetworkSelection.Mode.SPECIFIC, selection.mode());
        selection.none();
        assertEquals(0, selection.count());
        selection.apply(draft);
        assertFalse(draft.autoConnect);
        assertTrue(draft.config().networks().isEmpty());
        assertTrue(draft.config().minecraftSelectors().isEmpty());
    }

    @Test void allAndLanguageAreMutuallyExclusiveAndPersist() {
        var draft = new OpenRadMenuDraft(profile(false, List.of(), List.of("all")));
        var selection = new OpenRadNetworkSelection(draft);
        assertEquals(OpenRadNetworkSelection.Mode.ALL, selection.mode());
        selection.language("ptbr");
        selection.apply(draft);
        assertEquals(List.of("all-ptbr"), draft.config().minecraftSelectors());
        assertTrue(draft.config().networks().isEmpty());
        selection.all();
        selection.apply(draft);
        assertEquals(List.of("all"), draft.config().minecraftSelectors());
        selection.none();
        selection.apply(draft);
        assertTrue(draft.config().minecraftSelectors().isEmpty());
    }

    @Test void specificItemTogglesOnOffWithNoPhantomSelections() {
        var entry = MinecraftNetworks.catalog(List.of(
                new PublicNetworks.Network("Minecraft [English 2]", 1))).getFirst();
        var draft = new OpenRadMenuDraft(profile(false, List.of(), List.of("english-2")));
        var choice = new OpenRadNetworkSelection(draft);
        assertEquals(OpenRadNetworkSelection.Mode.SPECIFIC, choice.mode());
        assertTrue(choice.contains(entry));
        choice.toggle(entry);
        assertFalse(choice.contains(entry));
        assertFalse(choice.hasChoice());
        choice.apply(draft);
        assertTrue(draft.config().minecraftSelectors().isEmpty());
        choice.toggle(entry);
        assertTrue(choice.contains(entry));
        assertTrue(choice.hasChoice());
    }

    @Test void togglingExistingExactNetworkActuallyRemovesIt() {
        var entry = MinecraftNetworks.catalog(List.of(
                new PublicNetworks.Network("Minecraft [English 2]", 1))).getFirst();
        var draft = new OpenRadMenuDraft(profile(false, List.of(entry.name()), List.of()));
        var selection = new OpenRadNetworkSelection(draft);
        selection.toggle(entry);
        assertFalse(selection.contains(entry));
        selection.apply(draft);
        assertTrue(draft.config().networks().isEmpty());
        assertTrue(draft.config().minecraftSelectors().isEmpty());
    }

    @Test void selectionIsOnlyPersistedAfterExplicitApply() {
        var original = profile(false, List.of(), List.of("all-ptbr"));
        var draft = new OpenRadMenuDraft(original);
        var choice = new OpenRadNetworkSelection(draft);
        choice.none();
        assertEquals(original, draft.config(), "Cancel leaves the saved draft unchanged");
        choice.apply(draft);
        assertTrue(draft.config().minecraftSelectors().isEmpty());
    }
}
