package io.github.gringoestrangeiro.openradplugin.network;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.crypto.ShClient;
import io.github.gringoestrangeiro.openradplugin.crypto.ShRecord;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** One correlated join at a time also safely supports servers omitting SRP correlation. */
public final class NetworkJoin implements AutoCloseable {
    public static final class Refused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final long code;

        public Refused(long code) {
            this(code, "network join refused (" + code + ")");
        }

        public Refused(long code, String message) {
            super(message);
            this.code = code;
        }

        public long code() {
            return code;
        }
    }

    private final String name;
    private final long id;
    private final int sequence;
    private final ShClient auth;
    private int step = 2;
    private boolean complete, pendingApproval, started, closed;
    private String joinedGuid;
    private final Set<String> approvals = new HashSet<>();

    public NetworkJoin(String name, char[] password, long id, int sequence) {
        require(!name.isBlank() && name.length() <= 255 && id != 0 && sequence > 0, "invalid join");
        text(0x03000306, name);
        this.name = name;
        this.id = id;
        this.sequence = sequence;
        if (password == null) auth = null;
        else {
            byte[] raw = null;
            try {
                require(password.length >= 6 && password.length <= 256, "network password length");
                for (char c : password)
                    require(!Character.isISOControl(c), "invalid network password");
                raw = (new String(password) + "\0").getBytes(StandardCharsets.UTF_8);
                auth = new ShClient(networkIdentity(name), raw);
            } finally {
                if (raw != null) Arrays.fill(raw, (byte) 0);
                Arrays.fill(password, '\0');
            }
        }
    }

    public byte[] start() {
        require(!closed && !started, "join already started or closed");
        started = true;
        return concat(
                op(39),
                tlv(
                        0x131c,
                        concat(
                                text(0x03000306, name),
                                u32(0x0b00033f, auth == null ? 1 : 0),
                                auth == null ? new byte[0] : tlv(0x0a00030e, auth.start()),
                                u64(0x02000340, id),
                                u32(0x010003be, sequence))));
    }

    public byte[] handle(byte[] data, Membership membership) {
        require(!closed && started, "join not active");
        int operation = TlvCodec.operation(data);
        var outer = fields(data);
        if (operation == 40) {
            var f = fields(field(outer, 0x131c));
            if (TlvCodec.optional(f, 0x02000340)
                            .map(TlvCodec::int64)
                            .filter(v -> v != id)
                            .isPresent()
                    || TlvCodec.optional(f, 0x010003be)
                            .map(TlvCodec::int32)
                            .filter(v -> v != sequence)
                            .isPresent()) return null;
            require(
                    TlvCodec.optional(f, 0x0100034a).map(TlvCodec::int32).orElse(0L) == 0,
                    "join context mismatch");
            if (wide(f, 0x02000303) != 0) throw new Refused(wide(f, 0x02000303));
            require(auth != null, "network requires a password; use /orb join <rede> <senha>");
            byte[] blob = field(f, 0x0a00030e);
            // The service answers a wrong password with a lone SH index record (Rust network.rs).
            if (Arrays.equals(blob, ShRecord.record(0x10000000, new byte[4])))
                throw new Refused(-1, "wrong network password; check secrets.properties");
            byte[] next;
            switch (step) {
                case 2 -> next = auth.parameters(blob);
                case 4 -> next = auth.challenge(blob);
                case 6 -> {
                    byte[] unused = auth.confirm(blob);
                    Arrays.fill(unused, (byte) 0);
                    auth.close();
                    next = null;
                }
                default -> throw new IllegalArgumentException("join authentication stage");
            }
            step += 2;
            return next == null
                    ? null
                    : concat(
                            op(39),
                            tlv(0x131c, concat(tlv(0x0a00030e, next), u32(0x010003be, sequence))));
        }
        if (operation == 42) {
            for (var entry : fields(field(outer, 0x131f)))
                if (entry.tag() == 0x131e) {
                    approvals.add(guid(field(fields(entry.value()), 0x0d000309)));
                    require(approvals.size() <= 1024, "too many join approvals");
                }
        }
        if (operation == 37) {
            var f = fields(field(outer, 0x131a));
            if (wide(f, 0x02000340) != id) return null;
            require(number(f, 0x0100030c) == 2, "join action mismatch");
            var error = TlvCodec.optional(f, 0x010001d2);
            if (error.isPresent()) throw new Refused(TlvCodec.int32(error.orElseThrow()));
            require(auth == null || step == 8, "membership before password proof");
            byte[] root = field(f, 0x1316);
            Membership returned = new Membership(0);
            returned.snapshot(tlv(0x1316, root));
            var matching =
                    returned.networks().values().stream()
                            .filter(n -> n.name().equals(name))
                            .findFirst();
            require(matching.isPresent(), "join network name mismatch");
            joinedGuid = matching.orElseThrow().id();
            membership.snapshot(tlv(0x1316, root));
            pendingApproval =
                    Integer.valueOf(0).equals(membership.role(joinedGuid, membership.ownRid()));
        }
        complete = joinedGuid != null && (pendingApproval || approvals.contains(joinedGuid));
        return null;
    }

    public boolean complete() {
        return complete;
    }

    public boolean pendingApproval() {
        return pendingApproval;
    }

    @Override
    public void close() {
        if (auth != null) auth.close();
        closed = true;
        approvals.clear();
    }

    public static long networkIdentity(String name) {
        long crc = -1L;
        for (byte b : name.getBytes(StandardCharsets.UTF_16LE)) {
            crc ^= (long) (b & 255) << 56;
            for (int i = 0; i < 8; i++) crc = (crc << 1) ^ ((crc < 0) ? 0x42f0e1eba9ea3693L : 0);
        }
        return ~crc;
    }
}
