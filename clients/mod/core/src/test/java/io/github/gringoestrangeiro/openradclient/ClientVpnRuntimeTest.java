package io.github.gringoestrangeiro.openradclient;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.protocol.Wire;
import io.github.gringoestrangeiro.openradplugin.session.CoordinatorEndpoint;
import io.github.gringoestrangeiro.openradplugin.session.ServerDisconnect;
import io.github.gringoestrangeiro.openradplugin.session.SyntheticControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Timeout(30)
class ClientVpnRuntimeTest {
    private static final long RID = 123;
    private static final byte[] PASSWORD = "synthetic-fabric-password".getBytes(StandardCharsets.US_ASCII);
    private static final String NETWORK = "Synthetic test";
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

    @ParameterizedTest
    @ValueSource(ints = {44, 11})
    void peerRefusalOrMalformedOfferKeepsAttachmentUsable(int operation) throws Exception {
        var logs = new LinkedBlockingQueue<String>();
        var heartbeat = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var listener = new ServerSocket(0, 1, ipv4("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            configureIdentity(listener.getLocalPort());
            try (var runtime = new ClientVpnRuntime(new ClientConfig(false, List.of(NETWORK)), directory, logs::add)) {
                var server = executor.submit(() -> {
                    try (var attached = attach(listener)) {
                        byte[] request = attached.receive();
                        assertEquals(43, number(fields(request), TlvCodec.CLIENT_OP));
                        attached.send(server(operation));
                        answerCatalog(attached, request);
                        assertTrue(attached.stream().ready(Duration.ofSeconds(14)), "expected heartbeat");
                        assertEquals(4, number(fields(attached.receive()), TlvCodec.CLIENT_OP));
                        attached.send(server(17));
                        heartbeat.countDown();
                        assertThrows(EOFException.class, attached::receive);
                    }
                    return null;
                });
                assertTrue(runtime.connect());
                await(() -> runtime.status().phase().equals("CONNECTED") || server.isDone()
                                || logs.stream().anyMatch(line -> line.startsWith("Control connection interrupted")),
                        Duration.ofSeconds(5), () -> runtime.status() + " " + logs);
                if (server.isDone()) server.get();
                assertEquals("CONNECTED", runtime.status().phase(), () -> runtime.status() + " " + logs);
                assertEquals(List.of(NETWORK), runtime.status().networks());
                assertEquals("READY", runtime.minecraftCatalog().state());
                if (!heartbeat.await(15, TimeUnit.SECONDS)) {
                    if (server.isDone()) server.get();
                    fail("heartbeat left the original attachment");
                }
                assertFalse(logs.stream().anyMatch(line -> line.startsWith("Control connection interrupted")));
                runtime.close();
                assertTrue(runtime.awaitStopped(Duration.ofSeconds(5)));
                server.get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void attachmentCloseReauthenticatesWithTheSameIdentityAndBacksOffOnRepeatedCloses() throws Exception {
        var logs = new LinkedBlockingQueue<String>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var listener = new ServerSocket(0, 1, ipv4("127.0.0.1"))) {
            listener.setSoTimeout(8000);
            Path identity = configureIdentity(listener.getLocalPort());
            byte[] saved = Files.readAllBytes(identity);
            try (var runtime = new ClientVpnRuntime(new ClientConfig(false, List.of(NETWORK)), directory, logs::add)) {
                var server = executor.submit(() -> {
                    for (int i = 0; i < 2; i++) {
                        try (var attached = attach(listener)) {
                            assertEquals(43, number(fields(attached.receive()), TlvCodec.CLIENT_OP));
                            attached.send(server(16));
                            assertThrows(EOFException.class, attached::receive);
                        }
                    }
                    return null;
                });
                assertTrue(runtime.connect());
                String first = logs.poll(8, TimeUnit.SECONDS);
                if (server.isDone()) server.get();
                String second = logs.poll(8, TimeUnit.SECONDS);
                if (server.isDone()) server.get();
                assertNotNull(first, "first close was not reported");
                assertNotNull(second, () -> "second close was not reported after " + first + ": " + runtime.status());
                assertTrue(first.contains("ServerDisconnect: service disconnected session during Minecraft catalog query"), first);
                assertTrue(first.endsWith("retry in 3s"), first);
                assertTrue(second.contains("ServerDisconnect: service disconnected session"), second);
                assertTrue(second.endsWith("retry in 6s"), second);
                server.get(5, TimeUnit.SECONDS);
                assertArrayEquals(saved, Files.readAllBytes(identity));
                assertFalse(Files.exists(identity.resolveSibling("registration.pending")));
                runtime.disconnect();
                assertTrue(runtime.awaitStopped(Duration.ofSeconds(5)));
                try (var file = FileChannel.open(identity.resolveSibling("identity.lock"), StandardOpenOption.WRITE);
                     var lock = file.tryLock()) {
                    assertNotNull(lock, "disconnect must release the old identity lock");
                }
            }
        }
    }

    @Test
    void replacedAttachmentDuringLoginStopsAndPreservesTheIdentity() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var listener = new ServerSocket(0, 1, ipv4("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            Path identity = configureIdentity(listener.getLocalPort());
            byte[] saved = Files.readAllBytes(identity);
            try (var runtime = new ClientVpnRuntime(new ClientConfig(false, List.of(NETWORK)), directory, ignored -> {})) {
                var server = executor.submit(() -> {
                    try (var attached = SyntheticControlService.authenticate(listener.accept(), 4, RID, PASSWORD)) {
                        assertEquals(20, number(fields(attached.receive()), TlvCodec.CLIENT_OP));
                        attached.send(concat(server(ServerDisconnect.OPERATION),
                                u32(ServerDisconnect.REASON_TAG, ServerDisconnect.REASON_REPLACED)));
                        assertThrows(EOFException.class, attached::receive);
                    }
                    return null;
                });
                assertTrue(runtime.connect());
                server.get(5, TimeUnit.SECONDS);
                assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)), "a replaced identity must stop retrying");
                assertEquals("FAILED", runtime.status().phase());
                assertTrue(runtime.status().detail().contains("another client"));
                assertArrayEquals(saved, Files.readAllBytes(identity));
                assertFalse(Files.exists(identity.resolveSibling("registration.pending")));
            }
        }
    }

