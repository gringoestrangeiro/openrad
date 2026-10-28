package io.github.gringoestrangeiro.openradplugin.session;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.gringoestrangeiro.openradplugin.crypto.*;
import io.github.gringoestrangeiro.openradplugin.network.NetworkJoin;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ControlSessionTest {
    @TempDir Path directory;
    private static final String NAME = "Synthetic-control", NETWORK = "Synthetic 雪 🌐";
    private static final long RID = 12345;
    private static final byte[] CREDENTIAL =
            "synthetic-issued-credential".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NETWORK_PASSWORD =
            "synthetic network password\0".getBytes(StandardCharsets.UTF_8);

    private static int clientOperation(byte[] data) {
        return (int) TlvCodec.int32(field(fields(data), TlvCodec.CLIENT_OP));
    }

    private static byte[] joinBlob(byte[] request) {
        return field(fields(field(fields(request), 0x131c)), 0x0a00030e);
    }

    private static byte[] authResponse(byte[] blob) {
        return concat(
                u32(TlvCodec.SERVER_OP, 40),
                tlv(
                        0x131c,
                        concat(
                                u64(0x02000340, 7),
                                u32(0x010003be, 9),
                                u64(0x02000303, 0),
                                tlv(0x0a00030e, blob))));
    }

    private static byte[] assignedIdentity() {
        return concat(
                u32(TlvCodec.SERVER_OP, 8),
                tlv(
                        0x1233,
                        concat(
                                u64(0x020001e1, RID),
                                u32(0x01000305, 0x1a000005),
                                text(0x03000304, NAME),
                                tlv(0x09000305, new byte[16]),
                                tlv(0x0a0001c8, CREDENTIAL),
                                text(0x030001e2, "127.0.0.1"))));
    }

    @Test
    @Timeout(25)
    void redirectedRegistrationKeepsTheSameBootstrapIdentity() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
                var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())) {
            listener.setSoTimeout(10000);
            var service =
                    executor.submit(
                            () -> {
                                long bootstrapRid;
                                try (var first =
                                        SyntheticControlService.authenticate(
                                                listener.accept(),
                                                3,
                                                0,
                                                "rpassword".getBytes(StandardCharsets.US_ASCII))) {
                                    bootstrapRid = first.rid();
                                    assertEquals(20, clientOperation(first.receive()));
                                    first.send(
                                            concat(
                                                    u32(TlvCodec.SERVER_OP, 12),
                                                    tlv(0x1238, tlv(0x1234, text(0x030001c9, "127.0.0.2")))));
                                }
                                try (var second =
                                        SyntheticControlService.authenticate(
                                                listener.accept(),
                                                3,
                                                bootstrapRid,
                                                "rpassword".getBytes(StandardCharsets.US_ASCII))) {
                                    assertEquals(20, clientOperation(second.receive()));
                                    second.send(u32(TlvCodec.SERVER_OP, 21));
                                    second.send(assignedIdentity());
                                }
                                return null;
                            });
            try (Identity issued =
                    Provisioner.loadOrRegister(
                            directory,
                            NAME,
                            "127.0.0.1",
                            listener.getLocalPort(),
                            session -> {},
                            SyntheticControlService.modulus())) {
                assertEquals(RID, issued.rid());
                assertTrue(Files.isRegularFile(directory.resolve("identity.json")));
                assertFalse(Files.exists(directory.resolve("registration.pending")));
            }
            service.get(5, TimeUnit.SECONDS);
        }
    }
    @Test
    @Timeout(25)
    void localSyntheticServiceRegistersPersistsAttachesAndAuthenticatesPrivateJoin()
            throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())) {
            listener.setSoTimeout(10000);
            var service =
                    executor.submit(
                            () -> {
                                // Registration purpose 3: LoginComplete is a distinct record before
                                // Registered.
                                try (var registered =
                                        SyntheticControlService.authenticate(
                                                listener.accept(),
                                                3,
                                                0,
                                                "rpassword".getBytes(StandardCharsets.US_ASCII))) {
                                    assertEquals(20, clientOperation(registered.receive()));
                                    registered.send(u32(TlvCodec.SERVER_OP, 21));
                                    registered.send(assignedIdentity());
                                }
                                // Attachment purpose 4 repeats RSA and SH using the issued opaque
                                // credential.
                                try (var attached =
                                        SyntheticControlService.authenticate(
                                                listener.accept(), 4, RID, CREDENTIAL)) {
                                    assertEquals(20, clientOperation(attached.receive()));
                                    attached.send(
                                            concat(
                                                    u32(TlvCodec.SERVER_OP, 21),
                                                    tlv(
                                                            0x1340,
                                                            concat(
                                                                    tlv(
                                                                            0x0e00036c,
                                                                            "192.0.2.1\0"
                                                                                    .getBytes(
                                                                                            StandardCharsets
                                                                                                    .US_ASCII)),
                                                                    tlv(
                                                                            0x0e00036c,
                                                                            "198.51.100.1\0"
                                                                                    .getBytes(
                                                                                            StandardCharsets
                                                                                                    .US_ASCII)),
                                                                    tlv(
                                                                            0x0e00036c,
                                                                            "192.0.2.1\0"
                                                                                    .getBytes(
                                                                                            StandardCharsets
                                                                                                    .US_ASCII))))));
                                    attached.send(
                                            concat(
                                                    u32(TlvCodec.SERVER_OP, 38),
                                                    tlv(
                                                            0x1316,
                                                            concat(
                                                                    u32(0x01000305, 0x1a000005),
                                                                    tlv(
                                                                            0x09000305,
                                                                            new byte[16])))));
                                    byte[] join = attached.receive();
                                    assertEquals(39, clientOperation(join));
                                    var joinFields = fields(field(fields(join), 0x131c));
                                    assertEquals(
                                            NETWORK, TlvCodec.text(field(joinFields, 0x03000306)));
                                    assertEquals(0, number(joinFields, 0x0b00033f));
                                    assertEquals(7, wide(joinFields, 0x02000340));
                                    try (ShServer network =
                                            new ShServer(
                                                    NetworkJoin.networkIdentity(NETWORK),
                                                    NETWORK_PASSWORD)) {
                                        attached.send(authResponse(network.hello(joinBlob(join))));
                                        byte[] clientPublic = attached.receive();
                                        assertEquals(39, clientOperation(clientPublic));
                                        attached.send(
                                                authResponse(
                                                        network.clientPublic(
                                                                joinBlob(clientPublic))));
                                        try (var proof =
                                                network.proof(joinBlob(attached.receive()))) {
                                            attached.send(authResponse(proof.message()));
                                        }
                                    }
                                    byte[] guid = new byte[16];
                                    Arrays.fill(guid, (byte) 0x11);
                                    attached.send(
                                            concat(
                                                    u32(TlvCodec.SERVER_OP, 37),
                                                    tlv(
                                                            0x131a,
                                                            concat(
                                                                    u64(0x02000340, 7),
                                                                    u32(0x0100030c, 2),
                                                                    tlv(
                                                                            0x1316,
                                                                            tlv(
                                                                                    0x1315,
                                                                                    concat(
                                                                                            tlv(
                                                                                                    0x0d000309,
                                                                                                    guid),
                                                                                            text(
                                                                                                    0x03000306,
                                                                                                    NETWORK),
                                                                                            u32(
                                                                                                    0x0100030a,
                                                                                                    1))))))));
                                    attached.send(
                                            concat(
                                                    u32(TlvCodec.SERVER_OP, 42),
                                                    tlv(
                                                            0x131f,
                                                            tlv(
                                                                    0x131e,
                                                                    concat(
                                                                            u64(0x020001e1, 999),
                                                                            tlv(
                                                                                    0x0d000309,
                                                                                    guid))))));
                                    // A post-join record confirms that network SH did not rekey the
                                    // control stream.
                                    assertEquals(4, clientOperation(attached.receive()));
                                    attached.send(u32(TlvCodec.SERVER_OP, 4));
                                }
                                return null;
                            });
            try (Identity issued =
                    Provisioner.loadOrRegister(
                            directory,
                            NAME,
                            "127.0.0.1",
                            listener.getLocalPort(),
                            s -> {},
                            SyntheticControlService.modulus())) {
                assertEquals(RID, issued.rid());
                assertArrayEquals(CREDENTIAL, issued.password());
                assertTrue(Files.isRegularFile(directory.resolve("identity.json")));
                assertFalse(Files.exists(directory.resolve("registration.pending")));
            }
            try (Identity persisted = Identity.load(directory.resolve("identity.json"));
                    Attachment attachment =
                            Attachment.connect(
                                    persisted,
                                    listener.getLocalPort(),
                                    NAME,
                                    s -> {},
                                    SyntheticControlService.modulus());
                    NetworkJoin join =
                            new NetworkJoin(
                                    NETWORK, "synthetic network password".toCharArray(), 7, 9)) {
                assertEquals("26.0.0.5", attachment.vip().getHostAddress());
                assertEquals(2, attachment.uesHosts().size());
                attachment.session().send(join.start());
                for (int i = 0; i < 8 && !join.complete(); i++) {
                    byte[] data = attachment.session().receive();
                    byte[] reply = join.handle(data, attachment.membership());
                    if (reply != null) attachment.session().send(reply);
                }
                assertTrue(join.complete());
                assertFalse(join.pendingApproval());
                assertTrue(
                        attachment.membership().networks().values().stream()
                                .anyMatch(network -> network.name().equals(NETWORK)));
                attachment.session().send(op(4));
                assertEquals(4, TlvCodec.operation(attachment.session().receive()));
            }
            service.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void storageRetriesPreserveTheSameIssuedIdentityWithoutRegisteringAgain() throws Exception {
        Path intent = directory.resolve("registration.pending"),
                destination = directory.resolve("identity.json");
        Files.writeString(intent, "synthetic pending marker");
        AtomicInteger attempts = new AtomicInteger();
        try (Identity issued = Identity.registered(assignedIdentity())) {
            Provisioner.saveIssued(
                    issued,
                    destination,
                    intent,
                    (same, path) -> {
                        assertSame(issued, same);
                        if (attempts.incrementAndGet() < 3)
                            throw new IOException("synthetic disk unavailable");
                        same.save(path);
                    });
            assertEquals(3, attempts.get());
            assertFalse(Files.exists(intent));
            try (Identity loaded = Identity.load(destination)) {
                assertArrayEquals(issued.password(), loaded.password());
            }
        }
    }

    @Test
    void exhaustedStorageRetriesAndAmbiguousRegistrationKeepMarkerAndBlockNewRegistration()
            throws Exception {
        Path intent = directory.resolve("registration.pending");
        Files.writeString(intent, "synthetic pending marker");
        AtomicInteger attempts = new AtomicInteger();
        try (Identity issued = Identity.registered(assignedIdentity())) {
            assertThrows(
                    IOException.class,
                    () ->
                            Provisioner.saveIssued(
                                    issued,
                                    directory.resolve("identity.json"),
                                    intent,
                                    (same, path) -> {
                                        assertSame(issued, same);
                                        attempts.incrementAndGet();
                                        throw new IOException("synthetic disk unavailable");
                                    }));
            assertArrayEquals(CREDENTIAL, issued.password());
        }
        assertEquals(Provisioner.SAVE_ATTEMPTS, attempts.get());
        assertTrue(Files.exists(intent));
        assertThrows(
                IOException.class,
                () ->
                        Provisioner.loadOrRegister(
                                directory,
                                NAME,
                                "127.0.0.1",
                                1,
                                s -> fail("must not authenticate again"),
                                SyntheticControlService.modulus()));
    }

    @Test
    void uesParserRejectsMalformedAddressesAndCountsDuplicateEntriesBeforeDeduplication() {
        assertTrue(Attachment.uesHosts(u32(TlvCodec.SERVER_OP, 21)).isEmpty());
        for (String bad :
                new String[] {
                    "127.0.0.1\0",
                    "0.0.0.0\0",
                    "255.255.255.255\0",
                    "224.0.0.1\0",
                    "1.2.3\0",
                    "192.0.2.1",
                    "999.0.0.1\0",
                    "192.000.2.1\0"
                }) {
            byte[] packet =
                    concat(
                            u32(TlvCodec.SERVER_OP, 21),
                            tlv(0x1340, tlv(0x0e00036c, bad.getBytes(StandardCharsets.US_ASCII))));
            assertThrows(IllegalArgumentException.class, () -> Attachment.uesHosts(packet));
        }
        byte[] entry = tlv(0x0e00036c, "192.0.2.1\0".getBytes(StandardCharsets.US_ASCII));
        byte[][] entries = new byte[33][];
        Arrays.fill(entries, entry);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Attachment.uesHosts(
                                concat(u32(TlvCodec.SERVER_OP, 21), tlv(0x1340, concat(entries)))));
    }
}
