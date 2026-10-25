package io.github.gringoestrangeiro.openradclient.smoke;

import io.netty.channel.ChannelFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes application APIs inside a separate development Minecraft. No OS UI/input automation. */
public final class OpenRadRuntimeSmoke implements ClientModInitializer {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean FINISHED = new AtomicBoolean();
    private static final Path RESULT = Path.of("openrad-smoke-result.txt");

    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("openrad.smoke")) throw new IllegalStateException("Smoke mod requires its isolated run task");
        require(FabricLoader.getInstance().getGameDir().getFileName().toString().equals("run-smoke"), "isolated game directory");
        result("RUNNING");
        Thread.ofVirtual().name("openrad-smoke-watchdog").start(() -> {
            try { Thread.sleep(45_000); }
            catch (InterruptedException ignored) { return; }
            fail(new AssertionError("runtime smoke exceeded 45 seconds"));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!client.isGameLoadFinished() || client.getOverlay() != null || !STARTED.compareAndSet(false, true)) return;
            try {
                // Fresh profiles can show onboarding; this test uses game APIs after resource load.
                if (!(client.screen instanceof TitleScreen)) client.setScreen(new TitleScreen());
                // Force every required target to load in Fabric's real Mixin transformer.
                Class.forName(Connection.class.getName(), true, Connection.class.getClassLoader());
                new ServerStatusPinger();
                var multiplayer = new JoinMultiplayerScreen(client.screen);
                MixinEnvironment.getCurrentEnvironment().audit();
                System.out.println("OPENRAD_SMOKE MIXIN_AUDIT PASS");
                client.setScreen(multiplayer);
                multiplayer.tick();
                require(Screens.getButtons(multiplayer).stream()
                        .anyMatch(button -> button.getMessage().getString().startsWith("OpenRad")), "Multiplayer OpenRad button");
                client.setScreen(new TitleScreen()); // Closes only this test's physical LAN detector.
                System.out.println("OPENRAD_SMOKE MULTIPLAYER_INIT_TICK_BUTTON PASS");
                io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod.openMenu(multiplayer);
                require(client.screen instanceof io.github.gringoestrangeiro.openradclient.fabric.OpenRadMenuScreen,
                        "OpenRad menu opens from Multiplayer");
                require(Screens.getButtons(client.screen).size() >= 6,
                        "OpenRad menu actions available");
                require(client.screen.getTitle().getString().equals("OpenRad"),
                        "branding remains OpenRad");
                System.out.println("OPENRAD_SMOKE MENU_OPEN PASS");
                var openradMenu = (io.github.gringoestrangeiro.openradclient.fabric.OpenRadMenuScreen) client.screen;
                client.setScreen(new io.github.gringoestrangeiro.openradclient.fabric.OpenRadNetworkScreen(
                        openradMenu, io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod.controller(),
                        new io.github.gringoestrangeiro.openradclient.fabric.OpenRadMenuDraft(
                                io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod.controller().config())));
                require(Screens.getButtons(client.screen).size() == 6,
                        "network screen has only four modes plus save and cancel");
                ((net.minecraft.client.gui.components.Button) Screens.getButtons(client.screen).get(1)).onPress(); // All
                require(Screens.getButtons(client.screen).get(1).getMessage().getString().startsWith("[X]"),
                        "All networks can be selected");
                ((net.minecraft.client.gui.components.Button) Screens.getButtons(client.screen).get(0)).onPress(); // None
                require(Screens.getButtons(client.screen).get(0).getMessage().getString().startsWith("[X]"),
                        "All networks can be unselected");
                ((net.minecraft.client.gui.components.Button) Screens.getButtons(client.screen).get(2)).onPress(); // Language mode
                require(Screens.getButtons(client.screen).size() == 7, "language picker button available");
                ((net.minecraft.client.gui.components.Button) Screens.getButtons(client.screen).get(4)).onPress();
                require(client.screen instanceof io.github.gringoestrangeiro.openradclient.fabric.OpenRadNetworkPickerScreen,
                        "language picker opens independently of a remote catalog");
                require(Screens.getButtons(client.screen).size() >= 6,
                        "known languages are shown as a paged list");
                System.out.println("OPENRAD_SMOKE SIMPLIFIED_NETWORK_MENU PASS");
                Thread.ofVirtual().name("openrad-smoke-connections").start(() -> {
                    try {
                        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
                        try (ServerSocket ordinary = new ServerSocket(0, 1, loopback)) {
                            InetSocketAddress unchanged = connect(new InetSocketAddress(loopback, ordinary.getLocalPort()));
                            require(unchanged.getAddress().equals(loopback)
                                    && unchanged.getPort() == ordinary.getLocalPort(), "ordinary endpoint retained");
                        }
                        System.out.println("OPENRAD_SMOKE ORDINARY_CONNECTION PASS");
                        // Default isolated config has no networks, so this must hit loopback then fail closed.
                        InetSocketAddress intercepted = connect(new InetSocketAddress(
                                InetAddress.getByAddress(new byte[]{26, (byte) 254, (byte) 253, (byte) 252}), 25565));
                        require(intercepted.getAddress().equals(loopback) && intercepted.getPort() != 25565,
                                "26/8 routed to an ephemeral IPv4 loopback endpoint");
                        System.out.println("OPENRAD_SMOKE VIRTUAL_CONNECTION_INTERCEPT PASS");
                        if (FINISHED.compareAndSet(false, true)) {
                            result("PASS: required mixins, Multiplayer init/tick/button, ordinary connection, offline 26/8 loopback interception");
                            System.out.println("OPENRAD_SMOKE RESULT PASS");
                            client.execute(client::stop);
                        }
                    } catch (Throwable failure) { fail(failure); }
                });
            } catch (Throwable failure) { fail(failure); }
        });
    }

    private static InetSocketAddress connect(InetSocketAddress target) throws Exception {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        ChannelFuture future = Connection.connect(target, false, connection);
        try {
            require(future.await(5, TimeUnit.SECONDS) && future.isSuccess(), "Netty connect succeeded");
            // Queue behind channelActive, which saves the remote address even if offline VPN closes immediately.
            var observed = future.channel().eventLoop().submit(connection::getRemoteAddress);
            require(observed.await(5, TimeUnit.SECONDS) && observed.isSuccess(), "remote address observed");
            require(observed.getNow() instanceof InetSocketAddress, "IPv4 remote address");
            return (InetSocketAddress) observed.getNow();
        } finally { future.channel().close().syncUninterruptibly(); }
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
    private static void result(String message) {
        try { Files.writeString(RESULT, message + System.lineSeparator()); }
        catch (Exception failure) { throw new IllegalStateException("cannot save smoke result", failure); }
    }
    private static void fail(Throwable failure) {
        if (!FINISHED.compareAndSet(false, true)) return;
        failure.printStackTrace();
        result("FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        System.err.println("OPENRAD_SMOKE RESULT FAIL");
        // This mod exists only in the explicitly isolated test JVM; fail Gradle even after client startup.
        System.exit(1);
    }
}
