package io.github.gringoestrangeiro.openradplugin.session;

import io.github.gringoestrangeiro.openradplugin.protocol.Wire;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/** A pinned coordination server and the state directory reserved for its identities. */
public record CoordinatorEndpoint(String bootstrap, int port, byte[] modulus, Path identityDirectory) {
    public CoordinatorEndpoint {
        modulus = modulus.clone();
    }

    @Override
    public byte[] modulus() {
        return modulus.clone();
    }

    public static CoordinatorEndpoint load(Path directory, String defaultHost, int defaultPort)
            throws IOException {
        byte[] configuration;
        try {
            configuration = readBounded(directory.resolve("coordinator.properties"));
        } catch (NoSuchFileException absent) {
            return new CoordinatorEndpoint(defaultHost, defaultPort, ServiceConfig.modulus(), directory);
        }

        Properties settings = new Properties();
        String host;
        int port;
        try {
            settings.load(new ByteArrayInputStream(configuration));
            host = settings.getProperty("host", "").strip();
            var address = Wire.ipv4(host);
            if (address.isAnyLocalAddress() || address.isMulticastAddress() || host.equals("255.255.255.255"))
                throw new IllegalArgumentException("unusable IPv4 address");
            port = Integer.parseInt(settings.getProperty("port", Integer.toString(ServiceConfig.PORT)).strip());
            if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid coordinator.properties: set a numeric IPv4 host and a valid port", invalid);
        }

        byte[] modulus = readBounded(directory.resolve("modulus.bin"));
        if (modulus.length == 0) throw new IOException("Empty coordinator modulus.bin");
        var number = new BigInteger(1, modulus);
        int width = (number.bitLength() + 7) / 8;
        if (width < 87 || width > 1024 || !number.testBit(0))
            throw new IOException("Invalid OpenRad coordinator modulus.bin");

        // Use a canonical encoding so adding a sign byte does not select a new identity.
        byte[] canonical = number.toByteArray();
        String fingerprint;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        Path state = directory.resolve("coordinators").resolve(host + "-" + port + "-" + fingerprint);
        return new CoordinatorEndpoint(host, port, canonical, state);
    }

    private static byte[] readBounded(Path file) throws IOException {
        if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile())
            throw new IOException("Coordinator file must be a regular file: " + file.getFileName());
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(4097);
            if (bytes.length > 4096) throw new IOException("Coordinator file too large: " + file.getFileName());
            return bytes;
        }
    }
}
