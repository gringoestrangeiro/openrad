package io.github.gringoestrangeiro.openradplugin.session;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Registration never retries after a possibly issued identity. The marker survives restarts. */
public final class Provisioner {
    private Provisioner() {}

    public static Identity loadOrRegister(
            Path directory, String name, String host, int port, Consumer<ControlSession> owner)
            throws IOException {
        return loadOrRegister(directory, name, host, port, owner, ServiceConfig.modulus());
    }

    /**
     * Public-key injection supports isolated synthetic services without changing production
     * defaults.
     */
    public static Identity loadOrRegister(
            Path directory,
            String name,
            String host,
            int port,
            Consumer<ControlSession> owner,
            byte[] modulus)
            throws IOException {
        Path identity = directory.resolve("identity.json"),
                intent = directory.resolve("registration.pending");
        if (Files.exists(identity, LinkOption.NOFOLLOW_LINKS)) {
            Identity loaded = Identity.load(identity);
            Files.deleteIfExists(intent);
            return loaded;
        }
        if (Files.exists(intent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException(
                    "registration outcome unknown: preserve registration.pending and recover"
                        + " identity before another registration");
        Set<String> visited = new HashSet<>();
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        for (int attempt = 0; attempt < 3; attempt++) {
            require(visited.add(host), "registration redirect loop");
            try (Identity bootstrap = Identity.bootstrap(name, host);
                    ControlSession session =
                            ControlSession.authenticate(
                                    host,
                                    port,
                                    bootstrap,
                                    3,
                                    Duration.ofNanos(Math.max(1, until - System.nanoTime())),
                                    modulus,
                                    owner)) {
                writeIntent(intent);
                session.send(login(name, 0, 3, 0));
                byte[] reply = session.receive();
                if (TlvCodec.operation(reply) == 12) {
                    var f = fields(field(fields(field(fields(reply), 0x1238)), 0x1234));
                    host = TlvCodec.text(field(f, 0x030001c9));
                    try {
                        ipv4(host);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                "registration redirect host must be numeric IPv4", e);
                    }
                    Files.delete(intent);
                    continue;
                }
                require(TlvCodec.operation(reply) == 21, "expected provisioning LoginComplete");
                Identity registered = Identity.registered(session.receive());
                try {
                    saveIssued(registered, identity, intent, Identity::save);
                    return registered;
                } catch (IOException | RuntimeException e) {
                    registered.close();
                    throw e;
                }
            }
        }
        throw new IOException("registration redirect budget exhausted");
    }

    static final int SAVE_ATTEMPTS = 7;

    @FunctionalInterface
    interface IdentitySaver {
        void save(Identity identity, Path destination) throws IOException;
    }

    /**
     * Retain this issued credential throughout all storage retries; never repeat its registration.
     */
    static void saveIssued(Identity issued, Path destination, Path intent, IdentitySaver saver)
            throws IOException {
        IOException failure = null;
        // About 15 s in total: long enough to outlast a briefly locked file (antivirus, backup)
        // because a credential lost here cannot be registered again automatically.
        for (int attempt = 0; attempt < SAVE_ATTEMPTS; attempt++) {
            try {
                saver.save(issued, destination);
                Files.deleteIfExists(intent);
                return;
            } catch (IOException e) {
                failure = e;
            }
            if (attempt < SAVE_ATTEMPTS - 1) {
                try {
                    Thread.sleep(Math.min(4000L, 200L << attempt));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("identity storage retry interrupted");
                }
            }
        }
        throw new IOException(
                "issued identity could not be saved; registration remains blocked", failure);
    }

    private static void writeIntent(Path intent) throws IOException {
        Files.createFile(intent);
        Identity.secureFile(intent);
        byte[] notice =
                "A registration request may have reached the service. Do not delete until the issued identity is recovered or deliberate re-registration is chosen.\n"
                        .getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel =
                FileChannel.open(intent, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(notice);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
