package io.github.gringoestrangeiro.openradplugin.session;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import java.io.IOException;

/**
 * Operation 16 on an attachment: the service ended this session. Only the bounded reason code is
 * kept (the Rust client records the same field); other fields are never logged.
 *
 * <p>Reason 1 means the service accepted a newer attachment for this identity and dropped this
 * one. Reconnecting automatically would then drop the other client in turn, and both would keep
 * replacing each other every few seconds, so callers stop instead of retrying.
 */
public final class ServerDisconnect extends IOException {
    private static final long serialVersionUID = 1L;
    public static final int OPERATION = 16;
    public static final int REASON_TAG = 0x010001d2;
    public static final long REASON_REPLACED = 1;
    /** The reason code, or -1 when the record carried none (or an invalid one). */
    private final long reason;

    private ServerDisconnect(long reason) {
        super(describe(reason));
        this.reason = reason;
    }

    /** Decodes a server operation-16 record leniently: a malformed reason is just unknown. */
    public static ServerDisconnect of(byte[] record) {
        long reason = -1;
        try {
            byte[] value = TlvCodec.optional(TlvCodec.parse(record), REASON_TAG).orElse(null);
            if (value != null) reason = TlvCodec.int32(value);
        } catch (RuntimeException malformed) {
            reason = -1;
        }
        return new ServerDisconnect(reason);
    }

    public long reason() {
        return reason;
    }

    /** True when another client logged in with this same identity and replaced this session. */
    public boolean replaced() {
        return reason == REASON_REPLACED;
    }

    private static String describe(long reason) {
        if (reason == REASON_REPLACED)
            return "service replaced this session: the same OpenRad identity connected from"
                    + " another client";
        return reason < 0
                ? "service disconnected session"
                : "service disconnected session (reason " + reason + ")";
    }
}
