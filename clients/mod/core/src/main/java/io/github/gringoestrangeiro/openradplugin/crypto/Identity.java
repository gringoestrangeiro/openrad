package io.github.gringoestrangeiro.openradplugin.crypto;

import static io.github.gringoestrangeiro.openradplugin.crypto.Crypto.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.protocol.Wire;

import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** A Rust-compatible persistent device identity. Never include serialized identities in logs. */
public final class Identity implements AutoCloseable {
    private static final String FORMAT = "openrad-identity-v1";
    private static final int MAX_FILE = 65536;
    private final long rid;
    private final Inet4Address vip;
    private final String nodeName, serverAddress;
    private final byte[] address, password;

    public Identity(
            long rid,
            Inet4Address vip,
            String nodeName,
            byte[] address,
            byte[] password,
            String serverAddress) {
        this.rid = rid;
        this.vip = vip;
        this.nodeName = nodeName;
        this.address = address.clone();
        this.password = password.clone();
        this.serverAddress = serverAddress;
        validate(false);
    }

    public static Identity bootstrap(String name, String host) {
        return new Identity(
                new java.security.SecureRandom().nextInt(1000) + 1L,
                ipv4("0.0.0.0"),
                name,
                new byte[0],
                "rpassword".getBytes(StandardCharsets.US_ASCII),
                ipv4(host).getHostAddress());
    }

    public static Identity registered(byte[] data) {
        require(TlvCodec.operation(data) == 8, "expected Registered");
        var fields = TlvCodec.parse(TlvCodec.field(TlvCodec.parse(data), 0x1233));
        int ip = (int) TlvCodec.int32(TlvCodec.field(fields, 0x01000305));
        Identity identity =
                new Identity(
                        TlvCodec.int64(TlvCodec.field(fields, 0x020001e1)),
                        address(ByteBuffer.allocate(4).putInt(ip).array()),
                        TlvCodec.text(TlvCodec.field(fields, 0x03000304)),
                        TlvCodec.field(fields, 0x09000305),
                        TlvCodec.field(fields, 0x0a0001c8),
                        TlvCodec.text(TlvCodec.field(fields, 0x030001e2)));
        identity.validate(true);
        return identity;
    }

    public long rid() {
        return rid;
    }

    public Inet4Address vip() {
        return vip;
    }

    public String nodeName() {
        return nodeName;
    }

    public String serverAddress() {
        return serverAddress;
    }

    public byte[] address() {
        return address.clone();
    }

    public byte[] password() {
        return password.clone();
    }

    public static Identity fromSecret(byte[] bytes) {
        require(bytes.length <= MAX_FILE, "identity record too large");
        try {
            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            Map<String, Object> values = IdentityJson.parse(json);
            require(FORMAT.equals(string(values, "format")), "unsupported identity");
            Object id = values.get("rid");
            require(id instanceof BigInteger, "invalid identity file");
            BigInteger number = (BigInteger) id;
            require(number.signum() > 0 && number.bitLength() <= 64, "unsupported identity");
            Identity identity =
                    new Identity(
                            number.longValue(),
                            ipv4(string(values, "vip")),
                            string(values, "node_name"),
                            HexFormat.of().parseHex(string(values, "address")),
                            HexFormat.of().parseHex(string(values, "credential")),
                            string(values, "server_address"));
            identity.validate(true);
            return identity;
        } catch (CharacterCodingException | IllegalArgumentException e) {
            // Do not attach parser exceptions: their messages can contain input secrets.
            throw new IllegalArgumentException("invalid identity file");
        }
    }

    /** Bounded NOFOLLOW read; malformed files remain intact for repair. */
    public static Identity load(Path path) throws IOException {
        secureFile(path);
        try (FileChannel channel =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > MAX_FILE) throw new IOException("identity record too large");
            ByteBuffer buffer = ByteBuffer.allocate(MAX_FILE + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
            if (buffer.position() > MAX_FILE) throw new IOException("identity record too large");
            byte[] bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
            try {
                return fromSecret(bytes);
            } catch (IllegalArgumentException e) {
                throw new IOException("invalid identity file");
            } finally {
                clear(bytes);
                clear(buffer.array());
            }
        }
    }

    /**
     * Secure the temporary file before writing secrets, force it, then atomically replace the old
     * identity.
     */
    public void save(Path destination) throws IOException {
        validate(true);
        Path target = destination.toAbsolutePath().normalize(), directory = target.getParent();
        Files.createDirectories(directory);
        Path realDirectory = directory.toRealPath();
        if (Files.isSymbolicLink(target) || !Files.isDirectory(realDirectory)) {
            throw new IOException("unsafe identity file path");
        }
        Path temporary = createPrivateTemporary(realDirectory);
        byte[] bytes = toSecret();
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(
                    temporary,
                    realDirectory.resolve(target.getFileName()),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            clear(bytes);
            Files.deleteIfExists(temporary);
        }
    }

    public byte[] toSecret() {
        validate(true);
        String json =
                "{\"format\":\""
                        + FORMAT
                        + "\",\"rid\":"
                        + Long.toUnsignedString(rid)
                        + ",\"vip\":"
                        + IdentityJson.quote(vip.getHostAddress())
                        + ",\"node_name\":"
                        + IdentityJson.quote(nodeName)
                        + ",\"address\":\""
                        + HexFormat.of().formatHex(address)
                        + "\",\"credential\":\""
                        + HexFormat.of().formatHex(password)
                        + "\",\"server_address\":"
                        + IdentityJson.quote(serverAddress)
                        + ",\"legacy_credential\":\"\"}\n";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private void validate(boolean registered) {
        require(rid != 0 && password.length >= 6 && password.length <= 4096, "invalid identity");
        require(
                !nodeName.strip().isEmpty()
                        && nodeName.codePoints().noneMatch(Character::isISOControl),
                "invalid device name");
        TlvCodec.textValue(0x03000304, nodeName);
        ipv4(serverAddress);
        if (registered) {
            require(
                    !vip.isAnyLocalAddress() && !vip.getHostAddress().equals("255.255.255.255"),
                    "invalid VIP");
            require(address.length == 16, "invalid companion address");
        } else {
            require(address.length == 0 || address.length == 16, "invalid companion address");
        }
    }

    private static Path createPrivateTemporary(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return Files.createTempFile(
                    directory,
                    ".identity-",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        }
        Path temporary = Files.createTempFile(directory, ".identity-", ".tmp");
        try {
            secureFile(temporary);
            return temporary;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    /**
     * Restrict a file to its owner. Call before writing any credentials or registration markers.
     */
    public static void secureFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unsafe private file path");
        }
        PosixFileAttributeView posix =
                Files.getFileAttributeView(
                        path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(
                        path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("private file permissions unavailable");
        acl.setAcl(
                List.of(
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(acl.getOwner())
                                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                .build()));
    }

    private static String string(Map<String, Object> fields, String key) {
        require(fields.get(key) instanceof String, "invalid identity file");
        return (String) fields.get(key);
    }

    private static Inet4Address ipv4(String value) {
        return Wire.ipv4(value);
    }

    private static Inet4Address address(byte[] bytes) {
        try {
            return (Inet4Address) InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid IPv4 address");
        }
    }

    @Override
    public String toString() {
        return "Identity[rid=" + Long.toUnsignedString(rid) + ", redacted]";
    }

    @Override
    public void close() {
        clear(password);
    }
}
