package io.github.gringoestrangeiro.openradclient.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Intentionally simple: choose ONE mode, then save it directly.
 * Detailed language/network lists are separate, paged screens.
 */
public final class OpenRadNetworkScreen extends Screen {
    private final OpenRadMenuScreen parent;
    private final OpenRadController controller;
    private final OpenRadMenuDraft draft;
    private final OpenRadNetworkSelection choice;
    private String warning = "";

    public OpenRadNetworkScreen(OpenRadMenuScreen parent, OpenRadController controller, OpenRadMenuDraft draft) {
        super(Component.literal("OpenRad"));
        this.parent = parent;
        this.controller = controller;
        this.draft = draft;
        this.choice = new OpenRadNetworkSelection(draft);
    }

    @Override protected void init() {
        int panel = Math.min(350, width - 24);
        int left = (width - panel) / 2;
        modeButton(left, panel, 45, OpenRadNetworkSelection.Mode.NONE,
                "openrad.network.mode.none", () -> choice.none());
        modeButton(left, panel, 69, OpenRadNetworkSelection.Mode.ALL,
                "openrad.network.mode.all", () -> choice.all());
        modeButton(left, panel, 93, OpenRadNetworkSelection.Mode.LANGUAGE,
                "openrad.network.mode.language", () -> choice.language(choice.language()));
        modeButton(left, panel, 117, OpenRadNetworkSelection.Mode.SPECIFIC,
                "openrad.network.mode.specific", choice::specific);

        if (choice.mode() == OpenRadNetworkSelection.Mode.LANGUAGE) {
            addRenderableWidget(Button.builder(OpenRadI18n.tr("openrad.network.language.choice",
                    OpenRadNetworkLabels.name(choice.language(), minecraft.getLanguageManager().getSelected())),
                    b -> minecraft.setScreenAndShow(new OpenRadNetworkPickerScreen(this, controller, choice, true)))
                    .bounds(left, 148, panel, 20).build());
        } else if (choice.mode() == OpenRadNetworkSelection.Mode.SPECIFIC) {
            addRenderableWidget(Button.builder(OpenRadI18n.tr("openrad.network.specific.choice",
                    choice.count()), b -> minecraft.setScreenAndShow(new OpenRadNetworkPickerScreen(
                            this, controller, choice, false)))
                    .bounds(left, 148, panel, 20).build());
        }
        addRenderableWidget(Button.builder(OpenRadI18n.tr("openrad.network.apply"), b -> save())
                .bounds(left, height - 27, (panel - 6) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + (panel + 6) / 2, height - 27, (panel - 6) / 2, 20).build());
    }

    private void modeButton(int left, int panel, int y, OpenRadNetworkSelection.Mode mode,
                            String textKey, Runnable select) {
        Component label = Component.literal(choice.mode() == mode ? "[X]  " : "[ ]  ")
                .append(OpenRadI18n.tr(textKey));
        addRenderableWidget(Button.builder(label, b -> {
            select.run();
            warning = "";
            rebuildWidgets();
        }).bounds(left, y, panel, 20).build());
    }

    private void save() {
        if (controller.busy()) {
            warning = "openrad.menu.busy";
            return;
        }
        // A saved profile with no network is also NOT allowed to auto-reconnect.
        choice.apply(draft);
        try {
            if (controller.save(draft.config())) minecraft.setScreenAndShow(parent);
            else warning = "openrad.menu.busy";
        } catch (IllegalArgumentException invalid) {
            warning = "openrad.menu.config_error";
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float partialTick) {
        super.extractRenderState(graphics, x, y, partialTick);
        graphics.centeredText(font, title, width / 2, 10, 0xffffff);
        graphics.centeredText(font, OpenRadI18n.tr("openrad.network.subtitle"),
                width / 2, 28, 0xC2D6DB);
        String hint = switch (choice.mode()) {
            case NONE -> "openrad.network.summary.none";
            case ALL -> "openrad.network.summary.all";
            case LANGUAGE -> "openrad.network.summary.language";
            case SPECIFIC -> "openrad.network.summary.specific";
        };
        Component detail = switch (choice.mode()) {
            case LANGUAGE -> OpenRadI18n.tr(hint,
                    OpenRadNetworkLabels.name(choice.language(), minecraft.getLanguageManager().getSelected()));
            case SPECIFIC -> OpenRadI18n.tr(hint, choice.count());
            default -> OpenRadI18n.tr(hint);
        };
        if (!warning.isEmpty()) detail = OpenRadI18n.tr(warning);
        graphics.centeredText(font, font.plainSubstrByWidth(detail.getString(),
                width - 24), width / 2, height - 43, 0xFFCF8A);
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
