package io.github.gringoestrangeiro.openradclient.fabric;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Optional;

public final class VirtualAddressResolution {
    private VirtualAddressResolution() {}

    public static Optional<ResolvedServerAddress> resolve(String host, int port) {
        if (!LoopbackBridge.isVirtualLiteral(host)) return Optional.empty();

        String[] octets = host.split("\\.", -1);
        byte[] bytes = new byte[4];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(octets[i]);
        }

        try {
            InetAddress address = InetAddress.getByAddress(host, bytes);
            return Optional.of(ResolvedServerAddress.from(new InetSocketAddress(address, port)));
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
