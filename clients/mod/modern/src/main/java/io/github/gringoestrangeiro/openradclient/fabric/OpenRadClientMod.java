package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradclient.ClientVpnRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Fabric UI adapter; disk, runtime handover and network shutdown belong to the controller worker. */
public final class OpenRadClientMod implements ClientModInitializer {
    private static final Logger LOG = Logger.getLogger("OpenRad Client Mod");
    private static volatile OpenRadController controller;

    public static OpenRadController controller() { return controller; }
    /** Without an OpenRad session, 26.x addresses keep the normal path (e.g. official Radmin VPN). */
    public static InetSocketAddress route(InetSocketAddress destination) {
        OpenRadController current = controller;
        return current == null ? destination : current.route(destination);
    }
    /**
     * The legacy ping runs on the render thread, where an exception crashes the game. If the
     * bridge cannot take the connection, fail fast on a loopback port nothing can accept rather
     * than leaving the VPN.
     */
    public static InetSocketAddress routeLegacyPing(InetSocketAddress destination) {
        try { return route(destination); }
        catch (RuntimeException unavailable) { return LoopbackBridge.UNREACHABLE; }
    }
    public static List<ClientVpnRuntime.LanServer> lanServers() {
        OpenRadController current = controller;
        return current == null ? List.of() : current.lanServers();
    }
    public static void openMenu(Screen parent) {
        OpenRadController current = controller;
        if (current != null) Minecraft.getInstance().setScreenAndShow(new OpenRadMenuScreen(parent, current));
    }
    public static Component phase(String phase) {
        return OpenRadI18n.tr("openrad.phase." + phase.toLowerCase(Locale.ROOT));
    }
    private static Component feedback(String key, Object... arguments) {
        return Component.literal("[OpenRad] ").append(Component.translatable(key, arguments));
    }

    @Override public void onInitializeClient() {
        controller = new OpenRadController(FabricLoader.getInstance().getConfigDir().resolve("openrad-client"), LOG);
        controller.initialize();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> controller.close());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof JoinMultiplayerScreen || screen instanceof TitleScreen || screen instanceof PauseScreen)) return;
            Button button = Button.builder(Component.literal("OpenRad"), clicked -> openMenu(screen))
                    .bounds(width - 112, 5, 106, 20).build();
            ((io.github.gringoestrangeiro.openradclient.fabric.mixin.ScreenWidgetInvoker) screen).openrad$addRenderableWidget(button);
            ScreenEvents.afterTick(screen).register(ignored -> button.setTooltip(Tooltip.create(
                    OpenRadI18n.tr("openrad.menu.status", phase(controller.status().phase())))));
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("openrad")
                .executes(ctx -> show(ctx.getSource()))
                .then(literal("status").executes(ctx -> show(ctx.getSource())))
                .then(literal("menu").executes(ctx -> {
                    var client = ctx.getSource().getClient();
                    client.execute(() -> openMenu(null));
                    return 1;
                }))
                .then(literal("networks").executes(ctx -> {
                    ctx.getSource().sendFeedback(feedback("openrad.command.networks", String.join(", ", controller.status().networks())));
                    return 1;
                }))
                .then(literal("connect").executes(ctx -> {
                    boolean queued = controller.connect(controller.config());
                    ctx.getSource().sendFeedback(feedback(queued ? "openrad.command.connecting" : "openrad.command.already_connecting"));
                    return 1;
                }))
                .then(literal("disconnect").executes(ctx -> {
                    boolean queued = controller.disconnect();
                    ctx.getSource().sendFeedback(feedback(queued ? "openrad.menu.busy" : "openrad.command.already_connecting"));
                    return 1;
                }))));
        LOG.info("OpenRad Client Mod initialized; configuration and networking run in background.");
    }

    private int show(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        var status = controller.status();
        source.sendFeedback(feedback("openrad.command.status", phase(status.phase()),
                status.vip().isBlank() ? "—" : status.vip(), status.networks().size(), status.peers()));
        String message = controller.messageKey();
        if (!message.isBlank()) source.sendFeedback(feedback(message));
        return 1;
    }
}
