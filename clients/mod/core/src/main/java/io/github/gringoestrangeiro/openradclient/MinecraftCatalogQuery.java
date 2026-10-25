package io.github.gringoestrangeiro.openradclient;

import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;
import io.github.gringoestrangeiro.openradplugin.network.PublicNetworks;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import io.github.gringoestrangeiro.openradplugin.protocol.Wire;
import io.github.gringoestrangeiro.openradplugin.session.ControlSession;
import java.io.*;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Correlated catalog paging, strictly bounded even while membership and offers arrive. */
final class MinecraftCatalogQuery {
    interface Exchange {
        void send(byte[] record) throws IOException;
        boolean ready(Duration timeout) throws IOException;
        byte[] receive() throws IOException;
    }
    interface OtherRecord { void handle(byte[] record) throws IOException; }

    static List<MinecraftNetworks.Entry> fetch(ControlSession session, OtherRecord other, BooleanSupplier cancelled)
            throws IOException {
        return fetch(new Exchange() {
            public void send(byte[] record) throws IOException { session.send(record); }
            public boolean ready(Duration timeout) throws IOException { return session.ready(timeout); }
            public byte[] receive() throws IOException { return session.receive(); }
        }, other, cancelled, System::nanoTime);
    }

    static List<MinecraftNetworks.Entry> fetch(Exchange exchange, OtherRecord other,
                                               BooleanSupplier cancelled, LongSupplier clock) throws IOException {
        List<PublicNetworks.Network> networks = new ArrayList<>();
        Set<Long> cursors = new HashSet<>();
        long cursor = 0, id = 0x4d430000L, deadline = clock.getAsLong() + Duration.ofSeconds(20).toNanos();
        long heartbeat = clock.getAsLong() + Duration.ofSeconds(10).toNanos();
        int records = 0;
        for (int page = 0; page < 16; page++) {
            checkCancelled(cancelled);
            exchange.send(PublicNetworks.request("Minecraft", ++id, cursor));
            PublicNetworks.Page result = null;
            while (result == null) {
                checkCancelled(cancelled);
                long now = clock.getAsLong();
                if (now >= deadline) throw new SocketTimeoutException("Minecraft catalog timeout");
                if (now >= heartbeat) {
                    exchange.send(Wire.op(4));
                    heartbeat = now + Duration.ofSeconds(10).toNanos();
                }
                if (!exchange.ready(Duration.ofMillis(100))) continue;
                if (++records > 512) throw new IOException("Minecraft catalog record limit");
                byte[] record = exchange.receive();
                if (TlvCodec.operation(record) == 45) {
                    try { result = PublicNetworks.parse(record, id); }
                    catch (IllegalArgumentException invalid) { throw new IOException("Minecraft catalog response validation rejected", invalid); }
                } else other.handle(record);
            }
            networks.addAll(result.networks());
            if (networks.size() > 1024) throw new IOException("Minecraft catalog entry limit");
            if (result.cursor() == 0) return MinecraftNetworks.catalog(networks);
            if (!cursors.add(result.cursor())) throw new IOException("Minecraft catalog cursor loop");
            cursor = result.cursor();
        }
        throw new IOException("Minecraft catalog page limit");
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Minecraft catalog cancelled");
    }
}