    private Path configureIdentity(int port) throws IOException {
        Files.writeString(directory.resolve("coordinator.properties"), "host=127.0.0.1\nport=" + port);
        Files.write(directory.resolve("modulus.bin"), SyntheticControlService.modulus());
        Path state = CoordinatorEndpoint.load(directory, "127.0.0.1", port).identityDirectory();
        Files.createDirectories(state);
        Path identityFile = state.resolve("identity.json");
        try (var identity = new Identity(RID, ipv4("26.0.0.1"), "Synthetic fabric", new byte[16], PASSWORD, "127.0.0.1")) {
            identity.save(identityFile);
        }
        return identityFile;
    }

    private static SyntheticControlService.Authenticated attach(ServerSocket listener) throws IOException {
        var attached = SyntheticControlService.authenticate(listener.accept(), 4, RID, PASSWORD);
        try {
            assertEquals(20, number(fields(attached.receive()), TlvCodec.CLIENT_OP));
            attached.send(server(21));
            byte[] network = tlv(0x1315, concat(tlv(0x0d000309, new byte[16]),
                    text(0x03000306, NETWORK), u32(0x0100030a, 1)));
            attached.send(concat(server(38), tlv(0x1316, concat(
                    u32(0x01000305, 0x1a000001), tlv(0x09000305, new byte[16]), network))));
            attached.stream().sustain();
            return attached;
        } catch (IOException | RuntimeException | AssertionError failure) {
            attached.close();
            throw failure;
        }
    }

    private static void answerCatalog(SyntheticControlService.Authenticated attached, byte[] request)
            throws IOException {
        long id = wide(fields(field(fields(request), 0x1332)), 0x02000340);
        attached.send(concat(server(45), tlv(0x1334, concat(u64(0x02000340, id), u64(0x0200030f, 0)))));
    }

    private static byte[] server(int operation) {
        return u32(TlvCodec.SERVER_OP, operation);
    }

    private static void await(BooleanSupplier condition, Duration timeout, Supplier<String> detail) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), detail);
    }
}
