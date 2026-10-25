package io.github.gringoestrangeiro.openradplugin.session;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.crypto.*;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.transport.FramedTcp;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Authenticated control stream. One worker owns each instance. */
public final class ControlSession implements AutoCloseable {
    private final FramedTcp stream;
    private final Channel cipher;
    private final long latency;

    private ControlSession(FramedTcp stream, Channel cipher, long latency) {
        this.stream = stream;
        this.cipher = cipher;
        this.latency = latency;
    }

    public static ControlSession authenticate(
            String host, int port, Identity identity, int purpose, Duration timeout)
            throws IOException {
        return authenticate(
                host, port, identity, purpose, timeout, ServiceConfig.modulus(), s -> {});
    }

    public static ControlSession authenticate(
            String host,
            int port,
            Identity identity,
            int purpose,
            Duration timeout,
            byte[] modulus,
            Consumer<ControlSession> owner)
            throws IOException {
        // Production endpoints are numeric, so unbounded system DNS cannot hold shutdown.
        InetAddress address = literal(host);
        FramedTcp stream = FramedTcp.connect(new InetSocketAddress(address, port), timeout);
        Channel channel = null;
        try (RsaSession.Result rsa = RsaSession.create(modulus, purpose);
                ShClient sh = new ShClient(identity.rid(), identity.password())) {
            byte[] secret = rsa.secret();
            channel = new Channel(Arrays.copyOf(secret, 32));
            Arrays.fill(secret, (byte) 0);
            ControlSession session = new ControlSession(stream, channel, 0);
            owner.accept(session);
            stream.send(new byte[] {3, 0, 0, 0});
            stream.send(rsa.ciphertext());
            require(
                    Arrays.equals(stream.receive(4), new byte[] {3, 0, 0, 0}),
                    "RSession echo mismatch");
            byte[] next = sh.start();
            long latency = 0xffff_ffffL;
            for (int stage = 0; stage < 3; stage++) {
                long started = System.nanoTime();
                session.send(next);
                byte[] plain = session.receive();
                latency =
                        Math.min(latency, Math.round((System.nanoTime() - started) / 1_000_000.0));
                if (stage == 0) next = sh.parameters(plain);
                else if (stage == 1) next = sh.challenge(plain);
                else {
                    byte[] key = sh.confirm(plain);
                    try {
                        channel.rekey(Arrays.copyOf(key, 32));
                    } finally {
                        Arrays.fill(key, (byte) 0);
                    }
                }
            }
            return new ControlSession(stream, channel, latency);
        } catch (IOException | RuntimeException e) {
            stream.close();
            if (channel != null) channel.close();
            throw e;
        }
    }

    public void send(byte[] plain) throws IOException {
        stream.send(cipher.encrypt(plain));
    }

    public byte[] receive() throws IOException {
        return cipher.decrypt(stream.receive(TlvCodec.MAX_PAYLOAD));
    }

    public boolean ready(Duration timeout) throws IOException {
        return stream.ready(timeout);
    }

    public void sustain() {
        stream.sustain();
    }

    public long latency() {
        return latency;
    }

    public InetAddress localAddress() {
        return stream.socket().getLocalAddress();
    }

    @Override
    public void close() throws IOException {
        try {
            stream.close();
        } finally {
            cipher.close();
        }
    }
}
