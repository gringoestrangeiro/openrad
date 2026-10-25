import io.github.gringoestrangeiro.openradbridge.crypto.*;
import io.github.gringoestrangeiro.openradbridge.network.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit local developer setup for a later human official-client acceptance test. */
public final class FriendTestSetup {
    static void privateWrite(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createFile(path);
        Identity.secureFile(path);
        Files.writeString(
                path,
                value,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4)
            throw new IllegalArgumentException(
                    "prepare/remove, isolated creator profile, isolated plugin directory, private"
                            + " state path");
        String mode = args[0];
        Path source = Path.of(args[1]).toAbsolutePath().normalize(),
                data = Path.of(args[2]).toAbsolutePath().normalize(),
                state = Path.of(args[3]).toAbsolutePath().normalize();
        if (!source.getFileName().toString().equals("live-control-2")
                || !data.getFileName().toString().equals("OpenRadBridge")
                || !data.getParent().getFileName().toString().equals("plugins")
                || !data.getParent().getParent().getFileName().toString().equals("paper"))
            throw new IllegalArgumentException("designated isolated test paths required");
        if (!mode.equals("prepare") && !mode.equals("remove"))
            throw new IllegalArgumentException("unknown mode");
        Files.createDirectories(data);
        try (FileChannel file =
                        FileChannel.open(
                                data.resolve("identity.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock lock = file.tryLock()) {
            if (lock == null)
                throw new IOException(
                        "Stop the isolated Paper server before changing its test network");
            if (mode.equals("prepare") && Files.exists(state))
                throw new IOException(
                        "private test state already exists; preserve it and use remove before"
                                + " recreating");
            try (Identity identity = Identity.load(source.resolve("identity.json"));
                    var endpoint = new LivePrivateNetworkSmoke.Endpoint(identity)) {
                Properties properties = new Properties();
                if (mode.equals("remove")) {
                    try (Reader in = Files.newBufferedReader(state, StandardCharsets.UTF_8)) {
                        properties.load(in);
                    }
                    String name = properties.getProperty("network");
                    String guid =
                            endpoint.attachment.membership().networks().values().stream()
                                    .filter(n -> n.name().equals(name))
                                    .map(Membership.Network::id)
                                    .findFirst()
                                    .orElse(null);
                    if (guid != null)
                        try (var operation = NetworkManagement.delete(guid, 801, 801)) {
                            var result = LivePrivateNetworkSmoke.manage(endpoint, null, operation);
                            if (!result.success())
                                throw new IOException("test network deletion refused");
                        }
                    Path config = data.resolve("config.yml");
                    String yaml =
                            Files.readString(config, StandardCharsets.UTF_8)
                                    .replace("enabled: true", "enabled: false");
                    Files.writeString(config, yaml, StandardCharsets.UTF_8);
                    System.out.println("FRIEND_TEST_NETWORK_REMOVED_VPN_DISABLED");
                    return;
                }
                Path existing = data.resolve("identity.json");
                if (Files.exists(existing))
                    try (Identity old = Identity.load(existing)) {
                        if (old.rid() != identity.rid())
                            throw new IOException(
                                    "unrelated plugin identity will not be overwritten");
                    }
                identity.save(existing);
                String name = "OpenRadBridge-E2E-" + UUID.randomUUID().toString().substring(0, 8),
                        password = HexFormat.of().formatHex(Crypto.random(16));
                privateWrite(
                        state,
                        "network="
                                + name
                                + "\npassword="
                                + password
                                + "\nvip="
                                + identity.vip().getHostAddress()
                                + "\nport=25565\n");
                try (var operation =
                        NetworkManagement.createPrivate(name, password.toCharArray(), 800, 800)) {
                    var result = LivePrivateNetworkSmoke.manage(endpoint, null, operation);
                    if (!result.success()) throw new IOException("test network create refused");
                    privateWrite(
                            state,
                            Files.readString(state, StandardCharsets.UTF_8)
                                    + "guid="
                                    + result.networkId()
                                    + "\n");
                }
                privateWrite(data.resolve("secrets.properties"), "friend-test=" + password + "\n");
                String yaml =
                        "bridge:\n"
                                + "  enabled: true\n"
                                + "  backend-host: \"127.0.0.1\"\n"
                                + "  backend-port: 25575\n"
                                + "  listen-virtual-port: 25565\n"
                                + "  max-players-through-vpn: 4\n"
                                + "  max-pending-tcp: 8\n"
                                + "  connection-timeout-seconds: 15\n"
                                + "vpn:\n"
                                + "  device-name: \"OpenRadBridge-E2E-Test\"\n"
                                + "  networks:\n"
                                + "    - name: \""
                                + name
                                + "\"\n"
                                + "      secret: \"friend-test\"\n"
                                + "      allow-rids: []\n"
                                + "      deny-rids: []\n"
                                + "  max-peers: 8\n"
                                + "  transports:\n"
                                + "    direct-tcp: true\n"
                                + "    direct-udp: true\n"
                                + "    relay: true\n"
                                + "  acl:\n"
                                + "    allow-rids: []\n"
                                + "    deny-rids: []\n"
                                + "  reconnect:\n"
                                + "    base-seconds: 3\n"
                                + "    max-seconds: 30\n"
                                + "diagnostics:\n"
                                + "  tcp-egress-host: \"\"\n"
                                + "  tcp-egress-port: 0\n"
                                + "  udp-egress-host: \"\"\n"
                                + "  udp-egress-port: 0\n"
                                + "  timeout-millis: 1000\n";
                Files.writeString(data.resolve("config.yml"), yaml, StandardCharsets.UTF_8);
                System.out.println(
                        "FRIEND_TEST_NETWORK_CREATED_AND_CONFIGURED vip="
                                + identity.vip().getHostAddress()
                                + "; credentials stored only in private files");
            }
        }
    }
}
