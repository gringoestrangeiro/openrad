package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;
import java.util.*;

/**
 * One mutually-exclusive network choice. Unlike the old selector list,
 * "None" also removes legacy exact network names.
 * No changes to persistent config happen until the parent applies it.
 */
final class OpenRadNetworkSelection {
    enum Mode { NONE, ALL, LANGUAGE, SPECIFIC }

    private Mode mode;
    private String language = "ptbr";
    private final LinkedHashSet<String> exact = new LinkedHashSet<>();
    private final LinkedHashSet<String> selectors = new LinkedHashSet<>();

    OpenRadNetworkSelection(OpenRadMenuDraft draft) {
        exact.addAll(draft.networks);
        selectors.addAll(draft.selectors);
        if (exact.isEmpty() && selectors.isEmpty()) mode = Mode.NONE;
        else if (exact.isEmpty() && selectors.size() == 1 && selectors.contains("all")) mode = Mode.ALL;
        else if (exact.isEmpty() && selectors.size() == 1
                && selectors.iterator().next().startsWith("all-")) {
            mode = Mode.LANGUAGE;
            language = selectors.iterator().next().substring(4);
        } else mode = Mode.SPECIFIC;
    }

    Mode mode() { return mode; }
    String language() { return language; }
    List<String> exactNames() { return List.copyOf(exact); }
    List<String> individualSelectors() { return List.copyOf(selectors); }

    void none() {
        mode = Mode.NONE;
        selectors.clear();
        exact.clear();
    }

    void all() {
        mode = Mode.ALL;
        selectors.clear();
        exact.clear();
        selectors.add("all");
    }

    void language(String value) {
        // Saved or catalog groups outside the built-in list are valid; they only resolve
        // against the authenticated catalog.
        String clean = MinecraftNetworks.validateSelector(value);
        language = clean;
        mode = Mode.LANGUAGE;
        selectors.clear();
        exact.clear();
        selectors.add("all-" + clean);
    }

    void specific() {
        if (mode != Mode.SPECIFIC) {
            selectors.clear();
            exact.clear();
        }
        mode = Mode.SPECIFIC;
    }

    void toggle(MinecraftNetworks.Entry item) {
        specific();
        // When a previous profile selected the same network by exact name,
        // clicking it clears that selection instead of adding a second selector.
        if (exact.remove(item.name())) return;
        if (!selectors.remove(item.selector())) selectors.add(item.selector());
    }

    void toggleSavedSelector(String key) {
        specific();
        if (!selectors.remove(key)) selectors.add(MinecraftNetworks.validateSelector(key));
    }

    void toggleExactName(String key) {
        specific();
        if (!exact.remove(key)) exact.add(key);
    }

    void clearSpecific() {
        specific();
        selectors.clear();
        exact.clear();
    }

    boolean contains(MinecraftNetworks.Entry item) {
        return exact.contains(item.name()) || selectors.contains(item.selector());
    }

    boolean hasChoice() {
        return mode == Mode.ALL || mode == Mode.LANGUAGE
                || (mode == Mode.SPECIFIC && (!exact.isEmpty() || !selectors.isEmpty()));
    }

    int count() {
        return mode == Mode.SPECIFIC ? exact.size() + selectors.size() : hasChoice() ? 1 : 0;
    }

    void apply(OpenRadMenuDraft draft) {
        draft.networks = List.copyOf(exact);
        draft.selectors = List.copyOf(selectors);
        if (!hasChoice()) draft.autoConnect = false;
    }
}
