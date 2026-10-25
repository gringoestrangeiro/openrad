package io.github.gringoestrangeiro.openradclient.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Compact main menu; values remain a draft until an explicit save or connect. */
public final class OpenRadMenuScreen extends Screen {
    private final Screen parent;
    private final OpenRadController controller;
    private OpenRadMenuDraft draft;
    private boolean initialDraftPending;
    private Button connect, save, networks, automatic, advanced;

    public OpenRadMenuScreen(Screen parent, OpenRadController controller) {
        this(parent, controller, new OpenRadMenuDraft(controller.config()));
        initialDraftPending = !controller.initialized();
    }
    OpenRadMenuScreen(Screen parent, OpenRadController controller, OpenRadMenuDraft draft) {
        super(Component.literal("OpenRad"));
        this.parent = parent;
        this.controller = controller;
        this.draft = draft;
    }
    @Override protected void init() {
        int width = Math.min(360, this.width - 32), left = (this.width - width) / 2;
        int top = 70;
        connect = addRenderableWidget(Button.builder(Component.empty(), button -> {
            if (controller.active()) controller.disconnect();
            else try { controller.connect(draft.config()); }
            catch (IllegalArgumentException invalid) { }
        }).bounds(left, top, width, 20).build());
        networks = addRenderableWidget(Button.builder(Component.empty(), button ->
                minecraft.setScreenAndShow(new OpenRadNetworkScreen(this, controller, draft)))
                .bounds(left, top + 24, width, 20).build());
        automatic = addRenderableWidget(Button.builder(Component.empty(), button -> {
            draft.autoConnect = !draft.autoConnect;
            updateButtons();
        }).bounds(left, top + 48, width, 20).build());
        advanced = addRenderableWidget(Button.builder(OpenRadI18n.tr("openrad.menu.advanced"), button ->
                minecraft.setScreenAndShow(new OpenRadAdvancedScreen(this, draft)))
                .bounds(left, top + 72, width, 20).build());
        save = addRenderableWidget(Button.builder(Component.empty(), button -> {
            try { controller.save(draft.config()); }
            catch (IllegalArgumentException invalid) { }
        }).bounds(left, height - 27, (width - 6) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(left + (width + 6) / 2, height - 27, (width - 6) / 2, 20).build());
        updateButtons();
    }
    private void updateButtons() {
        if (connect == null) return;
        if (initialDraftPending && controller.initialized()) {
            draft = new OpenRadMenuDraft(controller.config());
            initialDraftPending = false;
        }
        boolean active = controller.active();
        boolean valid;
        try { draft.config(); valid = true; } catch (IllegalArgumentException invalid) { valid = false; }
        connect.active = !initialDraftPending && !controller.busy() && (active || valid && (!draft.selectors.isEmpty() || !draft.networks.isEmpty()));
        save.active = !initialDraftPending && !controller.busy() && valid;
        networks.active = automatic.active = advanced.active = !initialDraftPending && !controller.busy();
        connect.setMessage(Component.translatable(active ? "openrad.menu.disconnect" : "openrad.menu.connect"));
        save.setMessage(Component.translatable(active ? "openrad.menu.save_reconnect" : "openrad.menu.save"));
        save.setTooltip(active ? Tooltip.create(OpenRadI18n.tr("openrad.menu.reconnect_note")) : null);
        var networkChoice = new OpenRadNetworkSelection(draft);
        Component networkSummary = switch (networkChoice.mode()) {
            case NONE -> OpenRadI18n.tr("openrad.network.short.none");
            case ALL -> OpenRadI18n.tr("openrad.network.short.all");
            case LANGUAGE -> Component.literal(OpenRadNetworkLabels.name(
                    networkChoice.language(), minecraft.getLanguageManager().getSelected()));
            case SPECIFIC -> OpenRadI18n.tr("openrad.network.short.specific", networkChoice.count());
        };
        networks.setMessage(OpenRadI18n.tr("openrad.network.mainbutton", networkSummary));
        automatic.setMessage(OpenRadI18n.tr("openrad.menu.auto_connect").copy().append(": ")
                .append(Component.translatable(draft.autoConnect ? "options.on" : "options.off")));
    }
    @Override public void tick() { updateButtons(); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFF);
        var status = controller.status();
        Component phase = OpenRadI18n.tr("openrad.phase." + status.phase().toLowerCase(Locale.ROOT));
        graphics.centeredText(font, OpenRadI18n.tr("openrad.menu.status", phase), width / 2, 32, 0xD2E6E1);
        graphics.centeredText(font, OpenRadI18n.tr("openrad.menu.summary",
                status.vip().isBlank() ? "—" : status.vip(), status.networks().size(), status.peers()), width / 2, 46, 0xA5B8B3);
        String feedback = controller.messageKey();
        if (feedback.isBlank() && draft.selectors.isEmpty() && draft.networks.isEmpty()) feedback = "openrad.menu.no_networks";
        if (!feedback.isBlank()) graphics.centeredText(font, Component.translatable(feedback), width / 2, height - 44, 0xFFD37F);
        else if (controller.active()) graphics.centeredText(font,
                font.plainSubstrByWidth(OpenRadI18n.tr("openrad.menu.reconnect_note").getString(), width - 24), width / 2, height - 44, 0xA5B8B3);
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
