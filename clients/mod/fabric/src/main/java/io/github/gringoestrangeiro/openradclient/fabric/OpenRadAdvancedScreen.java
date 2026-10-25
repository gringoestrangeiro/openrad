package io.github.gringoestrangeiro.openradclient.fabric;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/** Technical limits and exact names stay out of the default flow. */
public final class OpenRadAdvancedScreen extends Screen {
    private final OpenRadMenuScreen parent;
    private final OpenRadMenuDraft draft;
    private EditBox peers, connections, timeout, maxNetworks, names, selectors;
    private boolean invalid;

    OpenRadAdvancedScreen(OpenRadMenuScreen parent, OpenRadMenuDraft draft) {
        super(Component.literal("OpenRad"));
        this.parent = parent;
        this.draft = draft;
    }
    @Override protected void init() {
        int panel = Math.min(400, width - 24), left = (width - panel) / 2, half = (panel - 12) / 2;
        String oldPeers = peers == null ? Integer.toString(draft.maxPeers) : peers.getValue();
        String oldConnections = connections == null ? Integer.toString(draft.maxConnections) : connections.getValue();
        String oldTimeout = timeout == null ? Integer.toString(draft.timeout) : timeout.getValue();
        String oldMax = maxNetworks == null ? Integer.toString(draft.maxNetworks) : maxNetworks.getValue();
        String oldNames = names == null ? String.join(";", draft.networks) : names.getValue();
        String oldSelectors = selectors == null ? String.join(";", draft.selectors) : selectors.getValue();
        peers = field(left, 43, half, "openrad.menu.max_peers", oldPeers, 4);
        connections = field(left + half + 12, 43, half, "openrad.menu.max_connections", oldConnections, 3);
        timeout = field(left, 87, half, "openrad.menu.timeout", oldTimeout, 3);
        maxNetworks = field(left + half + 12, 87, half, "openrad.menu.max_networks", oldMax, 4);
        names = field(left, 131, panel, "openrad.menu.exact_networks", oldNames, 4096);
        selectors = field(left, 175, panel, "openrad.menu.selectors_technical", oldSelectors, 4096);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> apply())
                .bounds(left, height - 27, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + half + 12, height - 27, half, 20).build());
        setInitialFocus(peers);
    }
    private EditBox field(int x, int y, int width, String key, String value, int limit) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.translatable(key));
        box.setMaxLength(limit);
        box.setValue(value);
        return addRenderableWidget(box);
    }
    private static List<String> split(String value) {
        return value.isBlank() ? List.of() : Arrays.stream(value.split(";", -1)).map(String::trim).toList();
    }
    private void apply() {
        try {
            var config = new io.github.gringoestrangeiro.openradclient.ClientConfig(draft.autoConnect,
                    split(names.getValue()), Integer.parseInt(peers.getValue()), Integer.parseInt(connections.getValue()),
                    Integer.parseInt(timeout.getValue()), split(selectors.getValue()), Integer.parseInt(maxNetworks.getValue()));
            draft.maxPeers = config.maxPeers();
            draft.maxConnections = config.maxConnections();
            draft.timeout = config.connectionTimeoutSeconds();
            draft.maxNetworks = config.maxNetworks();
            draft.networks = config.networks();
            draft.selectors = config.minecraftSelectors();
            minecraft.setScreen(parent);
        } catch (IllegalArgumentException failure) { invalid = true; }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        for (var item : List.of(new Object[]{peers, "openrad.menu.max_peers"}, new Object[]{connections, "openrad.menu.max_connections"},
                new Object[]{timeout, "openrad.menu.timeout"}, new Object[]{maxNetworks, "openrad.menu.max_networks"},
                new Object[]{names, "openrad.menu.exact_networks"}, new Object[]{selectors, "openrad.menu.selectors_technical"})) {
            EditBox box = (EditBox) item[0];
            graphics.drawString(font, font.plainSubstrByWidth(Component.translatable((String) item[1]).getString(), box.getWidth()),
                    box.getX(), box.getY() - 11, 0xD2E6E1);
        }
        if (invalid) graphics.drawCenteredString(font, OpenRadI18n.tr("openrad.menu.config_error"), width / 2, height - 42, 0xFF8585);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
