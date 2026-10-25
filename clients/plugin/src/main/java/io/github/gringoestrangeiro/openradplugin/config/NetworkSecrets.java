package io.github.gringoestrangeiro.openradplugin.config;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Explicit private file, never copied into status snapshots or public configuration. */
public final class NetworkSecrets implements AutoCloseable {
    private final Map<String, char[]> values = new HashMap<>();

    public static NetworkSecrets load(Path directory) throws IOException {
        Path path = directory.resolve("secrets.properties");
        Files.createDirectories(directory);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(
                        path,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(path);
            }
            Identity.secureFile(path);
            Files.writeString(
                    path,
                    "# Private network passwords. Keep this file out of backups shared with"
                        + " others.\n"
                        + "# example-key=your-network-password\n",
                    StandardCharsets.UTF_8);
        }
        if (Files.isSymbolicLink(path) || Files.size(path) > 65536)
            throw new IOException("invalid secrets file");
        Identity.secureFile(path);
        NetworkSecrets secrets = new NetworkSecrets();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            if (value.length() < 6 || value.length() > 256)
                throw new IOException(
                        "secrets.properties: password for '" + key + "' must have 6..256 characters");
            secrets.values.put(key, value.toCharArray());
        }
        properties.clear();
        return secrets;
    }

    public char[] password(String key) {
        if (key.isEmpty()) return null;
        char[] value = values.get(key);
        if (value == null)
            throw new IllegalArgumentException(
                    "secrets.properties has no password for '" + key
                            + "'; add " + key + "=<senha> or use /orb join <rede> <senha>");
        return value.clone();
    }

    public void validate(VpnSettings settings) {
        for (var network : settings.networks()) {
            char[] p = password(network.secret());
            if (p != null) {
                for (char c : p)
                    if (Character.isISOControl(c))
                        throw new IllegalArgumentException("invalid network password");
                Arrays.fill(p, '\0');
            }
        }
    }

    @Override
    public void close() {
        values.values().forEach(v -> Arrays.fill(v, '\0'));
        values.clear();
    }

    @Override
    public String toString() {
        return "NetworkSecrets[redacted]";
    }
}
