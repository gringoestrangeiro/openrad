package io.github.gringoestrangeiro.openradclient.fabric;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MenuButtonRegistryTest {
    private record Button(Runnable onPress) {}

    @Test void clickOpensTheMenuWithTheScreenTheButtonBelongsTo() {
        List<Object> opened = new ArrayList<>();
        var registry = new MenuButtonRegistry<Object, Button>(opened::add);
        Object screen = new Object();
        Button button = registry.create(screen, Button::new);
        assertSame(button, registry.get(screen));
        button.onPress().run();
        assertEquals(List.of(screen), opened);
    }

    @Test void registryEntryDoesNotKeepItsScreenReachable() throws Exception {
        var registry = new MenuButtonRegistry<Object, Button>(screen -> { });
        Object screen = new Object();
        WeakReference<Object> reference = new WeakReference<>(screen);
        Button button = registry.create(screen, Button::new);
        screen = null;
        long until = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (reference.get() != null && System.nanoTime() < until) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(reference.get(), "a screen owning only a registry button must be collectable");
        assertNotNull(button);
    }

    @Test void clickAfterTheScreenIsGoneDoesNothing() throws Exception {
        List<Object> opened = new ArrayList<>();
        var registry = new MenuButtonRegistry<Object, Button>(opened::add);
        Object screen = new Object();
        WeakReference<Object> reference = new WeakReference<>(screen);
        Button button = registry.create(screen, Button::new);
        screen = null;
        long until = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (reference.get() != null && System.nanoTime() < until) {
            System.gc();
            Thread.sleep(20);
        }
        button.onPress().run();
        assertTrue(opened.isEmpty());
    }
}
