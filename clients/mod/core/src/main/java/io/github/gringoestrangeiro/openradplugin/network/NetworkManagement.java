package io.github.gringoestrangeiro.openradplugin.network;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.crypto.Crypto;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Correlated private-network creation, leave and delete requests, matching canonical network.rs.
 */
public final class NetworkManagement implements AutoCloseable {
    private final int action, sequence;
    private final long id;
    private final String expectedName, expectedNetwork;
    private final byte[] request;
    private boolean started, closed, complete;

    private NetworkManagement(
            int action,
            long id,
            int sequence,
            String expectedName,
            String expectedNetwork,
            byte[] body) {
        require(id != 0 && sequence > 0, "invalid network request correlation");
        this.action = action;
        this.id = id;
        this.sequence = sequence;
        this.expectedName = expectedName;
        this.expectedNetwork = expectedNetwork;
        request =
                concat(
                        op(52),
                        tlv(
                                0x1319,
                                concat(
                                        u32(0x0100030c, action),
                                        u64(0x02000340, id),
                                        u32(0x0100034a, sequence),
                                        body)));
    }

    /** Consumes the supplied password array. The raw password is never put in a request. */
    public static NetworkManagement createPrivate(
            String name, char[] password, long id, int sequence) {
        return createPrivate(name, password, id, sequence, Crypto.random(32));
    }

    static NetworkManagement createPrivate(
            String name, char[] password, long id, int sequence, byte[] salt) {
        byte[] wirePassword = null, verifier = null;
        try {
            require(
                    name != null && !name.isBlank() && name.length() <= 255,
                    "invalid network name");
            text(0x03000306, name);
            require(
                    password != null && password.length >= 6 && password.length <= 256,
                    "invalid network password length");
            for (char c : password) require(!Character.isISOControl(c), "invalid network password");
            wirePassword = (new String(password) + "\0").getBytes(StandardCharsets.UTF_8);
            verifier =
                    Crypto.networkVerifier(NetworkJoin.networkIdentity(name), wirePassword, salt);
            return new NetworkManagement(
                    1,
                    id,
                    sequence,
                    name,
                    null,
                    tlv(
                            0x1314,
                            concat(
                                    u32(0x0b000308, 0),
                                    text(0x03000306, name),
                                    tlv(0x0a000307, verifier))));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (wirePassword != null) Arrays.fill(wirePassword, (byte) 0);
            if (verifier != null) Arrays.fill(verifier, (byte) 0);
        }
    }

    public static NetworkManagement leave(String networkId, long id, int sequence) {
        return new NetworkManagement(3, id, sequence, null, networkId, network(networkId));
    }

    public static NetworkManagement delete(String networkId, long id, int sequence) {
        return new NetworkManagement(7, id, sequence, null, networkId, network(networkId));
    }

    private static byte[] network(String networkId) {
        byte[] bytes;
        try {
            bytes = HexFormat.of().parseHex(networkId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid network ID");
        }
        require(bytes.length == 16 && !Arrays.equals(bytes, new byte[16]), "invalid network ID");
        return tlv(0x0d000309, bytes);
    }

    public byte[] start() {
        require(!started && !closed, "network operation already started or closed");
        started = true;
        return request.clone();
    }

    /**
     * Returns null for unrelated messages, and a result only for the matching operation 37
     * response.
     */
    public Result handle(byte[] data, Membership membership) {
        require(started && !closed && !complete, "network operation not active");
        if (TlvCodec.operation(data) != 37) return null;
        var response = fields(field(fields(data), 0x131a));
        if (wide(response, 0x02000340) != id) return null;
        require(number(response, 0x0100030c) == action, "network response action mismatch");
        require(number(response, 0x0100034a) == sequence, "network response context mismatch");
        var error = TlvCodec.optional(response, 0x010001d2);
        if (error.isPresent()) {
            complete = true;
            return new Result(false, TlvCodec.int32(error.orElseThrow()), expectedNetwork);
        }
        String networkId;
        if (action == 1) {
            byte[] root = tlv(0x1316, tlv(0x1315, field(response, 0x1315)));
            Membership returned = new Membership(0);
            returned.snapshot(root);
            var matching =
                    returned.networks().values().stream()
                            .filter(n -> n.name().equals(expectedName))
                            .findFirst();
            require(matching.isPresent(), "create response network name mismatch");
            networkId = matching.orElseThrow().id();
            membership.snapshot(root);
        } else {
            networkId = guid(field(response, 0x0d000309));
            require(networkId.equalsIgnoreCase(expectedNetwork), "network response ID mismatch");
            membership.removeNetwork(networkId);
        }
        complete = true;
        return new Result(true, 0, networkId);
    }

    public boolean complete() {
        return complete;
    }

    public record Result(boolean success, long errorCode, String networkId) {}

    @Override
    public void close() {
        Arrays.fill(request, (byte) 0);
        closed = true;
    }

    @Override
    public String toString() {
        return "NetworkManagement[action=" + action + ", redacted]";
    }
}
