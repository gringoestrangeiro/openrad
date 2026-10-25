package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * A dedicated, paged list instead of crowding the main four-choice screen.
 * "Specific networks" items are ONLY catalog entries or existing saved names.
 */
public final class OpenRadNetworkPickerScreen extends Screen {
    private record Item(String caption, MinecraftNetworks.Entry entry,
                        String savedSelector, String savedExactName) {}

    private final OpenRadNetworkScreen parent;
    private final OpenRadController controller;
    private final OpenRadNetworkSelection choice;
    private final boolean languages;
    private int page;
    private String signature = "";
    private List<Item> items = List.of();

    OpenRadNetworkPickerScreen(OpenRadNetworkScreen parent, OpenRadController controller,
                               OpenRadNetworkSelection choice, boolean languages) {
        super(Component.literal("OpenRad"));
        this.parent = parent;
        this.controller = controller;
        this.choice = choice;
        this.languages = languages;
    }

    private List<Item> items() {
        String locale = minecraft.getLanguageManager().getSelected();
        if (languages) {
            LinkedHashSet<String> options = new LinkedHashSet<>(MinecraftNetworks.knownLanguageGroups());
            options.add(choice.language());
            var groups = options.stream().sorted(Comparator.comparing(
                    code -> OpenRadNetworkLabels.name(code, locale))).toList();
            return groups.stream().map(code -> new Item(
                    OpenRadNetworkLabels.name(code, locale), null, code, null)).toList();
        }

        var listing = controller.catalog();
        List<MinecraftNetworks.Entry> entries = listing == null ? List.of() : listing.entries();
        List<Item> results = new ArrayList<>();
        Set<String> available = new HashSet<>();
        for (MinecraftNetworks.Entry entry : entries) {
            available.add(entry.selector());
            results.add(new Item(OpenRadNetworkLabels.name(entry.language(), locale)
                    + "  #" + entry.number(), entry, null, null));
        }
        // Saved filters and custom names must always be removable, even offline.
        for (String selector : choice.individualSelectors()) {
            if (!available.contains(selector))
                results.add(new Item(selector, null, selector, null));
        }
        Set<String> availableExact = new HashSet<>();
        for (MinecraftNetworks.Entry entry : entries) availableExact.add(entry.name());
        for (String name : choice.exactNames()) {
            if (!availableExact.contains(name)) results.add(new Item(name, null, null, name));
        }
        return List.copyOf(results);
    }

    private String signature() {
        var catalog = controller.catalog();
        return languages ? "languages" : catalog == null ? "empty"
                : catalog.entries().toString();
    }

    private int rows() { return Math.max(1, (height - 121) / 23); }

    private void goBack() { minecraft.setScreenAndShow(parent); }

    @Override protected void init() {
        signature = signature();
        items = items();
        int panel = Math.min(380, width - 24), left = (width - panel) / 2;
        int rows = rows();
        int pages = Math.max(1, (items.size() + rows - 1) / rows);
        page = Math.clamp(page, 0, pages - 1);
        for (int i = page * rows; i < Math.min(items.size(), (page + 1) * rows); i++) {
            Item item = items.get(i);
            boolean checked = languages
                    ? choice.language().equals(item.savedSelector())
                    : item.entry() != null ? choice.contains(item.entry())
                      : item.savedSelector() != null
                          ? choice.individualSelectors().contains(item.savedSelector())
                          : choice.exactNames().contains(item.savedExactName());
            String mark = checked ? "[X]  " : "[ ]  ";
            Button option = addRenderableWidget(Button.builder(
                    Component.literal(mark + item.caption()), b -> {
                        if (languages) {
                            choice.language(item.savedSelector());
                            goBack();
                        } else {
                            if (item.entry() != null) choice.toggle(item.entry());
                            else if (item.savedSelector() != null) choice.toggleSavedSelector(item.savedSelector());
                            else choice.toggleExactName(item.savedExactName());
                            rebuildWidgets();
                        }
                    }).bounds(left, 48 + (i % rows) * 23, panel, 20).build());
            if (item.entry() != null)
                option.setTooltip(Tooltip.create(Component.literal(item.entry().name())));
        }

        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            rebuildWidgets();
        }).bounds(left, height - 53, 38, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            rebuildWidgets();
        }).bounds(left + panel - 38, height - 53, 38, 20).build()).active = page < pages - 1;

        if (!languages) {
            addRenderableWidget(Button.builder(OpenRadI18n.tr("openrad.network.clear"), b -> {
                choice.clearSpecific();
                rebuildWidgets();
            }).bounds(left, height - 27, (panel - 6) / 2, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> goBack())
                .bounds(languages ? left : left + (panel + 6) / 2,
                        height - 27, languages ? panel : (panel - 6) / 2, 20).build());
    }

    @Override public void tick() {
        if (!signature().equals(signature)) rebuildWidgets();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float dt) {
        super.extractRenderState(graphics, mx, my, dt);
        graphics.centeredText(font, title, width / 2, 9, 0xFFFFFFFF);
        graphics.centeredText(font,
                OpenRadI18n.tr(languages ? "openrad.network.pick.language"
                        : "openrad.network.pick.specific"), width / 2, 25, 0xFFC2D6DB);
        if (!languages && items.isEmpty()) {
            graphics.centeredText(font, font.plainSubstrByWidth(
                    OpenRadI18n.tr("openrad.network.catalog.none").getString(), width - 24),
                    width / 2, 75, 0xFFFFCF8A);
        }
        graphics.centeredText(font, OpenRadI18n.tr("openrad.network.pagination",
                page + 1, Math.max(1, (items.size() + rows() - 1) / rows())),
                width / 2, height - 47, 0xFFB5D6D0);
    }

    @Override public void onClose() { goBack(); }
    @Override public boolean isPauseScreen() { return false; }
}
