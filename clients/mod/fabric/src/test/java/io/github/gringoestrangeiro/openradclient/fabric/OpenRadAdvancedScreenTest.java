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
            var screen = headlessScreen();
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

    private static OpenRadAdvancedScreen headlessScreen() throws Exception {
        // Screen constructors from 1.21.11 need the running Minecraft singleton.
        // This test exercises field/EditBox, with only the widget lists it uses.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        var instance = unsafeClass.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        var screen = (OpenRadAdvancedScreen) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(instance.get(null), OpenRadAdvancedScreen.class);
        for (Class<?> type = screen.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    field.set(screen, new ArrayList<>());
                }
            }
        }
        return screen;
    }
}
