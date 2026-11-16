package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradclient.ClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.gringoestrangeiro.openradclient.ClientVpnRuntime;
import io.github.gringoestrangeiro.openradclient.virtual.OutboundTcpStack;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OpenRadControllerTest {
    @TempDir Path directory;

    @Test void stoppingImmediatelyAfterAnAdmittedActionDoesNotRejectCleanup() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            var controller = new OpenRadController(directory, Logger.getAnonymousLogger());
            try {
                controller.initialize();
                idle(controller);
                assertTrue(controller.disconnect());
                assertDoesNotThrow(controller::close);
            } finally { controller.close(); }
        }
    }

    @Test void loadsExistingProfileAndSavesWithoutStartingVpn() throws Exception {
        ClientConfig previous = new ClientConfig(false, List.of("Minecraft [Português 1]"));
        previous.save(directory);
        try (var controller = new OpenRadController(directory, Logger.getAnonymousLogger())) {
            controller.initialize();
            idle(controller);
            assertTrue(controller.initialized());
            assertEquals(previous, controller.config());
            ClientConfig desired = new ClientConfig(false, List.of(), 128, 8, 25, List.of("all-english"), 200);
            assertTrue(controller.save(desired));
            idle(controller);
            assertEquals(desired, controller.config());
            assertEquals(desired, ClientConfig.load(directory));
            assertEquals("OFFLINE", controller.status().phase());
            assertFalse(Files.exists(directory.resolve("state/identity.json")));
        }
    }

    @Test void unchangedSaveKeepsPendingEndpointAndChangedSaveClosesIt() throws Exception {
        try (var controller = new OpenRadController(directory, Logger.getAnonymousLogger())) {
            controller.initialize();
            idle(controller);
            var target = new InetSocketAddress(InetAddress.getByAddress(new byte[]{26, 4, 5, 6}), 25565);
            assertSame(target, controller.route(target), "offline: 26.x keeps the normal path");
            var pending = controller.bridgeRoute(target);
            assertTrue(controller.save(controller.config()));
            idle(controller);
            try (Socket socket = new Socket()) {
                socket.connect(pending, 1_000);
                socket.setSoTimeout(1_000);
                assertEquals(-1, socket.getInputStream().read());
            }
            var closing = controller.bridgeRoute(target);
            assertTrue(controller.save(new ClientConfig(false, List.of(), 128, 8, 20, List.of("all"), 256)));
            idle(controller);
            assertThrows(IOException.class, () -> {
                try (Socket socket = new Socket()) { socket.connect(closing, 1_000); }
            });
            assertFalse(Files.exists(directory.resolve("state/identity.json")));
        }
    }

    @Test void changedSelectorReplacesLegacyNamesButOpeningDraftPreservesThem() {
        ClientConfig existing = new ClientConfig(false, List.of("Minecraft [Português 1]"));
        OpenRadMenuDraft draft = new OpenRadMenuDraft(existing);
        assertEquals(existing, draft.config());
        draft.select(List.of());
        assertEquals(existing.networks(), draft.config().networks());
        draft.select(List.of("all-english"));
        assertTrue(draft.config().networks().isEmpty());
        assertEquals(List.of("all-english"), draft.config().minecraftSelectors());
        assertEquals(List.of("Minecraft [Português 1]"), existing.networks());
    }

    @Test void connectingMessageDisappearsAfterVpnAttachmentOrFailure() {
        assertEquals("openrad.command.connecting",
                OpenRadController.visibleMessage("openrad.command.connecting", "CONNECTING"));
        assertEquals("",
                OpenRadController.visibleMessage("openrad.command.connecting", "JOINING_NETWORKS"));
        assertEquals("",
                OpenRadController.visibleMessage("openrad.command.connecting", "CONNECTED"));
        assertEquals("",
                OpenRadController.visibleMessage("openrad.command.connecting", "FAILED"));
        assertEquals("openrad.menu.saved",
                OpenRadController.visibleMessage("openrad.menu.saved", "CONNECTED"));
    }

    private static final class FakeEngine implements OpenRadController.Engine {
        final ClientConfig config;
        volatile boolean stopped = true;
        volatile boolean closed;
        volatile boolean connectResult = true;
        volatile String phase = "OFFLINE";
        int connectCalls;
        FakeEngine(ClientConfig config) { this.config = config; }
        public ClientVpnRuntime.Snapshot status() { return new ClientVpnRuntime.Snapshot(phase, "", List.of(), 0, ""); }
        public ClientVpnRuntime.CatalogSnapshot minecraftCatalog() {
            return new ClientVpnRuntime.CatalogSnapshot("OFFLINE", List.of(), java.util.Set.of(), List.of());
        }
        public List<ClientVpnRuntime.LanServer> lanServers() { return List.of(); }
        public OutboundTcpStack.Connection openTcp(Inet4Address destination, int port, Duration timeout) throws IOException {
            throw new IOException("fake");
        }
        public boolean connect() {
            connectCalls++;
            if (connectResult) phase = "CONNECTING";
            return connectResult;
        }
        public void disconnect() { phase = "STOPPED"; }
        public boolean awaitStopped(Duration timeout) { return stopped; }
        public void close() { closed = true; phase = "STOPPED"; }
    }

    private OpenRadController controller(List<FakeEngine> engines) {
        return new OpenRadController(directory, Logger.getAnonymousLogger(), (config, state, log) -> {
            var engine = new FakeEngine(config);
            engines.add(engine);
            return engine;
        }, Duration.ofMillis(50));
    }

    @Test void failedHandoverIsRecoverableAndNeverOpensTheIdentityWhileTheOldRuntimeRuns() throws Exception {
        new ClientConfig(false, List.of("net-a")).save(directory);
        var engines = new ArrayList<FakeEngine>();
        try (var controller = controller(engines)) {
            controller.initialize();
            idle(controller);
            assertEquals(1, engines.size());
            FakeEngine old = engines.get(0);
            old.stopped = false;
            ClientConfig second = new ClientConfig(false, List.of("net-b"));
            assertTrue(controller.save(second));
            idle(controller);
            assertEquals("openrad.menu.handover_timeout", controller.messageKey());
            assertEquals(second, controller.config(), "the saved configuration is the current one");
            assertEquals(second, ClientConfig.load(directory));
            assertTrue(old.closed);
            assertEquals("OFFLINE", controller.status().phase(), "the closed runtime is not kept installed");
            assertTrue(controller.connect(second));
            idle(controller);
            assertEquals("openrad.menu.handover_timeout", controller.messageKey());
            assertEquals(1, engines.size(), "no second owner while the first still runs");
            old.stopped = true;
            assertTrue(controller.connect(second));
            idle(controller);
            assertEquals(2, engines.size());
            assertEquals(second, engines.get(1).config);
            assertEquals(1, engines.get(1).connectCalls);
            assertEquals("openrad.command.connecting", controller.messageKey());
            assertEquals("CONNECTING", controller.status().phase());
        }
    }

    @Test void failedHandoverIsAlsoRetriedByAnUnchangedSave() throws Exception {
        new ClientConfig(false, List.of("net-a")).save(directory);
        var engines = new ArrayList<FakeEngine>();
        try (var controller = controller(engines)) {
            controller.initialize();
            idle(controller);
            engines.get(0).stopped = false;
            ClientConfig second = new ClientConfig(false, List.of("net-b"));
            assertTrue(controller.save(second));
            idle(controller);
            engines.get(0).stopped = true;
            assertTrue(controller.save(second));
            idle(controller);
            assertEquals("openrad.menu.saved", controller.messageKey());
            assertEquals(2, engines.size());
            assertEquals(second, engines.get(1).config);
        }
    }

    @Test void disconnectTimeoutHasItsOwnMessage() throws Exception {
        new ClientConfig(false, List.of("net-a")).save(directory);
        var engines = new ArrayList<FakeEngine>();
        try (var controller = controller(engines)) {
            controller.initialize();
            idle(controller);
            engines.get(0).stopped = false;
            assertTrue(controller.disconnect());
            idle(controller);
            assertEquals("openrad.menu.disconnect_timeout", controller.messageKey());
        }
    }

    @Test void aConnectThatDoesNotStartIsNotReportedAsMissingNetworks() throws Exception {
        var engines = new ArrayList<FakeEngine>();
        try (var controller = controller(engines)) {
            controller.initialize();
            idle(controller);
            engines.get(0).connectResult = false;
            assertTrue(controller.connect(controller.config()));
            idle(controller);
            assertEquals("openrad.menu.no_networks", controller.messageKey(), "the default configuration has no networks");
            ClientConfig selected = new ClientConfig(false, List.of("net-a"));
            assertTrue(controller.save(selected));
            idle(controller);
            engines.get(1).connectResult = false;
            assertTrue(controller.connect(selected));
            idle(controller);
            assertEquals("openrad.menu.not_started", controller.messageKey());
        }
    }

    @Test void connectingFromTheCommandNeverReplacesAnInvalidConfigurationFile() throws Exception {
        Path file = directory.resolve("openrad-client.properties");
        byte[] corrupt = "auto-connect=maybe\nnetwork-names=kept\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, corrupt);
        var engines = new ArrayList<FakeEngine>();
        try (var controller = controller(engines)) {
            controller.initialize();
            idle(controller);
            assertTrue(controller.invalidConfig());
            assertTrue(controller.connectSaved());
            idle(controller);
            assertEquals("openrad.menu.config_invalid", controller.messageKey());
            assertArrayEquals(corrupt, Files.readAllBytes(file));
            try (var files = Files.list(directory)) {
                assertEquals(List.of("openrad-client.properties"), files.map(path -> path.getFileName().toString()).sorted().toList());
            }
            assertTrue(engines.isEmpty());
            assertTrue(controller.invalidConfig());
        }
    }

    @Test void anExplicitSaveStillPreservesAnInvalidFileBeforeReplacingIt() throws Exception {
        Path file = directory.resolve("openrad-client.properties");
        Files.writeString(file, "auto-connect=maybe\n");
        var engines = new ArrayList<FakeEngine>();
        try (var controller = controller(engines)) {
            controller.initialize();
            idle(controller);
            assertTrue(controller.save(new ClientConfig(false, List.of("net-a"))));
            idle(controller);
            assertFalse(controller.invalidConfig());
            assertEquals(new ClientConfig(false, List.of("net-a")), ClientConfig.load(directory));
            try (var files = Files.list(directory)) {
                assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("openrad-client.properties.invalid-")).count());
            }
        }
    }

    @Test void failureAndRetryDetailsAreShownOnlyWhenActionable() {
        assertEquals("Reconnect in 5 s (x)", OpenRadController.visibleDetail("RETRY_WAIT", "Reconnect in 5 s (x)"));
        assertEquals("detail", OpenRadController.visibleDetail("FAILED", "detail"));
        assertEquals("", OpenRadController.visibleDetail("CONNECTED", "Control authenticated; 0 authenticated peer channel(s)"));
        assertEquals("", OpenRadController.visibleDetail("FAILED", null));
    }

    @Test void openingDraftReadsInitializationBeforeTheConfiguration() {
        var finished = new AtomicBoolean();
        ClientConfig defaults = new ClientConfig(false, List.of());
        var opening = OpenRadMenuDraft.opening(finished::get, () -> {
            finished.set(true);
            return defaults;
        });
        assertTrue(opening.pending(), "initialization finishing after the first read keeps the draft pending");
        assertEquals(defaults, opening.draft().config());
        assertFalse(OpenRadMenuDraft.opening(finished::get, () -> defaults).pending());
    }

    private static void idle(OpenRadController controller) throws InterruptedException {
        long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (controller.busy() && System.nanoTime() < until) Thread.sleep(10);
        assertFalse(controller.busy(), "settings worker finished within deadline");
    }
}
