package io.github.gringoestrangeiro.openradclient;

import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit opt-in: the mod does not contact the remote service on a fresh install. */
public record ClientConfig(boolean autoConnect, List<String> networks,
                           int maxPeers, int maxConnections, int connectionTimeoutSeconds,
                           List<String> minecraftSelectors, int maxNetworks) {
    public ClientConfig(boolean autoConnect, List<String> networks) {
        this(autoConnect, networks, 256, 16, 20, List.of(), 256);
    }

    public ClientConfig(boolean autoConnect, List<String> networks,
                        int maxPeers, int maxConnections, int connectionTimeoutSeconds) {
        this(autoConnect, networks, maxPeers, maxConnections, connectionTimeoutSeconds, List.of(), 256);
    }

    public ClientConfig {
        Objects.requireNonNull(networks);
        if (maxPeers < 1 || maxPeers > 4096)
            throw new IllegalArgumentException("max-peers must be between 1 and 4096");
        if (maxConnections < 1 || maxConnections > 64)
            throw new IllegalArgumentException("max-connections must be between 1 and 64");
        if (connectionTimeoutSeconds < 1 || connectionTimeoutSeconds > 120)
            throw new IllegalArgumentException("connection-timeout-seconds must be between 1 and 120");
        Objects.requireNonNull(minecraftSelectors);
        if (maxNetworks < 1 || maxNetworks > 1024)
            throw new IllegalArgumentException("max-networks must be between 1 and 1024");
        if (networks.size() > maxNetworks) throw new IllegalArgumentException("exact network names exceed max-networks");
        if (minecraftSelectors.size() > 1024) throw new IllegalArgumentException("too many Minecraft selectors");
        ArrayList<String> clean = new ArrayList<>();
        for (String input : networks) {
            String name = Objects.requireNonNull(input).trim();
            if (name.isBlank() || name.length() > 255 || name.contains(";")
                    || name.chars().anyMatch(Character::isISOControl)
                    || clean.contains(name))
                throw new IllegalArgumentException("invalid or duplicate network name");
            clean.add(name);
        }
        networks = List.copyOf(clean);
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        for (String input : minecraftSelectors) {
            String selector = MinecraftNetworks.validateSelector(Objects.requireNonNull(input));
            if (!selectors.add(selector)) throw new IllegalArgumentException("duplicate Minecraft selector");
        }
        minecraftSelectors = List.copyOf(selectors);
        if (autoConnect && networks.isEmpty() && minecraftSelectors.isEmpty())
            throw new IllegalArgumentException("auto-connect needs a selected network");
    }

    public static ClientConfig from(Properties p) {
        String autostart = p.getProperty("auto-connect","false").trim();
        if (!autostart.equals("true") && !autostart.equals("false"))
            throw new IllegalArgumentException("auto-connect must be true or false");
        String raw = p.getProperty("network-names","").trim();
        List<String> items = raw.isEmpty() ? List.of()
                : Arrays.asList(raw.split(";", -1));
        return new ClientConfig(Boolean.parseBoolean(autostart), items,
                integer(p, "max-peers", 256), integer(p, "max-connections", 16),
                integer(p, "connection-timeout-seconds", 20),
                list(p.getProperty("minecraft-selectors", "")), integer(p, "max-networks", 256));
    }

    private static List<String> list(String raw) {
        return raw.isBlank() ? List.of() : Arrays.asList(raw.trim().split(";", -1));
    }

    /** Atomic UTF-8 configuration update. The identity directory and credentials are never touched. */
    public void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("openrad-client.properties");
        if (Files.isSymbolicLink(file)) throw new IOException("config symlink not permitted");
        Properties properties = new Properties();
        properties.setProperty("auto-connect", Boolean.toString(autoConnect));
        properties.setProperty("network-names", String.join(";", networks));
        properties.setProperty("minecraft-selectors", String.join(";", minecraftSelectors));
        properties.setProperty("max-networks", Integer.toString(maxNetworks));
        properties.setProperty("max-peers", Integer.toString(maxPeers));
        properties.setProperty("max-connections", Integer.toString(maxConnections));
        properties.setProperty("connection-timeout-seconds", Integer.toString(connectionTimeoutSeconds));
        Path temporary = Files.createTempFile(directory, ".openrad-client-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(writer, "OpenRad Client Mod; selectors resolve only against the authenticated Minecraft catalog");
            }
            if (Files.isSymbolicLink(file)) throw new IOException("config symlink not permitted");
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static int integer(Properties properties, String key, int defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer", error);
        }
    }

    public static ClientConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("openrad-client.properties");
        if (Files.isSymbolicLink(file)) throw new IOException("config symlink not permitted");
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.writeString(file, """
                        # OpenRad Client Mod (Fabric 1.21.1)
                        # No network connection happens until an explicit user opt-in.
                        # Use exact PUBLIC OpenRad network names separated by semicolons.
                        # Example: network-names=Minecraft [Português 1]
                        # For encrypted private networks, configuration/UI will be added later.
                        auto-connect=false
                        network-names=
                        # all; all-ptbr; ptbr-1; ptbr-2; all-english; english-2; ...
                        # Resolved from the authenticated catalog; leave empty to use only exact names.
                        minecraft-selectors=
                        # Maximum total exact + catalog-selected networks (1..1024).
                        max-networks=256
                        # Authenticated peer channels; negotiations also have a concurrency bound.
                        max-peers=256
                        # Total simultaneous Minecraft status/game streams (1..64).
                        max-connections=16
                        # Includes waiting for an authorized peer and the virtual TCP handshake.
                        connection-timeout-seconds=20
                        """, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException ignored) {
                // Concurrent launch: read existing configuration below.
            }
        }
        if (Files.size(file) > 2 * 1024 * 1024) throw new IOException("config too large");
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
            return from(p);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid OpenRad client configuration: " + error.getMessage(), error);
        }
    }
}
