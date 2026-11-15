package io.github.gringoestrangeiro.openradclient.fabric;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Screen to injected-button map. The click action handed to the button factory reaches the screen
 * only weakly, so a registry value never keeps its own key (and every closed screen) reachable.
 * Render-thread only.
 */
final class MenuButtonRegistry<S, B> {
    private final Map<S, B> buttons = new WeakHashMap<>();
    private final Consumer<S> open;

    MenuButtonRegistry(Consumer<S> open) { this.open = open; }

    B get(S screen) { return buttons.get(screen); }

    B create(S screen, Function<Runnable, B> factory) {
        WeakReference<S> owner = new WeakReference<>(screen);
        B button = factory.apply(() -> {
            S current = owner.get();
            if (current != null) open.accept(current);
        });
        buttons.put(screen, button);
        return button;
    }
}
