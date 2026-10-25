package io.github.gringoestrangeiro.openradplugin.session;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.network.Membership;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public record Attachment(
        ControlSession session,
        Membership membership,
        Inet4Address vip,
        List<Inet4Address> uesHosts)
        implements AutoCloseable {
    public Attachment {
        uesHosts = List.copyOf(uesHosts);
    }

    public static Attachment connect(
            Identity identity, int port, String name, Consumer<ControlSession> owner)
            throws IOException {
        return connect(identity, port, name, owner, ServiceConfig.modulus());
    }

    public static Attachment connect(
            Identity identity,
            int port,
            String name,
            Consumer<ControlSession> owner,
            byte[] modulus)
            throws IOException {
        ControlSession s =
                ControlSession.authenticate(
                        identity.serverAddress(),
                        port,
                        identity,
                        4,
                        Duration.ofSeconds(45),
                        modulus,
                        owner);
        try {
            s.send(login(name, s.latency(), 4, 0));
            Membership membership = new Membership(identity.rid());
            List<Inet4Address> ues = List.of();
            for (int i = 0; i < 8; i++) {
                byte[] data = s.receive();
                int operation = TlvCodec.operation(data);
                require(
                        i == 0 ? operation == 21 : operation == 9 || operation == 38,
                        "unexpected attachment operation");
                if (i == 0) ues = uesHosts(data);
                membership.snapshot(data);
                Inet4Address vip = Membership.ownVip(data);
                if (vip != null) {
                    s.sustain();
                    return new Attachment(s, membership, vip, ues);
                }
            }
            throw new IOException("attachment VIP record budget");
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    static List<Inet4Address> uesHosts(byte[] loginComplete) {
        require(TlvCodec.operation(loginComplete) == 21, "expected LoginComplete");
        var root = TlvCodec.optional(fields(loginComplete), 0x1340);
        if (root.isEmpty()) return List.of();
        List<Inet4Address> hosts = new ArrayList<>();
        int count = 0;
        for (var record : fields(root.orElseThrow()))
            if (record.tag() == 0x0e00036c) {
                require(count++ < 32, "too many UES addresses");
                byte[] raw = record.value();
                require(
                        raw.length >= 2 && raw.length <= 16 && raw[raw.length - 1] == 0,
                        "invalid UES address");
                for (int i = 0; i < raw.length - 1; i++)
                    require(raw[i] > 0 && raw[i] < 128, "invalid UES address encoding");
                String text = new String(raw, 0, raw.length - 1, StandardCharsets.US_ASCII);
                require(
                        text.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}"),
                        "invalid UES IPv4 address");
                var parsed = literal(text);
                require(parsed instanceof Inet4Address, "invalid UES IPv4 address");
                Inet4Address address = (Inet4Address) parsed;
                require(
                        !address.isLoopbackAddress()
                                && !address.isMulticastAddress()
                                && !address.isAnyLocalAddress()
                                && !text.equals("255.255.255.255"),
                        "invalid UES address");
                if (!hosts.contains(address)) hosts.add(address);
            }
        return List.copyOf(hosts);
    }

    @Override
    public void close() throws IOException {
        session.close();
    }
}
