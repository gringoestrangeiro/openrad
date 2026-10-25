package io.github.gringoestrangeiro.openradclient;

import static org.junit.jupiter.api.Assertions.*;
import io.github.gringoestrangeiro.openradplugin.protocol.Wire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;

class ClientVpnRuntimeTest {
    @TempDir Path directory;

    @Test void freshUnconfiguredRuntimeDoesNotStartOrOpenGameTraffic() {
        try (ClientVpnRuntime runtime = new ClientVpnRuntime(new ClientConfig(false, List.of()), directory, ignored -> {})) {
            assertFalse(runtime.connect());
            assertEquals("OFFLINE", runtime.status().phase());
            assertTrue(runtime.lanServers().isEmpty());
            assertThrows(IOException.class, () -> runtime.openTcp(Wire.ipv4(0x1a000002), 25565, Duration.ofSeconds(1)));
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
            runtime.close();
            assertFalse(runtime.connect());
        } catch (IOException error) { fail(error); }
    }

    @Test void lockedIdentityFailsBeforeAnyRegistrationAndShutdownPreventsReconnect() throws Exception {
        try (FileChannel file = FileChannel.open(directory.resolve("identity.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = file.lock();
             ClientVpnRuntime runtime = new ClientVpnRuntime(new ClientConfig(false, List.of("Synthetic test")), directory, ignored -> {})) {
            assertTrue(runtime.connect());
            long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!runtime.status().phase().equals("FAILED") && System.nanoTime() < until) Thread.sleep(10);
            assertEquals("FAILED", runtime.status().phase());
            assertFalse(Files.exists(directory.resolve("registration.pending")));
            assertFalse(Files.exists(directory.resolve("identity.json")));
            runtime.close();
            assertFalse(runtime.connect());
        }
    }
}
