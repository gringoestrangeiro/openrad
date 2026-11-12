package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradclient.ClientVpnRuntime;
import io.netty.channel.ChannelFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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

import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Logger;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Fabric UI adapter; disk, runtime handover and network shutdown belong to the controller worker. */
public final class OpenRadClientMod implements ClientModInitializer {
    private static final Logger LOG = Logger.getLogger("OpenRad Client Mod");
    private static volatile OpenRadController controller;
    private boolean menuRequested;
    /** Render-thread only; weak so closed screens and their buttons are collected. */
    private static final java.util.Map<Screen, WeakReference<Button>> MENU_BUTTONS = new java.util.WeakHashMap<>();

    private static Button menuButton(Screen screen) {
        var button = MENU_BUTTONS.get(screen);
        return button == null ? null : button.get();
    }

    public static boolean usesVirtualRoute(String host) {
        OpenRadController current = controller;
        return current != null && current.active() && LoopbackBridge.isVirtualLiteral(host);
    }

    public static OpenRadController controller() { return controller; }
    /** Without an OpenRad session, 26.x addresses keep the normal path (e.g. official Radmin VPN). */
    public static ChannelFuture connect(InetSocketAddress destination, boolean legacyPing,
                                        Function<InetSocketAddress, ChannelFuture> connect) {
        OpenRadController current = controller;
        return current == null ? connect.apply(destination) : current.connect(destination, legacyPing, connect);
    }
    public static List<ClientVpnRuntime.LanServer> lanServers() {
        OpenRadController current = controller;
        return current == null ? List.of() : current.lanServers();
    }
    public static void openMenu(Screen parent) {
        OpenRadController current = controller;
        if (current != null) Minecraft.getInstance().setScreen(new OpenRadMenuScreen(parent, current));
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
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!menuRequested) return;
            menuRequested = false;
            openMenu(null);
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof JoinMultiplayerScreen || screen instanceof TitleScreen || screen instanceof PauseScreen)) return;
            // A window resize re-runs AFTER_INIT, but screens that only reposition their layout
            // (JoinMultiplayerScreen on 1.21+/26.x) keep their widgets: move the existing button
            // instead of stacking another one at every resize.
            Button existing = menuButton(screen);
            if (existing != null && screen.children().contains(existing)) {
                existing.setX(width - 112);
                existing.setY(5);
                return;
            }
            Button button = Button.builder(Component.literal("OpenRad"), clicked -> openMenu(screen))
                    .bounds(width - 112, 5, 106, 20).build();
            Screens.getButtons(screen).add(button);
            MENU_BUTTONS.put(screen, new WeakReference<>(button));
            // Rebuild the tooltip only when the phase changes, not on every client tick.
            String[] shown = {null};
            ScreenEvents.afterTick(screen).register(ignored -> {
                if (menuButton(screen) != button) return; // replaced by a full re-init
                String current = controller.status().phase();
                if (current.equals(shown[0])) return;
                shown[0] = current;
                button.setTooltip(Tooltip.create(OpenRadI18n.tr("openrad.menu.status", phase(current))));
            });
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("openrad")
                .executes(ctx -> show(ctx.getSource()))
                .then(literal("status").executes(ctx -> show(ctx.getSource())))
                .then(literal("menu").executes(ctx -> {
                    // ChatScreen clears itself after dispatching the command.
                    menuRequested = true;
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
