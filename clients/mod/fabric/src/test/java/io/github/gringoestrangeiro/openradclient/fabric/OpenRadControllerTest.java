package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradclient.ClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OpenRadControllerTest {
    @TempDir Path directory;

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

    private static void idle(OpenRadController controller) throws InterruptedException {
        long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (controller.busy() && System.nanoTime() < until) Thread.sleep(10);
        assertFalse(controller.busy(), "settings worker finished within deadline");
    }
}
