package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradclient.ClientConfig;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenRadAdvancedScreenTest {
    @Test void openingLongSavedNamesOrSelectorsPreservesEveryEntry() throws Exception {
        var names = new ArrayList<String>();
        for (int i = 0; i < 18; i++) names.add("network-" + i + "x".repeat(240));
        var selectors = new ArrayList<String>();
        for (int i = 0; i < 40; i++) selectors.add("selector-" + i + "x".repeat(116));
        for (boolean exactNames : new boolean[]{true, false}) {
            List<String> entries = exactNames ? names : selectors;
            var original = new ClientConfig(false, exactNames ? entries : List.of(),
                    256, 16, 20, exactNames ? List.of() : entries, 256);
            var screen = new OpenRadAdvancedScreen(null, new OpenRadMenuDraft(original));
            var field = OpenRadAdvancedScreen.class.getDeclaredMethod("field",
                    int.class, int.class, int.class, String.class, String.class, int.class);
            field.setAccessible(true);
            // The actual Minecraft EditBox needs no renderer/font to retain text.
            var box = (EditBox) field.invoke(screen, 0, 0, 100, "openrad.menu.exact_networks",
                    String.join(";", entries), 4096);
            List<String> retained = Arrays.stream(box.getValue().split(";", -1))
                    .map(String::trim).toList();
            var applied = new ClientConfig(false, exactNames ? retained : List.of(),
                    256, 16, 20, exactNames ? List.of() : retained, 256);
            assertEquals(original, applied, exactNames ? "exact names" : "selectors");
        }
    }
}
