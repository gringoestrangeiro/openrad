package io.github.gringoestrangeiro.openradplugin.peer;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.op;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

class AcceptorRelayGuardTest {
    private static final byte[] RELAY = op(22);
    private static final byte[] DIRECT = op(2);
    private static final long WINDOW = Duration.ofSeconds(5).toNanos();

    @Test
    void recentObjectionDisablesRelayForOnlyOneRuntimeAcrossReconnects() throws Exception {
        var clock = new AtomicLong();
        var guard = new AcceptorRelayGuard(clock::get);
        var otherRuntime = new AcceptorRelayGuard(clock::get);
        var sent = new ArrayList<byte[]>();
        guard.send(RELAY, sent::add);
        clock.set(WINDOW);
        assertTrue(guard.serverObjected());
        assertFalse(guard.serverObjected(), "repeated refusals should not repeat the state change");

        guard.startAttachment();
        guard.send(RELAY, sent::add);
        guard.send(DIRECT, sent::add);
        assertEquals(List.of(RELAY, DIRECT), sent);
        otherRuntime.send(RELAY, sent::add);
        assertEquals(List.of(RELAY, DIRECT, RELAY), sent);
    }

    @Test
    void oldUnsentOrFailedRequestsCannotDisableRelay() throws Exception {
        var clock = new AtomicLong();
        var guard = new AcceptorRelayGuard(clock::get);
        var sent = new ArrayList<byte[]>();
        assertFalse(guard.serverObjected(), "a request not yet sent cannot be refused");
        assertThrows(IOException.class, () -> guard.send(RELAY, ignored -> {
            throw new IOException("closed control stream");
        }));
        assertFalse(guard.serverObjected(), "a failed write does not start the window");

        guard.send(RELAY, sent::add);
        clock.set(WINDOW + 1);
        assertFalse(guard.serverObjected(), "a late refusal does not match the request");
        guard.send(RELAY, sent::add);
        guard.startAttachment();
        assertFalse(guard.serverObjected(), "the previous attachment's timestamp is cleared");
        guard.send(RELAY, sent::add);
        assertEquals(3, sent.size());
    }
}
