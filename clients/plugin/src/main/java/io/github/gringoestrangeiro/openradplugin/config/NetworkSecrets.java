package io.github.gringoestrangeiro.openradplugin.config;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Explicit private file, never copied into status snapshots or public configuration. */
public final class NetworkSecrets implements AutoCloseable {
    static final int MAX_FILE_BYTES = 65536;
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
        return read(path);
    }

    static NetworkSecrets readExisting(Path directory) throws IOException {
        Path path = directory.resolve("secrets.properties");
        return Files.notExists(path, LinkOption.NOFOLLOW_LINKS) ? new NetworkSecrets() : read(path);
    }

    private static NetworkSecrets read(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > MAX_FILE_BYTES)
            throw new IOException("invalid secrets file");
        Identity.secureFile(path);
        NetworkSecrets secrets = new NetworkSecrets();
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            try (Reader reader = new InputStreamReader(
                    new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder())) {
                if (bytes.length > MAX_FILE_BYTES) throw new IOException("invalid secrets file");
                properties.load(reader);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            for (String key : properties.stringPropertyNames()) {
                secrets.values.put(key, properties.getProperty(key).toCharArray());
            }
            return secrets;
        } catch (IOException | RuntimeException e) {
            secrets.close();
            throw e;
        } finally {
            properties.clear();
        }
    }

    public char[] password(String key) {
        return key.isEmpty() ? null : requiredPassword(key).clone();
    }

    private char[] requiredPassword(String key) {
        char[] value = values.get(key);
        if (value == null)
            throw new IllegalArgumentException(
                    "secrets.properties has no password for '" + key
                            + "'; add " + key + "=<senha> or use /orb join <rede> <senha>");
        if (value.length < 6 || value.length > 256)
            throw new IllegalArgumentException(
                    "secrets.properties: password for '" + key + "' must have 6..256 characters");
        for (char c : value)
            if (Character.isISOControl(c))
                throw new IllegalArgumentException("invalid network password");
        return value;
    }

    public void validate(VpnSettings settings) {
        validate(settings, "");
    }

    /** The editor separately validates the new password before staging it. */
    void validate(VpnSettings settings, String stagedSecret) {
        for (var network : settings.networks()) {
            String key = network.secret();
            if (!key.isEmpty() && !key.equals(stagedSecret)) requiredPassword(key);
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
